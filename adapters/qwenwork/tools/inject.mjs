#!/usr/bin/env node
// Explicit operator step: inject the qwenwork payload into the running QwenWork main process and write the 0600
// link handoff for the adapter driver. The app's Node inspector is closed again before this tool exits.
//
//   node adapters/qwenwork/tools/inject.mjs [--reopen-inspector --signal-proof <file>] [--stop] [--dry-run]
//
// Preconditions (otherwise it refuses and changes nothing):
//   * exactly one QwenWork main process, launched with EXACTLY `--inspect=127.0.0.1:9229` (a normally launched app is
//     never restarted, signalled or attached to by this tool);
//   * the inspector is listening on 127.0.0.1:9229 and owned by that process, OR --reopen-inspector is given together
//     with a signal proof whose Electron Framework sha256 matches the installed app (SIGUSR1 on an unproven build
//     may terminate the app, so it is never sent blindly).
// The payload coexists with other in-process bridges: own globals (__mmabQwenBridge), own tRPC id range.
import { createHash, randomBytes } from 'node:crypto';
import { mkdirSync, readFileSync, renameSync, writeFileSync, chmodSync, realpathSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  APP_BUNDLE, CheckError, EXECUTABLE, INSPECT_ARG, INSPECT_PORT, assertLoopbackOwner, defaultLinkFile, findAppProcesses,
  listeners, processInfo,
} from '../driver/process-check.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../../..');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const FRAMEWORK = `${APP_BUNDLE}/Contents/Frameworks/Electron Framework.framework/Versions/A/Electron Framework`;

/** Self-contained expression evaluated in the app's main process. The key only travels over loopback inspector. */
export function payloadExpression(keyB64) {
  const codec = readFileSync(path.join(repo, 'sdk/web/codec.js'), 'utf8');
  const bridge = readFileSync(path.join(repo, 'adapters/qwenwork/payload/main-bridge.js'), 'utf8');
  return `(async () => {
  const __holder = {};
  (function (globalThis) {\n${codec}\n})(__holder);
  ${bridge}
  const g = globalThis;
  if (g.__mmabQwenBridge) { try { await g.__mmabQwenBridge.stop(); } catch (e) {} }
  const r = process.mainModule.require;
  g.__mmabQwenBridge = createQwenBridge({ electron: r('electron'), net: r('net'), crypto: r('crypto'), asyncHooks: r('async_hooks'), codec: __holder.MmabCodec });
  return g.__mmabQwenBridge.start(${JSON.stringify(keyB64)});
})()`;
}

export const STOP_EXPRESSION = '(async () => { const b = globalThis.__mmabQwenBridge; if (!b) return { stopped: false }; '
  + 'const r = await b.stop(); delete globalThis.__mmabQwenBridge; return r; })()';
const CLOSE_INSPECTOR = "process.mainModule.require('inspector').close(); true";

function targetApp() {
  const pids = findAppProcesses();
  if (pids.length !== 1) throw new CheckError('WAIT_CONFIRM', `expected one QwenWork main process, found ${pids.length}`);
  const p = processInfo(pids[0]);
  if (!p || p.executable !== EXECUTABLE) throw new CheckError('PORT_OWNER_MISMATCH', 'unexpected executable');
  if (p.args !== `${EXECUTABLE} ${INSPECT_ARG}`) {
    throw new CheckError('WAIT_CONFIRM', 'QwenWork was not launched with exactly ' + INSPECT_ARG
      + '; restart it that way first (this tool never restarts the app)');
  }
  return p;
}

function frameworkHash() {
  return createHash('sha256').update(readFileSync(FRAMEWORK)).digest('hex');
}

async function inspectorUrl() {
  const res = await fetch(`http://127.0.0.1:${INSPECT_PORT}/json/list`, { signal: AbortSignal.timeout(3000) });
  const list = await res.json();
  const url = list?.[0]?.webSocketDebuggerUrl;
  if (typeof url !== 'string' || !url.startsWith(`ws://127.0.0.1:${INSPECT_PORT}/`)) {
    throw new CheckError('PORT_OWNER_MISMATCH', 'unexpected inspector target');
  }
  return url;
}

