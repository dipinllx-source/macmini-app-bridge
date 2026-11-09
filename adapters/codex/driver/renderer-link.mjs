// CDP link to the dedicated Codex renderer (app://-/index.html). Loopback only; the target must belong to the
// verified dedicated process. Installs codec.js + ports.js + payload/renderer-bridge.js and one binding.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../../..');
export const BINDING = '__mmabCodexOut';
export const RENDERER_URL = 'app://-/index.html';

export class RendererError extends Error {
  constructor(code, detail) {
    super(detail ? `${code}: ${detail}` : code);
    this.name = 'RendererError';
    this.code = code;
  }
}

/** The script installed in the renderer (also used by the offline tests). */
export function payloadSource() {
  const f = (p) => readFileSync(path.join(repo, p), 'utf8');
  return `${f('sdk/web/codec.js')}\n${f('sdk/web/ports.js')}\n${f('adapters/codex/payload/renderer-bridge.js')}\ntrue`;
}

/**
 * @param {{port:number, onBinding:(payload:string)=>void, onDisconnect?:()=>void, timeoutMs?:number}} o
 * @returns {Promise<{evaluate:(expr:string)=>Promise<any>, close:()=>void}>}
 */
export async function connectRenderer(o) {
  const res = await fetch(`http://127.0.0.1:${o.port}/json/list`, { signal: AbortSignal.timeout(5000) });
  const targets = await res.json();
  const t = targets.find((x) => x.type === 'page' && x.url === RENDERER_URL);
  if (!t) throw new RendererError('ADAPTER_UNAVAILABLE', 'Codex renderer not found');
  const url = new URL(t.webSocketDebuggerUrl);
  if (url.hostname !== '127.0.0.1' || url.port !== String(o.port)) throw new RendererError('PORT_OWNER_MISMATCH', 'non-loopback target');
  const ws = new WebSocket(url);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', () => reject(new RendererError('ADAPTER_UNAVAILABLE', 'CDP connect failed')), { once: true });
  });
  const timeoutMs = o.timeoutMs ?? 15_000;
  const pending = new Map();
  let id = 0;
  let closed = false;
  ws.addEventListener('message', (ev) => {
    let m;
    try { m = JSON.parse(ev.data); } catch { return; }
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id);
      pending.delete(m.id);
      clearTimeout(p.timer);
      if (m.error) p.reject(new RendererError('ADAPTER_UNAVAILABLE', 'CDP command failed'));
      else p.resolve(m.result);
      return;
    }
    if (m.method === 'Runtime.bindingCalled' && m.params?.name === BINDING) o.onBinding(m.params.payload);
    if (m.method === 'Runtime.executionContextsCleared') o.onDisconnect?.('reload');
  });
  ws.addEventListener('close', () => {
    closed = true;
    for (const p of pending.values()) { clearTimeout(p.timer); p.reject(new RendererError('ADAPTER_UNAVAILABLE', 'CDP closed')); }
    pending.clear();
    o.onDisconnect?.('closed');
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    if (closed) { reject(new RendererError('ADAPTER_UNAVAILABLE', 'CDP closed')); return; }
    const i = ++id;
    const timer = setTimeout(() => { pending.delete(i); reject(new RendererError('DEADLINE_EXCEEDED')); }, timeoutMs);
    pending.set(i, { resolve, reject, timer });
    ws.send(JSON.stringify({ id: i, method, params }));
  });
  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
      const msg = r.exceptionDetails.exception?.description ?? '';
      const code = /Error: ([A-Z_]{4,})/.exec(msg)?.[1];
      throw new RendererError(code ?? 'ADAPTER_UNAVAILABLE', 'renderer evaluation failed');
    }
    return r.result.value;
  };
  await send('Runtime.enable');
  await send('Runtime.addBinding', { name: BINDING });
  await evaluate(payloadSource());
  return {
    evaluate,
    async detach() {
      try { await evaluate('window.__mmabCodex && window.__mmabCodex.uninstall(); true'); } catch { /* gone */ }
      try { await send('Runtime.removeBinding', { name: BINDING }); } catch { /* gone */ }
      ws.close();
    },
    close: () => ws.close(),
    get closed() { return closed; },
  };
}
