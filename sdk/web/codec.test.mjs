// node --test sdk adapters
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { CodecError, decode, encode, MAX_DEPTH, PortRef } from './codec.mjs';

const fixtures = JSON.parse(readFileSync(new URL('../protocol/fixtures/codec-sv1.json', import.meta.url), 'utf8'));

const codeOf = (fn) => {
  try {
    fn();
  } catch (e) {
    if (e instanceof CodecError) return e.code;
    throw e;
  }
  return null;
};

test('shared fixtures: valid cases round-trip canonically', () => {
  assert.ok(fixtures.valid.length >= 25);
  for (const c of fixtures.valid) {
    const text = JSON.stringify(c.encoded);
    assert.equal(encode(decode(text)), text, c.name);
  }
});

test('shared fixtures: invalid cases are rejected with the expected code', () => {
  for (const c of fixtures.invalid) {
    if (c.javaOnly) continue;
    const text = c.encodedText ?? JSON.stringify(c.encoded);
    assert.equal(codeOf(() => decode(text)), c.error, c.name);
  }
});

test('native values round-trip', () => {
  const value = {
    n: -0, nan: NaN, inf: -Infinity, big: -(2n ** 70n), undef: undefined,
    when: new Date(1762473600123), bad: new Date(NaN),
    bytes: new Uint8Array([1, 2, 3]), f64: new Float64Array([1.5, -2]), buf: new ArrayBuffer(4),
    map: new Map([[{ k: 1 }, new Set(['a'])]]), err: new TypeError('boom'), port: new PortRef('p_1'),
    list: [1, 'a', null, [true]], text: '原版 \u0000 😀',
  };
  const out = decode(encode(value));
  assert.ok(Object.is(out.n, -0));
  assert.ok(Number.isNaN(out.nan));
  assert.equal(out.inf, -Infinity);
  assert.equal(out.big, -(2n ** 70n));
  assert.ok('undef' in out && out.undef === undefined);
  assert.equal(out.when.getTime(), 1762473600123);
  assert.ok(Number.isNaN(out.bad.getTime()));
  assert.deepEqual([...out.bytes], [1, 2, 3]);
  assert.ok(out.f64 instanceof Float64Array);
  assert.deepEqual([...out.f64], [1.5, -2]);
  assert.ok(out.buf instanceof ArrayBuffer && out.buf.byteLength === 4);
  const [[k, v]] = [...out.map];
  assert.deepEqual(k, { k: 1 });
  assert.deepEqual([...v], ['a']);
  assert.ok(out.err instanceof TypeError && out.err.message === 'boom');
  assert.ok(out.port instanceof PortRef && out.port.id === 'p_1');
  assert.deepEqual(out.list, [1, 'a', null, [true]]);
  assert.equal(out.text, value.text);
});

test('Node Buffer and typed-array views encode only their own bytes', () => {
  const backing = new Uint8Array([9, 9, 1, 2, 9]);
  assert.deepEqual([...decode(encode(backing.subarray(2, 4)))], [1, 2]);
  const out = decode(encode(Buffer.from([7, 8])));
  assert.ok(out instanceof Uint8Array);
  assert.deepEqual([...out], [7, 8]);
});

test('unsupported values are explicit errors, never silently dropped', () => {
  const cyc = { a: 1 };
  cyc.self = cyc;
  assert.equal(codeOf(() => encode(cyc)), 'CODEC_UNSUPPORTED');
  assert.equal(codeOf(() => encode({ f: () => 1 })), 'CODEC_UNSUPPORTED');
  assert.equal(codeOf(() => encode(Symbol('s'))), 'CODEC_UNSUPPORTED');
  assert.equal(codeOf(() => encode(new DataView(new ArrayBuffer(2)))), 'CODEC_UNSUPPORTED');
  assert.equal(codeOf(() => encode(new WeakMap())), 'CODEC_UNSUPPORTED');
  assert.equal(codeOf(() => encode(/re/)), 'CODEC_UNSUPPORTED');
  class Point { constructor() { this.x = 1; } }
  assert.equal(codeOf(() => encode(new Point())), 'CODEC_UNSUPPORTED');
  assert.equal(codeOf(() => encode(JSON.parse('{"__proto__": {"x": 1}}'))), 'CODEC_UNSUPPORTED');
});

test('shared (non-cyclic) references are allowed and duplicated', () => {
  const shared = { s: 1 };
  const out = decode(encode({ a: shared, b: shared }));
  assert.deepEqual(out, { a: { s: 1 }, b: { s: 1 } });
  assert.notEqual(out.a, out.b);
});

test('decoding never pollutes Object.prototype', () => {
  const evil = '{"$t":"object","v":{"__proto__":{"$t":"object","v":{"polluted":true}}}}';
  assert.equal(codeOf(() => decode(evil)), 'CODEC_UNSUPPORTED');
  assert.equal({}.polluted, undefined);
});

test('depth limit applies to encode and decode', () => {
  let ok = 1;
  for (let i = 0; i < MAX_DEPTH; i++) ok = [ok];
  assert.doesNotThrow(() => decode(encode(ok)));
  assert.equal(codeOf(() => encode([ok])), 'CODEC_UNSUPPORTED');
});
