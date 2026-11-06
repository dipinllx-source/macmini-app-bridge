// MMAB Adapter SDK (Node) - adapter-side runtime for Adapter Protocol v1.
//
// Lifecycle (sdk/protocol/adapter-wire-v1.md):
//   1. read ONE bootstrap JSON line from stdin (service key, instanceId, generation, profileId), then stop reading;
//      the key is never written to argv, env, files or logs;
//   2. listen on 127.0.0.1 (ephemeral port) and print {"mmabAdapterReady":1,"port":N} on stdout;
//   3. every connection must complete the HMAC handshake within 3 s; anything else -> {"t":"error"} + close.
//      Being on loopback grants nothing. Only one authenticated platform connection at a time;
//   4. after auth every frame is MAC'd with a per-connection session key and a strict +1 counter;
//   5. the adapter enforces its own fence: generation / lease / fencing token pushed by the platform via
//      CONTROL mmab.fence. Requests with a stale fence, no lease, unknown operation or expired deadline are refused.
import net from 'node:net';
import { decode, encode, CodecError, CODEC_VERSION } from '../web/codec.mjs';
import { envelopeProblem, NO_LEASE, PLATFORM_CONNECTION, PROTOCOL_VERSION } from './envelope.mjs';
import {
  ADAPTER_TO_PLATFORM, HANDSHAKE_TIMEOUT_MS, LineSplitter, MAX_HANDSHAKE_LINE_BYTES, MAX_WIRE_LINE_BYTES,
  PLATFORM_TO_ADAPTER, WireError, adapterProof, decodeFrame, encodeFrame, newNonce, platformProof, safeEqual,
  sessionKey,
} from './wire.mjs';

const RESPONSE_TTL_MS = 30_000;

/** Read the single bootstrap line from stdin (max 4 KiB). */
export function readBootstrap(stream = process.stdin) {
  return new Promise((resolve, reject) => {
    const splitter = new LineSplitter(MAX_HANDSHAKE_LINE_BYTES);
    const onData = (chunk) => {
      let lines;
      try {
        lines = splitter.push(chunk);
      } catch (e) {
        cleanup();
        reject(e);
        return;
      }
      if (lines.length) {
        cleanup();
        try {
          const boot = JSON.parse(lines[0]);
          if (boot.mmabAdapterBootstrap !== 1 || typeof boot.serviceKey !== 'string'
              || typeof boot.instanceId !== 'string' || !Number.isSafeInteger(boot.generation)) {
            throw new Error('invalid bootstrap');
          }
          resolve(boot);
        } catch (e) {
          reject(e);
        }
      }
    };
    const onEnd = () => {
      cleanup();
      reject(new Error('stdin closed before bootstrap'));
    };
    const cleanup = () => {
      stream.off('data', onData);
      stream.off('end', onEnd);
      stream.pause();
    };
    stream.on('data', onData);
    stream.on('end', onEnd);
  });
}

/**
 * Start an adapter.
 * @param {object} opts
 * @param {string} opts.adapterVersion
 * @param {Record<string, (value: any, ctx: object) => any>} opts.handlers  operation name -> handler
 * @param {object} [opts.bootstrap] for tests; defaults to reading stdin
 * @param {boolean} [opts.exitWhenPlatformLeaves=true]
 * @param {(line: string) => void} [opts.announce] defaults to stdout
 */
