import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  ADAPTER_TO_PLATFORM, LineSplitter, PLATFORM_TO_ADAPTER, WireError, adapterProof, decodeFrame, encodeFrame,
  frameMac, newNonce, platformProof, sessionKey,
} from './wire.mjs';
import { envelopeProblem } from './envelope.mjs';

const vector = JSON.parse(readFileSync(new URL('../protocol/fixtures/wire-v1-vector.json', import.meta.url), 'utf8'));
const codeOf = (fn) => {
  try {
    fn();
  } catch (e) {
    if (e instanceof WireError) return e.code;
    throw e;
  }
  return null;
};

test('cross-language vector matches (same as Java WireAuthTest)', () => {
  const key = Buffer.from(vector.serviceKeyBase64, 'base64');
  const args = [vector.adapterNonce, vector.clientNonce, vector.instanceId, vector.generation, vector.protocol];
  assert.equal(platformProof(key, ...args), vector.platformProof);
  assert.equal(adapterProof(key, ...args), vector.adapterProof);
  const sk = sessionKey(key, vector.adapterNonce, vector.clientNonce);
  assert.equal(sk.toString('hex'), vector.sessionKeyHex);
  assert.equal(frameMac(sk, vector.frameDirection, vector.frameJson), vector.frameMac);
});

test('frames: round trip, replay, reflection, tamper, foreign session', () => {
  const key = Buffer.alloc(32, 7);
  const sk = sessionKey(key, newNonce(), newNonce());
  const env = { instanceId: 'mock-01', n: 'x' };
  const line = encodeFrame(sk, PLATFORM_TO_ADAPTER, 1, env);
  assert.deepEqual(decodeFrame(sk, PLATFORM_TO_ADAPTER, 1, line), env);
  assert.equal(codeOf(() => decodeFrame(sk, PLATFORM_TO_ADAPTER, 2, line)), 'REPLAY_REJECTED');
  assert.equal(codeOf(() => decodeFrame(sk, ADAPTER_TO_PLATFORM, 1, line)), 'REPLAY_REJECTED');
  assert.equal(codeOf(() => decodeFrame(sk, PLATFORM_TO_ADAPTER, 1, line.replace('mock-01', 'mock-02'))),
    'REPLAY_REJECTED');
  const other = sessionKey(key, newNonce(), newNonce());
  assert.equal(codeOf(() => decodeFrame(other, PLATFORM_TO_ADAPTER, 1, line)), 'REPLAY_REJECTED');
  assert.equal(codeOf(() => decodeFrame(sk, PLATFORM_TO_ADAPTER, 1, '{"n":1}')), 'MALFORMED_FRAME');
});

test('line splitter enforces the limit and handles split chunks', () => {
  const s = new LineSplitter(8);
  assert.deepEqual(s.push(Buffer.from('ab')), []);
  assert.deepEqual(s.push(Buffer.from('c\nde\n')), ['abc', 'de']);
  assert.equal(codeOf(() => s.push(Buffer.from('123456789'))), 'QUEUE_LIMIT');
});

test('envelope fixtures agree with the schema-mirroring validator', () => {
  for (const name of ['request', 'response', 'response-error', 'event', 'control-fence']) {
    const env = JSON.parse(readFileSync(new URL(`../protocol/fixtures/envelopes/valid/${name}.json`, import.meta.url)));
    assert.equal(envelopeProblem(env), null, name);
  }
  for (const name of ['request-without-operation', 'unknown-field', 'negative-generation',
    'response-without-correlation', 'bad-operation-name', 'error-on-event', 'bad-instance-id']) {
    const env = JSON.parse(readFileSync(new URL(`../protocol/fixtures/envelopes/invalid/${name}.json`, import.meta.url)));
    assert.notEqual(envelopeProblem(env), null, name);
  }
});
