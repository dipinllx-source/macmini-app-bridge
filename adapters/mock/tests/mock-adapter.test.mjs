// Contract tests for the mock adapter process: internal authentication + fence enforcement.
// Plays the platform side with sdk/driver-node/wire.mjs; never touches a real application.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import net from 'node:net';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { encode, decode } from '../../../sdk/web/codec.mjs';
import {
  ADAPTER_TO_PLATFORM, LineSplitter, PLATFORM_TO_ADAPTER, decodeFrame, encodeFrame, newNonce, platformProof,
  adapterProof, sessionKey,
} from '../../../sdk/driver-node/wire.mjs';

const MAIN = fileURLToPath(new URL('../driver/main.mjs', import.meta.url));
const GEN = 3;

function startAdapter(key, generation = GEN) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [MAIN], { stdio: ['pipe', 'pipe', 'pipe'] });
    child.stdin.end(`${JSON.stringify({
      mmabAdapterBootstrap: 1, serviceKey: key.toString('base64'), instanceId: 'mock-01', generation,
      profileId: 'mock/0.1.0', protocol: '1.0',
    })}\n`);
    const s = new LineSplitter(4096);
    child.stdout.on('data', (c) => {
      for (const line of s.push(c)) {
        const ready = JSON.parse(line);
        if (ready.mmabAdapterReady) resolve({ child, port: ready.port });
      }
    });
    child.on('exit', (code) => reject(new Error(`adapter exited ${code}`)));
  });
}

/** Raw line connection to the adapter port. */
function connect(port) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(port, '127.0.0.1');
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
    const next = () => new Promise((r) => {
      if (queue.length) r(queue.shift());
      else if (closed) r(null);
      else waiters.push(r);
    });
    sock.on('connect', () => resolve({ sock, next, write: (l) => sock.write(`${l}\n`), isClosed: () => closed }));
    sock.on('error', reject);
  });
}

/** Authenticate as the platform; returns a framed client. */
async function platform(port, key, generation = GEN) {
  const c = await connect(port);
  const hello = JSON.parse(await c.next());
  assert.equal(hello.t, 'hello');
  const cn = newNonce();
  c.write(JSON.stringify({
    t: 'auth', protocol: '1.0', nonce: cn,
    mac: platformProof(key, hello.nonce, cn, 'mock-01', generation, '1.0'),
  }));
  const ok = JSON.parse(await c.next());
  assert.equal(ok.t, 'auth-ok');
  assert.equal(ok.mac, adapterProof(key, hello.nonce, cn, 'mock-01', generation, '1.0'), 'mutual authentication');
  const sk = sessionKey(key, hello.nonce, cn);
  let outN = 0;
  let inN = 0;
  return {
    ...c,
    send: (env) => c.write(encodeFrame(sk, PLATFORM_TO_ADAPTER, ++outN, env)),
    recv: async () => {
      const line = await c.next();
      return line === null ? null : decodeFrame(sk, ADAPTER_TO_PLATFORM, ++inN, line);
    },
  };
}

const base = (over) => ({
  protocolVersion: '1.0', profileId: 'mock/0.1.0', instanceId: 'mock-01', processGeneration: GEN,
  connectionId: 'c-1', leaseId: 'l-1', fencingToken: 5, sequence: 1, messageKind: 'REQUEST',
  correlationId: 'c-1/1', operation: 'mock.echo', deadline: new Date(Date.now() + 30_000).toISOString(),
  codecVersion: 'mmab-sv1', payload: encode({ hello: 'world' }), ...over,
});
const fence = (leaseId, fencingToken, generation = GEN) => ({
  protocolVersion: '1.0', profileId: 'mock/0.1.0', instanceId: 'mock-01', processGeneration: generation,
  connectionId: 'platform', leaseId, fencingToken, sequence: 1, messageKind: 'CONTROL', operation: 'mmab.fence',
  deadline: new Date(Date.now() + 30_000).toISOString(), codecVersion: 'mmab-sv1', payload: '',
});

let key;
let adapter;
before(async () => {
  key = randomBytes(32);
  adapter = await startAdapter(key);
});
after(() => adapter?.child.kill());

test('unauthenticated loopback connection is refused (frame before auth)', async () => {
  const c = await connect(adapter.port);
  await c.next(); // hello
  c.write(encodeFrame(Buffer.alloc(32), PLATFORM_TO_ADAPTER, 1, base({})));
  assert.deepEqual(JSON.parse(await c.next()), { t: 'error', code: 'UNAUTHENTICATED' });
  assert.equal(await c.next(), null, 'connection closed');
});

