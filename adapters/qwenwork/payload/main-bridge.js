// MMAB qwenwork payload (strategy S1, electron-main-inject). Runs INSIDE the QwenWork Electron main process.
//
// Loaded by adapters/qwenwork/tools/inject.mjs as part of a self-contained bundle (codec.js + this file), evaluated
// once through the app's own Node inspector, which is closed right after. It only uses Node built-ins and the app's
// `electron` module, injected via `deps` so the same code runs offline against a fake ipcMain in tests.
//
// What it does: listens on 127.0.0.1 (ephemeral port) for exactly ONE authenticated link from the qwenwork adapter
// driver (mutual HMAC-SHA256 handshake with a key generated per injection), and turns link frames into the same IPC
// the app's main window would produce: ipcMain invoke handlers, ipcMain.emit for send / sendSync, and mirrors
// main-process pushes to the main window (webContents.send) back over the link. Values travel as mmab-sv1 text.
//
// What it never does: listen on a non-loopback address, serve HTTP, read files, accept unauthenticated frames, or
// act on the Mac's own windows / tray / shell (deny list below; those calls resolve to undefined).
//
// Native file dialogs (file.pick): an electron.dialog.showOpenDialog / showSaveDialog started while handling a
// browser connection's IPC would open on the Mac's screen, where the remote user cannot see it, and the request would
// hang. Such calls (and only those: tracked with AsyncLocalStorage) are sent to that browser page as an
// `mmab:dialog` event instead; the page answers through the driver, which validates the chosen paths first. Dialogs
// of the Mac's own windows are untouched. While a browser-started dialog runs, BrowserWindow#focus is a no-op so the
// Mac window is not brought to the front.
/* eslint-disable no-var */
function createQwenBridge(deps) {
  'use strict';
  var electron = deps.electron;
  var net = deps.net;
  var crypto = deps.crypto;
  var codec = deps.codec;
  var ipcMain = electron.ipcMain;
  var BrowserWindow = electron.BrowserWindow;
  var log = deps.log || function () {};
  var als = deps.asyncHooks ? new deps.asyncHooks.AsyncLocalStorage() : null;
  function inConn(conn, fn) { return als && conn ? als.run({ conn: conn }, fn) : fn(); }

  var LINK_VERSION = 'mmab-qwen-link-v1';
  var TRPC = 'trpc-electron';
  var MAX_LINE = 16 * 1024 * 1024;
  var MAX_PENDING_LINE = 64 * 1024; // before authentication
  var TRPC_ID_BASE = 3000000000; // distinct from the app's own ids and from the legacy bridge (2e9)

  // Calls that would act on the Mac itself (windows, tray, badge, hotkeys, local file manager): answered with
  // undefined, never forwarded. Same list as the validated prototype bridge.
  var DENY = [
    /^window:(close|minimize|maximize|set-|toggle-devtools|unlock-devtools|zoom-|new$|open-artifact-preview|open-mcp-app-preview)/,
    /^app:(upgrade-version|set-badge|set-custom-hotkeys)/, /^startup-auth:relaunch$/, /^tray:/, /^quickpick:/,
    /^voiceOverlay:/, /^shell:(open-path|reveal-in-file-manager)$/,
  ];
  function denied(ch) {
    for (var i = 0; i < DENY.length; i++) if (DENY[i].test(ch)) return true;
    return false;
  }

  // ------------------------------------------------------------------ structured-clone-like normalisation
  // Electron IPC serialises class instances as plain objects; mmab-sv1 accepts only plain objects, so mirror that.
  // Functions / symbols become undefined (the validated prototype did the same; Electron itself would throw).
  function cloneable(v, depth, seen) {
    if (v === null || v === undefined) return v;
    var t = typeof v;
    if (t === 'function' || t === 'symbol') return undefined;
    if (t !== 'object') return v;
    if (depth > 30) throw new Error('value too deep');
    if (seen.indexOf(v) >= 0) return undefined;
    if (v instanceof ArrayBuffer || ArrayBuffer.isView(v) || v instanceof Date || v instanceof Error) return v;
    seen.push(v);
    try {
      if (Array.isArray(v)) return v.map(function (x) { return cloneable(x, depth + 1, seen); });
      if (v instanceof Map) {
        var m = new Map();
        v.forEach(function (val, key) { m.set(cloneable(key, depth + 1, seen), cloneable(val, depth + 1, seen)); });
        return m;
      }
      if (v instanceof Set) {
        var s = new Set();
        v.forEach(function (val) { s.add(cloneable(val, depth + 1, seen)); });
        return s;
      }
      var o = {};
      Object.keys(v).forEach(function (k) {
        if (k === '__proto__' || k === 'constructor' || k === 'prototype') return;
        o[k] = cloneable(v[k], depth + 1, seen);
      });
      return o;
    } finally {
      seen.pop();
    }
  }
  function enc(v) { return codec.encode(cloneable(v, 0, [])); }

  // ------------------------------------------------------------------ main window identity
  function mainWin() {
    var all = BrowserWindow.getAllWindows().filter(function (w) { return !w.isDestroyed(); });
    for (var i = 0; i < all.length; i++) {
      try {
        if (/windowId=main/.test(all[i].webContents.getURL())) return all[i];
      } catch (e) { /* window going away */ }
    }
    return all[0] || null;
  }
  // The app's desktop API trusts calls whose sender / senderFrame are the main window: present that identity.
  function makeEvent(onReply) {
    var w = mainWin();
    if (!w) {
      var err = new Error('QwenWork main window not found');
      err.code = 'ADAPTER_UNAVAILABLE';
      throw err;
    }
    var wc = w.webContents;
    var frame = wc.mainFrame;
    return {
      sender: wc, senderFrame: frame, frameId: frame && frame.routingId, processId: frame && frame.processId,
      ports: [], returnValue: undefined, defaultPrevented: false,
      preventDefault: function () { this.defaultPrevented = true; },
      reply: function (ch) { if (onReply) onReply(ch, Array.prototype.slice.call(arguments, 1)); },
    };
  }

  function rendererArgv() {
    var w = mainWin();
    var extra = [];
    try { extra = (w.webContents.getLastWebPreferences() || {}).additionalArguments || []; } catch (e) { extra = []; }
    var lang = 'en';
    try { lang = electron.app.getLocale(); } catch (e) { /* keep default */ }
    return ['QwenWorkCN Helper (Renderer)', '--type=renderer', '--lang=' + lang].concat(extra);
  }

  // ------------------------------------------------------------------ per browser connection state (tRPC ids)
  var nextTrpcId = TRPC_ID_BASE;
  var conns = new Map(); // conn -> {byBridge: Map(bridgeId -> {cid, type}), byClient: Map(cid -> bridgeId)}
  function connState(conn) {
    var c = conns.get(conn);
    if (!c) { c = { byBridge: new Map(), byClient: new Map() }; conns.set(conn, c); }
    return c;
  }
  function closeConn(conn) {
    seen.delete(conn);
    cancelDialogs(function (d) { return d.conn === conn; });
    var c = conns.get(conn);
    if (!c) return;
    conns.delete(conn);
    c.byBridge.forEach(function (_v, bid) {
      try { ipcMain.emit(TRPC, makeEvent(null), { method: 'subscription.stop', id: bid }); } catch (e) { /* app gone */ }
    });
  }

  // ------------------------------------------------------------------ native dialogs -> the browser page (file.pick)
  var seen = new Map(); // browser connections that sent frames recently: conn -> last frame time
  var dialogs = new Map(); // id -> {conn, kind, resolve, timer}
  var dialogSeq = 0;
  var DIALOG_TTL_MS = 10 * 60 * 1000;
  var OPEN_PROPS = ['openFile', 'openDirectory', 'multiSelections', 'showHiddenFiles', 'createDirectory'];

  function cancelled(kind) { return kind === 'save' ? { canceled: true, filePath: '' } : { canceled: true, filePaths: [] }; }
  function cancelDialogs(pred) {
    dialogs.forEach(function (d, id) {
      if (!pred(d)) return;
      dialogs.delete(id);
      clearTimeout(d.timer);
      d.resolve(cancelled(d.kind));
    });
  }
  function dialogTarget(conn) {
    if (conn && seen.has(conn)) return conn;
    var best = null;
    var at = -1;
    seen.forEach(function (t, c) { if (t > at) { at = t; best = c; } });
    return best; // the origin page reloaded: the operator's newest page answers instead
  }
  function sanitizeOptions(kind, o) {
    o = o && typeof o === 'object' ? o : {};
    var out = {};
    ['title', 'defaultPath', 'buttonLabel', 'message', 'nameFieldLabel'].forEach(function (k) {
      if (typeof o[k] === 'string') out[k] = o[k].slice(0, 4096);
    });
    if (Array.isArray(o.filters)) {
      out.filters = o.filters.slice(0, 20).map(function (f) {
        return { name: String((f && f.name) || ''), extensions: Array.isArray(f && f.extensions) ? f.extensions.slice(0, 50).map(String) : [] };
      });
    }
    if (kind === 'open') {
      out.properties = (Array.isArray(o.properties) ? o.properties : ['openFile']).filter(function (p) { return OPEN_PROPS.indexOf(p) >= 0; });
    }
    return out;
  }
  function remoteDialog(kind, options, store) {
    var target = dialogTarget(store.conn);
    if (!target || !link) return null; // no browser page to ask: let the Mac show it
    var id = ++dialogSeq;
    return new Promise(function (resolve) {
      var timer = setTimeout(function () {
        if (!dialogs.delete(id)) return;
        emitEvent(target, 'mmab:dialog-cancel', [{ id: id }]);
        resolve(cancelled(kind));
      }, DIALOG_TTL_MS);
      dialogs.set(id, { conn: target, kind: kind, resolve: resolve, timer: timer });
      emitEvent(target, 'mmab:dialog', [{ id: id, kind: kind, options: sanitizeOptions(kind, options) }]);
      log('dialog to browser', kind, id);
    });
  }
  function isAbs(p) { return typeof p === 'string' && p.charAt(0) === '/' && p.indexOf(String.fromCharCode(0)) < 0 && p.length < 4096; }
  /** Answer from the page (paths already validated by the driver). Only the page that was asked may answer. */
  function answerDialog(conn, req) {
    if (!req || typeof req.id !== 'number') return { ok: false, reason: 'MALFORMED' };
    var d = dialogs.get(req.id);
    if (!d || d.conn !== conn) return { ok: false, reason: 'NO_SUCH_DIALOG' };
    var res = req.result || {};
    var value;
    if (res.canceled === true) value = cancelled(d.kind);
    else if (d.kind === 'save' && isAbs(res.filePath)) value = { canceled: false, filePath: res.filePath };
    else if (d.kind === 'open' && Array.isArray(res.filePaths) && res.filePaths.length > 0 && res.filePaths.length <= 200
      && res.filePaths.every(isAbs)) value = { canceled: false, filePaths: res.filePaths.slice() };
    else return { ok: false, reason: 'MALFORMED' };
    dialogs.delete(req.id);
    clearTimeout(d.timer);
    d.resolve(value);
    return { ok: true };
  }
  function hookDialogs() {
    var g = globalThis;
    var dlg = electron.dialog;
    if (g.__mmabQwDialogHooked || !als || !dlg) return;
    var proto = BrowserWindow && BrowserWindow.prototype && typeof BrowserWindow.prototype.focus === 'function'
      ? BrowserWindow.prototype : null;
    var rec = {
      dlg: dlg, open: dlg.showOpenDialog, save: dlg.showSaveDialog, proto: proto,
      ownFocus: !!proto && Object.prototype.hasOwnProperty.call(proto, 'focus'), focus: proto && proto.focus,
    };
    function wrap(kind, orig) {
      return function () {
        var store = als.getStore();
        var options = arguments.length > 1 ? arguments[1] : arguments[0];
        var p = store ? remoteDialog(kind, options, store) : null;
        return p || orig.apply(this, arguments);
      };
    }
    rec.openW = wrap('open', rec.open);
    rec.saveW = wrap('save', rec.save);
    rec.focusW = function () {
      if (als.getStore()) return undefined; // a browser-started dialog: leave the Mac's windows where they are
      return rec.focus.apply(this, arguments);
    };
    dlg.showOpenDialog = rec.openW;
    dlg.showSaveDialog = rec.saveW;
    if (proto) proto.focus = rec.focusW;
    g.__mmabQwDialogHooked = rec;
  }
  function unhookDialogs() {
    var g = globalThis;
    var rec = g.__mmabQwDialogHooked;
    if (!rec) return;
    if (rec.dlg.showOpenDialog === rec.openW) rec.dlg.showOpenDialog = rec.open;
    if (rec.dlg.showSaveDialog === rec.saveW) rec.dlg.showSaveDialog = rec.save;
    if (rec.proto && rec.proto.focus === rec.focusW) {
      if (rec.ownFocus) rec.proto.focus = rec.focus;
      else delete rec.proto.focus;
    }
    delete g.__mmabQwDialogHooked;
  }

  // ------------------------------------------------------------------ link (one authenticated peer at a time)
  var key = null;
  var server = null;
  var link = null; // {sock, write(obj)}
  var sendQueue = Promise.resolve();

  function hmac(label, a, b) {
    return crypto.createHmac('sha256', key).update(LINK_VERSION + '|' + label + '|' + a + '|' + b).digest('base64');
  }
  function safeEq(a, b) {
    var x = Buffer.from(String(a));
    var y = Buffer.from(String(b));
    return x.length === y.length && crypto.timingSafeEqual(x, y);
  }

  function onReplyFor(conn) {
    return function (ch, args) {
      if (!link) return;
      if (ch === TRPC && args[0] && args[0].id != null) {
        var c = conns.get(conn);
        if (!c) return;
        var msg = args[0];
        var m = c.byBridge.get(msg.id);
        if (!m) return;
        var out = Object.assign({}, msg, { id: m.cid });
        var done = m.type !== 'subscription' || (msg.result && msg.result.type === 'stopped') || msg.error;
        if (done) { c.byBridge.delete(msg.id); c.byClient.delete(m.cid); }
        emitEvent(conn, ch, [out]);
        return;
      }
      emitEvent(conn, ch, args);
    };
  }
  function emitEvent(conn, ch, args) {
    if (!link) return;
    var p;
    try { p = enc({ channel: ch, args: args }); } catch (e) { log('event not encodable', ch); return; }
    link.write({ t: 'evt', conn: conn, p: p });
  }

  // main process -> main window pushes (except tRPC, which is answered per connection) are mirrored to the link.
  // The previous send (the app's own, or another bridge's layer) is kept in the hook record and restored on stop.
  function hookSend() {
    var g = globalThis;
    if (g.__mmabQwSendHooked) return;
    var w = mainWin();
    if (!w) return;
    var proto = Object.getPrototypeOf(w.webContents);
    var orig = proto.send;
    var wrapper = function (ch) {
      var args = Array.prototype.slice.call(arguments, 1);
      try {
        if (link && ch !== TRPC) {
          var mw = mainWin();
          if (mw && this === mw.webContents) emitEvent(null, ch, args);
        }
      } catch (e) { log('mirror failed', ch); }
      return orig.apply(this, arguments);
    };
    proto.send = wrapper;
    g.__mmabQwSendHooked = { proto: proto, wrapper: wrapper, orig: orig };
  }
  function unhookSend() {
    var g = globalThis;
    var h = g.__mmabQwSendHooked;
    if (!h) return;
    // only undo our own layer; if someone wrapped on top of us, our wrapper stays a pass-through (link is null)
    if (h.proto.send === h.wrapper) h.proto.send = h.orig;
    delete g.__mmabQwSendHooked;
  }

  function answer(id, value) {
    if (!link) return;
    var p;
    try { p = enc(value); } catch (e) {
      link.write({ t: 'res', id: id, ok: false, code: 'CODEC_UNSUPPORTED' });
      return;
    }
    link.write({ t: 'res', id: id, ok: true, p: p });
  }
  function fail(id, code) { if (link) link.write({ t: 'res', id: id, ok: false, code: code }); }

  function handleCall(f) {
    var req;
    try { req = codec.decode(f.p); } catch (e) { return fail(f.id, 'MALFORMED_FRAME'); }
    if (f.kind === 'bootstrap') {
      return answer(f.id, { argv: rendererArgv(), platform: process.platform, arch: process.arch,
        versions: { electron: process.versions.electron, chrome: process.versions.chrome, node: process.versions.node } });
    }
    if (f.kind === 'dialog') return answer(f.id, answerDialog(typeof f.conn === 'string' ? f.conn : null, req));
    if (!req || typeof req.channel !== 'string' || !Array.isArray(req.args)) return fail(f.id, 'MALFORMED_FRAME');
    var ch = req.channel;
    var args = req.args;
    var conn = typeof f.conn === 'string' ? f.conn : null;
    if (conn) seen.set(conn, Date.now());
    if (f.kind === 'invoke') {
      if (denied(ch)) { log('deny invoke', ch); return answer(f.id, { ok: true, v: undefined }); }
      var h = ipcMain._invokeHandlers && ipcMain._invokeHandlers.get(ch);
      if (!h) return answer(f.id, { ok: false, message: "No handler registered for '" + ch + "'" });
      var ev;
      try { ev = makeEvent(conn ? onReplyFor(conn) : null); } catch (e) { return fail(f.id, e.code || 'ADAPTER_UNAVAILABLE'); }
      inConn(conn, function () { return Promise.resolve().then(function () { return h.apply(null, [ev].concat(args)); }); }).then(
        function (v) { answer(f.id, { ok: true, v: v }); },
        function (e) {
          answer(f.id, { ok: false, message: "Error invoking remote method '" + ch + "': " + ((e && e.message) || e) });
        });
      return undefined;
    }
    if (f.kind === 'send') {
      if (!conn) return fail(f.id, 'MALFORMED_FRAME');
      // sends keep their order (tRPC requests / stops); answered once handed to ipcMain
      sendQueue = sendQueue.then(function () {
        if (ch === TRPC) {
          var msg = args[0];
          var c = connState(conn);
          if (msg && msg.method === 'request' && msg.operation) {
            var bid = nextTrpcId++;
            c.byBridge.set(bid, { cid: msg.operation.id, type: msg.operation.type });
            c.byClient.set(msg.operation.id, bid);
            msg.operation.id = bid;
          } else if (msg && msg.method === 'subscription.stop') {
            var sb = c.byClient.get(msg.id);
            if (sb == null) return answer(f.id, null);
            c.byClient.delete(msg.id);
            c.byBridge.delete(sb);
            msg.id = sb;
          }
        } else if (denied(ch)) {
          return answer(f.id, null);
        }
        var sev;
        try { sev = makeEvent(onReplyFor(conn)); } catch (e) { return fail(f.id, e.code || 'ADAPTER_UNAVAILABLE'); }
        inConn(conn, function () { ipcMain.emit.apply(ipcMain, [ch, sev].concat(args)); });
        return answer(f.id, null);
      }).catch(function () { fail(f.id, 'ADAPTER_UNAVAILABLE'); });
      return undefined;
    }
    if (f.kind === 'sendSync') {
      if (denied(ch)) return answer(f.id, undefined);
      var yev;
      try { yev = makeEvent(null); } catch (e) { return fail(f.id, e.code || 'ADAPTER_UNAVAILABLE'); }
      ipcMain.emit.apply(ipcMain, [ch, yev].concat(args));
      return answer(f.id, yev.returnValue);
    }
    return fail(f.id, 'UNKNOWN_OPERATION');
  }

  function onLine(line) {
    var f;
    try { f = JSON.parse(line); } catch (e) { return; }
    if (!f || typeof f !== 'object') return;
    if (f.t === 'call' && typeof f.id === 'number' && typeof f.p === 'string') handleCall(f);
    else if (f.t === 'close' && typeof f.conn === 'string') closeConn(f.conn);
  }

  function attach(sock) {
    var buf = '';
    var authed = false;
    var serverNonce = crypto.randomBytes(16).toString('base64');
    var timer = setTimeout(function () { sock.destroy(); }, 5000);
    var write = function (o) { if (!sock.destroyed) sock.write(JSON.stringify(o) + '\n'); };
    sock.setEncoding('utf8');
    write({ t: 'hello', v: LINK_VERSION, nonce: serverNonce });
    sock.on('data', function (chunk) {
      buf += chunk;
      var limit = authed ? MAX_LINE : MAX_PENDING_LINE;
      for (;;) {
        var i = buf.indexOf('\n');
        if (i < 0) break;
        var line = buf.slice(0, i);
        buf = buf.slice(i + 1);
        if (line.length > limit) { sock.destroy(); return; }
        if (authed) { onLine(line); continue; }
        var a;
        try { a = JSON.parse(line); } catch (e) { sock.destroy(); return; }
        if (!a || a.t !== 'auth' || typeof a.nonce !== 'string' || typeof a.mac !== 'string'
            || !safeEq(a.mac, hmac('client', serverNonce, a.nonce))) {
          log('link auth rejected');
          sock.destroy();
          return;
        }
        authed = true;
        clearTimeout(timer);
        if (link) { try { link.sock.destroy(); } catch (e) { /* old link */ } }
        link = { sock: sock, write: write };
        hookSend();
        hookDialogs();
        write({ t: 'ok', mac: hmac('server', a.nonce, serverNonce) });
        log('link authenticated');
      }
      if (buf.length > limit) sock.destroy();
    });
    sock.on('close', function () {
      clearTimeout(timer);
      if (link && link.sock === sock) {
        link = null;
        Array.from(conns.keys()).forEach(closeConn);
        seen.clear();
        cancelDialogs(function () { return true; });
        log('link closed');
      }
    });
    sock.on('error', function () { /* close follows */ });
  }

  return {
    /** @param {string} keyB64 32-byte link key (base64). Resolves {port} of the loopback listener. */
    start: function (keyB64) {
      var k = Buffer.from(String(keyB64), 'base64');
      if (k.length !== 32) return Promise.reject(new Error('link key must be 32 bytes'));
      if (server) return Promise.reject(new Error('already started'));
      key = k;
      return new Promise(function (resolve, reject) {
        server = net.createServer(attach);
        server.maxConnections = 4;
        server.once('error', reject);
        server.listen(0, '127.0.0.1', function () { resolve({ port: server.address().port }); });
      });
    },
    stop: function () {
      unhookSend();
      unhookDialogs();
      cancelDialogs(function () { return true; });
      seen.clear();
      if (link) { try { link.sock.destroy(); } catch (e) { /* ignore */ } link = null; }
      Array.from(conns.keys()).forEach(closeConn);
      return new Promise(function (resolve) {
        if (!server) return resolve({ stopped: false });
        server.close(function () { server = null; key = null; resolve({ stopped: true }); });
      });
    },
    status: function () {
      return {
        listening: !!server, port: server ? server.address().port : null, linked: !!link, connections: conns.size,
        dialogs: dialogs.size, dialogHook: !!globalThis.__mmabQwDialogHooked,
        invokeHandlers: ipcMain._invokeHandlers ? ipcMain._invokeHandlers.size : -1,
      };
    },
  };
}

if (typeof module !== 'undefined' && module.exports) module.exports = { createQwenBridge: createQwenBridge };
