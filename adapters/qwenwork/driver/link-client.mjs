// Client side of the qwenwork link (driver <-> payload inside the QwenWork main process).
// Protocol mmab-qwen-link-v1 (JSON lines over 127.0.0.1 TCP):
//   payload -> {"t":"hello","v":"mmab-qwen-link-v1","nonce":sn}
//   driver  -> {"t":"auth","nonce":cn,"mac":HMAC(key, "mmab-qwen-link-v1|client|"+sn+"|"+cn)}
//   payload -> {"t":"ok","mac":HMAC(key, "mmab-qwen-link-v1|server|"+cn+"|"+sn)}   (mutual authentication)
//   driver  -> {"t":"call","id":n,"kind":"invoke"|"send"|"sendSync"|"bootstrap","conn":id|null,"p":<mmab-sv1>}
//              {"t":"close","conn":id}
//   payload -> {"t":"res","id":n,"ok":true,"p":<mmab-sv1>} | {"t":"res","id":n,"ok":false,"code":<ErrorCode>}
//              {"t":"evt","conn":id|null,"p":<mmab-sv1 {channel,args}>}   (null = mirror for every connection)
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import net from 'node:net';

export const LINK_VERSION = 'mmab-qwen-link-v1';
const MAX_LINE = 16 * 1024 * 1024;
const HANDSHAKE_MS = 5000;

export class LinkError extends Error {
  constructor(code, detail) {
    super(detail ? `${code}: ${detail}` : code);
    this.name = 'LinkError';
    this.code = code;
  }
}

const mac = (key, label, a, b) => createHmac('sha256', key).update(`${LINK_VERSION}|${label}|${a}|${b}`).digest('base64');
function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && timingSafeEqual(x, y);
}

/**
 * Connect and authenticate.
 * @param {{port: number, key: Buffer, onEvent?: (conn: string|null, payloadText: string) => void,
 *          onClose?: () => void, callTimeoutMs?: number}} o
 */
export function connectLink(o) {
  return new Promise((resolve, reject) => {
    const sock = net.connect({ host: '127.0.0.1', port: o.port });
    sock.setEncoding('utf8');
    const pending = new Map();
    const callTimeoutMs = o.callTimeoutMs ?? 30_000;
    let buf = '';
    let state = 'hello';
    let clientNonce = null;
    let serverNonce = null;
    let nextId = 0;
    let closed = false;
    const timer = setTimeout(() => {
      sock.destroy();
      reject(new LinkError('ADAPTER_UNAVAILABLE', 'link handshake timeout'));
    }, HANDSHAKE_MS);

    const write = (obj) => {
      if (!sock.destroyed) sock.write(`${JSON.stringify(obj)}\n`);
    };
    const link = {
      /** @returns {Promise<string>} mmab-sv1 payload text */
      call(kind, conn, payloadText) {
        if (closed) return Promise.reject(new LinkError('ADAPTER_UNAVAILABLE', 'link closed'));
        const id = ++nextId;
        return new Promise((res, rej) => {
          const t = setTimeout(() => {
            pending.delete(id);
            rej(new LinkError('DEADLINE_EXCEEDED'));
          }, callTimeoutMs);
          pending.set(id, { res, rej, t });
          write({ t: 'call', id, kind, conn: conn ?? null, p: payloadText }); // written synchronously: order kept
        });
      },
      closeConnection(conn) {
        write({ t: 'close', conn });
      },
      close() {
        sock.destroy();
      },
      get closed() {
        return closed;
      },
    };

    function onLine(line) {
      let f;
      try {
        f = JSON.parse(line);
      } catch {
        sock.destroy();
        return;
      }
      if (state === 'hello') {
        if (f?.t !== 'hello' || f.v !== LINK_VERSION || typeof f.nonce !== 'string') {
          sock.destroy();
          return;
        }
        serverNonce = f.nonce;
        clientNonce = randomBytes(16).toString('base64');
        write({ t: 'auth', nonce: clientNonce, mac: mac(o.key, 'client', serverNonce, clientNonce) });
        state = 'auth';
        return;
      }
      if (state === 'auth') {
        if (f?.t !== 'ok' || !safeEqual(f.mac, mac(o.key, 'server', clientNonce, serverNonce))) {
          sock.destroy(); // server could not prove the key: not our payload
          return;
        }
        state = 'open';
        clearTimeout(timer);
        resolve(link);
        return;
      }
      if (f?.t === 'res' && pending.has(f.id)) {
        const p = pending.get(f.id);
        pending.delete(f.id);
        clearTimeout(p.t);
        if (f.ok && typeof f.p === 'string') p.res(f.p);
        else p.rej(new LinkError(typeof f.code === 'string' ? f.code : 'ADAPTER_UNAVAILABLE'));
        return;
      }
      if (f?.t === 'evt' && typeof f.p === 'string' && o.onEvent) {
        try {
          o.onEvent(typeof f.conn === 'string' ? f.conn : null, f.p);
        } catch {
          // a failing listener must not break the link
        }
      }
    }

    sock.on('data', (chunk) => {
      buf += chunk;
      for (;;) {
        const i = buf.indexOf('\n');
        if (i < 0) break;
        const line = buf.slice(0, i);
        buf = buf.slice(i + 1);
        onLine(line);
        if (sock.destroyed) return;
      }
      if (buf.length > MAX_LINE) sock.destroy();
    });
    sock.on('error', () => {});
    sock.on('close', () => {
      closed = true;
      clearTimeout(timer);
      for (const p of pending.values()) {
        clearTimeout(p.t);
        p.rej(new LinkError('ADAPTER_UNAVAILABLE', 'link closed'));
      }
      pending.clear();
      if (state !== 'open') reject(new LinkError('ADAPTER_UNAVAILABLE', 'link refused'));
      else if (o.onClose) o.onClose();
    });
  });
}
