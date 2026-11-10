// MMAB qwenwork browser shim (classic script). Injected by the platform after codec.js / transport.js /
// electron-compat.js and BEFORE the wrapped original preload (resource:/mmab-preload.js) and the app's own scripts.
//
// Gives the original QwenWork preload the Electron surface it expects, backed by the instance WS:
//   require('electron') -> { contextBridge (same-world), ipcRenderer, webUtils }
//   process             -> renderer-like object from the synchronous qwenwork.bootstrap call
// Clipboard writes and external links act on THIS browser's computer, not on the Mac.
// File / folder pickers run inside the page (qwen-picker.js) instead of the Mac's native dialog.
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
    // 连接未能无损恢复（超过宽限期 / 平台重启）：原版页面持有的订阅与端口已失效，受控地整页重新加载
    onLost: function () { root.location.reload(); },
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
  // ------------------------------------------------------------------ 原生对话框（tRPC）
  // 这些 tRPC 调用在主进程弹 electron.dialog —— 面板出现在 Mac 屏幕上，远程页面看不到，请求会一直挂起。
  // 选择文件 / 目录改走页内选择器（file.pick，qwen-picker.js）；保存类对话框暂不支持，立即报错而不是挂起。
  var TRPC = 'trpc-electron';
  var NATIVE_DIALOG_ONLY = {
    'files.showSaveDialog': '另存为', 'files.saveAs': '另存为', 'files.saveImageAs': '保存图片',
    'files.saveTextDocumentAs': '另存为', 'skills.selectSkillZip': '导入技能包',
    'commonCliConnectors.selectPackage': '导入安装包',
  };
  function trpcAnswer(msg) {
    root.setTimeout(function () { dispatch(TRPC, [msg]); }, 0); // 与真实 IPC 一样异步到达
  }
  function trpcData(id, data) {
    trpcAnswer({ id: id, result: { type: 'data', data: { json: data } } }); // 应用使用 superjson：{json}
  }
  function trpcError(id, path, message) {
    trpcAnswer({ id: id, error: { json: { message: message, code: -32603,
      data: { code: 'INTERNAL_SERVER_ERROR', httpStatus: 500, path: path } } } });
  }
  function pickPaths(op) {
    var input = (op.input && op.input.json) || {};
    var picker = root.__mmabQwPicker;
    if (!picker) return trpcData(op.id, { canceled: true, paths: [] });
    picker.open({
      type: input.type, title: input.title, defaultPath: input.defaultPath,
      multiSelect: input.multiSelect !== false,
      list: function (v) { return t.request('qwenwork.fs.list', v); },
      describe: function (v) { return t.request('qwenwork.fs.describe', v); },
    }).then(function (res) { trpcData(op.id, res); }, function () { trpcData(op.id, { canceled: true, paths: [] }); });
  }
  /** @returns {boolean} true = 已在页内处理，不再发往 Mac */
  function interceptTrpc(msg) {
    if (!msg || msg.method !== 'request' || !msg.operation || typeof msg.operation.path !== 'string') return false;
    var op = msg.operation;
    if (op.path === 'files.selectFilesAndFolders') {
      pickPaths(op);
      return true;
    }
    if (Object.prototype.hasOwnProperty.call(NATIVE_DIALOG_ONLY, op.path)) {
      var what = NATIVE_DIALOG_ONLY[op.path];
      if (root.__mmabQwPicker) root.__mmabQwPicker.toast('“' + what + '”需要 Mac 上的系统对话框，远程网页暂不支持。');
      trpcError(op.id, op.path, '远程网页暂不支持“' + what + '”（需要 Mac 上的系统对话框）');
      return true;
    }
    return false;
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
      if (ch === TRPC && interceptTrpc(args[0])) return;
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
