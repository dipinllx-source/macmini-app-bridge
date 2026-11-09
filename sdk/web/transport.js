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
  const HEARTBEAT_MS = 15000;

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
    if (code === 1013) return 'macmini App 引桥：该实例已在另一个标签页打开（或本页过慢被断开）。请关闭另一个标签页后回到控制台重新打开。';
    if (code === 1009) return 'macmini App 引桥：单帧超过 1 MiB，连接已关闭。';
    // 1006（无关闭帧）/ QUEUE_LIMIT：多为网络中断或推送积压被平台断开；会话与租约仍在，刷新本页即可重连
    if (code === 1006 || reason === 'QUEUE_LIMIT') {
      return `macmini App 引桥连接意外中断（${reason || code}）。刷新本页即可重新连接；若刷新后仍失败，请回到控制台重新打开。`;
    }
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
    let queue = [];
    let seq = 0;
    let closed = null;
    let timer = null;
    const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + WS_PATH);

    const ready = new Promise((resolve, reject) => {
      ws.addEventListener('open', () => {
        stats.connected = true;
        for (const f of queue) ws.send(f);
        queue = [];
        timer = setInterval(() => {
          if (ws.readyState === WebSocket.OPEN) ws.send('{"kind":"HEARTBEAT"}');
        }, HEARTBEAT_MS);
        resolve();
      }, { once: true });
      ws.addEventListener('close', (ev) => reject(new BridgeError(ev.reason || `CLOSED_${ev.code}`)), { once: true });
    });
    ready.catch(() => {});

    function sendFrame(frame) {
      const text = JSON.stringify(frame);
      if (closed) throw new BridgeError(closed);
      if (ws.readyState === WebSocket.OPEN) ws.send(text);
      else queue.push(text);
    }

    ws.addEventListener('message', (ev) => {
      let f;
      try {
        f = JSON.parse(ev.data);
      } catch {
        return;
      }
      switch (f.kind) {
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
    });
    ws.addEventListener('close', (ev) => {
      closed = ev.reason || `CLOSED_${ev.code}`;
      stats.connected = false;
      stats.closeCode = ev.code;
      clearInterval(timer);
      for (const p of pending.values()) p.reject(new BridgeError(closed));
      pending.clear();
      banner(closeMessage(ev.code, ev.reason));
      if (o.onClose) o.onClose(ev.code, ev.reason);
    });

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
      isOpen: () => ws.readyState === WebSocket.OPEN,
      close() {
        ws.close(1000, 'client');
      },
    };
  }

  root.MmabTransport = Object.freeze({ create, banner, BridgeError });
})(globalThis);
