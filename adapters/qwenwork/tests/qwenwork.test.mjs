// qwenwork adapter (S1) offline contract: payload inside a FAKE Electron main process <-> authenticated link <->
// driver <-> platform (played by sdk/driver-node/testing/platform-client.mjs). Never touches the real app.
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import net from 'node:net';
import crypto from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import vm from 'node:vm';
import '../../../sdk/web/codec.js';
import '../../../sdk/web/electron-compat.js';
import { inProcess, platform } from '../../../sdk/driver-node/testing/platform-client.mjs';
import { createDriver } from '../driver/main.mjs';
import { connectLink } from '../driver/link-client.mjs';
import { readHandoff } from '../driver/process-check.mjs';
import { wrapPreload } from '../tools/extract.mjs';
import { payloadExpression } from '../tools/inject.mjs';

const require = createRequire(import.meta.url);
const { createQwenBridge } = require('../payload/main-bridge.js');
const { encode, decode } = globalThis.MmabCodec;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ------------------------------------------------------------------ fake Electron main process
function fakeElectron() {
  const sent = [];
  class WebContents {
    constructor() { this.mainFrame = { routingId: 1, processId: 2 }; }
    getURL() { return 'file:///renderer/index.html#windowId=main'; }
    getLastWebPreferences() { return { additionalArguments: ['--qw-test=1'] }; }
    send(ch, ...args) { sent.push([ch, args]); }
  }
  const native = [];
  const fx = { sent, WebContents, native, focused: 0 };
  class BrowserWindow {
    constructor() { this.webContents = new WebContents(); }
    isDestroyed() { return false; }
    focus() { fx.focused++; }
    static getAllWindows() { return [win]; }
  }
  const win = new BrowserWindow();
  const ipcMain = new EventEmitter();
  ipcMain._invokeHandlers = new Map();
  ipcMain.handle = (ch, fn) => ipcMain._invokeHandlers.set(ch, fn);
  const dialog = {
    showOpenDialog: async (...a) => { native.push(['open', a]); return { canceled: true, filePaths: [] }; },
    showSaveDialog: async (...a) => { native.push(['save', a]); return { canceled: true, filePath: '' }; },
  };
  return Object.assign(fx, {
    electron: { ipcMain, BrowserWindow, dialog, app: { getLocale: () => 'zh-CN' } },
    win,
  });
}

function handoffFile(dir, h) {
  const d = path.join(dir, 'qwenwork');
  mkdirSync(d, { recursive: true, mode: 0o700 });
  chmodSync(d, 0o700);
  const f = path.join(d, 'link.json');
  writeFileSync(f, JSON.stringify(h), { mode: 0o600 });
  return f;
}

async function setup(o = {}) {
  const fx = fakeElectron();
  const bridge = createQwenBridge({ electron: fx.electron, net, crypto, codec: globalThis.MmabCodec, asyncHooks: o.asyncHooks });
  const key = crypto.randomBytes(32);
  const { port } = await bridge.start(key.toString('base64'));
  const dir = mkdtempSync(path.join(os.tmpdir(), 'mmab-qw-'));
  const linkFile = handoffFile(dir, { v: 1, pid: 4242, started: 'test', port, key: key.toString('base64') });
  const verified = [];
  const driver = createDriver({ linkFile, verify: (h) => verified.push(h.pid), pickRoots: o.pickRoots });
  const adapter = await inProcess({ handlers: driver.handlers, onConnectionClosed: driver.onConnectionClosed });
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
  const call = async (conn, op, value) => {
    const cid = p.request(conn, op, encode(value));
    return waitFor((e) => e.messageKind === 'RESPONSE' && e.correlationId === cid);
  };
  const done = async () => {
    p.close();
    driver.close();
    await adapter.close();
    await bridge.stop();
    rmSync(dir, { recursive: true, force: true });
  };
  // fire a request without waiting; returns its correlation id
  const fire = (conn, op, value) => p.request(conn, op, encode(value));
  return { fx, bridge, key, port, p, call, fire, waitFor, verified, done, linkFile };
}

