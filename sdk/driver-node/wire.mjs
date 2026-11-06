// MMAB adapter wire v1 (platform <-> adapter internal authentication and framing).
// Spec: sdk/protocol/adapter-wire-v1.md. Java counterpart: protocol/wire/WireAuth.java.
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

export const AUTH_LABEL = 'mmab-auth-v1';
export const SESSION_LABEL = 'mmab-session-v1';
export const PLATFORM_TO_ADAPTER = 'p2a';
export const ADAPTER_TO_PLATFORM = 'a2p';
export const MAX_FRAME_BYTES = 1024 * 1024;
export const MAX_WIRE_LINE_BYTES = MAX_FRAME_BYTES + 64 * 1024;
export const MAX_HANDSHAKE_LINE_BYTES = 4 * 1024;
export const HANDSHAKE_TIMEOUT_MS = 3000;

export class WireError extends Error {
  constructor(code, detail) {
    super(`${code}: ${detail}`);
    this.name = 'WireError';
    this.code = code;
  }
}

export function hmac(key, message) {
  return createHmac('sha256', key).update(message, 'utf8').digest();
}

export function newNonce() {
  return randomBytes(32).toString('base64url');
}

export function platformProof(key, adapterNonce, clientNonce, instanceId, generation, protocol) {
  return hmac(key, [AUTH_LABEL, 'platform', adapterNonce, clientNonce, instanceId, String(generation), protocol]
    .join('\n')).toString('hex');
}

export function adapterProof(key, adapterNonce, clientNonce, instanceId, generation, protocol) {
  return hmac(key, [AUTH_LABEL, 'adapter', adapterNonce, clientNonce, instanceId, String(generation), protocol]
    .join('\n')).toString('hex');
}

export function sessionKey(key, adapterNonce, clientNonce) {
  return hmac(key, [SESSION_LABEL, adapterNonce, clientNonce].join('\n'));
}

export function frameMac(sk, direction, json) {
  return hmac(sk, `${direction}\n${json}`).toString('hex');
}

export function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const ba = Buffer.from(a, 'utf8');
  const bb = Buffer.from(b, 'utf8');
  return ba.length === bb.length && timingSafeEqual(ba, bb);
}

/** Encode one frame: `<hex mac> {"n":n,"env":{...}}`. */
export function encodeFrame(sk, direction, n, envelope) {
  const json = JSON.stringify({ n, env: envelope });
  return `${frameMac(sk, direction, json)} ${json}`;
}

/** Decode and verify; MAC or counter mismatch → REPLAY_REJECTED (caller must drop the connection). */
export function decodeFrame(sk, direction, expectedN, line) {
  const sp = line.indexOf(' ');
  if (sp !== 64) throw new WireError('MALFORMED_FRAME', 'frame must start with 64 hex mac');
  const mac = line.slice(0, sp);
  const json = line.slice(sp + 1);
  if (!safeEqual(mac, frameMac(sk, direction, json))) throw new WireError('REPLAY_REJECTED', 'frame mac mismatch');
  let frame;
  try {
    frame = JSON.parse(json);
  } catch {
    throw new WireError('MALFORMED_FRAME', 'invalid json');
  }
  if (frame === null || typeof frame !== 'object' || Object.keys(frame).length !== 2
      || !Number.isSafeInteger(frame.n) || typeof frame.env !== 'object' || frame.env === null) {
    throw new WireError('MALFORMED_FRAME', 'frame shape');
  }
  if (frame.n !== expectedN) throw new WireError('REPLAY_REJECTED', 'frame counter out of order');
  return frame.env;
}

/** Splits a byte stream into '\n'-terminated UTF-8 lines with a hard length limit. */
export class LineSplitter {
  constructor(maxBytes) {
    this.maxBytes = maxBytes;
    this.parts = [];
    this.size = 0;
  }

  setLimit(maxBytes) {
    this.maxBytes = maxBytes;
  }

  push(chunk) {
    const lines = [];
    let start = 0;
    for (;;) {
      const nl = chunk.indexOf(0x0a, start);
      if (nl < 0) {
        const rest = chunk.subarray(start);
        this.size += rest.length;
        if (this.size > this.maxBytes) throw new WireError('QUEUE_LIMIT', 'line too long');
        if (rest.length) this.parts.push(rest);
        return lines;
      }
      const piece = chunk.subarray(start, nl);
      this.size += piece.length;
      if (this.size > this.maxBytes) throw new WireError('QUEUE_LIMIT', 'line too long');
      this.parts.push(piece);
      lines.push(Buffer.concat(this.parts).toString('utf8'));
      this.parts = [];
      this.size = 0;
      start = nl + 1;
    }
  }
}
