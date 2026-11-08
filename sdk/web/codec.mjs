// MMAB structured value codec mmab-sv1 - ES module facade over the classic build in codec.js (single source).
// Spec: sdk/protocol/codec-sv1.md. Java counterpart: protocol/codec/StructuredCodec.java.
// Shared fixtures: sdk/protocol/fixtures/codec-sv1.json.
import './codec.js';

const C = globalThis.MmabCodec;
export const { CODEC_VERSION, MAX_DEPTH, FORBIDDEN_KEYS, CodecError, PortRef, encode, decode } = C;