test('bootstrap, invoke with main-window identity, unknown channel and deny list', async () => {
  const s = await setup();
  try {
    const boot = decode((await s.call('sync-1', 'qwenwork.bootstrap', null)).payload);
    assert.ok(boot.argv.includes('--qw-test=1'));
    assert.equal(boot.argv[2], '--lang=zh-CN');
    assert.deepEqual(s.verified, [4242], 'handoff verified before the first connection');

    let seen;
    s.fx.electron.ipcMain.handle('desktop:ping', (ev, a, b) => {
      seen = ev;
      return { sum: a + b, bytes: Buffer.from('hi'), tags: new Map([['k', new Set([1])]]) };
    });
    const r = decode((await s.call('c-1', 'qwenwork.invoke', { channel: 'desktop:ping', args: [2, 3] })).payload);
    assert.equal(r.ok, true);
    assert.equal(r.v.sum, 5);
    assert.deepEqual([...r.v.bytes], [104, 105]);
    assert.ok(r.v.tags.get('k').has(1));
    assert.equal(seen.sender, s.fx.win.webContents, 'sender is the main window');
    assert.equal(seen.senderFrame, s.fx.win.webContents.mainFrame);

    const missing = decode((await s.call('c-1', 'qwenwork.invoke', { channel: 'nope:x', args: [] })).payload);
    assert.equal(missing.ok, false);
    assert.match(missing.message, /No handler registered/);

    let closed = false;
    s.fx.electron.ipcMain.handle('window:close', () => { closed = true; });
    const denied = decode((await s.call('c-1', 'qwenwork.invoke', { channel: 'window:close', args: [] })).payload);
    assert.deepEqual(denied, { ok: true, v: undefined });
    assert.equal(closed, false, 'denied channels never reach the app');

    s.fx.electron.ipcMain.handle('desktop:boom', () => { throw new Error('kaput'); });
    const boom = decode((await s.call('c-1', 'qwenwork.invoke', { channel: 'desktop:boom', args: [] })).payload);
    assert.equal(boom.ok, false);
    assert.match(boom.message, /kaput/);
  } finally {
    await s.done();
  }
});

test('tRPC ids are remapped per connection, replies routed back, subscriptions stopped on close', async () => {
  const s = await setup();
  try {
    const ops = [];
    s.fx.electron.ipcMain.on('trpc-electron', (ev, msg) => {
      ops.push(msg);
      if (msg.method === 'request') {
        ev.reply('trpc-electron', { id: msg.operation.id, result: { type: 'data', data: { echo: msg.operation.input } } });
      }
    });
    const req = { method: 'request', operation: { id: 7, type: 'subscription', path: 'x.y', input: 'hello' } };
    const ack = await s.call('c-1', 'qwenwork.send', { channel: 'trpc-electron', args: [req] });
    assert.equal(ack.error, undefined);
    assert.ok(ops[0].operation.id >= 3_000_000_000, 'bridge id space is distinct from the app and the legacy bridge');

    const ev = await s.waitFor((e) => e.messageKind === 'EVENT' && e.connectionId === 'c-1');
    assert.equal(ev.operation, 'qwenwork.ipc');
    const v = decode(ev.payload);
    assert.equal(v.channel, 'trpc-electron');
    assert.equal(v.args[0].id, 7, 'client id restored');
    assert.deepEqual(v.args[0].result.data, { echo: 'hello' });

    // the browser connection goes away: its open subscription is stopped with the BRIDGE id
    s.p.closeConnection('c-1');
    await sleep(150);
    const stop = ops.find((m) => m.method === 'subscription.stop');
    assert.ok(stop, 'subscription stopped');
    assert.equal(stop.id, ops[0].operation.id);
  } finally {
    await s.done();
  }
});

test('main-process pushes are mirrored to every tracked connection and still reach the main window', async () => {
  const s = await setup();
  try {
    await s.call('c-1', 'qwenwork.invoke', { channel: 'nope:warmup', args: [] });
    await s.call('c-2', 'qwenwork.invoke', { channel: 'nope:warmup', args: [] });
    s.fx.win.webContents.send('app:state', { n: 1 });
    s.fx.win.webContents.send('trpc-electron', { id: 1 }); // tRPC is answered per connection, never mirrored
    const a = await s.waitFor((e) => e.messageKind === 'EVENT' && e.connectionId === 'c-1');
    const b = await s.waitFor((e) => e.messageKind === 'EVENT' && e.connectionId === 'c-2');
    for (const e of [a, b]) assert.deepEqual(decode(e.payload), { channel: 'app:state', args: [{ n: 1 }] });
    assert.equal(await s.waitFor((e) => e.messageKind === 'EVENT', 300), undefined, 'no tRPC mirror');
    assert.deepEqual(s.fx.sent.map((x) => x[0]), ['app:state', 'trpc-electron'], 'original send still called');
  } finally {
    await s.done();
  }
});

