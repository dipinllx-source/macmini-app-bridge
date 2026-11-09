#!/usr/bin/env node
// MMAB codex adapter driver (strategy S2, electron-renderer-cdp).
// Started by mmab-server: node adapters/codex/driver/main.mjs [--profile <dir>] [--cdp-port <n>] [--assets-dir <dir>]
//
// Attaches ONLY to a dedicated Codex instance (exact executable + exact --user-data-dir + loopback-only CDP,
// re-verified before every attach), never to the operator's normal Codex. Browser -> renderer frames are applied in
// arrival order and in batches (startup produces hundreds of MessagePort frames within a second).
import { realpathSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import '../../../sdk/web/codec.js';
import { runAdapter } from '../../../sdk/driver-node/adapter-runtime.mjs';
import { RendererError, connectRenderer } from './renderer-link.mjs';
import { DEFAULT_CDP_PORT, defaultProfile, verifyAssets, verifyDedicated } from './process-check.mjs';

export const ADAPTER_VERSION = '0.1.0';
const MAX_QUEUED = 4096;
const MAX_BATCH = 256;
const RETRY_MS = 2000;

/**
 * @param {object} o
 * @param {number} [o.cdpPort]
 * @param {string} [o.profile]
 * @param {string} [o.assetsDir]
 * @param {() => Promise<object>} [o.connect] renderer factory (tests inject an in-process fake)
 * @param {() => void} [o.verify]
 */
export function createDriver(o = {}) {
  const cdpPort = o.cdpPort ?? DEFAULT_CDP_PORT;
  const profile = o.profile ?? defaultProfile();
  const verify = o.verify ?? (() => { verifyAssets(o.assetsDir); verifyDedicated(cdpPort, profile); });
  let api = null;
  let link = null;
  let linking = null;
  let lastFailure = 0;
  let generationOfLink = 0;

  function onBinding(payload) {
    let m;
    try { m = JSON.parse(payload); } catch { return; }
    if (!api || !m || typeof m !== 'object') return;
    if (m.k === 'host' && typeof m.text === 'string') {
      for (const c of api.connections()) api.emit(c, 'codex.hostMessage', m.text, { raw: true });
    } else if (m.k === 'theme' && typeof m.text === 'string') {
      for (const c of api.connections()) api.emit(c, 'codex.theme', m.text, { raw: true });
    } else if (m.k === 'git' && typeof m.conn === 'string' && typeof m.text === 'string') {
      api.emit(m.conn, 'codex.gitPort', m.text, { raw: true });
    } else if (m.k === 'port' && typeof m.conn === 'string' && typeof m.port === 'string' && typeof m.text === 'string') {
      api.portData(m.conn, m.port, m.text);
    } else if (m.k === 'portClose' && typeof m.conn === 'string' && typeof m.port === 'string') {
      api.portClose(m.conn, m.port);
    }
  }

  function onDisconnect() {
    // the renderer reloaded or went away: its ports / app-host sessions are gone, so are the browser sessions on them
    link = null;
    if (api) for (const c of api.connections()) api.closeConnection(c, 'ADAPTER_UNAVAILABLE');
  }

  async function ensureLink() {
    if (link && !link.closed) return link;
    if (linking) return linking;
    if (Date.now() - lastFailure < RETRY_MS) throw new RendererError('ADAPTER_UNAVAILABLE', 'retry later');
    linking = (async () => {
      try {
        verify();
        const connect = o.connect ?? (() => connectRenderer({ port: cdpPort, onBinding, onDisconnect }));
        link = await connect({ onBinding, onDisconnect });
        generationOfLink++;
        return link;
      } catch (e) {
        lastFailure = Date.now();
        throw e?.code ? e : new RendererError('ADAPTER_UNAVAILABLE');
      } finally {
        linking = null;
      }
    })();
    return linking;
  }

  // ---------------------------------------------------------------- ordered, batched browser -> renderer frames
  const queue = [];
  let pumping = false;
  function enqueue(f, a) {
    if (queue.length >= MAX_QUEUED) {
      const e = new RendererError('QUEUE_LIMIT');
      return Promise.reject(e);
    }
    return new Promise((resolve, reject) => {
      queue.push({ f, a, resolve, reject });
      if (!pumping) pump();
    });
  }
  async function pump() {
    pumping = true;
    try {
      while (queue.length) {
        const items = queue.splice(0, MAX_BATCH);
        let results;
        try {
          const l = await ensureLink();
          results = await l.evaluate(`window.__mmabCodex.batch(${JSON.stringify(items.map(({ f, a }) => ({ f, a })))})`);
        } catch (e) {
          for (const it of items) it.reject(e);
          continue;
        }
        items.forEach((it, i) => {
          const r = results?.[i];
          if (!r) it.reject(new RendererError('ADAPTER_UNAVAILABLE'));
          else if (r.e) it.reject(new RendererError(r.e));
          else it.resolve(r.v);
        });
      }
    } finally {
      pumping = false;
    }
  }

  const value = (text) => (text == null ? 'null' : text); // raw handlers return mmab-sv1 text
  const handlers = {
    'codex.bootstrap': {
      raw: true,
      async handle() {
        const l = await ensureLink();
        return l.evaluate('window.__mmabCodex.bootstrap()');
      },
    },
    'codex.read': {
      raw: true,
      async handle(text) {
        const v = globalThis.MmabCodec.decode(text);
        if (!v || typeof v.method !== 'string') throw new RendererError('MALFORMED_FRAME');
        const arg = v.arg === undefined ? null : globalThis.MmabCodec.encode(v.arg);
        return value(await enqueue('read', [v.method, arg]));
      },
    },
    'codex.viewReady': { raw: true, handle: async () => value(await enqueue('viewReady', [])) },
    'codex.viewRead': {
      raw: true,
      async handle(text) {
        const v = globalThis.MmabCodec.decode(text);
        if (!v || typeof v.type !== 'string') throw new RendererError('MALFORMED_FRAME');
        return value(await enqueue('viewRead', [v.type]));
      },
    },
    'codex.connectAppHost': {
      raw: true,
      handle: async (text, ctx) => value(await enqueue('appHost', [ctx.connectionId, text])),
    },
    'codex.connectGit': {
      raw: true,
      async handle(text, ctx) {
        const v = globalThis.MmabCodec.decode(text);
        if (!v || typeof v.requestId !== 'string') throw new RendererError('MALFORMED_FRAME');
        return value(await enqueue('connectGit', [ctx.connectionId, v.requestId]));
      },
    },
  };

  return {
    handlers,
    ports: {
      onData: (conn, port, text) => enqueue('portData', [conn, port, text]).catch((e) => {
        if (e?.code === 'QUEUE_LIMIT') throw e;
      }),
      onClose: (conn, port) => enqueue('portClose', [conn, port]).catch(() => {}),
    },
    onConnectionClosed(conn) {
      enqueue('closeConnection', [conn]).catch(() => {});
    },
    bind(adapterApi) { api = adapterApi; },
    async close() { if (link) await link.detach(); },
    get attachCount() { return generationOfLink; },
  };
}

const isMain = process.argv[1] !== undefined
  && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href;
if (isMain) {
  const arg = (k) => {
    const i = process.argv.indexOf(k);
    return i > 0 ? process.argv[i + 1] : undefined;
  };
  const port = arg('--cdp-port') ? Number(arg('--cdp-port')) : undefined;
  const d = createDriver({
    cdpPort: port, profile: arg('--profile') ? path.resolve(arg('--profile')) : undefined,
    assetsDir: arg('--assets-dir') ? path.resolve(arg('--assets-dir')) : undefined,
  });
  const shutdown = () => d.close().finally(() => process.exit(0));
  process.once('SIGTERM', shutdown);
  runAdapter({ adapterVersion: ADAPTER_VERSION, handlers: d.handlers, ports: d.ports, onConnectionClosed: d.onConnectionClosed })
    .then((adapter) => d.bind(adapter.api))
    .catch(() => {
      process.stderr.write('codex adapter failed to start\n');
      process.exit(1);
    });
}