function evaluator(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const pending = new Map();
    let id = 0;
    ws.addEventListener('open', () => resolve({
      evaluate(expression, { wait = true, timeoutMs = 20000 } = {}) {
        const i = ++id;
        ws.send(JSON.stringify({ id: i, method: 'Runtime.evaluate', params: { expression, awaitPromise: true, returnByValue: true } }));
        if (!wait) return Promise.resolve(undefined);
        return new Promise((res, rej) => {
          const t = setTimeout(() => { pending.delete(i); rej(new Error('inspector timeout')); }, timeoutMs);
          pending.set(i, (m) => {
            clearTimeout(t);
            const r = m.result;
            if (m.error || r?.exceptionDetails) rej(new Error('evaluation failed in the app (details withheld)'));
            else res(r?.result?.value);
          });
        });
      },
      close: () => ws.close(),
    }), { once: true });
    ws.addEventListener('error', () => reject(new Error('inspector connection failed')), { once: true });
    ws.addEventListener('message', (ev) => {
      let m;
      try { m = JSON.parse(ev.data); } catch { return; }
      const cb = pending.get(m.id);
      if (cb) { pending.delete(m.id); cb(m); }
    });
  });
}

function writeHandoff(file, h) {
  const dir = path.dirname(file);
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  chmodSync(dir, 0o700);
  const tmp = `${file}.${process.pid}.tmp`;
  writeFileSync(tmp, `${JSON.stringify(h)}\n`, { mode: 0o600 });
  renameSync(tmp, file);
}

export async function run({ reopen = false, proofFile = null, stop = false, dryRun = false, linkFile = defaultLinkFile() } = {}) {
  const app = targetApp();
  let { pids } = listeners(INSPECT_PORT);
  if (pids.length === 0) {
    if (!reopen) throw new CheckError('WAIT_CONFIRM', 'inspector is closed; pass --reopen-inspector with --signal-proof');
    if (!proofFile) throw new CheckError('WAIT_CONFIRM', '--signal-proof is required to reopen the inspector');
    const proof = JSON.parse(readFileSync(proofFile, 'utf8'));
    if (proof.frameworkHash !== frameworkHash()) throw new CheckError('BLOCKED_UPGRADE', 'signal proof does not match this build');
    if (dryRun) return { app: app.pid, action: 'would reopen inspector' };
    process.kill(app.pid, 'SIGUSR1');
    for (let i = 0; i < 20 && listeners(INSPECT_PORT).pids.length === 0; i++) await sleep(250);
  }
  assertLoopbackOwner(INSPECT_PORT, app.pid);
  if (dryRun) return { app: app.pid, action: 'would inject' };

  const ev = await evaluator(await inspectorUrl());
  let result;
  let cleanupFailed = false;
  try {
    if (stop) {
      result = await ev.evaluate(STOP_EXPRESSION);
      rmSync(linkFile, { force: true });
    } else {
      const key = randomBytes(32).toString('base64');
      const r = await ev.evaluate(payloadExpression(key));
      if (!r || !Number.isSafeInteger(r.port)) throw new Error('payload did not report a port');
      assertLoopbackOwner(r.port, app.pid);
      writeHandoff(linkFile, { v: 1, pid: app.pid, started: app.started, port: r.port, key, createdAt: new Date().toISOString() });
      result = { port: r.port, linkFile };
    }
  } finally {
    await ev.evaluate(CLOSE_INSPECTOR, { wait: false }).catch(() => {});
    await sleep(300);
    ev.close();
    for (let i = 0; i < 20 && listeners(INSPECT_PORT).pids.length; i++) await sleep(250);
    cleanupFailed = listeners(INSPECT_PORT).pids.length > 0;
  }
  if (cleanupFailed) throw new CheckError('INSPECTOR_CLEANUP_FAILED', 'inspector still listening; handle manually');
  return { app: app.pid, ...result, inspector: 'closed' };
}

const isMain = process.argv[1] !== undefined
  && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href;
if (isMain) {
  const a = process.argv.slice(2);
  const val = (k) => {
    const i = a.indexOf(k);
    return i >= 0 ? a[i + 1] : null;
  };
  run({ reopen: a.includes('--reopen-inspector'), proofFile: val('--signal-proof'), stop: a.includes('--stop'),
    dryRun: a.includes('--dry-run') })
    .then((r) => process.stdout.write(`${JSON.stringify(r)}\n`))
    .catch((e) => {
      process.stderr.write(`inject refused: ${e.code ?? 'ERROR'} ${e.message}\n`);
      process.exit(2);
    });
}