test('sendSync returns event.returnValue; denied sendSync answers undefined', async () => {
  const s = await setup();
  try {
    s.fx.electron.ipcMain.on('sync:get', (ev, x) => { ev.returnValue = { got: x }; });
    assert.deepEqual(decode((await s.call('sync-2', 'qwenwork.sendSync', { channel: 'sync:get', args: [5] })).payload), { got: 5 });
    assert.equal(decode((await s.call('sync-3', 'qwenwork.sendSync', { channel: 'tray:x', args: [] })).payload), undefined);
  } finally {
    await s.done();
  }
});

test('link refuses a wrong key; payload stays reachable for the right one', async () => {
  const s = await setup();
  try {
    await assert.rejects(connectLink({ port: s.port, key: crypto.randomBytes(32) }), /ADAPTER_UNAVAILABLE/);
    // an unauthenticated peer must not get frames processed either
    const raw = net.connect({ port: s.port, host: '127.0.0.1' });
    await new Promise((r) => raw.once('data', r));
    raw.write(`${JSON.stringify({ t: 'call', id: 1, kind: 'bootstrap', conn: null, p: encode(null) })}\n`);
    const closed = await new Promise((r) => { raw.on('close', () => r(true)); setTimeout(() => r(false), 1000); });
    assert.equal(closed, true, 'unauthenticated frame closes the socket');
    const boot = await s.call('sync-9', 'qwenwork.bootstrap', null);
    assert.equal(boot.error, undefined);
  } finally {
    await s.done();
  }
});

