// Minimal envelope validation mirroring sdk/protocol/envelope.schema.json (the schema stays authoritative).
export const PROTOCOL_VERSION = '1.0';
export const NO_LEASE = 'none';
export const PLATFORM_CONNECTION = 'platform';

const ID_RE = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/;
const OP_RE = /^[a-z][a-z0-9]*(\.[a-z][a-zA-Z0-9-]*)+$/;
const KINDS = new Set(['REQUEST', 'RESPONSE', 'EVENT', 'PORT_OPEN', 'PORT_TRANSFER', 'PORT_DATA', 'PORT_CLOSE',
  'PORT_ERROR', 'HEARTBEAT', 'CONTROL']);
const FIELDS = new Set(['protocolVersion', 'profileId', 'instanceId', 'processGeneration', 'connectionId', 'leaseId',
  'fencingToken', 'streamId', 'sequence', 'correlationId', 'messageKind', 'operation', 'deadline', 'codecVersion',
  'payload', 'error']);
const COUNTER = (v) => Number.isSafeInteger(v) && v >= 0;

/** @returns {string|null} null when valid, otherwise a short reason */
export function envelopeProblem(e) {
  if (e === null || typeof e !== 'object' || Array.isArray(e)) return 'not an object';
  for (const k of Object.keys(e)) if (!FIELDS.has(k)) return `unknown field ${k}`;
  if (typeof e.protocolVersion !== 'string' || !/^\d+\.\d+$/.test(e.protocolVersion)) return 'protocolVersion';
  for (const f of ['profileId', 'instanceId', 'connectionId', 'leaseId']) {
    if (typeof e[f] !== 'string' || !ID_RE.test(e[f])) return f;
  }
  for (const f of ['processGeneration', 'fencingToken', 'sequence']) if (!COUNTER(e[f])) return f;
  if (!KINDS.has(e.messageKind)) return 'messageKind';
  if (typeof e.deadline !== 'string' || Number.isNaN(Date.parse(e.deadline))) return 'deadline';
  if (typeof e.codecVersion !== 'string' || !e.codecVersion) return 'codecVersion';
  if (typeof e.payload !== 'string') return 'payload';
  if (e.correlationId != null && (typeof e.correlationId !== 'string' || !ID_RE.test(e.correlationId))) {
    return 'correlationId';
  }
  if ((e.messageKind === 'REQUEST' || e.messageKind === 'RESPONSE') && e.correlationId == null) return 'correlationId';
  if (e.operation != null && (typeof e.operation !== 'string' || e.operation.length > 64 || !OP_RE.test(e.operation))) {
    return 'operation';
  }
  if ((e.messageKind === 'REQUEST' || e.messageKind === 'CONTROL') && e.operation == null) return 'operation';
  if (e.error != null && !['RESPONSE', 'CONTROL', 'PORT_ERROR'].includes(e.messageKind)) return 'error';
  return null;
}
