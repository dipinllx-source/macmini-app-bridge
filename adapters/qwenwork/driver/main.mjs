#!/usr/bin/env node
// MMAB qwenwork adapter driver (strategy S1, electron-main-inject).
// Started by mmab-server with the service key on stdin (sdk/driver-node/adapter-runtime.mjs).
//
// The driver itself never opens the app's inspector. Injection is an explicit operator step (tools/inject.mjs) that
// writes a 0600 handoff {pid, started, port, key}; the driver re-verifies that handoff (same process, exact
// executable, loopback port owned by it) before every (re)connection and talks to the payload over the
// authenticated link. Payloads stay mmab-sv1 text end to end: the driver routes them without re-encoding.
import { realpathSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { runAdapter } from '../../../sdk/driver-node/adapter-runtime.mjs';
import { LinkError, connectLink } from './link-client.mjs';
import { createFsPick } from './fs-pick.mjs';
import { defaultLinkFile, readHandoff, verifyHandoff } from './process-check.mjs';

export const ADAPTER_VERSION = '0.4.0';
const RETRY_MS = 2000;

/**
 * @param {object} o
 * @param {string} [o.linkFile]
 * @param {(h: object) => void} [o.verify] process identity check (tests replace it; production uses verifyHandoff)
 * @param {(f: string) => object} [o.read]
 * @param {string[]} [o.pickRoots] file.pick 允许列举的根目录（默认用户主目录；环境变量 MMAB_PICK_ROOTS 以 : 分隔）
 */
export function createDriver(o = {}) {
  const linkFile = o.linkFile ?? defaultLinkFile();
  const verify = o.verify ?? verifyHandoff;
  const read = o.read ?? readHandoff;
  let link = null;
  let connecting = null;
  let lastFailure = 0;
  let api = null;
  const envRoots = (process.env.MMAB_PICK_ROOTS || '').split(':').filter(Boolean);
  const pick = createFsPick({ roots: o.pickRoots ?? envRoots });

  function onEvent(conn, payloadText) {
    if (!api) return;
    const targets = conn === null ? api.connections() : [conn];
    for (const c of targets) api.emit(c, 'qwenwork.ipc', payloadText, { raw: true });
  }

  async function ensureLink() {
    if (link && !link.closed) return link;
    if (connecting) return connecting;
    if (Date.now() - lastFailure < RETRY_MS) throw new LinkError('ADAPTER_UNAVAILABLE', 'retry later');
    connecting = (async () => {
      try {
        const h = read(linkFile);
        verify(h);
        link = await connectLink({ port: h.port, key: h.key, onEvent, onClose: () => { link = null; } });
        return link;
      } catch (e) {
        lastFailure = Date.now();
        throw e instanceof LinkError || e?.code ? e : new LinkError('ADAPTER_UNAVAILABLE');
      } finally {
        connecting = null;
      }
    })();
    return connecting;
  }

  const call = (kind, conn) => ({
    raw: true,
    async handle(payloadText, ctx) {
      const l = await ensureLink();
      return l.call(kind, conn === 'connection' ? ctx.connectionId : null, payloadText);
    },
  });

  return {
    handlers: {
      'qwenwork.bootstrap': call('bootstrap', null),
      'qwenwork.invoke': call('invoke', 'connection'),
      'qwenwork.send': call('send', 'connection'),
      'qwenwork.sendSync': call('sendSync', null),
      // file.pick：网页版选择器的数据源，在驱动进程内完成，不经过千问主进程
      'qwenwork.fs.list': (value) => pick.list(value),
      'qwenwork.fs.describe': (value) => pick.describe(value),
    },
    onConnectionClosed(conn) {
      if (link && !link.closed) link.closeConnection(conn);
    },
    bind(adapterApi) {
      api = adapterApi;
    },
    close() {
      if (link) link.close();
    },
  };
}

const isMain = process.argv[1] !== undefined
  && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href;
if (isMain) {
  const d = createDriver();
  runAdapter({ adapterVersion: ADAPTER_VERSION, handlers: d.handlers, onConnectionClosed: d.onConnectionClosed })
    .then((adapter) => d.bind(adapter.api))
    .catch(() => {
      process.stderr.write('qwenwork adapter failed to start\n');
      process.exit(1);
    });
}
