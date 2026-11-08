// Minimal read-only reader for Electron asar archives (zero dependencies).
// Used by adapters to extract original front-end resources from the locally installed app at runtime into the
// user data directory. Nothing extracted here is ever committed or redistributed (design §10.4, §14.3).
//
// Format: [u32 4][u32 headerPickleSize] then a pickle {u32 payloadSize, u32 jsonLength, json} and file data starting
// at 8 + headerPickleSize. File entries: {size, offset:"<string>", unpacked?, executable?, integrity?} or {link}.
import { closeSync, openSync, readSync, fstatSync, readFileSync } from 'node:fs';
import path from 'node:path';

const MAX_HEADER_BYTES = 64 * 1024 * 1024;

export class AsarError extends Error {
  constructor(detail) {
    super(`asar: ${detail}`);
    this.name = 'AsarError';
  }
}

function readExactly(fd, length, position) {
  const buf = Buffer.alloc(length);
  let done = 0;
  while (done < length) {
    const n = readSync(fd, buf, done, length - done, position + done);
    if (n === 0) throw new AsarError('unexpected end of archive');
    done += n;
  }
  return buf;
}

/** A safe relative archive path: no empty, '.', '..', absolute, backslash or NUL segments. */
export function safeRelative(p) {
  if (typeof p !== 'string' || p.length === 0 || p.length > 1024 || p.includes('\\') || p.includes('\0')) return false;
  if (p.startsWith('/')) return false;
  return p.split('/').every((s) => s.length > 0 && s !== '.' && s !== '..');
}

export class AsarArchive {
  /** @param {string} file absolute path to app.asar */
  constructor(file) {
    this.file = file;
    const fd = openSync(file, 'r');
    try {
      const size = fstatSync(fd).size;
      const pre = readExactly(fd, 8, 0);
      if (pre.readUInt32LE(0) !== 4) throw new AsarError('bad size pickle');
      const headerSize = pre.readUInt32LE(4);
      if (headerSize < 8 || headerSize > MAX_HEADER_BYTES || headerSize + 8 > size) throw new AsarError('bad header size');
      const header = readExactly(fd, headerSize, 8);
      const jsonLength = header.readUInt32LE(4);
      if (jsonLength + 8 > headerSize) throw new AsarError('bad header string');
      this.header = JSON.parse(header.subarray(8, 8 + jsonLength).toString('utf8'));
      this.base = 8 + headerSize;
      this.size = size;
    } finally {
      closeSync(fd);
    }
    if (!this.header || typeof this.header.files !== 'object') throw new AsarError('no file table');
  }

  /** @returns {object|null} the raw entry (file, directory or link) */
  entry(rel) {
    if (!safeRelative(rel)) return null;
    let node = this.header;
    for (const seg of rel.split('/')) {
      if (!node.files || !Object.hasOwn(node.files, seg)) return null;
      node = node.files[seg];
    }
    return node;
  }

  /** Iterate regular files under a directory prefix (links are skipped, never followed). */
  *files(prefix = '') {
    const start = prefix ? this.entry(prefix) : this.header;
    if (!start || !start.files) return;
    const stack = [[prefix, start]];
    while (stack.length) {
      const [dir, node] = stack.pop();
      for (const [name, child] of Object.entries(node.files)) {
        const rel = dir ? `${dir}/${name}` : name;
        if (!safeRelative(rel)) throw new AsarError('unsafe entry name');
        if (child.files) stack.push([rel, child]);
        else if (child.link === undefined) yield rel;
      }
    }
  }

  /** Read one regular file. Unpacked entries are read from app.asar.unpacked (path checked to stay inside it). */
  read(rel) {
    const e = this.entry(rel);
    if (!e || e.files || e.link !== undefined) throw new AsarError('not a regular file');
    const size = Number(e.size);
    if (!Number.isSafeInteger(size) || size < 0) throw new AsarError('bad file size');
    if (e.unpacked) {
      const root = `${this.file}.unpacked`;
      const target = path.resolve(root, rel);
      if (!target.startsWith(root + path.sep)) throw new AsarError('unpacked path escapes');
      return readFileSync(target);
    }
    const offset = Number(e.offset);
    if (!Number.isSafeInteger(offset) || offset < 0 || this.base + offset + size > this.size) {
      throw new AsarError('bad file offset');
    }
    const fd = openSync(this.file, 'r');
    try {
      return readExactly(fd, size, this.base + offset);
    } finally {
      closeSync(fd);
    }
  }
}
