// Process identity checks for the qwenwork adapter (design §7.1: PID + start time + executable + port owner).
// Read-only: runs ps / lsof, never signals or modifies a process.
import { execFileSync } from 'node:child_process';
import { lstatSync, readFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const APP_BUNDLE = '/Applications/QwenWorkCN.app';
export const EXECUTABLE = `${APP_BUNDLE}/Contents/MacOS/QwenWorkCN`;
export const INSPECT_ARG = '--inspect=127.0.0.1:9229';
export const INSPECT_PORT = 9229;

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

/** @returns {{pid:number, started:string, executable:string, args:string}|null} */
export function processInfo(pid) {
  if (!Number.isSafeInteger(pid) || pid <= 1) return null;
  const started = run('/bin/ps', ['-p', String(pid), '-o', 'lstart=']).trim();
  const executable = run('/bin/ps', ['-p', String(pid), '-o', 'comm=']).trim();
  const args = run('/bin/ps', ['-p', String(pid), '-o', 'args=']).trim();
  if (!started || !executable) return null;
  return { pid, started, executable, args };
}

/** Main QwenWork processes (exact executable, not helpers / child scripts). */
export function findAppProcesses() {
  const out = run('/bin/ps', ['-axo', 'pid=,args=']);
  const found = [];
  for (const line of out.split('\n')) {
    const m = line.trim().match(/^(\d+)\s+(.*)$/);
    if (!m) continue;
    const args = m[2];
    if (args === EXECUTABLE || args.startsWith(`${EXECUTABLE} --`)) found.push(Number(m[1]));
  }
  return found;
}

/** PIDs listening on 127.0.0.1:<port> and the listen addresses. */
export function listeners(port) {
  const out = run('/usr/sbin/lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-Fpn']);
  const pids = new Set();
  const names = new Set();
  for (const line of out.split('\n')) {
    if (line.startsWith('p')) pids.add(Number(line.slice(1)));
    else if (line.startsWith('n')) names.add(line.slice(1));
  }
  return { pids: [...pids], names: [...names] };
}

/** The listener on <port> must be exactly <pid>, bound to 127.0.0.1 only. */
export function assertLoopbackOwner(port, pid) {
  const l = listeners(port);
  if (l.pids.length !== 1 || l.pids[0] !== pid) throw new CheckError('PORT_OWNER_MISMATCH', `port ${port}`);
  if (!l.names.length || !l.names.every((n) => n === `127.0.0.1:${port}`)) {
    throw new CheckError('PORT_OWNER_MISMATCH', `port ${port} is not loopback-only`);
  }
}

export function defaultDataDir() {
  return process.env.MMAB_DATA_DIR || path.join(os.homedir(), 'Library', 'Application Support', 'MacminiAppBridge');
}
export function defaultLinkFile() {
  return process.env.MMAB_QWEN_LINK || path.join(defaultDataDir(), 'qwenwork', 'link.json');
}

/**
 * Read the handoff written by tools/inject.mjs. The file must be a regular 0600 file owned by this user, inside a
 * 0700 directory, and contain {v:1, pid, started, port, key}.
 */
export function readHandoff(file) {
  let st;
  let dst;
  try {
    st = lstatSync(file);
    dst = lstatSync(path.dirname(file));
  } catch {
    throw new CheckError('ADAPTER_UNAVAILABLE', 'no link handoff (run tools/inject.mjs)');
  }
  const uid = process.getuid();
  if (!st.isFile() || st.uid !== uid || (st.mode & 0o077) !== 0) throw new CheckError('UNAUTHENTICATED', 'handoff file permissions');
  if (!dst.isDirectory() || dst.uid !== uid || (dst.mode & 0o077) !== 0) throw new CheckError('UNAUTHENTICATED', 'handoff dir permissions');
  const h = JSON.parse(readFileSync(file, 'utf8'));
  const key = Buffer.from(String(h.key ?? ''), 'base64');
  if (h.v !== 1 || !Number.isSafeInteger(h.pid) || typeof h.started !== 'string' || !Number.isSafeInteger(h.port)
      || h.port < 1024 || h.port > 65535 || key.length !== 32) {
    throw new CheckError('MALFORMED_FRAME', 'handoff content');
  }
  return { pid: h.pid, started: h.started, port: h.port, key };
}

/** The handoff still describes the running app: same PID, same start time, exact executable, port owned by it. */
export function verifyHandoff(h) {
  const p = processInfo(h.pid);
  if (!p || p.started !== h.started || p.executable !== EXECUTABLE) {
    throw new CheckError('STALE_GENERATION', 'QwenWork process changed since injection');
  }
  assertLoopbackOwner(h.port, h.pid);
}
