// M3 browser-side SDK: MessagePort relay, Electron compatibility helpers, classic transport (in a vm sandbox).
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';
import './codec.js';
import './ports.js';
import './electron-compat.js';

const { encode, decode, PortRef } = globalThis.MmabCodec;
const src = (f) => readFileSync(new URL(f, import.meta.url), 'utf8');

/** Two relays wired back to back (browser "w" <-> native "n"), like browser <-> platform <-> renderer. */
function pair() {
  let web;
  let native;
  const wire = [];
  web = globalThis.MmabPorts.create({
    prefix: 'w',
    sendData: (id, text) => { wire.push(['w->n', id]); setImmediate(() => native.receiveData(id, text)); },
    sendClose: (id) => setImmediate(() => native.receiveClose(id)),
  });
  native = globalThis.MmabPorts.create({
    prefix: 'n',
    sendData: (id, text) => { wire.push(['n->w', id]); setImmediate(() => web.receiveData(id, text)); },
    sendClose: (id) => setImmediate(() => web.receiveClose(id)),
  });
  return { web, native, wire };
}

const nextMessage = (port) => new Promise((resolve) => { port.onmessage = (e) => resolve(e); });

test('nested MessagePorts relay in both directions with typed values and separate id spaces', async () => {
  const { web, native, wire } = pair();
  const app = new MessageChannel(); // the page's own channel: port1 handed to the relay, port2 kept by the "app"
  const text = web.encode({ type: 'connect-app-host', port: app.port1, n: 1n, bytes: new Uint8Array([1, 2]) });
  assert.match(text, /"\$t":"port","id":"w1"/);
  const transfers = [];
  const delivered = native.decode(text, transfers);
  assert.equal(transfers.length, 1);
  assert.equal(delivered.n, 1n);
  assert.ok(delivered.bytes instanceof Uint8Array);
  const nativeSide = delivered.port;
  try {
    // native -> web over the relayed port, carrying a NEW nested native port
    const nested = new MessageChannel();
    const gotOnWeb = nextMessage(app.port2);
    nativeSide.postMessage({ hello: 'native', child: nested.port1 }, [nested.port1]);
    const e = await gotOnWeb;
    assert.equal(e.data.hello, 'native');
    assert.ok(e.data.child instanceof MessagePort, 'nested port arrives as a real MessagePort');
    assert.ok(wire.some(([dir, id]) => dir === 'n->w' && id === 'w1'));
    // web -> native back through the nested port
    const gotOnNative = nextMessage(nested.port2);
    e.data.child.postMessage({ back: 'web', f64: new Float64Array([1.5]) });
    const back = await gotOnNative;
    assert.equal(back.data.back, 'web');
    assert.ok(back.data.f64 instanceof Float64Array);
    assert.ok(native.size() >= 1 && web.size() >= 2);
    e.data.child.close();
    nested.port2.close();
  } finally {
    app.port2.close();
    nativeSide.close();
    web.closeAll();
    native.closeAll();
  }
  assert.equal(web.size(), 0);
  assert.equal(native.size(), 0);
});

test('relay refuses ports from its own id space, reused ids and frames for closed ports', () => {
  const r = globalThis.MmabPorts.create({ prefix: 'w', sendData() {} });
  assert.throws(() => r.decode(encode({ p: new PortRef('w5') }), []), /foreign or reused/);
  const t = [];
  r.decode(encode({ p: new PortRef('n5') }), t);
  assert.throws(() => r.decode(encode({ p: new PortRef('n5') }), []), /foreign or reused/);
  assert.equal(r.receiveClose('n5'), true);
  assert.equal(r.receiveData('n5', encode(1)), false, 'closed port: dropped');
  assert.throws(() => r.decode(encode({ p: new PortRef('n5') }), []), /foreign or reused/, 'ids are never reused');
  for (const p of t) p.close();
  assert.throws(() => globalThis.MmabPorts.create({ prefix: 'x', sendData() {} }));
});

test('SHA-256 fill-in matches node:crypto', () => {
  for (const s of ['', 'abc', 'a'.repeat(55), 'a'.repeat(56), 'a'.repeat(64), '原版 😀'.repeat(40)]) {
    const data = new TextEncoder().encode(s);
    const ours = Buffer.from(globalThis.MmabElectronCompat.sha256(data)).toString('hex');
    assert.equal(ours, createHash('sha256').update(data).digest('hex'), JSON.stringify(s.slice(0, 8)));
  }
});

test('same-world contextBridge: normal API, identical immutable snapshot, conflicting immutable value', () => {
  const { contextBridge } = globalThis.MmabElectronCompat;
  const win = {};
  const api = {};
  contextBridge(win).exposeInMainWorld('desktopApi', api);
  assert.equal(win.desktopApi, api);
  const snapshot = Object.freeze({ version: 2 });
  Object.defineProperty(win, '__startup', { value: snapshot });
  contextBridge(win).exposeInMainWorld('__startup', snapshot);
  assert.equal(win.__startup, snapshot);
  assert.throws(() => contextBridge(win).exposeInMainWorld('__startup', {}), TypeError);
});

