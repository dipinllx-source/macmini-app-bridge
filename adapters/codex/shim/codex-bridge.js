// MMAB codex browser shim (classic script). Injected after codec.js / transport.js / ports.js and BEFORE the original
// Codex webview scripts. Provides window.electronBridge (the surface the Codex preload exposes in Electron) backed by
// the instance WS: getters from a synchronous startup snapshot, the app-host MessageChannel relayed to the dedicated
// native renderer, host -> view messages replayed as window "message" events.
//
// Not exposed: arbitrary IPC (sendMessageFromView only forwards `ready` and three startup reads), worker messages,
// file drag / paths, context menus, Sentry. Browser telemetry never leaves the page.
(function (root) {
  'use strict';
  if (root.electronBridge) return;
  var T = root.MmabTransport;
  var P = root.MmabPorts;
  var VIEW_READS = ['persisted-atom-sync-request', 'electron-window-focus-request', 'request-app-server-state'];
  var stats = { hostMessages: 0, droppedViewMessages: 0, droppedTypes: [] };

  var relay;
  var themeListeners = new Set();
  var t = T.create({
    onEvent: function (op, value, payloadText) {
      if (op === 'codex.hostMessage') {
        stats.hostMessages++;
        root.dispatchEvent(new MessageEvent('message', { data: value }));
      } else if (op === 'codex.theme') {
        theme = value;
        themeListeners.forEach(function (fn) { try { fn(); } catch (e) { console.error('[mmab-codex] theme listener', e); } });
      } else if (op === 'codex.gitPort') {
        var transfers = [];
        var v = relay.decode(payloadText, transfers);
        root.postMessage(v.message, root.location.origin, v.ports);
      }
    },
    onPortData: function (port, text) { relay.receiveData(port, text); },
    onPortClose: function (port) { relay.receiveClose(port); },
    onClose: function () { relay.closeAll(); },
  });
  relay = P.create({
    prefix: 'w',
    sendData: function (port, text) { try { t.sendPortData(port, text); } catch (e) { /* closed: banner shown */ } },
    sendClose: function (port) { try { t.sendPortClose(port); } catch (e) { /* closed */ } },
  });

  var initial = t.call('codex.bootstrap', null);
  var theme = initial.getSystemThemeVariant;

  function swallow(op) {
    return function (e) { console.warn('[mmab-codex]', op, e && e.code); };
  }

  // Electron's Sentry preload transport does not exist in a browser: keep its interface local, forward nothing.
  root.__SENTRY_IPC__ = { 'sentry-ipc': {} };
  ['sendRendererStart', 'sendScope', 'sendEnvelope', 'sendStatus', 'sendStructuredLog', 'sendMetric'].forEach(function (n) {
    root.__SENTRY_IPC__['sentry-ipc'][n] = function () {};
  });
  root.codexWindowType = 'electron';

  var bridge = { windowType: 'electron' };
  Object.keys(initial).forEach(function (k) {
    var v = initial[k];
    bridge[k] = function () { return v; };
  });
  bridge.getSystemThemeVariant = function () { return theme; };
  bridge.subscribeToSystemThemeVariant = function (fn) {
    themeListeners.add(fn);
    return function () { themeListeners.delete(fn); };
  };
  bridge.acknowledgeChunkedMessage = function () {};
  bridge.sendMessageFromView = function (message) {
    var type = message && message.type;
    if (type === 'ready') return t.request('codex.viewReady', null).then(function () {}, swallow('ready'));
    if (VIEW_READS.indexOf(type) >= 0) return t.request('codex.viewRead', { type: type }).then(function () {}, swallow(type));
    stats.droppedViewMessages++;
    if (typeof type === 'string' && stats.droppedTypes.length < 128 && stats.droppedTypes.indexOf(type) < 0) stats.droppedTypes.push(type);
    return Promise.resolve();
  };
  bridge.getFastModeRolloutMetrics = function (arg) {
    return t.request('codex.read', { method: 'getFastModeRolloutMetrics', arg: arg });
  };
  bridge.getPathForFile = function () { return null; };
  bridge.startFileDrag = function () { return false; };
  bridge.startLinkDrag = function () {};
  bridge.connectGitWorker = function (requestId) {
    return t.request('codex.connectGit', { requestId: String(requestId) }).then(function () { return true; }, function (e) {
      swallow('connectGit')(e);
      return true;
    });
  };
  bridge.sendWorkerMessageFromView = function () { return Promise.reject(new Error('[mmab-codex] worker relay is not available')); };
  bridge.subscribeToWorkerMessages = function () { return function () {}; };
  bridge.showContextMenu = function () { return Promise.resolve(null); };
  bridge.triggerSentryTestError = function () { return Promise.reject(new Error('[mmab-codex] disabled')); };
  root.electronBridge = Object.freeze(bridge);

  // The view connects to the app host with window.postMessage({type:'connect-app-host', ...}, origin, [port]).
  root.addEventListener('message', function (e) {
    if (e.source !== root || e.origin !== root.location.origin || !e.data || e.data.type !== 'connect-app-host') return;
    var text;
    try {
      text = relay.encode(e.data);
    } catch (err) {
      console.error('[mmab-codex] app-host handoff not encodable', err && err.code);
      return;
    }
    t.requestRaw('codex.connectAppHost', text).catch(swallow('connectAppHost'));
  });

  // Visible failure instead of a silent blank page.
  setTimeout(function () {
    if (!document.querySelector('#root button, #root textarea, #root [contenteditable="true"]')) {
      T.banner('macmini App 引桥：Codex 30 秒内未完成渲染，请检查连接后刷新本页。', '__mmab_startup');
    }
  }, 30000);
  var obs = new MutationObserver(function () {
    if (document.querySelector('#root button, #root textarea, #root [contenteditable="true"]')) {
      T.banner('', '__mmab_startup');
      obs.disconnect();
    }
  });
  obs.observe(document.documentElement, { childList: true, subtree: true });

  root.__mmabCodexWeb = Object.freeze({ stats: stats, transport: t.stats }); // diagnostics only
})(globalThis);
