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
//   6. (M3) browser connections are tracked from their fenced REQUESTs; the adapter may push EVENTs, MessagePort
//      frames (PORT_DATA / PORT_CLOSE, streamId = port id) and ask the platform to close a connection
//      (CONTROL mmab.close). The platform reports closed browser connections with CONTROL mmab.close, and a lease
//      change implicitly closes every connection of the old lease.
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
 * @param {Record<string, ((value: any, ctx: object) => any) | {raw: true, handle: (payloadText: string, ctx: object) => string}>} opts.handlers
 *        operation name -> handler (decoded value in / value out, or raw mmab-sv1 text in / text out)
 * @param {{onData: Function, onClose: Function}} [opts.ports] MessagePort frames from the browser (M3)
 * @param {(connectionId: string) => void} [opts.onConnectionClosed] browser connection gone (M3)
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
  const portHandlers = opts.ports ?? null;
  const onConnectionClosed = opts.onConnectionClosed ?? (() => {});
  /** connectionId -> fence snapshot of its last accepted REQUEST (sync-* one-shot calls are not tracked). */
  const connections = new Map();
  let active = null;
  let activeSend = null;

  const forget = (connectionId) => {
    if (!connections.delete(connectionId)) return;
    try {
      onConnectionClosed(connectionId);
    } catch {
      // adapter cleanup must never break the platform link
    }
  };
  const fenceStillHolds = (c) => c.generation === fence.generation && c.leaseId === fence.leaseId
    && c.fencingToken === fence.fencingToken;

  /** Adapter-initiated frames towards one tracked browser connection. Resolves false when not deliverable. */
  const api = {
    fence,
    connections: () => [...connections.keys()],
    hasConnection: (id) => connections.has(id) && fenceStillHolds(connections.get(id)),
    emit(connectionId, operation, value, { raw = false } = {}) {
      return toConnection(connectionId, { messageKind: 'EVENT', operation, payload: raw ? value : encode(value) });
    },
    portData(connectionId, portId, payloadText) {
      return toConnection(connectionId, { messageKind: 'PORT_DATA', streamId: portId, payload: payloadText });
    },
    portClose(connectionId, portId) {
      return toConnection(connectionId, { messageKind: 'PORT_CLOSE', streamId: portId, payload: '' });
    },
    /** Ask the platform to close the browser connection (e.g. QUEUE_LIMIT); the adapter forgets it at once. */
    closeConnection(connectionId, code = 'QUEUE_LIMIT') {
      const r = toConnection(connectionId, { messageKind: 'CONTROL', operation: 'mmab.close', error: { code } });
      forget(connectionId);
      return r;
    },
  };
  function toConnection(connectionId, fields) {
    const c = connections.get(connectionId);
    if (!c || !activeSend || !fenceStillHolds(c)) return Promise.resolve(false);
    return activeSend(c, fields).then(() => true);
  }

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

    const sendTracked = (c, fields) => {
      const seq = (sequences.get(c.connectionId) ?? 0) + 1;
      sequences.set(c.connectionId, seq);
      return send({
        protocolVersion: PROTOCOL_VERSION,
        profileId: c.profileId,
        instanceId,
        processGeneration: c.generation,
        connectionId: c.connectionId,
        leaseId: c.leaseId,
        fencingToken: c.fencingToken,
        sequence: seq,
        deadline: new Date(Date.now() + RESPONSE_TTL_MS).toISOString(),
        codecVersion: CODEC_VERSION,
        payload: '',
        ...fields,
      });
    };
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
        if (env.operation === 'mmab.close') {
          // platform: this browser connection is gone (closed / revoked); release its native-side state
          if (env.processGeneration === fence.generation) forget(env.connectionId);
          return undefined;
        }
        if (env.operation !== 'mmab.fence') return replyError(env, 'UNKNOWN_OPERATION');
        if (env.processGeneration !== fence.generation) return replyError(env, 'STALE_GENERATION');
        if (env.fencingToken < fence.fencingToken && env.leaseId !== NO_LEASE) {
          return replyError(env, 'STALE_GENERATION'); // fences only move forward
        }
        fence.leaseId = env.leaseId;
        fence.fencingToken = env.leaseId === NO_LEASE ? fence.fencingToken : env.fencingToken;
        for (const [id, c] of [...connections]) if (!fenceStillHolds(c)) forget(id);
        return undefined;
      }
      if (env.messageKind === 'PORT_DATA' || env.messageKind === 'PORT_CLOSE') {
        // fenced like requests; frames of unknown / stale connections are dropped (the platform closed them)
        const c = connections.get(env.connectionId);
        if (!c || !fenceStillHolds(c) || env.leaseId !== fence.leaseId || env.fencingToken !== fence.fencingToken
            || env.processGeneration !== fence.generation || typeof env.streamId !== 'string' || !portHandlers) {
          return undefined;
        }
        try {
          if (env.messageKind === 'PORT_DATA') await portHandlers.onData(env.connectionId, env.streamId, env.payload, api);
          else await portHandlers.onClose(env.connectionId, env.streamId, api);
        } catch (e) {
          if (e?.code === 'QUEUE_LIMIT') api.closeConnection(env.connectionId, 'QUEUE_LIMIT');
        }
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
      if (!env.connectionId.startsWith('sync-') && !connections.has(env.connectionId)) {
        connections.set(env.connectionId, {
          connectionId: env.connectionId, profileId: env.profileId, generation: env.processGeneration,
          leaseId: env.leaseId, fencingToken: env.fencingToken,
        });
      }
      const raw = typeof handler === 'object' && handler.raw === true;
      let value;
      try {
        value = raw ? env.payload : decode(env.payload);
      } catch (e) {
        return replyError(env, e instanceof CodecError ? e.code : 'MALFORMED_FRAME');
      }
      const ctx = {
        connectionId: env.connectionId,
        api,
        // Events carry the request's fence; the platform drops them if the fence moved on meanwhile.
        emit: (operation, eventValue) => reply(env, {
          messageKind: 'EVENT', operation, payload: encode(eventValue),
        }),
        stillCurrent: () => env.leaseId === fence.leaseId && env.fencingToken === fence.fencingToken,
      };
      try {
        const result = raw ? await handler.handle(value, ctx) : await handler(value, ctx);
        // raw handlers return an already-encoded mmab-sv1 text (e.g. a value produced inside the app's renderer)
        const payload = raw ? String(result) : encode(result);
        return reply(env, { messageKind: 'RESPONSE', correlationId: env.correlationId, payload });
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
          activeSend = sendTracked;
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
        activeSend = null;
        for (const id of [...connections.keys()]) forget(id);
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
    api,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

export { PLATFORM_CONNECTION };
