// 网页版“选择文件 / 目录”的 Mac 端数据源（能力 file.pick）。
//
// 原版千问在主进程里弹 electron.dialog.showOpenDialog —— 远程浏览器看不见那个面板，请求会一直挂起。
// shim 把 files.selectFilesAndFolders 改成页内选择器，由这里列出 Mac 上的目录内容。
//
// 边界：只在允许的根目录（默认本机用户主目录）之内列举；路径先 realpath 再判断，符号链接不能逃出根目录；
// 只返回名称 / 类型 / 大小，从不读取文件内容，也不创建、修改、删除任何东西。
import { readdir, realpath, stat } from 'node:fs/promises';
import { homedir } from 'node:os';
import path from 'node:path';

export const MAX_ENTRIES = 2000;

function within(p, root) {
  const r = path.relative(root, p);
  return r === '' || (!r.startsWith('..') && !path.isAbsolute(r));
}

/**
 * @param {object} [o]
 * @param {string[]} [o.roots] 允许列举的根目录（绝对路径），默认 [os.homedir()]
 */
export function createFsPick(o = {}) {
  const home = homedir();
  const rootsIn = o.roots && o.roots.length ? o.roots : [home];
  let roots = null;
  async function resolvedRoots() {
    if (!roots) roots = await Promise.all(rootsIn.map((r) => realpath(r).catch(() => path.resolve(r))));
    return roots;
  }
  function expand(p) {
    if (p === '~') return home;
    if (p.startsWith('~/')) return path.join(home, p.slice(2));
    return p;
  }

  /**
   * @param {{path?: string, files?: boolean, hidden?: boolean}} req
   * @returns {Promise<object>} {ok:true, path, parent, home, entries, truncated} 或 {ok:false, reason, path?}
   */
  async function list(req) {
    const rs = await resolvedRoots();
    const wanted = req && typeof req.path === 'string' && req.path.trim() ? expand(req.path.trim()) : rs[0];
    if (!path.isAbsolute(wanted)) return { ok: false, reason: 'NOT_ABSOLUTE' };
    let real;
    try {
      real = await realpath(wanted);
    } catch (e) {
      return { ok: false, reason: e && e.code === 'EACCES' ? 'ACCESS_DENIED' : 'NOT_FOUND', path: wanted };
    }
    const root = rs.find((r) => within(real, r));
    if (!root) return { ok: false, reason: 'OUTSIDE_ROOT', path: real, roots: rs };
    let st;
    try {
      st = await stat(real);
    } catch {
      return { ok: false, reason: 'NOT_FOUND', path: real };
    }
    if (!st.isDirectory()) return { ok: false, reason: 'NOT_DIRECTORY', path: real };
    let dirents;
    try {
      dirents = await readdir(real, { withFileTypes: true });
    } catch (e) {
      return { ok: false, reason: e && (e.code === 'EACCES' || e.code === 'EPERM') ? 'ACCESS_DENIED' : 'READ_FAILED', path: real };
    }
    const files = !!(req && req.files);
    const hidden = !!(req && req.hidden);
    const entries = [];
    for (const d of dirents) {
      if (!hidden && d.name.startsWith('.')) continue;
      let type = d.isDirectory() ? 'folder' : d.isFile() ? 'file' : null;
      if (d.isSymbolicLink()) {
        // 链接按目标类型显示；指向根目录之外的链接不列出
        try {
          const t = await realpath(path.join(real, d.name));
          if (!rs.some((r) => within(t, r))) continue;
          const ts = await stat(t);
          type = ts.isDirectory() ? 'folder' : ts.isFile() ? 'file' : null;
        } catch {
          continue;
        }
      }
      if (type === null || (type === 'file' && !files)) continue;
      entries.push({ name: d.name, type });
      if (entries.length >= MAX_ENTRIES) break;
    }
    entries.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name, 'zh-CN') : a.type === 'folder' ? -1 : 1));
    const parent = real === root ? null : path.dirname(real);
    return { ok: true, path: real, parent, home, roots: rs, entries, truncated: entries.length >= MAX_ENTRIES };
  }

  /** 选中项的最终信息（与原版 selectFilesAndFolders 返回的 paths[] 同形）。 */
  async function describe(req) {
    const rs = await resolvedRoots();
    const items = req && Array.isArray(req.paths) ? req.paths.slice(0, 200) : [];
    const out = [];
    for (const p of items) {
      if (typeof p !== 'string' || !path.isAbsolute(p)) return { ok: false, reason: 'NOT_ABSOLUTE' };
      let real;
      try {
        real = await realpath(p);
      } catch {
        return { ok: false, reason: 'NOT_FOUND', path: p };
      }
      if (!rs.some((r) => within(real, r))) return { ok: false, reason: 'OUTSIDE_ROOT', path: real };
      const st = await stat(real);
      const dir = st.isDirectory();
      out.push({ path: real, name: path.basename(real), type: dir ? 'folder' : 'file', size: dir ? 0 : st.size });
    }
    return { ok: true, paths: out };
  }

  /**
   * 另存为的目标：父目录必须存在且在根目录内；文件名不能含路径分隔符；不能是已存在的目录。
   * @returns {Promise<object>} {ok:true, filePath, exists} 或 {ok:false, reason}
   */
  async function checkSave(req) {
    const rs = await resolvedRoots();
    const p = req && typeof req.filePath === 'string' ? expand(req.filePath) : '';
    if (!path.isAbsolute(p)) return { ok: false, reason: 'NOT_ABSOLUTE' };
    const name = path.basename(p);
    if (!name || name === '.' || name === '..' || name.length > 255 || /[\/\u0000]/.test(name)) return { ok: false, reason: 'BAD_NAME' };
    let dir;
    try {
      dir = await realpath(path.dirname(p));
    } catch {
      return { ok: false, reason: 'NOT_FOUND', path: path.dirname(p) };
    }
    if (!rs.some((r) => within(dir, r))) return { ok: false, reason: 'OUTSIDE_ROOT', path: dir };
    if (!(await stat(dir)).isDirectory()) return { ok: false, reason: 'NOT_DIRECTORY', path: dir };
    const filePath = path.join(dir, name);
    let exists = false;
    try {
      const st = await stat(filePath);
      if (st.isDirectory()) return { ok: false, reason: 'IS_DIRECTORY', path: filePath };
      exists = true;
      const real = await realpath(filePath); // 已存在的链接不能把写入带出根目录
      if (!rs.some((r) => within(real, r))) return { ok: false, reason: 'OUTSIDE_ROOT', path: real };
    } catch (e) {
      if (e && e.code !== 'ENOENT') return { ok: false, reason: 'ACCESS_DENIED', path: filePath };
    }
    return { ok: true, filePath, exists };
  }

  return { list, describe, checkSave };
}
