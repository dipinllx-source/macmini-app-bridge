// codex adapter (S2) offline contract: driver <-> FAKE Codex renderer (vm realm with real MessageChannels and a fake
// electronBridge / app host) <-> platform (played by sdk/driver-node/testing/platform-client.mjs).
// Never touches a real Codex instance.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';
import '../../../sdk/web/codec.js';
import '../../../sdk/web/ports.js';
import { inProcess, platform } from '../../../sdk/driver-node/testing/platform-client.mjs';
import { createDriver } from '../driver/main.mjs';
import { payloadSource } from '../driver/renderer-link.mjs';

const { encode, decode, PortRef } = globalThis.MmabCodec;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** A fake Codex renderer: window with postMessage, a fake electronBridge and an app host answering on its port. */
function fakeRenderer(onBinding) {
  const listeners = new Set();
  const sentFromView = [];
  const gitRequests = [];
  let themeCb = null;
  const win = {
    location: { origin: 'app://-' },
    addEventListener: (t, fn) => { if (t === 'message') listeners.add(fn); },
    removeEventListener: (t, fn) => listeners.delete(fn),
    postMessage(data, origin, transfers = []) {
      setImmediate(() => { for (const fn of [...listeners]) fn({ data, ports: transfers, source: win, origin }); });
    },
    electronBridge: {
      getPreloadStartedAtMs: () => 1, getInitialSidebarBootstrap: () => ({ s: 1 }), getSystemThemeVariant: () => 'dark',
      getSentryInitOptions: () => ({}), getDesktopUserAgent: () => 'Codex Desktop/test', getAppSessionId: () => 'sess',
      getBuildFlavor: () => 'prod', isDeviceCheckSupported: () => false, isIntelMacBuild: () => false,
      getFastModeRolloutMetrics: async (a) => ({ metrics: a }),
      sendMessageFromView: async (m) => { sentFromView.push(m.type); },
      connectGitWorker: async (id) => { gitRequests.push(id); return true; },
      subscribeToSystemThemeVariant: (cb) => { themeCb = cb; return () => { themeCb = null; }; },
    },
    __mmabCodexOut: (payload) => onBinding(payload),
  };
  // the native app host: on connect-app-host, greet on the port and echo (with a nested native port on request)
  const hostPorts = [];
  listeners.add((e) => {
    if (e.data?.type !== 'connect-app-host' || !e.ports.length) return;
    const port = e.ports[0];
    hostPorts.push(port);
    port.onmessage = (m) => {
      if (m.data?.give) {
        const child = new MessageChannel();
        child.port1.onmessage = (x) => child.port1.postMessage({ childEcho: x.data });
        port.postMessage({ child: child.port2 }, [child.port2]);
      } else port.postMessage({ echo: m.data });
    };
    port.start?.();
    port.postMessage({ hello: 'app-host', at: new Date(5) });
  });
  new Function('globalThis', 'window', payloadSource())(win, win);
  return {
    win, sentFromView, gitRequests, hostPorts,
    evaluate: async (expr) => new Function('window', `return (${expr});`)(win),
    hostSays: (data, transfers) => win.postMessage(data, 'app://-', transfers),
    theme: () => themeCb && themeCb(),
  };
}

async function setup() {
  let renderer;
  const attach = [];
  const driver = createDriver({
    verify: () => attach.push('verified'),
    connect: async ({ onBinding }) => {
      renderer = fakeRenderer(onBinding);
      return { evaluate: renderer.evaluate, detach: async () => {}, close() {}, closed: false };
    },
  });
  const adapter = await inProcess({ handlers: driver.handlers, ports: driver.ports, onConnectionClosed: driver.onConnectionClosed });
  driver.bind(adapter.api);
  const p = await platform(adapter);
  p.fence('lease-1', 1);
  const frames = [];
  const waitFor = async (pred, ms = 5000) => {
    const end = Date.now() + ms;
    for (;;) {
      const i = frames.findIndex(pred);
      if (i >= 0) return frames.splice(i, 1)[0];
      const left = end - Date.now();
      if (left <= 0) return undefined;
      const e = await p.next(left);
      if (e == null) return e;
      frames.push(e);
    }
  };
  const call = async (conn, op, payloadText) => {
    const cid = p.request(conn, op, payloadText);
    return waitFor((e) => e.messageKind === 'RESPONSE' && e.correlationId === cid);
  };
  return { driver, adapter, p, waitFor, call, attach, get renderer() { return renderer; },
    done: async () => { p.close(); await adapter.close(); } };
}

