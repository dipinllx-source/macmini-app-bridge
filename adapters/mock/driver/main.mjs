#!/usr/bin/env node
// MMAB mock adapter (strategy test-mock, offline tests only). Connects to NO real application.
// Started by mmab-server with the service key on stdin; see sdk/driver-node/adapter-runtime.mjs.
import { realpathSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { runAdapter } from '../../../sdk/driver-node/adapter-runtime.mjs';
import { CodecError, PortRef, decode, encode } from '../../../sdk/web/codec.mjs';

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

  // ---- M3 platform generics (offline tests) ----
  /** Declared sync: callable through POST /__mmab/bridge/call. */
  'mock.syncEcho': (value) => ({ sync: value }),

  /** Opens a native-side port (id space "n") and returns it; data on it is echoed back (see ports below). */
  'mock.openPort': (value, ctx) => ({ port: new PortRef(nextNativePort(ctx.connectionId)) }),

  /** {port: PortRef("w...")}: the browser transferred a port; the adapter greets on it. */
  'mock.adoptPort': async (value, ctx) => {
    const id = value?.port instanceof PortRef ? value.port.id : null;
    if (!id) throw new CodecError('MALFORMED_FRAME', 'mock.adoptPort expects {port}');
    await ctx.api.portData(ctx.connectionId, id, encode({ hello: 'from adapter' }));
    return true;
  },

  /** Returns a port reference in the BROWSER id space: the platform must refuse it (PORT_STATE). */
  'mock.forgePort': () => ({ port: new PortRef('w999') }),

  /** Asks the platform to close this browser connection. */
  'mock.closeMe': (value, ctx) => {
    setTimeout(() => ctx.api.closeConnection(ctx.connectionId, 'QUEUE_LIMIT'), 20);
    return 'closing';
  },

  /** Diagnostics for tests: connections the platform reported closed, port frames seen. */
  'mock.stats': () => ({ closed: [...stats.closed], portFrames: stats.portFrames, portCloses: stats.portCloses }),
};

const stats = { closed: [], portFrames: 0, portCloses: 0 };
const nativeCounters = new Map();
function nextNativePort(connectionId) {
  const n = (nativeCounters.get(connectionId) ?? 0) + 1;
  nativeCounters.set(connectionId, n);
  return `n${n}`;
}

/** Port frames: echo data back on the same port; nested browser ports in the data get a greeting. */
export const ports = {
  async onData(connectionId, portId, payloadText, api) {
    stats.portFrames++;
    const value = decode(payloadText);
    if (value && typeof value === 'object' && value.reply instanceof PortRef) {
      await api.portData(connectionId, value.reply.id, encode({ nested: 'hello' }));
      return;
    }
    if (value === 'close') {
      await api.portClose(connectionId, portId);
      return;
    }
    if (value && typeof value === 'object' && value.give === true) {
      // native side transfers a NEW native port through an existing port (nested, depth + 1)
      await api.portData(connectionId, portId, encode({ child: new PortRef(nextNativePort(connectionId)) }));
      return;
    }
    await api.portData(connectionId, portId, encode({ echo: value }));
  },
  onClose() {
    stats.portCloses++;
  },
};

export function onConnectionClosed(connectionId) {
  if (stats.closed.length < 64) stats.closed.push(connectionId);
  nativeCounters.delete(connectionId);
}

const isMain = process.argv[1] !== undefined
  && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href;
if (isMain) {
  runAdapter({ adapterVersion: ADAPTER_VERSION, handlers, ports, onConnectionClosed }).catch(() => {
    process.stderr.write('mock adapter failed to start\n');
    process.exit(1);
  });
}
