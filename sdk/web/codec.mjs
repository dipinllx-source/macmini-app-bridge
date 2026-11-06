// MMAB structured value codec mmab-sv1 (browser + Node, no dependencies).
// Spec: sdk/protocol/codec-sv1.md. Java counterpart: protocol/codec/StructuredCodec.java.
// Shared fixtures: sdk/protocol/fixtures/codec-sv1.json.
//
// Every JSON object in the encoded form is a tagged node {"$t": ...}; plain objects are wrapped in
// {"$t":"object","v":{...}}, so business data can never forge a type tag. Unsupported values throw
// CodecError('CODEC_UNSUPPORTED') instead of being silently turned into {} or null.

export const CODEC_VERSION = 'mmab-sv1';
export const MAX_DEPTH = 32;
export const FORBIDDEN_KEYS = new Set(['__proto__', 'constructor', 'prototype']);

const ERROR_CTORS = {
  Error, EvalError, RangeError, ReferenceError, SyntaxError, TypeError, URIError,
};

// Order matters only for documentation; instanceof checks are exact per class.
const VIEW_KINDS = [
  ['Int8Array', Int8Array], ['Uint8Array', Uint8Array], ['Uint8ClampedArray', Uint8ClampedArray],
  ['Int16Array', Int16Array], ['Uint16Array', Uint16Array], ['Int32Array', Int32Array],
  ['Uint32Array', Uint32Array], ['Float32Array', Float32Array], ['Float64Array', Float64Array],
  ['BigInt64Array', BigInt64Array], ['BigUint64Array', BigUint64Array],
];
const VIEW_BY_NAME = new Map(VIEW_KINDS);
const BIGINT_RE = /^-?(0|[1-9][0-9]{0,4095})$/;
const PORT_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
const BASE64_RE = /^[A-Za-z0-9+/]*={0,2}$/;

export class CodecError extends Error {
  constructor(code, detail) {
    super(`${code}: ${detail}`);
    this.name = 'CodecError';
    this.code = code;
  }
}

/** Reference to a MessagePort carried by id; the port lifecycle lives outside the codec. */
export class PortRef {
  constructor(id) {
    if (typeof id !== 'string' || !PORT_ID_RE.test(id)) throw new CodecError('MALFORMED_FRAME', 'invalid port id');
    this.id = id;
    Object.freeze(this);
  }
}

const unsupported = (d) => new CodecError('CODEC_UNSUPPORTED', d);
const malformed = (d) => new CodecError('MALFORMED_FRAME', d);

// ------------------------------------------------------------------ encode

export function encode(value) {
  return JSON.stringify(toEncoded(value, 0, new Set()));
}

function checkDepth(depth) {
  if (depth > MAX_DEPTH) throw unsupported(`nesting depth exceeds ${MAX_DEPTH}`);
}

function toEncoded(v, depth, stack) {
  if (v === null) return null;
  switch (typeof v) {
    case 'undefined': return { $t: 'undef' };
    case 'boolean':
    case 'string': return v;
    case 'number':
      if (Number.isNaN(v)) return { $t: 'num', v: 'NaN' };
      if (v === Infinity) return { $t: 'num', v: 'Infinity' };
      if (v === -Infinity) return { $t: 'num', v: '-Infinity' };
      if (Object.is(v, -0)) return { $t: 'num', v: '-0' };
      return v;
    case 'bigint': return { $t: 'bigint', v: v.toString() };
    case 'object': break;
    default: throw unsupported(`type ${typeof v}`);
  }
  if (v instanceof PortRef) return { $t: 'port', id: v.id };
  if (v instanceof Date) {
    const t = v.getTime();
    return { $t: 'date', v: Number.isNaN(t) ? null : t };
  }
  if (v instanceof ArrayBuffer) return { $t: 'bytes', k: 'ArrayBuffer', v: toBase64(new Uint8Array(v)) };
  if (ArrayBuffer.isView(v)) {
    const kind = VIEW_KINDS.find(([, C]) => v instanceof C && Object.getPrototypeOf(v) === C.prototype)
      ?? (v instanceof Uint8Array ? ['Uint8Array', Uint8Array] : null); // Node Buffer → Uint8Array
    if (!kind) throw unsupported('unsupported binary view');
    return { $t: 'bytes', k: kind[0], v: toBase64(new Uint8Array(v.buffer, v.byteOffset, v.byteLength)) };
  }
  if (v instanceof Error) {
    const name = Object.hasOwn(ERROR_CTORS, v.name) ? v.name : 'Error';
    return { $t: 'error', name, message: String(v.message ?? '') };
  }
  if (stack.has(v)) throw unsupported('cycle');
  checkDepth(depth + 1);
  stack.add(v);
  try {
    if (Array.isArray(v)) {
      const out = new Array(v.length);
      for (let i = 0; i < v.length; i++) out[i] = toEncoded(v[i], depth + 1, stack);
      return out;
    }
    if (v instanceof Map) {
      const out = [];
      for (const [k, val] of v) out.push([toEncoded(k, depth + 1, stack), toEncoded(val, depth + 1, stack)]);
      return { $t: 'map', v: out };
    }
    if (v instanceof Set) {
      const out = [];
      for (const item of v) out.push(toEncoded(item, depth + 1, stack));
      return { $t: 'set', v: out };
    }
    const proto = Object.getPrototypeOf(v);
    if (proto !== Object.prototype && proto !== null) throw unsupported('non-plain object');
    const out = {};
    for (const key of Object.keys(v)) {
      if (FORBIDDEN_KEYS.has(key)) throw unsupported('forbidden key');
      out[key] = toEncoded(v[key], depth + 1, stack);
    }
    return { $t: 'object', v: out };
  } finally {
    stack.delete(v);
  }
}

