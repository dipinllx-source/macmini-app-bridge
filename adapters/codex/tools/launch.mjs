#!/usr/bin/env node
// Operator step: start / stop the DEDICATED Codex instance the adapter attaches to. Never touches the operator's
// normal Codex (different executable arguments; stop only signals the verified dedicated PID).
//
//   node adapters/codex/tools/launch.mjs [--profile <dir>] [--cdp-port 9341]          start (idempotent)
//   node adapters/codex/tools/launch.mjs --stop [--profile <dir>] [--cdp-port 9341]   SIGTERM the verified instance
//
// Risk accepted by enabling this adapter (adapter.yaml risk.notes): the CDP port stays open on 127.0.0.1 while the
// instance runs; any local process of the same user could drive that Codex window. A separate user-data-dir does
// not guarantee a separate account.
import { execFileSync } from 'node:child_process';
import { mkdirSync, realpathSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { APP_BUNDLE, CheckError, DEFAULT_CDP_PORT, defaultProfile, listeners, verifyDedicated } from '../driver/process-check.mjs';
import { RENDERER_URL } from '../driver/renderer-link.mjs';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function rendererReady(port) {
  try {
    const r = await fetch(`http://127.0.0.1:${port}/json/list`, { signal: AbortSignal.timeout(1500) });
    return (await r.json()).some((t) => t.type === 'page' && t.url === RENDERER_URL);
  } catch {
    return false;
  }
}

export async function start({ profile = defaultProfile(), cdpPort = DEFAULT_CDP_PORT } = {}) {
  const abs = path.resolve(profile);
  const l = listeners(cdpPort);
  if (l.pids.length) {
    const v = verifyDedicated(cdpPort, abs); // throws when the port belongs to anything else
    return { pid: v.pid, started: false };
  }
  mkdirSync(abs, { recursive: true, mode: 0o700 });
  execFileSync('/usr/bin/open', ['-n', '-a', APP_BUNDLE, '--args', `--user-data-dir=${abs}`,
    '--remote-debugging-address=127.0.0.1', `--remote-debugging-port=${cdpPort}`], { stdio: 'ignore' });
  for (let i = 0; i < 60; i++) {
    if (listeners(cdpPort).pids.length && await rendererReady(cdpPort)) {
      const v = verifyDedicated(cdpPort, abs);
      return { pid: v.pid, started: true };
    }
    await sleep(500);
  }
  throw new CheckError('ADAPTER_UNAVAILABLE', 'dedicated Codex did not expose its renderer within 30 s');
}

export async function stop({ profile = defaultProfile(), cdpPort = DEFAULT_CDP_PORT } = {}) {
  const v = verifyDedicated(cdpPort, path.resolve(profile));
  process.kill(v.pid, 'SIGTERM');
  for (let i = 0; i < 40 && listeners(cdpPort).pids.length; i++) await sleep(250);
  if (listeners(cdpPort).pids.length) throw new CheckError('WAIT_CONFIRM', 'instance still running (quit confirmation?)');
  return { pid: v.pid, stopped: true };
}

const isMain = process.argv[1] !== undefined
  && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href;
if (isMain) {
  const a = process.argv.slice(2);
  const val = (k) => {
    const i = a.indexOf(k);
    return i >= 0 ? a[i + 1] : undefined;
  };
  const opts = { profile: val('--profile') ?? defaultProfile(), cdpPort: val('--cdp-port') ? Number(val('--cdp-port')) : DEFAULT_CDP_PORT };
  (a.includes('--stop') ? stop(opts) : start(opts))
    .then((r) => process.stdout.write(`${JSON.stringify(r)}\n`))
    .catch((e) => {
      process.stderr.write(`launch refused: ${e.code ?? 'ERROR'} ${e.message}\n`);
      process.exit(2);
    });
}
