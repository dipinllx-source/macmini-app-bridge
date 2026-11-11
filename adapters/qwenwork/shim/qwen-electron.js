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

  // 超过单帧上限的事件由驱动拆成 qwenwork.ipc.part {id, i, n, d}，同一连接上按序到达；拼齐后按 qwenwork.ipc 处理
  var parts = null; // {id, n, got: string[]}
  function onPart(v) {
    if (!v || typeof v.id !== 'number' || typeof v.i !== 'number' || typeof v.n !== 'number' || typeof v.d !== 'string') return null;
    if (!parts || parts.id !== v.id) {
      if (v.i !== 0) return null; // 缺了开头（例如重连）：整组丢弃
      parts = { id: v.id, n: v.n, got: [] };
    }
    if (v.i !== parts.got.length) { parts = null; return null; }
    parts.got.push(v.d);
    if (parts.got.length < parts.n) return null;
    var text = parts.got.join('');
    parts = null;
    try { return root.MmabCodec.decode(text); } catch (e) {
      console.error('[mmab-qwen] chunked event could not be decoded', e && e.code);
      return null;
    }
  }
  function onIpc(value) {
    if (!value || typeof value.channel !== 'string' || !Array.isArray(value.args)) return;
    if (value.channel === 'mmab:dialog') return onNativeDialog(value.args[0]);
    if (value.channel === 'mmab:dialog-cancel') return onNativeDialogCancel(value.args[0]);
    dispatch(value.channel, value.args);
  }

  var t = transport.create({
    // disconnect diagnostics remember recent IPC channel names (never arguments)
    label: function (op, value) { return value && typeof value.channel === 'string' ? value.channel : op; },
    // 连接未能无损恢复（超过宽限期 / 平台重启）：原版页面持有的订阅与端口已失效，受控地整页重新加载
    onLost: function () { root.location.reload(); },
    onEvent: function (op, value) {
      if (op === 'qwenwork.ipc') return onIpc(value);
      if (op === 'qwenwork.ipc.part') return onIpc(onPart(value));
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
  // ------------------------------------------------------------------ 原生对话框
  // 千问在 Mac 主进程弹 electron.dialog —— 面板出现在 Mac 屏幕上，远程页面看不到，请求会一直挂起。
  // 1) 选择文件 / 目录（files.selectFilesAndFolders）直接在页内处理，不发往 Mac；
  // 2) 其余对话框（另存为、保存图片、导入技能包、导入安装包……）由 payload 拦下并发来 mmab:dialog 事件，
  //    在页内选择后经驱动校验回答，应用随后照常在 Mac 上读写所选路径。
  var TRPC = 'trpc-electron';
  function trpcAnswer(msg) {
    root.setTimeout(function () { dispatch(TRPC, [msg]); }, 0); // 与真实 IPC 一样异步到达
  }
  function trpcData(id, data) {
    trpcAnswer({ id: id, result: { type: 'data', data: { json: data } } }); // 应用使用 superjson：{json}
  }
  var pickerApi = {
    list: function (v) { return t.request('qwenwork.fs.list', v); },
    describe: function (v) { return t.request('qwenwork.fs.describe', v); },
    checkSave: function (v) { return t.request('qwenwork.fs.checkSave', v); },
  };
  function openPicker(o) {
    var picker = root.__mmabQwPicker;
    if (!picker) return Promise.resolve(null);
    return picker.open(Object.assign(o, pickerApi));
  }
  function pickPaths(op) {
    var input = (op.input && op.input.json) || {};
    openPicker({
      type: input.type, title: input.title, defaultPath: input.defaultPath, filters: input.filters,
      multiSelect: input.multiSelect !== false,
    }).then(function (res) { trpcData(op.id, res || { canceled: true, paths: [] }); },
      function () { trpcData(op.id, { canceled: true, paths: [] }); });
  }
  /** @returns {boolean} true = 已在页内处理，不再发往 Mac */
  function interceptTrpc(msg) {
    if (!msg || msg.method !== 'request' || !msg.operation || msg.operation.path !== 'files.selectFilesAndFolders') return false;
    pickPaths(msg.operation);
    return true;
  }

  var openDialogs = new Map(); // payload dialog id -> picker options (for close on timeout)
  function answerDialog(d, result) {
    openDialogs.delete(d.id);
    t.request('qwenwork.dialog.answer', { id: d.id, kind: d.kind, result: result }).then(function (r) {
      if (r && r.ok === false && root.__mmabQwPicker) root.__mmabQwPicker.toast('操作未完成：' + (r.reason || '路径不可用'));
    }, function (e) {
      if (root.__mmabQwPicker) root.__mmabQwPicker.toast('操作未完成：' + ((e && e.code) || '连接异常'));
    });
  }
  function onNativeDialog(d) {
    if (!d || typeof d.id !== 'number') return;
    var opts = d.options || {};
    var po;
    if (d.kind === 'save') {
      po = { mode: 'save', title: opts.title, defaultPath: opts.defaultPath, filters: opts.filters, buttonLabel: opts.buttonLabel };
    } else {
      var props = opts.properties || [];
      var dirs = props.indexOf('openDirectory') >= 0;
      var files = props.indexOf('openFile') >= 0 || !dirs;
      po = { type: dirs && files ? 'both' : dirs ? 'folders' : 'files', title: opts.title || opts.message,
        defaultPath: opts.defaultPath, filters: opts.filters, buttonLabel: opts.buttonLabel,
        multiSelect: props.indexOf('multiSelections') >= 0 };
    }
    openDialogs.set(d.id, po);
    openPicker(po).then(function (res) {
      if (!openDialogs.has(d.id)) return; // 已被 Mac 端撤销
      if (!res || res.canceled) return answerDialog(d, { canceled: true });
      if (d.kind === 'save') return answerDialog(d, { canceled: false, filePath: res.filePath });
      return answerDialog(d, { canceled: false, filePaths: res.paths.map(function (x) { return x.path; }) });
    }, function () { answerDialog(d, { canceled: true }); });
  }
  function onNativeDialogCancel(d) {
    var po = d && openDialogs.get(d.id);
    if (!po) return;
    openDialogs.delete(d.id);
    if (po.close) po.close();
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

  var info = transport.bootstrapOrWait(t, 'qwenwork.bootstrap', null, '千问办公');
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