test('wrong key and replayed auth line are refused', async () => {
  const c = await connect(adapter.port);
  const hello = JSON.parse(await c.next());
  const cn = newNonce();
  c.write(JSON.stringify({ t: 'auth', protocol: '1.0', nonce: cn,
    mac: platformProof(randomBytes(32), hello.nonce, cn, 'mock-01', GEN, '1.0') }));
  assert.equal(JSON.parse(await c.next()).code, 'UNAUTHENTICATED');

  // A valid proof for connection A does not authenticate connection B (fresh adapter nonce per connection).
  const a = await connect(adapter.port);
  const helloA = JSON.parse(await a.next());
  const replayed = JSON.stringify({ t: 'auth', protocol: '1.0', nonce: cn,
    mac: platformProof(key, helloA.nonce, cn, 'mock-01', GEN, '1.0') });
  const b = await connect(adapter.port);
  await b.next();
  b.write(replayed);
  assert.equal(JSON.parse(await b.next()).code, 'UNAUTHENTICATED');
  a.sock.destroy();
});

test('wrong generation in the proof is refused', async () => {
  const c = await connect(adapter.port);
  const hello = JSON.parse(await c.next());
  const cn = newNonce();
  c.write(JSON.stringify({ t: 'auth', protocol: '1.0', nonce: cn,
    mac: platformProof(key, hello.nonce, cn, 'mock-01', GEN - 1, '1.0') }));
  assert.equal(JSON.parse(await c.next()).code, 'UNAUTHENTICATED');
});

test('authenticated platform: lease required, fence, stale generation, unknown op, deadline, echo', async () => {
  const p = await platform(adapter.port, key);

  // no lease yet
  p.send(base({}));
  let r = await p.recv();
  assert.equal(r.error.code, 'LEASE_REQUIRED');

  p.send(fence('l-1', 5));
  p.send(base({ correlationId: 'c-1/2' }));
  r = await p.recv();
  assert.equal(r.messageKind, 'RESPONSE');
  assert.equal(r.correlationId, 'c-1/2');
  assert.deepEqual(decode(r.payload), { hello: 'world' });

  p.send(base({ correlationId: 'c-1/3', processGeneration: GEN - 1 }));
  assert.equal((await p.recv()).error.code, 'STALE_GENERATION');

  p.send(base({ correlationId: 'c-1/4', fencingToken: 4 }));
  assert.equal((await p.recv()).error.code, 'STALE_GENERATION');

  p.send(base({ correlationId: 'c-1/5', leaseId: 'l-0' }));
  assert.equal((await p.recv()).error.code, 'STALE_GENERATION');

  p.send(base({ correlationId: 'c-1/6', operation: 'mock.nope' }));
  assert.equal((await p.recv()).error.code, 'UNKNOWN_OPERATION');

  p.send(base({ correlationId: 'c-1/7', deadline: new Date(Date.now() - 1000).toISOString() }));
  assert.equal((await p.recv()).error.code, 'DEADLINE_EXCEEDED');

  p.send(base({ correlationId: 'c-1/8', operation: 'mock.add', payload: encode({ a: 2, b: 40 }) }));
  assert.equal(decode((await p.recv()).payload), 42);

  // fences never move backwards
  p.send(fence('l-0', 4));
  r = await p.recv();
  assert.equal(r.messageKind, 'CONTROL');
  assert.equal(r.error.code, 'STALE_GENERATION');

  // revoke lease -> requests refused again
  p.send(fence('none', 0));
  p.send(base({ correlationId: 'c-1/9' }));
  assert.equal((await p.recv()).error.code, 'LEASE_REQUIRED');

  // a second authenticated platform connection is refused while this one is active
  const second = await connect(adapter.port);
  const hello = JSON.parse(await second.next());
  const cn = newNonce();
  second.write(JSON.stringify({ t: 'auth', protocol: '1.0', nonce: cn,
    mac: platformProof(key, hello.nonce, cn, 'mock-01', GEN, '1.0') }));
  assert.equal(JSON.parse(await second.next()).code, 'UNAUTHENTICATED');

  // tampering after auth drops the connection
  p.write(`${'0'.repeat(64)} {"n":99,"env":{}}`);
  assert.equal(JSON.parse(await p.next()).code, 'REPLAY_REJECTED');
});