test('insecure-context fill-ins: randomUUID, digest, clipboard, mediaDevices without getUserMedia', async () => {
  const win = {
    crypto: { getRandomValues: (a) => globalThis.crypto.getRandomValues(a) },
    navigator: {},
    document: {},
  };
  const filled = globalThis.MmabElectronCompat.polyfillInsecureContext(win);
  assert.deepEqual(filled, ['crypto.randomUUID', 'crypto.subtle.digest', 'navigator.clipboard', 'navigator.mediaDevices']);
  assert.match(win.crypto.randomUUID(), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  const d = await win.crypto.subtle.digest('SHA-256', new TextEncoder().encode('abc'));
  assert.equal(Buffer.from(d).toString('hex'), createHash('sha256').update('abc').digest('hex'));
  await assert.rejects(win.crypto.subtle.digest('SHA-1', new Uint8Array()));
  assert.equal(typeof win.navigator.mediaDevices.getUserMedia, 'undefined');
  assert.deepEqual(await win.navigator.mediaDevices.enumerateDevices(), []);
  assert.deepEqual(globalThis.MmabElectronCompat.polyfillInsecureContext({
    crypto: globalThis.crypto, navigator: { clipboard: {}, mediaDevices: {} },
  }), [], 'secure context: nothing replaced');
});

/** Loads codec.js + transport.js into a sandbox with fake WebSocket / XHR / document. */
function transportSandbox({ xhrResponse = { status: 200, body: '{"payload":"1"}' }, extra = {} } = {}) {
  const sockets = [];
  const xhrs = [];
  const nodes = new Map();
  class FakeWS extends EventTarget {
    static OPEN = 1;
    constructor(url) {
      super();
      this.url = url;
      this.readyState = 0;
      this.sent = [];
      sockets.push(this);
    }
    send(t) { this.sent.push(JSON.parse(t)); }
    open() { this.readyState = 1; this.dispatchEvent(new Event('open')); }
    message(obj) { this.dispatchEvent(Object.assign(new Event('message'), { data: JSON.stringify(obj) })); }
    shut(code, reason) {
      this.readyState = 3;
      this.dispatchEvent(Object.assign(new Event('close'), { code, reason }));
    }
  }
  class FakeXHR {
    constructor() { xhrs.push(this); this.headers = {}; }
    open(m, u, async) { Object.assign(this, { m, u, async }); }
    setRequestHeader(k, v) { this.headers[k] = v; }
    send(body) { this.body = body; this.status = xhrResponse.status; this.responseText = xhrResponse.body; }
  }
  const document = {
    readyState: 'complete',
    body: { appendChild: (n) => nodes.set(n.id, n) },
    getElementById: (id) => nodes.get(id),
    createElement: () => ({ style: {}, setAttribute() {}, children: [], appendChild(c) { this.children.push(c); }, addEventListener() {} }),
    addEventListener() {},
  };
  const ctx = vm.createContext({
    WebSocket: FakeWS, XMLHttpRequest: FakeXHR, document, location: { protocol: 'https:', host: '10.0.0.5:8445' },
    setInterval: () => 1, clearInterval() {}, console, Event, EventTarget, TextEncoder, TextDecoder, btoa, atob,
    ...extra,
  });
  ctx.globalThis = ctx;
  vm.runInContext(src('./codec.js'), ctx);
  vm.runInContext(src('./transport.js'), ctx);
  return { ctx, sockets, xhrs, nodes };
}

test('transport queues until open, matches responses, routes events / port frames and shows close banners', async () => {
  const { ctx, sockets, nodes } = transportSandbox();
  const events = [];
  const portFrames = [];
  const t = ctx.MmabTransport.create({
    onEvent: (op, v) => events.push([op, v]),
    onPortData: (port, text) => portFrames.push([port, text]),
  });
  const ws = sockets[0];
  assert.equal(ws.url, 'wss://10.0.0.5:8445/__mmab/bridge/ws');
  const inCtx = (code) => vm.runInContext(`(${code})`, ctx); // values from the page's own realm
  const p = t.request('qwenwork.invoke', inCtx("{ ch: 'x', args: [1n] }"));
  assert.equal(ws.sent.length, 0, 'queued until open');
  ws.open();
  assert.equal(ws.sent.length, 1);
  assert.equal(ws.sent[0].kind, 'REQUEST');
  assert.equal(ws.sent[0].op, 'qwenwork.invoke');
  ws.message({ kind: 'RESPONSE', cid: ws.sent[0].cid, payload: encode({ ok: true }) });
  assert.deepEqual({ ...(await p) }, { ok: true });
  ws.message({ kind: 'EVENT', op: 'qwenwork.ipc', payload: encode({ ch: 'a', args: [] }) });
  assert.equal(events[0][0], 'qwenwork.ipc');
  ws.message({ kind: 'PORT_DATA', port: 'n1', payload: '1' });
  assert.deepEqual(portFrames, [['n1', '1']]);
  const failing = t.request('qwenwork.invoke', inCtx('{}'));
  ws.message({ kind: 'RESPONSE', cid: ws.sent[1].cid, error: { code: 'CAPABILITY_DISABLED' } });
  await assert.rejects(failing, (e) => e.code === 'CAPABILITY_DISABLED');
  const dangling = t.request('qwenwork.invoke', inCtx('{}'));
  ws.shut(1013, 'LEASE_OCCUPIED');
  await assert.rejects(dangling, (e) => e.code === 'LEASE_OCCUPIED');
  assert.match(nodes.get('__mmab_banner').textContent, /另一个标签页/);
  assert.throws(() => t.sendPortData('n1', '1'), (e) => e.code === 'LEASE_OCCUPIED');
  assert.equal(nodes.get('__mmab_banner').children.length, 0, 'occupied: no reload button');
});

test('abnormal close (1006) tells the user a reload reconnects and offers a button', () => {
  const { ctx, sockets, nodes } = transportSandbox();
  ctx.MmabTransport.create();
  sockets[0].open();
  sockets[0].shut(1006, '');
  const b = nodes.get('__mmab_banner');
  assert.match(b.textContent, /1006/);
  assert.match(b.textContent, /刷新本页即可重新连接/);
  assert.equal(b.children.length, 1);
  assert.equal(b.children[0].textContent, '刷新重连');
});

test('transport sync call uses the sync endpoint and surfaces gateway errors', () => {
  const ok = transportSandbox({ xhrResponse: { status: 200, body: JSON.stringify({ payload: encode({ v: 2 }) }) } });
  const t = ok.ctx.MmabTransport.create();
  assert.equal(t.call('codex.bootstrap', undefined).v, 2);
  const x = ok.xhrs[0];
  assert.equal(x.m, 'POST');
  assert.equal(x.u, '/__mmab/bridge/call');
  assert.equal(x.async, false);
  assert.equal(x.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(x.body), { op: 'codex.bootstrap', payload: '{"$t":"undef"}' });
  const denied = transportSandbox({ xhrResponse: { status: 200, body: '{"error":{"code":"UNKNOWN_OPERATION"}}' } });
  assert.throws(() => denied.ctx.MmabTransport.create().call('x.y', 1), (e) => e.code === 'UNKNOWN_OPERATION');
  const http = transportSandbox({ xhrResponse: { status: 401, body: '' } });
  assert.throws(() => http.ctx.MmabTransport.create().call('x.y', 1), (e) => e.code === 'HTTP_401');
});

test('codec.js defines MmabCodec once and codec.mjs re-exports the same classes', async () => {
  const m = await import('./codec.mjs');
  assert.equal(m.PortRef, globalThis.MmabCodec.PortRef);
  assert.equal(decode(encode(new m.PortRef('w1'))).id, 'w1');
  const before = globalThis.MmabCodec;
  vm.runInThisContext(src('./codec.js'));
  assert.equal(globalThis.MmabCodec, before, 'second load is a no-op');
});

test('disconnect diagnostics: close / unload / next-load beacons carry metadata and names only', async () => {
  const beacons = [];
  const store = new Map();
  const winListeners = {};
  const extra = {
    navigator: { sendBeacon: (url, blob) => { beacons.push([url, blob]); return true; } },
    Blob, Date, JSON,
    sessionStorage: { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, v), removeItem: (k) => store.delete(k) },
    addEventListener: (t, fn) => { winListeners[t] = fn; },
    performance: { getEntriesByType: () => [{ type: 'navigate' }] },
  };
  const { ctx, sockets } = transportSandbox({ extra });
  const t = ctx.MmabTransport.create({ label: (op, v) => (v && v.channel) || op, onEvent() {} });
  sockets[0].open();
  sockets[0].message({ kind: 'EVENT', op: 'qwenwork.ipc', payload: encode({ channel: 'app:state', args: ['SECRET-CONTENT'] }) });
  t.note('view:ready');
  t.note('bad name with spaces');
  t.note('x'.repeat(65));
  sockets[0].shut(1006, '');
  const read = async (i) => JSON.parse(await beacons[i][1].text());
  assert.equal(beacons[0][0], '/__mmab/bridge/diag');
  const close = await read(0);
  assert.equal(close.e, 'close');
  assert.equal(close.code, 1006);
  assert.equal(close.clean, false);
  assert.equal(close.unl, false);
  assert.deepEqual(close.recent, ['app:state', 'view:ready'], 'names only, invalid names dropped');
  assert.ok(Number.isInteger(close.up) && Number.isInteger(close.ws));
  assert.doesNotMatch(JSON.stringify(close), /SECRET/);

  winListeners.pagehide();
  const unload = await read(1);
  assert.equal(unload.e, 'unload');
  assert.ok(store.has('__mmab_last_unload'));

  // the next page load in the same tab reports the previous unload
  const again = transportSandbox({ extra: { ...extra, performance: { getEntriesByType: () => [{ type: 'reload' }] } } });
  again.ctx.MmabTransport.create();
  const load = await read(2);
  assert.equal(load.e, 'load');
  assert.equal(load.nav, 'reload');
  assert.equal(load.prev.e, 'unload');
  assert.equal(store.has('__mmab_last_unload'), false);
});
