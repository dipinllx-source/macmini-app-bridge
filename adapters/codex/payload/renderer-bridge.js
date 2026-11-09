// MMAB codex payload (strategy S2, electron-renderer-cdp). Evaluated INSIDE the dedicated Codex renderer
// (app://-/index.html) through CDP, after sdk/web/codec.js and sdk/web/ports.js. Installs window.__mmabCodex.
//
// Talks to the driver only through the CDP binding `__mmabCodexOut` (renderer -> driver) and through
// Runtime.evaluate calls on window.__mmabCodex (driver -> renderer). Nothing listens on a network port here.
//
// Per browser connection it keeps a MessagePort relay (id space "n"); the browser side uses "w" (sdk/web/ports.js).
// Host -> view messages are mirrored to the browser except requests the native view must answer itself
// (`*-request`), internal `codex_desktop:*` traffic, chunked messages (ACKed natively) and messages carrying ports.
/* eslint-disable no-var */
(function (root) {
  'use strict';
  var BRIDGE = root.electronBridge;
  var Codec = root.MmabCodec;
  var Ports = root.MmabPorts;
  var out = root.__mmabCodexOut;
  if (!BRIDGE || !Codec || !Ports || typeof out !== 'function') throw new Error('mmab codex payload: prerequisites missing');
  if (root.__mmabCodex) root.__mmabCodex.uninstall();

  var READ_METHODS = ['getPreloadStartedAtMs', 'getInitialSidebarBootstrap', 'getSystemThemeVariant', 'getSentryInitOptions',
    'getDesktopUserAgent', 'getAppSessionId', 'getBuildFlavor', 'isDeviceCheckSupported', 'isIntelMacBuild'];
  var ASYNC_READ_METHODS = ['getFastModeRolloutMetrics'];
  // startup reads the browser view may trigger; the native view owns everything else it sends
  var VIEW_READS = ['persisted-atom-sync-request', 'electron-window-focus-request', 'request-app-server-state'];
  var MAX_GIT_REQUESTS = 32;
  var stats = { mirrored: 0, filtered: 0, unencodable: 0 };

  function emit(o) {
    try { out(JSON.stringify(o)); } catch (e) { /* binding gone: driver detached */ }
  }

  var conns = new Map(); // connectionId -> {relay, git:Set}
  function conn(id) {
    var c = conns.get(id);
    if (c) return c;
    c = {
      relay: Ports.create({
        prefix: 'n',
        sendData: function (port, text) { emit({ k: 'port', conn: id, port: port, text: text }); },
        sendClose: function (port) { emit({ k: 'portClose', conn: id, port: port }); },
      }),
      git: new Set(),
    };
    conns.set(id, c);
    return c;
  }

  function mirrorable(data) {
    return data !== null && typeof data === 'object' && typeof data.type === 'string'
      && !/-request$/.test(data.type) && data.type.indexOf('codex_desktop:') !== 0
      && data.marker !== 'codex-host-chunked-message-v1';
  }

  function onMessage(e) {
    var d = e.data;
    if (d && d.type === 'codex_desktop:git-worker-port') {
      conns.forEach(function (c, id) {
        if (!c.git.has(d.requestId)) return;
        var text;
        try {
          text = c.relay.encode({ message: d, ports: Array.prototype.slice.call(e.ports || []) });
        } catch (err) { stats.unencodable++; return; }
        emit({ k: 'git', conn: id, text: text });
        if (d.closed) c.git.delete(d.requestId);
      });
      return;
    }
    if (e.ports && e.ports.length) return; // our own connect-app-host posts and other port handoffs
    if (!mirrorable(d)) { stats.filtered++; return; }
    var t;
    try {
      t = Codec.encode(d);
    } catch (err) {
      try { t = Codec.encode(JSON.parse(JSON.stringify(d))); } catch (err2) { stats.unencodable++; return; }
    }
    stats.mirrored++;
    emit({ k: 'host', text: t });
  }
  root.addEventListener('message', onMessage);

  var themeOff = null;
  try {
    themeOff = BRIDGE.subscribeToSystemThemeVariant(function () {
      emit({ k: 'theme', text: Codec.encode(BRIDGE.getSystemThemeVariant()) });
    });
  } catch (e) { themeOff = null; }

  var api = {
    version: 1,
    stats: stats,
    /** synchronous startup snapshot of the native getters */
    bootstrap: function () {
      var o = {};
      READ_METHODS.forEach(function (k) { o[k] = BRIDGE[k](); });
      return Codec.encode(o);
    },
    read: function (method, argText) {
      if (ASYNC_READ_METHODS.indexOf(method) < 0 && READ_METHODS.indexOf(method) < 0) throw new Error('UNKNOWN_OPERATION');
      var arg = argText ? Codec.decode(argText) : undefined;
      return Promise.resolve(BRIDGE[method](arg)).then(function (v) { return Codec.encode(v); });
    },
    viewReady: function () {
      return Promise.resolve(BRIDGE.sendMessageFromView({ type: 'ready' })).then(function () { return Codec.encode(true); });
    },
    viewRead: function (type) {
      if (VIEW_READS.indexOf(type) < 0) throw new Error('UNKNOWN_OPERATION');
      return Promise.resolve(BRIDGE.sendMessageFromView({ type: type })).then(function () { return Codec.encode(true); });
    },
    /** the browser view's connect-app-host message (with its MessagePort) handed to the native app host */
    appHost: function (id, text) {
      var transfers = [];
      var data = conn(id).relay.decode(text, transfers);
      if (!data || data.type !== 'connect-app-host') throw new Error('MALFORMED_FRAME');
      root.postMessage(data, root.location.origin, transfers);
      return Codec.encode(true);
    },
    connectGit: function (id, requestId) {
      if (typeof requestId !== 'string' || !/^[A-Za-z0-9-]{8,64}$/.test(requestId)) throw new Error('MALFORMED_FRAME');
      var c = conn(id);
      if (c.git.size >= MAX_GIT_REQUESTS) throw new Error('QUEUE_LIMIT');
      c.git.add(requestId);
      return Promise.resolve(BRIDGE.connectGitWorker(requestId)).then(function (v) { return Codec.encode(v === undefined ? true : v); });
    },
    portData: function (id, port, text) {
      var c = conns.get(id);
      return !!(c && c.relay.receiveData(port, text));
    },
    portClose: function (id, port) {
      var c = conns.get(id);
      return !!(c && c.relay.receiveClose(port));
    },
    /** apply a batch of browser -> renderer frames in order; returns one result (text) or error code per item */
    batch: function (items) {
      var results = [];
      var chain = Promise.resolve();
      items.forEach(function (it, i) {
        chain = chain.then(function () {
          var f = api[it.f];
          if (typeof f !== 'function' || ['portData', 'portClose', 'appHost', 'connectGit', 'read', 'viewReady', 'viewRead',
            'closeConnection'].indexOf(it.f) < 0) throw new Error('UNKNOWN_OPERATION');
          return f.apply(null, it.a);
        }).then(function (v) { results[i] = { v: typeof v === 'string' ? v : null }; }, function (e) {
          var code = e && /^[A-Z_]+$/.test(e.message) ? e.message : (e && e.code) || 'ADAPTER_UNAVAILABLE';
          results[i] = { e: code };
        });
      });
      return chain.then(function () { return results; });
    },
    closeConnection: function (id) {
      var c = conns.get(id);
      if (!c) return false;
      c.relay.closeAll();
      conns.delete(id);
      return true;
    },
    uninstall: function () {
      root.removeEventListener('message', onMessage);
      if (themeOff) { try { themeOff(); } catch (e) { /* ignore */ } }
      conns.forEach(function (c) { c.relay.closeAll(); });
      conns.clear();
      delete root.__mmabCodex;
    },
  };
  root.__mmabCodex = api;
})(globalThis);
