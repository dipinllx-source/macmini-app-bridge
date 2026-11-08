// MMAB browser shim (injected by the platform into the instance's original page, before app scripts).
// Exposes window.mmab = { ready, invoke(op, value), on(op, listener), close() }.
// The page never sees tickets, cookies, lease ids or fencing tokens: the server binds those to the session.
import { decode, encode } from './codec.mjs';

const pending = new Map();
const listeners = new Map();
let socket = null;
let seq = 0;
let closedReason = null;

class BridgeError extends Error {
  constructor(code) {
    super(code);
    this.name = 'BridgeError';
    this.code = code;
  }
}

function banner(text) {
  // Visible failure instead of a silent white screen (design §14.2 "可见错误提示").
  let el = document.getElementById('__mmab_banner');
  if (!el) {
    el = document.createElement('div');
    el.id = '__mmab_banner';
    el.setAttribute('role', 'alert');
    el.style.cssText = 'position:fixed;left:0;right:0;top:0;z-index:2147483647;padding:8px 12px;'
      + 'background:#b42318;color:#fff;font:14px/1.4 -apple-system,sans-serif';
    (document.body ?? document.documentElement).appendChild(el);
  }
  el.textContent = text;
}

async function connect() {
  const res = await fetch('/__mmab/bridge/bootstrap', { credentials: 'same-origin', cache: 'no-store' });
  if (!res.ok) throw new BridgeError(res.status === 401 ? 'UNAUTHENTICATED' : `BOOTSTRAP_${res.status}`);
  const boot = await res.json();
  const url = new URL(boot.wsPath, location.href);
  url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(url);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('close', (ev) => reject(new BridgeError(ev.reason || `CLOSED_${ev.code}`)), { once: true });
  });
  socket = ws;
  ws.addEventListener('message', (ev) => {
    let frame;
    try {
      frame = JSON.parse(ev.data);
    } catch {
      return;
    }
    if (frame.kind === 'RESPONSE') {
      const p = pending.get(frame.cid);
      if (!p) return;
      pending.delete(frame.cid);
      if (frame.error) p.reject(new BridgeError(frame.error.code));
      else {
        try {
          p.resolve(decode(frame.payload));
        } catch (e) {
          p.reject(e);
        }
      }
    } else if (frame.kind === 'EVENT') {
      const set = listeners.get(frame.op);
      if (!set) return;
      let value;
      try {
        value = decode(frame.payload);
      } catch {
        return;
      }
      for (const fn of set) fn(value);
    } else if (frame.kind === 'ERROR') {
      banner(`macmini App 引桥：${frame.error?.code ?? 'ERROR'}`);
    }
  });
  ws.addEventListener('close', (ev) => {
    closedReason = ev.reason || `CLOSED_${ev.code}`;
    for (const p of pending.values()) p.reject(new BridgeError(closedReason));
    pending.clear();
    clearInterval(timer);
    banner(`macmini App 引桥连接已断开（${closedReason}），请回到控制台重新打开。`);
  });
  const timer = setInterval(() => {
    if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ kind: 'HEARTBEAT' }));
  }, Math.max(5, boot.heartbeatSeconds ?? 15) * 1000);
  return boot;
}

const ready = connect().catch((e) => {
  banner(`macmini App 引桥未连接（${e?.code ?? e?.message ?? e}）`);
  throw e;
});
ready.catch(() => {});

window.mmab = Object.freeze({
  ready,
  invoke(op, value) {
    return ready.then(() => new Promise((resolve, reject) => {
      if (closedReason) return reject(new BridgeError(closedReason));
      const cid = String(++seq);
      let payload;
      try {
        payload = encode(value);
      } catch (e) {
        return reject(e);
      }
      pending.set(cid, { resolve, reject });
      socket.send(JSON.stringify({ kind: 'REQUEST', op, cid, payload }));
      return undefined;
    }));
  },
  on(op, fn) {
    if (!listeners.has(op)) listeners.set(op, new Set());
    listeners.get(op).add(fn);
    return () => listeners.get(op)?.delete(fn);
  },
  close() {
    socket?.close(1000, 'client');
  },
});
