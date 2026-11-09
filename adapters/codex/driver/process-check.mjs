// Identity checks for the dedicated Codex instance (design §7.1). Read-only: ps / lsof / PlistBuddy.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const APP_BUNDLE = '/Applications/ChatGPT.app';
export const EXECUTABLE = `${APP_BUNDLE}/Contents/MacOS/ChatGPT`;
export const DEFAULT_CDP_PORT = 9341;

export class CheckError extends Error {
  constructor(code, detail) {
    super(detail ? `${code}: ${detail}` : code);
    this.name = 'CheckError';
    this.code = code;
  }
}

function run(cmd, args) {
  try {
    return execFileSync(cmd, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 5000 });
  } catch {
    return '';
  }
}

export function defaultDataDir() {
  return path.join(os.homedir(), 'Library', 'Application Support', 'MacminiAppBridge');
}
export function defaultProfile() {
  return path.join(defaultDataDir(), 'codex', 'profile');
}

export function listeners(port) {
  const out = run('/usr/sbin/lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-Fpn']);
  const pids = new Set();
  const names = [];
  for (const line of out.split('\n')) {
    if (line.startsWith('p')) pids.add(Number(line.slice(1)));
    else if (line.startsWith('n')) names.push(line.slice(1));
  }
  return { pids: [...pids], names };
}

/**
 * The CDP port must be owned by exactly one process, bound to 127.0.0.1 only, that is the Codex executable with
 * exactly one --user-data-dir equal to `profile`. A normal Codex (the operator's own) never matches.
 * @returns {{pid:number, started:string}}
 */
export function verifyDedicated(port, profile) {
  const l = listeners(port);
  if (l.pids.length !== 1) throw new CheckError('PORT_OWNER_MISMATCH', `CDP port ${port} owners: ${l.pids.length}`);
  if (!l.names.length || l.names.some((n) => n !== `127.0.0.1:${port}`)) {
    throw new CheckError('PORT_OWNER_MISMATCH', 'CDP is not loopback-only');
  }
  const pid = l.pids[0];
  const exe = run('/bin/ps', ['-p', String(pid), '-o', 'comm=']).trim();
  const args = run('/bin/ps', ['-p', String(pid), '-o', 'args=']).trim();
  const started = run('/bin/ps', ['-p', String(pid), '-o', 'lstart=']).trim();
  if (exe !== EXECUTABLE || !args.startsWith(`${EXECUTABLE} `)) throw new CheckError('PORT_OWNER_MISMATCH', 'not the Codex executable');
  const dirs = args.slice(EXECUTABLE.length + 1).split(' ').filter((a) => a.startsWith('--user-data-dir'));
  if (dirs.length !== 1 || dirs[0] !== `--user-data-dir=${path.resolve(profile)}`) {
    throw new CheckError('PORT_OWNER_MISMATCH', 'not the dedicated profile');
  }
  return { pid, started };
}

export function appVersion(app = APP_BUNDLE) {
  return run('/usr/libexec/PlistBuddy', ['-c', 'Print :CFBundleShortVersionString', `${app}/Contents/Info.plist`]).trim();
}

/** Extracted original assets must match the installed app version (Codex upgrades itself often). */
export function verifyAssets(assetsDir, version = appVersion()) {
  if (!assetsDir) return null;
  let m;
  try {
    m = JSON.parse(readFileSync(path.join(assetsDir, '.mmab-source.json'), 'utf8'));
  } catch {
    throw new CheckError('COMPATIBILITY_BLOCKED', 'assets manifest missing (run tools/extract.mjs)');
  }
  if (m.appId !== 'codex' || m.version !== version) {
    throw new CheckError('BLOCKED_UPGRADE', `assets ${m.version} != installed ${version}; re-extract`);
  }
  return m.version;
}
