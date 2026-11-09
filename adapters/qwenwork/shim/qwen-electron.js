// MMAB qwenwork browser shim (classic script). Injected by the platform after codec.js / transport.js /
// electron-compat.js and BEFORE the wrapped original preload (resource:/mmab-preload.js) and the app's own scripts.
//
// Gives the original QwenWork preload the Electron surface it expects, backed by the instance WS:
//   require('electron') -> { contextBridge (same-world), ipcRenderer, webUtils }
//   process             -> renderer-like object from the synchronous qwenwork.bootstrap call
// Clipboard writes and external links act on THIS browser's computer, not on the Mac.
(function (root) {
  'use strict';
  if (root.__mmabQwRequire) return;
  var compat = root.MmabElectronCompat;
  var transport = root.MmabTransport;
  compat.polyfillInsecureContext(root);

  // The app routes its main window by hash.
  if (!/windowId=/.test(root.location.hash)) {
    root.history.replaceState(null, '', root.location.pathname + root.location.search + '#windowId=main');
  }

  var listeners = new Map();
  var ipcRenderer;
  function dispatch(channel, args) {
    var set = listeners.get(channel);
    if (!set || !set.size) return;
    var evt = { sender: ipcRenderer, senderId: 0, ports: [] };
    Array.from(set).forEach(function (fn) {
      try {
        fn.apply(null, [evt].concat(args));
      } catch (err) {
        console.error('[mmab-qwen] listener failed', channel, err);
      }
    });
  }

  var t = transport.create({
    // disconnect diagnostics remember recent IPC channel names (never arguments)
    label: function (op, value) { return value && typeof value.channel === 'string' ? value.channel : op; },
    onEvent: function (op, value) {
      if (op !== 'qwenwork.ipc' || !value || typeof value.channel !== 'string' || !Array.isArray(value.args)) return;
      dispatch(value.channel, value.args);
    },
  });

  function copyLocal(text) {
    return root.navigator.clipboard.writeText(String(text));
  }
  function pickText(e) {
    return typeof e === 'string' ? e : (e && (e.text || e.plainText || e.markdown || e.html)) || '';
  }
  function openLocal(url) {
    var u = String(url);
    if (!/^https?:\/\//i.test(u)) return undefined; // only web links leave the page
    root.open(u, '_blank', 'noopener');
    return undefined;
  }
  var LOCAL = {
    'clipboard:write': function (e) { return copyLocal(pickText(e)); },
    'clipboard:write-rich-text': function (e, txt) { return copyLocal(pickText(typeof txt === 'string' ? txt : e)); },
    'shell:open-external': openLocal,
    'shell:open-external-with-pending-org': openLocal,
  };

  ipcRenderer = {
    on: function (ch, fn) {
      if (!listeners.has(ch)) listeners.set(ch, new Set());
      listeners.get(ch).add(fn);
      return ipcRenderer;
    },
    once: function (ch, fn) {
      var w = function () {
        ipcRenderer.removeListener(ch, w);
        fn.apply(null, arguments);
      };
      return ipcRenderer.on(ch, w);
    },
    addListener: function (ch, fn) { return ipcRenderer.on(ch, fn); },
    removeListener: function (ch, fn) {
      var s = listeners.get(ch);
      if (s) s.delete(fn);
      return ipcRenderer;
    },
    off: function (ch, fn) { return ipcRenderer.removeListener(ch, fn); },
    removeAllListeners: function (ch) {
      if (ch) listeners.delete(ch);
      else listeners.clear();
      return ipcRenderer;
    },
    send: function (ch) {
      var args = Array.prototype.slice.call(arguments, 1);
      t.request('qwenwork.send', { channel: String(ch), args: args }).catch(function (e) {
        console.warn('[mmab-qwen] send failed', ch, e && e.code);
      });
    },
    invoke: function (ch) {
      var args = Array.prototype.slice.call(arguments, 1);
      if (LOCAL[ch]) return Promise.resolve().then(function () { return LOCAL[ch].apply(null, args); });
      return t.request('qwenwork.invoke', { channel: String(ch), args: args }).then(function (r) {
        if (r && r.ok === true) return r.v;
        throw new Error((r && r.message) || 'invoke failed');
      });
    },
    sendSync: function (ch) {
      var args = Array.prototype.slice.call(arguments, 1);
      return t.call('qwenwork.sendSync', { channel: String(ch), args: args });
    },
    postMessage: function () {
      console.warn('[mmab-qwen] ipcRenderer.postMessage is not supported');
    },
  };

  // Attachments need a Mac-side path: not supported yet (capability file.upload is UNVERIFIED / not exposed).
  function getPathForFile() {
    throw new Error('[mmab-qwen] file attachments are not available through the bridge yet');
  }

  var info = t.call('qwenwork.bootstrap', null);
  root.__mmabQwProcess = {
    platform: info.platform, arch: info.arch, argv: info.argv, versions: info.versions, env: {},
    type: 'renderer', isMainFrame: true, crash: function () {},
  };
  var electronApi = Object.freeze({
    contextBridge: compat.contextBridge(root),
    ipcRenderer: ipcRenderer,
    webUtils: { getPathForFile: getPathForFile },
  });
  root.__mmabQwRequire = function (name) {
    if (name === 'electron') return electronApi;
    throw new Error('[mmab-qwen] require(' + name + ') is not available');
  };
  root.__mmabQwTransport = t; // diagnostics only (stats), no credentials
})(globalThis);
