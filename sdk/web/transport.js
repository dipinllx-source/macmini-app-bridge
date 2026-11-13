// MMAB browser transport (classic script, M3). Injected by the platform before the original app's scripts so that
// adapter shims can offer a synchronous Electron-like surface while frames travel over the instance WS.
// Requires codec.js (globalThis.MmabCodec). Exposes globalThis.MmabTransport.create(options).
//
// Wire (sdk/protocol/README.md): REQUEST / RESPONSE / EVENT / HEARTBEAT / ERROR and M3 PORT_DATA / PORT_CLOSE.
// Synchronous calls use POST /__mmab/bridge/call (operations declared `sync: true` only).
// The page never sees tickets, cookies, lease ids or fencing tokens: the server binds them to the session.
(function (root) {
  'use strict';
  if (root.MmabTransport) return;
  const { encode, decode } = root.MmabCodec;
  const WS_PATH = '/__mmab/bridge/ws';
  const CALL_PATH = '/__mmab/bridge/call';
  const STATUS_PATH = '/__mmab/bridge/status';
  const HEARTBEAT_MS = 15000;
  const DIAG_PATH = '/__mmab/bridge/diag';
  const DIAG_KEY = '__mmab_last_unload';
  const NAME = /^[A-Za-z0-9_.:\/-]{1,64}$/;
  const loadedAt = Date.now();

  /** Navigation type of this page load (navigate / reload / back_forward / prerender), metadata only. */
  function navType() {
    try {
      const e = performance.getEntriesByType('navigation')[0];
      return e && ['navigate', 'reload', 'back_forward', 'prerender'].includes(e.type) ? e.type : undefined;
    } catch {
      return undefined;
    }
  }
  function visibility() {
    const v = typeof document !== 'undefined' && document.visibilityState;
    return v === 'visible' || v === 'hidden' || v === 'prerender' ? v : undefined;
  }
  /** Disconnect diagnostics (reliable-personal-entry D1): enums, numbers and message / channel NAMES only. */
  function beacon(o) {
    try {
      const body = JSON.stringify(o);
      if (typeof navigator !== 'undefined' && navigator.sendBeacon) {
        navigator.sendBeacon(DIAG_PATH, new Blob([body], { type: 'text/plain' }));
      }
    } catch {
      // diagnostics must never break the page
    }
  }

  class BridgeError extends Error {
    constructor(code) {
      super(code);
      this.name = 'BridgeError';
      this.code = code;
    }
  }

  /** Visible failure instead of a silent white screen (design §14.2). Never shows payloads. */
  function banner(text, id) {
    const show = () => {
      let el = document.getElementById(id || '__mmab_banner');
      if (!el) {
        el = document.createElement('div');
        el.id = id || '__mmab_banner';
        el.setAttribute('role', 'alert');
        el.style.cssText = 'position:fixed;left:50%;top:0;transform:translateX(-50%);z-index:2147483647;'
          + 'max-width:90vw;padding:8px 14px;border-radius:0 0 8px 8px;background:#b42318;color:#fff;'
          + 'font:13px/1.4 -apple-system,BlinkMacSystemFont,sans-serif;white-space:pre-wrap';
        (document.body || document.documentElement).appendChild(el);
      }
      el.textContent = text;
      if (text && /刷新本页/.test(text)) {
        const b = document.createElement('button');
        b.type = 'button';
        b.textContent = '刷新重连';
        b.style.cssText = 'margin-left:10px;padding:2px 10px;border:0;border-radius:4px;background:#fff;color:#b42318;cursor:pointer';
        b.addEventListener('click', () => location.reload());
        el.appendChild(b);
      }
      el.style.display = text ? 'block' : 'none';
    };
    if (document.body || document.readyState !== 'loading') show();
    else document.addEventListener('DOMContentLoaded', show, { once: true });
  }

  function closeMessage(code, reason) {
    if (code === 1013 || reason === 'LEASE_OCCUPIED') {
      return 'macmini App 引桥：该实例已在其他标签页或设备上打开。请关闭另一个页面后刷新本页，或回到控制台重新打开。';
    }
    if (reason === 'UNAUTHENTICATED') return 'macmini App 引桥：登录已失效（过期或已注销）。请回到控制台重新登录后打开。';
    if (reason === 'BLOCKED_UPGRADE' || reason === 'COMPATIBILITY_BLOCKED') {
      return `macmini App 引桥：本地应用已升级，平台尚未适配（${reason}）。请在控制台查看详情。`;
    }
    if (code === 1009) return 'macmini App 引桥：单帧超过 1 MiB，连接已关闭。';
    return `macmini App 引桥连接已断开（${reason || code}）。请回到控制台重新打开。`;
  }

  /**
   * @param {object} o
   * @param {(op: string, value: any) => void} [o.onEvent]
   * @param {(port: string, payloadText: string) => void} [o.onPortData]
   * @param {(port: string) => void} [o.onPortClose]
   * @param {(code: number, reason: string) => void} [o.onClose]
   */
  function create(o = {}) {
    const pending = new Map();
    const stats = { connected: false, requests: 0, events: 0, portFrames: 0, errors: 0, closeCode: null };
    const recent = [];
    let openedAt = null;
    let unloading = false;
    /** Remember the last few app message / IPC channel names (never content) for disconnect diagnostics. */
    function note(name) {
      if (typeof name !== 'string' || !NAME.test(name) || recent[recent.length - 1] === name) return;
      recent.push(name);
      if (recent.length > 16) recent.shift();
    }
    const base = () => {
      const o = { vis: visibility(), up: Math.max(0, Date.now() - loadedAt), recent: recent.slice() };
      if (openedAt !== null) o.ws = Math.max(0, Date.now() - openedAt);
      return o;
    };
    try {
      const prev = typeof sessionStorage !== 'undefined' && sessionStorage.getItem(DIAG_KEY);
      if (prev) {
        sessionStorage.removeItem(DIAG_KEY);
        beacon({ e: 'load', nav: navType(), prev: JSON.parse(prev) });
      } else if (navType() === 'reload') {
        beacon({ e: 'load', nav: 'reload' });
      }
    } catch {
      // no storage: skip
    }
    if (typeof addEventListener === 'function') {
      addEventListener('pagehide', () => {
        unloading = true;
        try {
          if (ws && ws.readyState === WebSocket.OPEN) ws.close(1000, 'pagehide'); // 页面离开：干净关闭，平台不挂起
        } catch {
          // ignore
        }
        const o = { e: 'unload', ...base() };
        beacon(o);
        try {
          sessionStorage.setItem(DIAG_KEY, JSON.stringify(o));
        } catch {
          // ignore
        }
      });
    }
    let queue = [];
    let seq = 0;
    let closed = null; // fatal reason once the page gave up
    let timer = null;
    let ws = null;
    let connectionId = null;
    let everOpen = false;
    let attempt = 0;
    let retryTimer = null;
    let reconnectingSince = null;
    const MAX_QUEUED = 2048;
    // retryable: no close frame / going away / server restart / adapter restart / lease or generation moved on.
    // fatal: session gone, another tab, upgrade blocked, oversized frame (the server already explained why).
    const FATAL_REASONS = new Set(['UNAUTHENTICATED', 'LEASE_OCCUPIED', 'BLOCKED_UPGRADE', 'COMPATIBILITY_BLOCKED',
      'ORIGIN_REJECTED', 'HOST_REJECTED']);
    const isFatal = (code, reason) => code === 1013 || code === 1009 || code === 1003 || FATAL_REASONS.has(reason);

    let readyResolve;
    let readyReject;
    const ready = new Promise((resolve, reject) => { readyResolve = resolve; readyReject = reject; });
    ready.catch(() => {});

    function sendFrame(frame) {
      const text = JSON.stringify(frame);
      if (closed) throw new BridgeError(closed);
      if (ws && ws.readyState === WebSocket.OPEN) ws.send(text);
      else {
        if (queue.length >= MAX_QUEUED) throw new BridgeError('QUEUE_LIMIT');
        queue.push(text); // sent once (re)connected; never replays frames that already left
      }
    }

    function flush(sock) {
      const q = queue;
      queue = [];
      for (const f of q) if (sock.readyState === WebSocket.OPEN) sock.send(f);
    }

    function giveUp(code, reason) {
      if (closed) return;
      closed = reason || `CLOSED_${code}`;
      clearTimeout(retryTimer);
      queue = [];
      for (const p of pending.values()) p.reject(new BridgeError(closed));
      pending.clear();
      readyReject(new BridgeError(closed));
      banner(closeMessage(code, reason));
      if (o.onClose) o.onClose(code, reason);
    }

    function onMessage(ev) {
      let f;
      try {
        f = JSON.parse(ev.data);
      } catch {
        return;
      }
      switch (f.kind) {
        case 'HELLO': {
          const previous = connectionId;
          connectionId = typeof f.connectionId === 'string' ? f.connectionId : null;
          if (previous && !f.resumed) {
            // the server no longer has this page's ports / subscriptions: frames queued for the old state are
            // meaningless, drop them and let the app start over (controlled re-initialisation)
            queue = [];
            if (o.onLost) o.onLost();
            return;
          }
          if (previous) flush(ws);
          return;
        }
        case 'RESPONSE': {
          const p = pending.get(f.cid);
          if (!p) return;
          pending.delete(f.cid);
          if (f.error) p.reject(new BridgeError(f.error.code));
          else if (p.raw) p.resolve(f.payload);
          else {
            try {
              p.resolve(decode(f.payload));
            } catch (e) {
              p.reject(e);
            }
          }
          return;
        }
        case 'EVENT': {
          stats.events++;
          if (!o.onEvent) return;
          let value;
          try {
            value = decode(f.payload);
          } catch {
            stats.errors++;
            return;
          }
          try {
            if (o.label) note(o.label(f.op, value));
            o.onEvent(f.op, value, f.payload);
          } catch (e) {
            stats.errors++;
            console.error('[mmab] event listener failed', f.op, e);
          }
          return;
        }
        case 'PORT_DATA':
          stats.portFrames++;
          if (o.onPortData) o.onPortData(f.port, f.payload);
          return;
        case 'PORT_CLOSE':
          if (o.onPortClose) o.onPortClose(f.port);
          return;
        case 'ERROR':
          stats.errors++;
          console.warn('[mmab] bridge error', f.error && f.error.code);
          return;
        default:
      }
    }

    function connect() {
      const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + WS_PATH
        + (connectionId ? `?resume=${encodeURIComponent(connectionId)}` : '');
      const sock = new WebSocket(url);
      ws = sock;
      sock.addEventListener('open', () => {
        if (ws !== sock) return;
        stats.connected = true;
        openedAt = Date.now();
        everOpen = true;
        attempt = 0;
        reconnectingSince = null;
        stats.reconnects = (stats.reconnects || 0) + (stats.opens ? 1 : 0);
        stats.opens = (stats.opens || 0) + 1;
        banner('', '__mmab_reconnect');
        // first connection: nothing to lose, flush now; after a reconnect wait for HELLO (resumed or not)
        if (!connectionId) flush(sock);
        clearInterval(timer);
        timer = setInterval(() => {
          if (sock.readyState === WebSocket.OPEN) sock.send('{"kind":"HEARTBEAT"}');
        }, HEARTBEAT_MS);
        readyResolve();
      }, { once: true });
      sock.addEventListener('message', (ev) => { if (ws === sock) onMessage(ev); });
      sock.addEventListener('close', (ev) => {
        if (ws !== sock) return;
        stats.connected = false;
        stats.closeCode = ev.code;
        clearInterval(timer);
        const d = { e: 'close', code: ev.code, clean: !!ev.wasClean, unl: unloading, ...base() };
        if (/^[A-Z_]{1,40}$/.test(ev.reason || '')) d.reason = ev.reason;
        beacon(d);
        // requests already on the wire: their outcome is unknown, they are NOT replayed
        for (const p of pending.values()) p.reject(new BridgeError('DEADLINE_EXCEEDED'));
        pending.clear();
        if (unloading || closed) return;
        if (isFatal(ev.code, ev.reason) || (!everOpen && ev.code !== 1006)) {
          giveUp(ev.code, ev.reason);
          return;
        }
        scheduleReconnect();
      });
    }

    function hidden() {
      return typeof document !== 'undefined' && document.visibilityState === 'hidden';
    }

    function scheduleReconnect() {
      if (closed) return;
      if (reconnectingSince === null) reconnectingSince = Date.now();
      const delay = Math.min(hidden() ? 30000 : 15000, 1000 * 2 ** Math.min(attempt, 5));
      attempt++;
      clearTimeout(retryTimer);
      retryTimer = setTimeout(() => {
        if (Date.now() - reconnectingSince > 2000) {
          banner('macmini App 引桥：连接中断，正在自动重新连接…', '__mmab_reconnect');
        }
        // keep trying while hidden too: background timers are throttled but still fire, and the server only keeps
        // this page's state for a limited grace period (a visible tab retries at once via visibilitychange)
        probeThenConnect();
      }, delay);
    }

    /** Ask the instance port why we were cut off before reopening (a failed WS handshake never says why). */
    function probeThenConnect() {
      const done = (status, reason) => {
        if (status === 200) connect();
        else if (status === 401) giveUp(1008, 'UNAUTHENTICATED');
        else if (status === 409) giveUp(1013, 'LEASE_OCCUPIED');
        else if (status === 503 && (reason === 'BLOCKED_UPGRADE' || reason === 'COMPATIBILITY_BLOCKED')) giveUp(1008, reason);
        else scheduleReconnect(); // platform restarting / adapter not ready / network still down
      };
      if (typeof fetch !== 'function') {
        connect();
        return;
      }
      fetch(STATUS_PATH, { credentials: 'same-origin', cache: 'no-store' })
        .then((r) => r.json().catch(() => ({})).then((j) => done(r.status, j && (j.reason || (j.error && j.error.code)))))
        .catch(() => scheduleReconnect());
    }

    if (typeof document !== 'undefined' && document.addEventListener) {
      document.addEventListener('visibilitychange', () => {
        if (!hidden() && reconnectingSince !== null && !closed && !(ws && ws.readyState <= 1)) {
          clearTimeout(retryTimer);
          probeThenConnect();
        }
      });
    }

    connect();

    function request(op, value) {
      return requestRaw(op, encode(value), false);
    }

    /** payloadText is already mmab-sv1 (e.g. produced by the port relay); raw=true resolves with payload text. */
    function requestRaw(op, payloadText, raw = true) {
      return new Promise((resolve, reject) => {
        const cid = String(++seq);
        pending.set(cid, { resolve, reject, raw });
        stats.requests++;
        try {
          sendFrame({ kind: 'REQUEST', op, cid, payload: payloadText });
        } catch (e) {
          pending.delete(cid);
          reject(e);
        }
      });
    }

    /** Synchronous call (blocks the page): only for operations declared `sync: true` (bootstrap, sendSync). */
    function call(op, value) {
      const x = new XMLHttpRequest();
      x.open('POST', CALL_PATH, false);
      x.setRequestHeader('Content-Type', 'application/json');
      x.send(JSON.stringify({ op, payload: encode(value) }));
      if (x.status !== 200) throw new BridgeError(`HTTP_${x.status}`);
      const r = JSON.parse(x.responseText);
      if (r.error) throw new BridgeError(r.error.code);
      return decode(r.payload);
    }

    return {
      ready,
      stats,
      request,
      requestRaw,
      call,
      sendPortData(port, payloadText) {
        sendFrame({ kind: 'PORT_DATA', port, payload: payloadText });
      },
      sendPortClose(port) {
        sendFrame({ kind: 'PORT_CLOSE', port });
      },
      isOpen: () => !!ws && ws.readyState === WebSocket.OPEN,
      note,
      close() {
        closed = 'client';
        clearTimeout(retryTimer);
        if (ws) ws.close(1000, 'client');
      },
    };
  }

  /**
   * 启动引导（同步调用）。失败时不再让页面静默白屏：显示原因，并每 10 秒在后台重试同一调用，成功即整页刷新。
   * 典型场景：本地应用刚升级 / 重启，适配器连不上应用（ADAPTER_UNAVAILABLE），或版本未适配（BLOCKED_UPGRADE）。
   * @returns {*} 引导结果；失败时抛出原错误（调用方的 shim 随之停止，原版脚本不会在缺桥的情况下运行）
   */
  function bootstrapOrWait(t, op, value, appName) {
    try {
      return t.call(op, value);
    } catch (err) {
      const code = (err && err.code) || 'UNKNOWN';
      const name = appName || '应用';
      let text;
      if (code === 'HTTP_401') text = closeMessage(1008, 'UNAUTHENTICATED');
      else if (code === 'HTTP_409') text = closeMessage(1013, 'LEASE_OCCUPIED');
      else if (code === 'BLOCKED_UPGRADE' || code === 'COMPATIBILITY_BLOCKED') {
        text = `macmini App 引桥：本机${name}已升级，平台尚未适配（${code}）。适配完成后本页会自动刷新。`;
      } else if (code === 'PORT_OWNER_MISMATCH') {
        text = `macmini App 引桥：平台专用的${name}实例没有在运行（应用自动升级或重启后会被关闭），需要在 Mac 上重新启动；恢复后本页会自动刷新。`;
      } else {
        text = `macmini App 引桥：暂时连不上本机${name}（${code}）。常见原因是应用刚升级或重启；恢复后本页会自动刷新。`;
      }
      banner(text);
      if (code !== 'HTTP_401' && code !== 'HTTP_409') {
        const timer = setInterval(() => {
          try {
            t.call(op, value);
            clearInterval(timer);
            location.reload();
          } catch (e) { /* still unavailable */ }
        }, 10000);
      }
      throw err;
    }
  }

  root.MmabTransport = Object.freeze({ create, banner, bootstrapOrWait, BridgeError });
})(globalThis);