export async function runAdapter(opts) {
  const boot = opts.bootstrap ?? await readBootstrap();
  const key = Buffer.from(boot.serviceKey, 'base64');
  if (key.length !== 32) throw new Error('service key must be 32 bytes');
  const instanceId = boot.instanceId;
  const fence = { generation: boot.generation, leaseId: NO_LEASE, fencingToken: 0 };
  const handlers = new Map(Object.entries(opts.handlers));
  const exitWhenPlatformLeaves = opts.exitWhenPlatformLeaves ?? true;
  let active = null;

  const server = net.createServer((sock) => handleConnection(sock));
  server.maxConnections = 8;

  function handleConnection(sock) {
    sock.setNoDelay(true);
    const adapterNonce = newNonce();
    const splitter = new LineSplitter(MAX_HANDSHAKE_LINE_BYTES);
    const sequences = new Map();
    let authed = false;
    let sk = null;
    let inN = 0;
    let outN = 0;
    let writeChain = Promise.resolve();

    const fail = (code) => {
      if (sock.destroyed) return;
      sock.end(`${JSON.stringify({ t: 'error', code })}\n`);
      setTimeout(() => sock.destroy(), 100).unref();
    };
    const timer = setTimeout(() => fail('UNAUTHENTICATED'), HANDSHAKE_TIMEOUT_MS);
    timer.unref();

    const send = (env) => {
      writeChain = writeChain.then(() => new Promise((resolve) => {
        if (sock.destroyed) return resolve();
        const line = `${encodeFrame(sk, ADAPTER_TO_PLATFORM, ++outN, env)}\n`;
        if (sock.write(line)) resolve();
        else sock.once('drain', resolve);
      }));
      return writeChain;
    };

    const reply = (req, fields) => {
      const seqKey = req.connectionId;
      const seq = (sequences.get(seqKey) ?? 0) + 1;
      sequences.set(seqKey, seq);
      return send({
        protocolVersion: PROTOCOL_VERSION,
        profileId: req.profileId,
        instanceId,
        processGeneration: req.processGeneration,
        connectionId: req.connectionId,
        leaseId: req.leaseId,
        fencingToken: req.fencingToken,
        sequence: seq,
        deadline: new Date(Date.now() + RESPONSE_TTL_MS).toISOString(),
        codecVersion: CODEC_VERSION,
        payload: '',
        ...fields,
      });
    };
    const replyError = (req, code) => reply(req, {
      messageKind: req.messageKind === 'CONTROL' ? 'CONTROL' : 'RESPONSE',
      correlationId: req.correlationId ?? undefined,
      operation: req.operation,
      error: { code },
    });

    async function onEnvelope(env) {
      const problem = envelopeProblem(env);
      if (problem) throw new WireError('MALFORMED_FRAME', problem);
      if (env.instanceId !== instanceId) return replyError(env, 'STALE_GENERATION');
      if (env.messageKind === 'CONTROL') {
        if (env.operation !== 'mmab.fence') return replyError(env, 'UNKNOWN_OPERATION');
        if (env.processGeneration !== fence.generation) return replyError(env, 'STALE_GENERATION');
        if (env.fencingToken < fence.fencingToken && env.leaseId !== NO_LEASE) {
          return replyError(env, 'STALE_GENERATION'); // fences only move forward
        }
        fence.leaseId = env.leaseId;
        fence.fencingToken = env.leaseId === NO_LEASE ? fence.fencingToken : env.fencingToken;
        return undefined;
      }
      if (env.messageKind !== 'REQUEST') return undefined;
      if (env.processGeneration !== fence.generation) return replyError(env, 'STALE_GENERATION');
      if (fence.leaseId === NO_LEASE) return replyError(env, 'LEASE_REQUIRED');
      if (env.leaseId !== fence.leaseId || env.fencingToken !== fence.fencingToken) {
        return replyError(env, 'STALE_GENERATION');
      }
      if (Date.parse(env.deadline) < Date.now()) return replyError(env, 'DEADLINE_EXCEEDED');
      if (env.codecVersion !== CODEC_VERSION) return replyError(env, 'CODEC_UNSUPPORTED');
      const handler = handlers.get(env.operation);
      if (!handler) return replyError(env, 'UNKNOWN_OPERATION');
      let value;
      try {
        value = decode(env.payload);
      } catch (e) {
        return replyError(env, e instanceof CodecError ? e.code : 'MALFORMED_FRAME');
      }
      const ctx = {
        connectionId: env.connectionId,
        // Events carry the request's fence; the platform drops them if the fence moved on meanwhile.
        emit: (operation, eventValue) => reply(env, {
          messageKind: 'EVENT', operation, payload: encode(eventValue),
        }),
        stillCurrent: () => env.leaseId === fence.leaseId && env.fencingToken === fence.fencingToken,
      };
      try {
        const result = await handler(value, ctx);
        return reply(env, { messageKind: 'RESPONSE', correlationId: env.correlationId, payload: encode(result) });
      } catch (e) {
        return replyError(env, e instanceof CodecError ? e.code : (e?.code ?? 'MALFORMED_FRAME'));
      }
    }

    sock.on('data', (chunk) => {
      let lines;
      try {
        lines = splitter.push(chunk);
      } catch {
        fail('QUEUE_LIMIT');
        return;
      }
      for (const line of lines) {
        if (sock.destroyed) return;
        if (!authed) {
          let msg;
          try {
            msg = JSON.parse(line);
          } catch {
            fail('UNAUTHENTICATED');
            return;
          }
          if (msg?.t !== 'auth' || typeof msg.nonce !== 'string' || typeof msg.mac !== 'string'
              || typeof msg.protocol !== 'string') {
            fail('UNAUTHENTICATED');
            return;
          }
          if (msg.protocol.split('.')[0] !== PROTOCOL_VERSION.split('.')[0]) {
            fail('PROTOCOL_VERSION_UNSUPPORTED');
            return;
          }
          const expected = platformProof(key, adapterNonce, msg.nonce, instanceId, fence.generation, msg.protocol);
          if (!safeEqual(msg.mac, expected) || msg.nonce === adapterNonce) {
            fail('UNAUTHENTICATED');
            return;
          }
          if (active) {
            fail('UNAUTHENTICATED'); // exclusive: one authenticated platform connection
            return;
          }
          authed = true;
          active = sock;
          clearTimeout(timer);
          sk = sessionKey(key, adapterNonce, msg.nonce);
          splitter.setLimit(MAX_WIRE_LINE_BYTES);
          sock.write(`${JSON.stringify({
            t: 'auth-ok', mac: adapterProof(key, adapterNonce, msg.nonce, instanceId, fence.generation, msg.protocol),
          })}\n`);
          continue;
        }
        let env;
        try {
          env = decodeFrame(sk, PLATFORM_TO_ADAPTER, ++inN, line);
        } catch (e) {
          fail(e.code ?? 'MALFORMED_FRAME');
          return;
        }
        onEnvelope(env).catch((e) => fail(e?.code ?? 'MALFORMED_FRAME'));
      }
    });
    sock.on('error', () => sock.destroy());
    sock.on('close', () => {
      clearTimeout(timer);
      if (active === sock) {
        active = null;
        if (exitWhenPlatformLeaves) {
          server.close();
          process.exitCode = 0;
          setTimeout(() => process.exit(0), 50).unref();
        }
      }
    });

    sock.write(`${JSON.stringify({
      t: 'hello',
      protocol: { min: PROTOCOL_VERSION, max: PROTOCOL_VERSION },
      nonce: adapterNonce,
      instanceId,
      adapterVersion: opts.adapterVersion,
    })}\n`);
  }

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  const announce = opts.announce ?? ((line) => process.stdout.write(`${line}\n`));
  announce(JSON.stringify({ mmabAdapterReady: 1, port, adapterVersion: opts.adapterVersion }));
  return {
    port,
    fence,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

export { PLATFORM_CONNECTION };
