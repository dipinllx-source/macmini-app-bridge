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
import { MAX_FRAME_BYTES } from '../../../sdk/driver-node/wire.mjs';
import { LinkError, connectLink } from './link-client.mjs';
import { decode, encode } from '../../../sdk/web/codec.mjs';
import { createFsPick } from './fs-pick.mjs';
import { defaultLinkFile, readHandoff, verifyHandoff } from './process-check.mjs';

export const ADAPTER_VERSION = '0.5.0';
const RETRY_MS = 2000;
// 单帧上限 1MiB；千问的 tRPC 回包（例如打开一段很长的会话）可能更大。超过的事件拆成 qwenwork.ipc.part 分片，
// 由网页 shim 按序拼回；每片 ≤ 256K 字符（payload 是 JSON 文本，UTF-8 最坏 3 字节/字符 ≈ 768KB）。
export const PART_CHARS = 256 * 1024;
export const MAX_CHUNKED_BYTES = 24 * 1024 * 1024; // 再大就按原规则只关这一条浏览器连接（平台出站预算 32MiB）
const DIRECT_LIMIT = MAX_FRAME_BYTES - 4096;

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

  let partSeq = 0;
  function emitTo(c, payloadText, bytes) {
    if (bytes <= DIRECT_LIMIT || bytes > MAX_CHUNKED_BYTES) {
      api.emit(c, 'qwenwork.ipc', payloadText, { raw: true }); // 过大者由运行时只关闭这一条连接（并记录大小）
      return;
    }
    const id = ++partSeq;
    const n = Math.ceil(payloadText.length / PART_CHARS);
    for (let i = 0; i < n; i++) {
      api.emit(c, 'qwenwork.ipc.part', encode({ id, i, n, d: payloadText.slice(i * PART_CHARS, (i + 1) * PART_CHARS) }),
        { raw: true });
    }
    process.stderr.write(`[mmab-adapter] chunked event payloadBytes=${bytes} parts=${n} connection=${c}\n`);
  }
  function onEvent(conn, payloadText) {
    if (!api) return;
    const bytes = Buffer.byteLength(payloadText, 'utf8');
    const targets = conn === null ? api.connections() : [conn];
    for (const c of targets) emitTo(c, payloadText, bytes);
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
      'qwenwork.fs.checkSave': (value) => pick.checkSave(value),
      // 千问主进程转给网页的原生对话框（payload 的 mmab:dialog 事件）的回答：先在这里校验路径，再交给 payload
      'qwenwork.dialog.answer': async (value, ctx) => {
        const v = value && typeof value === 'object' ? value : {};
        if (!Number.isSafeInteger(v.id)) return { ok: false, reason: 'MALFORMED' };
        const res = v.result && typeof v.result === 'object' ? v.result : {};
        let result;
        if (res.canceled === true) {
          result = { canceled: true };
        } else if (v.kind === 'save') {
          const c = await pick.checkSave({ filePath: res.filePath });
          if (!c.ok) return c;
          result = { canceled: false, filePath: c.filePath };
        } else if (v.kind === 'open') {
          const d = await pick.describe({ paths: Array.isArray(res.filePaths) ? res.filePaths : [] });
          if (!d.ok) return d;
          if (!d.paths.length) return { ok: false, reason: 'MALFORMED' };
          result = { canceled: false, filePaths: d.paths.map((x) => x.path) };
        } else {
          return { ok: false, reason: 'MALFORMED' };
        }
        const l = await ensureLink();
        return decode(await l.call('dialog', ctx.connectionId, encode({ id: v.id, result })));
      },
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
