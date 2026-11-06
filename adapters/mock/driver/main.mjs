#!/usr/bin/env node
// MMAB mock adapter (strategy test-mock, offline tests only). Connects to NO real application.
// Started by mmab-server with the service key on stdin; see sdk/driver-node/adapter-runtime.mjs.
import { realpathSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { runAdapter } from '../../../sdk/driver-node/adapter-runtime.mjs';
import { CodecError } from '../../../sdk/web/codec.mjs';

export const ADAPTER_VERSION = '0.1.0';
const MAX_BURST_COUNT = 256;
const MAX_CHUNK_BYTES = 900 * 1024;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const isPlainObject = (v) => v !== null && typeof v === 'object' && Object.getPrototypeOf(v) === Object.prototype;

export const handlers = {
  /** Returns its input unchanged (exercises codec round trip end to end). */
  'mock.echo': (value) => value,

  /** {a:number, b:number} -> a + b */
  'mock.add': (value) => {
    if (!isPlainObject(value) || typeof value.a !== 'number' || typeof value.b !== 'number') {
      throw new CodecError('MALFORMED_FRAME', 'mock.add expects {a:number, b:number}');
    }
    return value.a + value.b;
  },

  /** {count, size}: pushes `count` mock.chunk events of `size` bytes plus coalescible mock.tick events. */
  'mock.burst': async (value, ctx) => {
    const count = Math.min(Number(value?.count ?? 1), MAX_BURST_COUNT);
    const size = Math.min(Number(value?.size ?? 16), MAX_CHUNK_BYTES);
    const data = 'x'.repeat(size);
    for (let i = 0; i < count && ctx.stillCurrent(); i++) {
      await ctx.emit('mock.chunk', { i, data });
      await ctx.emit('mock.tick', { i });
    }
    return { sent: count };
  },

  /** {ms}: answers late (deadline tests). */
  'mock.slow': async (value) => {
    await sleep(Math.min(Number(value?.ms ?? 0), 60_000));
    return 'late';
  },

  /** Declared with capability ui.compose = DISABLED; the platform gateway must never let it through. */
  'mock.compose': () => 'compose reached the adapter',
};

const isMain = process.argv[1] !== undefined
  && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href;
if (isMain) {
  runAdapter({ adapterVersion: ADAPTER_VERSION, handlers }).catch(() => {
    process.stderr.write('mock adapter failed to start\n');
    process.exit(1);
  });
}
