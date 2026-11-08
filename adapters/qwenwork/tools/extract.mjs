#!/usr/bin/env node
// Extract the original QwenWork renderer from the locally installed app into the MMAB data directory, and write the
// browser wrapper for the original preload. Nothing extracted is committed or redistributed (design §10.4, §14.3).
//
//   node adapters/qwenwork/tools/extract.mjs [--app /Applications/QwenWorkCN.app] [--data-dir <dir>]
//
// Output: <data-dir>/qwenwork/<version>/renderer/{...original files..., mmab-preload.js, .mmab-source.json}
// Configure the instance with  resources: data:qwenwork/<version>/renderer
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { realpathSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { AsarArchive } from '../../../sdk/driver-node/asar.mjs';
import { APP_BUNDLE, defaultDataDir } from '../driver/process-check.mjs';

export const PRELOAD_GLOBALS = { require: '__mmabQwRequire', process: '__mmabQwProcess' };

/** Wrap the CommonJS preload so it runs as a classic script in the page with the shim's require / process. */
export function wrapPreload(source) {
  const { require: r, process: p } = PRELOAD_GLOBALS;
  return `(function (require, process, module, exports) {\n${source}\n}).call(window, window.${r}, window.${p}, `
    + '{ exports: {} }, {});\n';
}

export function appVersion(app) {
  return execFileSync('/usr/libexec/PlistBuddy', ['-c', 'Print :CFBundleShortVersionString', `${app}/Contents/Info.plist`],
    { encoding: 'utf8' }).trim();
}

export function extract({ app = APP_BUNDLE, dataDir = defaultDataDir() } = {}) {
  const version = appVersion(app);
  if (!/^[0-9][0-9A-Za-z.+-]{0,40}$/.test(version)) throw new Error('unexpected app version string');
  const asarFile = `${app}/Contents/Resources/app.asar`;
  const out = path.join(dataDir, 'qwenwork', version, 'renderer');
  if (existsSync(out) && readdirSync(out).length) {
    throw new Error(`${out} is not empty; move it aside before extracting again`);
  }
  const archive = new AsarArchive(asarFile);
  let count = 0;
  for (const rel of archive.files('out/renderer')) {
    const target = path.join(out, rel.slice('out/renderer/'.length));
    mkdirSync(path.dirname(target), { recursive: true, mode: 0o700 });
    writeFileSync(target, archive.read(rel), { mode: 0o600 });
    count++;
  }
  if (!count || !existsSync(path.join(out, 'index.html'))) throw new Error('renderer index.html not found in app.asar');
  const preload = archive.read('out/preload/index.js').toString('utf8');
  writeFileSync(path.join(out, 'mmab-preload.js'), wrapPreload(preload), { mode: 0o600 });
  const st = statSync(asarFile);
  writeFileSync(path.join(out, '.mmab-source.json'), `${JSON.stringify({
    appId: 'qwenwork', version, asarSize: st.size, asarMtimeMs: Math.round(st.mtimeMs), files: count,
    extractedAt: new Date().toISOString(),
  }, null, 2)}\n`, { mode: 0o600 });
  return { version, out, files: count };
}

const isMain = process.argv[1] !== undefined
  && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href;
if (isMain) {
  const arg = (k) => {
    const i = process.argv.indexOf(k);
    return i > 0 ? process.argv[i + 1] : undefined;
  };
  try {
    const r = extract({ app: arg('--app') ?? APP_BUNDLE, dataDir: arg('--data-dir') ?? defaultDataDir() });
    process.stdout.write(`Extracted ${r.files} renderer files for QwenWork ${r.version}\n`
      + `resources: data:qwenwork/${r.version}/renderer\n`);
  } catch (e) {
    process.stderr.write(`extract failed: ${e.message}\n`);
    process.exit(1);
  }
}