test('bootstrap snapshot, allow-listed reads, view ready / startup reads; nothing else reaches the native view', async () => {
  const s = await setup();
  try {
    const boot = decode((await s.call('sync-1', 'codex.bootstrap', encode(null))).payload);
    assert.equal(boot.getDesktopUserAgent, 'Codex Desktop/test');
    assert.deepEqual(boot.getInitialSidebarBootstrap, { s: 1 });
    assert.deepEqual(s.attach, ['verified'], 'dedicated instance verified before attaching');

    assert.deepEqual(decode((await s.call('c-1', 'codex.read', encode({ method: 'getFastModeRolloutMetrics', arg: 3 }))).payload), { metrics: 3 });
    const denied = await s.call('c-1', 'codex.read', encode({ method: 'connectGitWorker' }));
    assert.equal(denied.error.code, 'UNKNOWN_OPERATION');

    assert.equal((await s.call('c-1', 'codex.viewReady', encode(null))).error, undefined);
    assert.equal((await s.call('c-1', 'codex.viewRead', encode({ type: 'request-app-server-state' }))).error, undefined);
    const bad = await s.call('c-1', 'codex.viewRead', encode({ type: 'open-in-new-window' }));
    assert.equal(bad.error.code, 'UNKNOWN_OPERATION');
    assert.deepEqual(s.renderer.sentFromView, ['ready', 'request-app-server-state']);
  } finally {
    await s.done();
  }
});

test('app-host MessageChannel is relayed both ways, including nested native ports and typed values', async () => {
  const s = await setup();
  try {
    const r = await s.call('c-1', 'codex.connectAppHost', encode({ type: 'connect-app-host', port: new PortRef('w1'), n: 7n }));
    assert.equal(r.error, undefined);
    const hello = await s.waitFor((e) => e.messageKind === 'PORT_DATA' && e.streamId === 'w1');
    const v = decode(hello.payload);
    assert.equal(v.hello, 'app-host');
    assert.ok(v.at instanceof Date);

    s.p.portData('c-1', 'w1', encode({ ping: new Uint8Array([1, 2]) }));
    const echo = decode((await s.waitFor((e) => e.messageKind === 'PORT_DATA' && e.streamId === 'w1')).payload);
    assert.deepEqual([...echo.echo.ping], [1, 2]);

    s.p.portData('c-1', 'w1', encode({ give: true }));
    const nested = decode((await s.waitFor((e) => e.messageKind === 'PORT_DATA' && e.streamId === 'w1')).payload);
    assert.ok(nested.child instanceof PortRef);
    assert.match(nested.child.id, /^n\d+$/, 'native-side port ids live in the "n" space');
    s.p.portData('c-1', nested.child.id, encode('via child'));
    const childEcho = decode((await s.waitFor((e) => e.messageKind === 'PORT_DATA' && e.streamId === nested.child.id)).payload);
    assert.deepEqual(childEcho, { childEcho: 'via child' });
  } finally {
    await s.done();
  }
});

test('host messages are mirrored except native-owned requests, internal traffic, chunks and port handoffs', async () => {
  const s = await setup();
  try {
    await s.call('c-1', 'codex.viewReady', encode(null)); // tracks the browser connection
    s.renderer.hostSays({ type: 'electron-app-state-snapshot-request' });
    s.renderer.hostSays({ type: 'codex_desktop:internal' });
    s.renderer.hostSays({ type: 'x', marker: 'codex-host-chunked-message-v1' });
    const ch = new MessageChannel();
    s.renderer.hostSays({ type: 'has-port' }, [ch.port1]);
    s.renderer.hostSays({ type: 'thread-updated', n: 1 });
    const ev = await s.waitFor((e) => e.messageKind === 'EVENT');
    assert.equal(ev.operation, 'codex.hostMessage');
    assert.deepEqual(decode(ev.payload), { type: 'thread-updated', n: 1 });
    assert.equal(await s.waitFor((e) => e.messageKind === 'EVENT', 200), undefined, 'nothing else mirrored');
    ch.port2.close();

    s.renderer.theme();
    const th = await s.waitFor((e) => e.messageKind === 'EVENT' && e.operation === 'codex.theme');
    assert.equal(decode(th.payload), 'dark');
  } finally {
    await s.done();
  }
});

test('git worker handshake: port handed to the requesting connection only; bad request ids refused', async () => {
  const s = await setup();
  try {
    assert.equal((await s.call('c-1', 'codex.connectGit', encode({ requestId: 'aaaaaaaa-1111' }))).error, undefined);
    await s.call('c-2', 'codex.viewReady', encode(null));
    const ch = new MessageChannel();
    s.renderer.hostSays({ type: 'codex_desktop:git-worker-port', requestId: 'aaaaaaaa-1111' }, [ch.port1]);
    const ev = await s.waitFor((e) => e.messageKind === 'EVENT' && e.operation === 'codex.gitPort');
    assert.equal(ev.connectionId, 'c-1');
    const v = decode(ev.payload);
    assert.equal(v.message.requestId, 'aaaaaaaa-1111');
    assert.ok(v.ports[0] instanceof PortRef);
    assert.equal(await s.waitFor((e) => e.messageKind === 'EVENT' && e.connectionId === 'c-2', 200), undefined);
    const bad = await s.call('c-1', 'codex.connectGit', encode({ requestId: '../../x' }));
    assert.equal(bad.error.code, 'MALFORMED_FRAME');
    ch.port2.close();
  } finally {
    await s.done();
  }
});