// ------------------------------------------------------------------ decode

export function decode(text) {
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw malformed('invalid json');
  }
  return fromEncoded(parsed, 0);
}

function onlyKeys(n, ...allowed) {
  for (const k of Object.keys(n)) if (!allowed.includes(k)) throw malformed('unexpected key in tagged node');
}

function str(n, field) {
  const v = n[field];
  if (typeof v !== 'string') throw malformed(`${field} must be a string`);
  return v;
}

function arr(n, field) {
  const v = n[field];
  if (!Array.isArray(v)) throw malformed(`${field} must be an array`);
  return v;
}

function fromEncoded(n, depth) {
  if (n === null || typeof n === 'boolean' || typeof n === 'string' || typeof n === 'number') return n;
  if (Array.isArray(n)) {
    checkDepth(depth + 1);
    return n.map((item) => fromEncoded(item, depth + 1));
  }
  if (typeof n !== 'object') throw malformed('unexpected json value');
  if (!Object.hasOwn(n, '$t') || typeof n.$t !== 'string') throw malformed('object without $t tag');
  switch (n.$t) {
    case 'undef':
      onlyKeys(n, '$t');
      return undefined;
    case 'num': {
      onlyKeys(n, '$t', 'v');
      const v = str(n, 'v');
      if (v === 'NaN') return NaN;
      if (v === 'Infinity') return Infinity;
      if (v === '-Infinity') return -Infinity;
      if (v === '-0') return -0;
      throw malformed('invalid special number');
    }
    case 'bigint': {
      onlyKeys(n, '$t', 'v');
      const v = str(n, 'v');
      if (!BIGINT_RE.test(v)) throw malformed('invalid bigint');
      return BigInt(v);
    }
    case 'date': {
      onlyKeys(n, '$t', 'v');
      if (!Object.hasOwn(n, 'v')) throw malformed('date without v');
      if (n.v === null) return new Date(NaN);
      if (typeof n.v !== 'number') throw malformed('date v must be number|null');
      return new Date(n.v);
    }
    case 'bytes': {
      onlyKeys(n, '$t', 'k', 'v');
      const k = str(n, 'k');
      if (k !== 'ArrayBuffer' && !VIEW_BY_NAME.has(k)) throw unsupported('unsupported binary view');
      const bytes = fromBase64(str(n, 'v'));
      if (k === 'ArrayBuffer') return bytes.buffer;
      const C = VIEW_BY_NAME.get(k);
      if (bytes.byteLength % C.BYTES_PER_ELEMENT !== 0) throw malformed('byte length not aligned');
      return new C(bytes.buffer);
    }
    case 'object': {
      onlyKeys(n, '$t', 'v');
      checkDepth(depth + 1);
      const v = n.v;
      if (v === null || typeof v !== 'object' || Array.isArray(v)) throw malformed('object v must be a json object');
      const out = {};
      for (const key of Object.keys(v)) {
        if (FORBIDDEN_KEYS.has(key)) throw unsupported('forbidden key');
        out[key] = fromEncoded(v[key], depth + 1);
      }
      return out;
    }
    case 'map': {
      onlyKeys(n, '$t', 'v');
      checkDepth(depth + 1);
      const m = new Map();
      for (const pair of arr(n, 'v')) {
        if (!Array.isArray(pair) || pair.length !== 2) throw malformed('map entry must be [k, v]');
        m.set(fromEncoded(pair[0], depth + 1), fromEncoded(pair[1], depth + 1));
      }
      return m;
    }
    case 'set': {
      onlyKeys(n, '$t', 'v');
      checkDepth(depth + 1);
      const s = new Set();
      for (const item of arr(n, 'v')) s.add(fromEncoded(item, depth + 1));
      return s;
    }
    case 'error': {
      onlyKeys(n, '$t', 'name', 'message');
      const name = str(n, 'name');
      if (!Object.hasOwn(ERROR_CTORS, name)) throw malformed('unknown error name');
      return new ERROR_CTORS[name](str(n, 'message'));
    }
    case 'port':
      onlyKeys(n, '$t', 'id');
      return new PortRef(str(n, 'id'));
    default:
      throw unsupported('unknown tag');
  }
}

// ------------------------------------------------------------------ base64 (no Buffer in browsers)

function toBase64(bytes) {
  let bin = '';
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
  }
  return btoa(bin);
}

function fromBase64(text) {
  if (!BASE64_RE.test(text)) throw malformed('invalid base64');
  let bin;
  try {
    bin = atob(text);
  } catch {
    throw malformed('invalid base64');
  }
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