test('driver without a valid handoff answers ADAPTER_UNAVAILABLE; handoff permissions are enforced', async () => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'mmab-qw-'));
  try {
    const driver = createDriver({ linkFile: path.join(dir, 'missing', 'link.json') });
    const adapter = await inProcess({ handlers: driver.handlers });
    driver.bind(adapter.api);
    const p = await platform(adapter);
    p.fence('lease-1', 1);
    const r = await p.response(p.request('c-1', 'qwenwork.invoke', encode({ channel: 'a', args: [] })));
    assert.equal(r.error.code, 'ADAPTER_UNAVAILABLE');
    p.close();
    await adapter.close();

    const f = handoffFile(dir, { v: 1, pid: 2, started: 'x', port: 5000, key: crypto.randomBytes(32).toString('base64') });
    assert.equal(readHandoff(f).port, 5000);
    chmodSync(f, 0o644);
    assert.throws(() => readHandoff(f), /UNAUTHENTICATED/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('preload wrapper runs the CommonJS preload against the shim globals', () => {
  const src = "const { contextBridge, ipcRenderer } = require('electron');\n"
    + "contextBridge.exposeInMainWorld('desktopApi', { platform: process.platform, ping: () => ipcRenderer.invoke('p') });";
  const calls = [];
  const window = { __mmabQwProcess: { platform: 'darwin' } };
  const api = {
    contextBridge: { exposeInMainWorld: (k, v) => { window[k] = v; } },
    ipcRenderer: { invoke: (c) => { calls.push(c); return Promise.resolve(1); } },
  };
  window.__mmabQwRequire = (n) => (n === 'electron' ? api : undefined);
  vm.runInNewContext(wrapPreload(src), { window });
  assert.equal(window.desktopApi.platform, 'darwin');
  window.desktopApi.ping();
  assert.deepEqual(calls, ['p']);
});

test('injected expression is self-contained and only binds loopback (evaluated against the fake app)', async () => {
  const fx = fakeElectron();
  const key = crypto.randomBytes(32).toString('base64');
  const expr = payloadExpression(key);
  assert.ok(!expr.includes('require(\'ws\')'), 'no third-party modules');
  const sandboxProcess = { ...process, mainModule: { require: (n) => ({ electron: fx.electron, net, crypto })[n] } };
  const ctx = vm.createContext({ process: sandboxProcess, Buffer, setTimeout, clearTimeout, atob, btoa, console });
  ctx.globalThis = ctx;
  const r = await vm.runInContext(expr, ctx);
  try {
    assert.ok(Number.isSafeInteger(r.port));
    assert.equal(ctx.__mmabQwenBridge.status().listening, true);
    const link = await connectLink({ port: r.port, key: Buffer.from(key, 'base64') });
    const boot = decode(await link.call('bootstrap', null, encode(null)));
    assert.ok(boot.argv.includes('--qw-test=1'));
    link.close();
  } finally {
    await ctx.__mmabQwenBridge.stop();
  }
});

test('browser shim: Electron surface over the transport, local clipboard / links, main-window hash', async () => {
  const requests = [];
  const calls = [];
  let onEvent;
  const opened = [];
  const copied = [];
  const win = {
    location: { pathname: '/', search: '', hash: '' },
    history: { replaceState: (_s, _t, u) => { win.location.hash = u.slice(u.indexOf('#')); } },
    navigator: { clipboard: { writeText: (t) => { copied.push(t); return Promise.resolve(); } }, mediaDevices: {} },
    crypto: globalThis.crypto,
    open: (u) => opened.push(u),
    console,
    MmabElectronCompat: globalThis.MmabElectronCompat,
    MmabTransport: {
      create: (o) => {
        onEvent = o.onEvent;
        return {
          request: (op, value) => {
            requests.push([op, value]);
            return Promise.resolve(op === 'qwenwork.invoke' ? { ok: value.channel !== 'bad', v: 'ok', message: 'nope' } : null);
          },
          call: (op, value) => {
            calls.push([op, value]);
            return op === 'qwenwork.bootstrap' ? { platform: 'darwin', arch: 'arm64', argv: ['x'], versions: {} } : 99;
          },
        };
      },
    },
  };
  win.globalThis = win;
  vm.runInNewContext(readFileSync(new URL('../shim/qwen-electron.js', import.meta.url), 'utf8'), win);
  assert.equal(win.location.hash, '#windowId=main');
  assert.equal(win.__mmabQwProcess.platform, 'darwin');
  const { ipcRenderer, contextBridge } = win.__mmabQwRequire('electron');
  assert.equal(await ipcRenderer.invoke('desktop:x', 1), 'ok');
  await assert.rejects(ipcRenderer.invoke('bad'), /nope/);
  await ipcRenderer.invoke('clipboard:write', { text: 'copy me' });
  await ipcRenderer.invoke('shell:open-external', 'https://example.com/');
  await ipcRenderer.invoke('shell:open-external', 'file:///etc/passwd');
  assert.deepEqual(copied, ['copy me']);
  assert.deepEqual(opened, ['https://example.com/'], 'only web links are opened locally');
  assert.equal(ipcRenderer.sendSync('s:y', 2), 99);
  ipcRenderer.send('trpc-electron', { method: 'request' });
  assert.deepEqual(requests.map((r) => r[0]), ['qwenwork.invoke', 'qwenwork.invoke', 'qwenwork.send']);
  const got = [];
  ipcRenderer.on('app:state', (_e, v) => got.push(v));
  onEvent('qwenwork.ipc', { channel: 'app:state', args: [{ n: 1 }] });
  onEvent('other.op', { channel: 'app:state', args: [{ n: 2 }] });
  assert.deepEqual(got, [{ n: 1 }]);
  contextBridge.exposeInMainWorld('desktopApi', { a: 1 });
  assert.equal(win.desktopApi.a, 1);
  assert.throws(() => win.__mmabQwRequire('fs'), /not available/);
});

test('file.pick: lists only inside the allowed root, hides dot entries, never follows links outside', async () => {
  const { createFsPick } = await import('../driver/fs-pick.mjs');
  const base = mkdtempSync(path.join(os.tmpdir(), 'mmab-pick-'));
  try {
    const root = path.join(base, 'home');
    const outside = path.join(base, 'outside');
    mkdirSync(path.join(root, 'proj', 'src'), { recursive: true });
    mkdirSync(path.join(root, '.hidden'));
    mkdirSync(outside);
    writeFileSync(path.join(root, 'notes.txt'), 'abc');
    const { symlinkSync, realpathSync } = await import('node:fs');
    symlinkSync(outside, path.join(root, 'escape'));
    symlinkSync(path.join(root, 'proj'), path.join(root, 'proj-link'));
    const realRoot = realpathSync(root);
    const pick = createFsPick({ roots: [root] });

    const top = await pick.list({});
    assert.equal(top.ok, true);
    assert.equal(top.path, realRoot);
    assert.equal(top.parent, null, 'no way up from the root');
    assert.deepEqual(top.entries, [{ name: 'proj', type: 'folder' }, { name: 'proj-link', type: 'folder' }],
      'folders only by default; dot entries and links leaving the root are hidden');
    const withFiles = await pick.list({ path: root, files: true, hidden: true });
    assert.deepEqual(withFiles.entries.map((e) => e.name), ['.hidden', 'proj', 'proj-link', 'notes.txt']);

    const sub = await pick.list({ path: path.join(root, 'proj') });
    assert.equal(sub.parent, realRoot);
    assert.deepEqual(sub.entries, [{ name: 'src', type: 'folder' }]);

    assert.equal((await pick.list({ path: outside })).reason, 'OUTSIDE_ROOT');
    assert.equal((await pick.list({ path: path.join(root, 'escape') })).reason, 'OUTSIDE_ROOT', 'realpath first');
    assert.equal((await pick.list({ path: path.join(root, '..') })).reason, 'OUTSIDE_ROOT');
    assert.equal((await pick.list({ path: path.join(root, 'nope') })).reason, 'NOT_FOUND');
    assert.equal((await pick.list({ path: path.join(root, 'notes.txt') })).reason, 'NOT_DIRECTORY');
    assert.equal((await pick.list({ path: 'relative/x' })).reason, 'NOT_ABSOLUTE');

    const d = await pick.describe({ paths: [path.join(root, 'proj'), path.join(root, 'notes.txt')] });
    assert.deepEqual(d, { ok: true, paths: [
      { path: path.join(realRoot, 'proj'), name: 'proj', type: 'folder', size: 0 },
      { path: path.join(realRoot, 'notes.txt'), name: 'notes.txt', type: 'file', size: 3 },
    ] });
    assert.equal((await pick.describe({ paths: [path.join(root, 'escape')] })).reason, 'OUTSIDE_ROOT');
  } finally {
    rmSync(base, { recursive: true, force: true });
  }
});

test('browser shim: folder picks are answered in the page; forwarded native dialogs open the page picker', async () => {
  const requests = [];
  let onEvent;
  const pickerCalls = [];
  const toasts = [];
  const win = {
    location: { pathname: '/', search: '', hash: '#windowId=main' },
    history: { replaceState: () => {} },
    navigator: { clipboard: { writeText: () => Promise.resolve() }, mediaDevices: {} },
    crypto: globalThis.crypto,
    setTimeout,
    console,
    MmabElectronCompat: globalThis.MmabElectronCompat,
    __mmabQwPicker: {
      open: (o) => {
        pickerCalls.push(o);
        return o.list({ path: '' }).then(() => ({ canceled: false, paths: [{ path: '/Users/u/proj', name: 'proj', type: 'folder', size: 0 }] }));
      },
      toast: (t) => toasts.push(t),
    },
    MmabTransport: {
      create: (o) => ({
        _: (onEvent = o.onEvent),
        request: (op, value) => {
          requests.push([op, value]);
          return Promise.resolve(op === 'qwenwork.fs.list' ? { ok: true, path: '/Users/u', entries: [] } : { ok: true });
        },
        call: () => ({ platform: 'darwin', arch: 'arm64', argv: [], versions: {} }),
      }),
    },
  };
  win.globalThis = win;
  vm.runInNewContext(readFileSync(new URL('../shim/qwen-electron.js', import.meta.url), 'utf8'), win);
  const { ipcRenderer } = win.__mmabQwRequire('electron');
  const replies = [];
  ipcRenderer.on('trpc-electron', (_e, m) => replies.push(m));

  ipcRenderer.send('trpc-electron', { method: 'request', operation: { id: 7, type: 'mutation',
    path: 'files.selectFilesAndFolders', input: { json: { type: 'folders', multiSelect: false, allowCreateDirectory: true } } } });
  ipcRenderer.send('trpc-electron', { method: 'request', operation: { id: 9, type: 'query', path: 'localProjects.list' } });
  await sleep(20);

  assert.deepEqual(requests.map((r) => r[0]), ['qwenwork.fs.list', 'qwenwork.send'],
    'the folder pick stays in the page; the ordinary query is forwarded');
  assert.equal(requests[1][1].args[0].operation.path, 'localProjects.list');
  assert.equal(pickerCalls.length, 1);
  assert.equal(pickerCalls[0].type, 'folders');
  assert.equal(pickerCalls[0].multiSelect, false);
  assert.deepEqual(JSON.parse(JSON.stringify(replies.find((m) => m.id === 7))), { id: 7, result: { type: 'data', data: { json: {
    canceled: false, paths: [{ path: '/Users/u/proj', name: 'proj', type: 'folder', size: 0 }] } } } });

  // payload-forwarded native dialogs (save as / import): picker in the page, answer through the driver
  requests.length = 0;
  pickerCalls.length = 0;
  win.__mmabQwPicker.open = (o) => {
    pickerCalls.push(o);
    return Promise.resolve(o.mode === 'save' ? { canceled: false, filePath: '/Users/u/Downloads/a.png' }
      : { canceled: false, paths: [{ path: '/Users/u/skill.zip', name: 'skill.zip', type: 'file', size: 9 }] });
  };
  onEvent('qwenwork.ipc', { channel: 'mmab:dialog', args: [{ id: 3, kind: 'save', options: { defaultPath: '/Users/u/Downloads/x.png', filters: [{ name: 'PNG', extensions: ['png'] }] } }] });
  onEvent('qwenwork.ipc', { channel: 'mmab:dialog', args: [{ id: 4, kind: 'open', options: { properties: ['openFile'], filters: [{ name: 'ZIP', extensions: ['zip'] }] } }] });
  await sleep(20);
  assert.equal(pickerCalls[0].mode, 'save');
  assert.equal(pickerCalls[0].defaultPath, '/Users/u/Downloads/x.png');
  assert.equal(pickerCalls[1].type, 'files');
  assert.equal(pickerCalls[1].multiSelect, false);
  assert.deepEqual(JSON.parse(JSON.stringify(requests)), [
    ['qwenwork.dialog.answer', { id: 3, kind: 'save', result: { canceled: false, filePath: '/Users/u/Downloads/a.png' } }],
    ['qwenwork.dialog.answer', { id: 4, kind: 'open', result: { canceled: false, filePaths: ['/Users/u/skill.zip'] } }],
  ]);
  assert.equal(replies.filter((m) => m.id === 3 || m.id === 4).length, 0, 'dialog events never reach the app listeners');
  assert.equal(toasts.length, 0);
});

test('payload: dialogs started by a browser connection go to that page; the Mac keeps its own dialogs', async () => {
  const asyncHooks = await import('node:async_hooks');
  const base = mkdtempSync(path.join(os.tmpdir(), 'mmab-dlg-'));
  const { realpathSync, symlinkSync } = await import('node:fs');
  const root = realpathSync(base);
  mkdirSync(path.join(root, 'Downloads'));
  writeFileSync(path.join(root, 'skill.zip'), 'zip');
  symlinkSync(os.tmpdir(), path.join(root, 'out'));
  const s = await setup({ asyncHooks, pickRoots: [root] });
  try {
    const { electron, win } = s.fx;
    electron.ipcMain.handle('files:save-as', async () => {
      win.focus();
      return electron.dialog.showSaveDialog(win, { title: 'Save', defaultPath: path.join(root, 'Downloads', 'a.png'), filters: [{ name: 'PNG', extensions: ['png'] }], bogus: 1 });
    });
    let picked;
    electron.ipcMain.on('skills:pick', async () => {
      picked = await electron.dialog.showOpenDialog({ properties: ['openFile', 'evilProp'], filters: [{ name: 'ZIP', extensions: ['zip'] }] });
    });
    const dialogEvent = async () => {
      const e = await s.waitFor((f) => f.messageKind === 'EVENT' && f.operation === 'qwenwork.ipc'
        && decode(f.payload).channel === 'mmab:dialog');
      assert.ok(e, 'dialog event reached the page');
      return { conn: e.connectionId, d: decode(e.payload).args[0] };
    };
    // save as, started from browser connection c-1
    const cid = s.fire('c-1', 'qwenwork.invoke', { channel: 'files:save-as', args: [] });
    const { conn, d } = await dialogEvent();
    assert.equal(conn, 'c-1');
    assert.equal(d.kind, 'save');
    assert.deepEqual(d.options, { title: 'Save', defaultPath: path.join(root, 'Downloads', 'a.png'), filters: [{ name: 'PNG', extensions: ['png'] }] });
    assert.equal(s.fx.native.length, 0, 'nothing opened on the Mac');
    assert.equal(s.fx.focused, 0, 'the Mac window is not brought to the front');
    const ans = async (c, v) => decode((await s.call(c, 'qwenwork.dialog.answer', v)).payload);
    assert.equal((await ans('c-1', { id: d.id, kind: 'save', result: { filePath: path.join(root, 'out', 'x.png') } })).reason, 'OUTSIDE_ROOT');
    assert.equal((await ans('c-1', { id: d.id, kind: 'save', result: { filePath: path.join(root, 'Downloads') } })).reason, 'IS_DIRECTORY');
    assert.equal((await ans('c-2', { id: d.id, kind: 'save', result: { filePath: path.join(root, 'Downloads', 'b.png') } })).reason,
      'NO_SUCH_DIALOG', 'only the page that was asked may answer');
    assert.deepEqual(await ans('c-1', { id: d.id, kind: 'save', result: { filePath: path.join(root, 'Downloads', 'b.png') } }), { ok: true });
    const res = await s.waitFor((f) => f.messageKind === 'RESPONSE' && f.correlationId === cid);
    assert.deepEqual(decode(res.payload), { ok: true, v: { canceled: false, filePath: path.join(root, 'Downloads', 'b.png') } });
    assert.equal((await ans('c-1', { id: d.id, kind: 'save', result: { canceled: true } })).reason, 'NO_SUCH_DIALOG', 'answered once');

    // open dialog started through a plain send (the tRPC path uses send too)
    await s.call('c-1', 'qwenwork.send', { channel: 'skills:pick', args: [] });
    const o = await dialogEvent();
    assert.deepEqual(o.d.options.properties, ['openFile'], 'unknown dialog properties are dropped');
    assert.equal((await ans('c-1', { id: o.d.id, kind: 'open', result: { filePaths: ['/etc/hosts'] } })).reason, 'OUTSIDE_ROOT');
    assert.deepEqual(await ans('c-1', { id: o.d.id, kind: 'open', result: { filePaths: [path.join(root, 'skill.zip')] } }), { ok: true });
    await sleep(20);
    assert.deepEqual(picked, { canceled: false, filePaths: [path.join(root, 'skill.zip')] });

    // the Mac's own window: native dialog and focus as before
    await electron.ipcMain._invokeHandlers.get('files:save-as')({});
    assert.equal(s.fx.native.length, 1);
    assert.equal(s.fx.focused, 1);

    // a pending dialog is cancelled when its page goes away
    s.fire('c-1', 'qwenwork.invoke', { channel: 'files:save-as', args: [] });
    await dialogEvent();
    assert.equal(s.bridge.status().dialogs, 1);
    await s.bridge.stop();
    assert.equal(s.bridge.status().dialogs, 0);
    assert.equal(s.bridge.status().dialogHook, false, 'stop restores electron.dialog');
    await electron.dialog.showOpenDialog({});
    assert.equal(s.fx.native.length, 2);
    assert.ok(!Object.prototype.hasOwnProperty.call(electron.BrowserWindow.prototype, 'focus') || electron.BrowserWindow.prototype.focus.name === 'focus');
  } finally {
    await s.done();
    rmSync(base, { recursive: true, force: true });
  }
});
