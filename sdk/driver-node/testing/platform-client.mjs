// Test helper (M3): plays the PLATFORM side of Adapter Protocol v1 against an adapter, in-process or as a child
// process. Used by sdk / adapters contract tests; never touches a real application.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import net from 'node:net';
import { runAdapter } from '../adapter-runtime.mjs';
import {
  ADAPTER_TO_PLATFORM, LineSplitter, PLATFORM_TO_ADAPTER, adapterProof, decodeFrame, encodeFrame, newNonce,
  platformProof, sessionKey,
} from '../wire.mjs';

export const newKey = () => randomBytes(32);

/** Start an adapter in-process (handlers etc. as for runAdapter). */
export async function inProcess(opts, { instanceId = 'test-01', generation = 1, key = newKey() } = {}) {
  const adapter = await runAdapter({
    adapterVersion: 'test',
    exitWhenPlatformLeaves: false,
    announce: () => {},
    bootstrap: {
      mmabAdapterBootstrap: 1, serviceKey: key.toString('base64'), instanceId, generation, profileId: 'test/1',
    },
    ...opts,
  });
  return { ...adapter, key, instanceId, generation };
}

/** Spawn an adapter process with the bootstrap line on stdin; resolves on its ready line. */
export function spawnAdapter(main, { args = [], instanceId = 'test-01', generation = 1, key = newKey(), env } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [main, ...args], { stdio: ['pipe', 'pipe', 'pipe'], env });
    child.stdin.end(`${JSON.stringify({
      mmabAdapterBootstrap: 1, serviceKey: key.toString('base64'), instanceId, generation, profileId: 'test/1',
      protocol: '1.0',
    })}\n`);
    const s = new LineSplitter(4096);
    let stderr = '';
    child.stderr.on('data', (c) => { stderr += c; });
    child.stdout.on('data', (c) => {
      for (const line of s.push(c)) {
        const ready = JSON.parse(line);
        if (ready.mmabAdapterReady) resolve({ child, port: ready.port, key, instanceId, generation });
      }
    });
    child.on('exit', (code) => reject(Object.assign(new Error(`adapter exited ${code}`), { code, stderr })));
  });
}

/** Authenticated platform connection with envelope helpers. */
export async function platform({ port, key, instanceId, generation }) {
  const sock = net.connect(port, '127.0.0.1');
  await new Promise((r, j) => { sock.once('connect', r); sock.once('error', j); });
  const splitter = new LineSplitter(2 * 1024 * 1024);
  const queue = [];
  const waiters = [];
  let closed = false;
  sock.on('data', (c) => {
    for (const line of splitter.push(c)) {
      const w = waiters.shift();
      if (w) w(line);
      else queue.push(line);
    }
  });
  sock.on('close', () => {
    closed = true;
    while (waiters.length) waiters.shift()(null);
  });
  sock.on('error', () => {});
  const nextLine = (ms = 5000) => new Promise((resolve) => {
    if (queue.length) return resolve(queue.shift());
    if (closed) return resolve(null);
    const t = setTimeout(() => {
      const i = waiters.indexOf(w);
      if (i >= 0) waiters.splice(i, 1);
      resolve(undefined);
    }, ms);
    const w = (l) => { clearTimeout(t); resolve(l); };
    waiters.push(w);
    return undefined;
  });
  const write = (l) => sock.write(`${l}\n`);

  const hello = JSON.parse(await nextLine());
  assert.equal(hello.t, 'hello');
  const cn = newNonce();
  write(JSON.stringify({ t: 'auth', protocol: '1.0', nonce: cn,
    mac: platformProof(key, hello.nonce, cn, instanceId, generation, '1.0') }));
  const ok = JSON.parse(await nextLine());
  assert.equal(ok.t, 'auth-ok');
  assert.equal(ok.mac, adapterProof(key, hello.nonce, cn, instanceId, generation, '1.0'));
  const sk = sessionKey(key, hello.nonce, cn);
  let outN = 0;
  let inN = 0;
  let seq = 0;
  const lease = { leaseId: 'none', fencingToken: 0 };

  const base = (fields) => ({
    protocolVersion: '1.0', profileId: 'test/1', instanceId, processGeneration: generation,
    connectionId: 'platform', leaseId: lease.leaseId, fencingToken: lease.fencingToken, sequence: ++seq,
    deadline: new Date(Date.now() + 30_000).toISOString(), codecVersion: 'mmab-sv1', payload: '', ...fields,
  });
  const send = (env) => write(encodeFrame(sk, PLATFORM_TO_ADAPTER, ++outN, env));
  return {
    hello,
    lease,
    isClosed: () => closed,
    close: () => sock.destroy(),
    send,
    /** next decoded envelope (undefined on timeout, null when closed) */
    async next(ms) {
      const l = await nextLine(ms);
      if (l == null) return l;
      return decodeFrame(sk, ADAPTER_TO_PLATFORM, ++inN, l);
    },
    async nextWhere(pred, ms = 5000) {
      const end = Date.now() + ms;
      for (;;) {
        const left = end - Date.now();
        if (left <= 0) return undefined;
        const e = await this.next(left);
        if (e == null) return e;
        if (pred(e)) return e;
      }
    },
    fence(leaseId, fencingToken) {
      lease.leaseId = leaseId;
      lease.fencingToken = fencingToken;
      send(base({ messageKind: 'CONTROL', operation: 'mmab.fence' }));
    },
    request(connectionId, operation, payload, cid = String(seq + 1)) {
      send(base({ connectionId, messageKind: 'REQUEST', operation, payload, correlationId: `${connectionId}/${cid}` }));
      return `${connectionId}/${cid}`;
    },
    portData(connectionId, streamId, payload) {
      send(base({ connectionId, messageKind: 'PORT_DATA', streamId, payload }));
    },
    portClose(connectionId, streamId) {
      send(base({ connectionId, messageKind: 'PORT_CLOSE', streamId }));
    },
    closeConnection(connectionId) {
      send(base({ connectionId, messageKind: 'CONTROL', operation: 'mmab.close' }));
    },
    async response(correlationId, ms = 5000) {
      return this.nextWhere((e) => e.messageKind === 'RESPONSE' && e.correlationId === correlationId, ms);
    },
  };
}