test('a startup burst of 800 port frames is applied in order without dropping the connection', async () => {
  const s = await setup();
  try {
    await s.call('c-1', 'codex.connectAppHost', encode({ type: 'connect-app-host', port: new PortRef('w1') }));
    await s.waitFor((e) => e.messageKind === 'PORT_DATA' && e.streamId === 'w1'); // hello
    for (let i = 0; i < 800; i++) s.p.portData('c-1', 'w1', encode(i));
    const seen = [];
    while (seen.length < 800) {
      const f = await s.waitFor((e) => e.messageKind === 'PORT_DATA' && e.streamId === 'w1', 10000);
      assert.ok(f, `frame ${seen.length} arrived`);
      seen.push(decode(f.payload).echo);
    }
    assert.deepEqual(seen, [...Array(800).keys()], 'order preserved');
  } finally {
    await s.done();
  }
});

test('closing a browser connection closes its relayed ports in the renderer', async () => {
  const s = await setup();
  try {
    await s.call('c-1', 'codex.connectAppHost', encode({ type: 'connect-app-host', port: new PortRef('w1') }));
    await s.waitFor((e) => e.messageKind === 'PORT_DATA' && e.streamId === 'w1');
    s.p.closeConnection('c-1');
    await sleep(100);
    assert.equal(await s.renderer.evaluate("window.__mmabCodex.closeConnection('c-1')"), false, 'already closed');
  } finally {
    await s.done();
  }
});

test('browser shim: electronBridge surface, app-host handoff through the relay, host messages replayed', async () => {
  const requests = [];
  const raw = [];
  let o;
  const listeners = new Set();
  const dispatched = [];
  const posted = [];
  const win = {
    location: { origin: 'https://h:8445' },
    addEventListener: (t, fn) => { if (t === 'message') listeners.add(fn); },
    dispatchEvent: (e) => dispatched.push(e.data),
    postMessage: (d, origin, ports) => posted.push([d, ports]),
    MessageEvent: class { constructor(t, i) { this.type = t; this.data = i.data; } },
    MutationObserver: class { observe() {} disconnect() {} },
    document: { querySelector: () => null, documentElement: {} },
    setTimeout: () => 0, console, MessageChannel, MessagePort,
    MmabCodec: globalThis.MmabCodec, MmabPorts: globalThis.MmabPorts,
    MmabTransport: {
      banner() {},
      create: (opts) => {
        o = opts;
        return {
          stats: {},
          call: (op) => (op === 'codex.bootstrap' ? { getDesktopUserAgent: 'UA', getSystemThemeVariant: 'light' } : null),
          request: (op, v) => { requests.push([op, v]); return Promise.resolve(true); },
          requestRaw: (op, text) => { raw.push([op, text]); return Promise.resolve('true'); },
          sendPortData() {}, sendPortClose() {},
        };
      },
    },
  };
  const ctx = vm.createContext(win);
  vm.runInContext(readFileSync(new URL('../shim/codex-bridge.js', import.meta.url), 'utf8'), ctx);
  const self = vm.runInContext('globalThis', ctx); // what the page sees as window
  const b = self.electronBridge;
  assert.equal(b.getDesktopUserAgent(), 'UA');
  assert.equal(b.getSystemThemeVariant(), 'light');
  await b.sendMessageFromView({ type: 'ready' });
  await b.sendMessageFromView({ type: 'persisted-atom-sync-request' });
  await b.sendMessageFromView({ type: 'open-in-new-window', path: '/x' });
  assert.deepEqual(requests.map((r) => r[0]), ['codex.viewReady', 'codex.viewRead']);
  assert.equal(self.__mmabCodexWeb.stats.droppedViewMessages, 1);
  assert.equal(self.__SENTRY_IPC__['sentry-ipc'].sendEnvelope(), undefined, 'telemetry stays local');

  const ch = new MessageChannel();
  for (const fn of listeners) fn({ source: self, origin: 'https://h:8445', data: { type: 'connect-app-host', port: ch.port1 } });
  for (const fn of listeners) fn({ source: {}, origin: 'https://h:8445', data: { type: 'connect-app-host', port: ch.port1 } });
  assert.equal(raw.length, 1, 'only the page itself can hand over an app-host port');
  assert.equal(raw[0][0], 'codex.connectAppHost');
  assert.match(raw[0][1], /"\$t":"port","id":"w1"/);
  ch.port2.close();

  o.onEvent('codex.hostMessage', { type: 'thread-updated' }, encode({ type: 'thread-updated' }));
  assert.deepEqual(dispatched, [{ type: 'thread-updated' }]);
  o.onEvent('codex.theme', 'dark', encode('dark'));
  assert.equal(b.getSystemThemeVariant(), 'dark');
  o.onClose();
});
