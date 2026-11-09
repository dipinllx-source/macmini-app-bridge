#!/usr/bin/env node
// Extract the original Codex webview (front end) from the locally installed app into the MMAB data directory.
// Nothing extracted is committed or redistributed (design §10.4, §14.3).
//
//   node adapters/codex/tools/extract.mjs [--app /Applications/ChatGPT.app] [--data-dir <dir>]
//
// Output: <data-dir>/codex/<version>/webview/{...original files..., .mmab-source.json}
// Configure:  resources: data:codex/<version>/webview   and the driver with  --assets-dir <that directory>
import { existsSync, mkdirSync, readdirSync, realpathSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { AsarArchive } from '../../../sdk/driver-node/asar.mjs';
import { APP_BUNDLE, appVersion, defaultDataDir } from '../driver/process-check.mjs';

export function extract({ app = APP_BUNDLE, dataDir = defaultDataDir() } = {}) {
  const version = appVersion(app);
  if (!/^[0-9][0-9A-Za-z.+-]{0,40}$/.test(version)) throw new Error('unexpected app version string');
  const asarFile = `${app}/Contents/Resources/app.asar`;
  const out = path.join(dataDir, 'codex', version, 'webview');
  if (existsSync(out) && readdirSync(out).length) throw new Error(`${out} is not empty; move it aside first`);
  const archive = new AsarArchive(asarFile);
  let count = 0;
  for (const rel of archive.files('webview')) {
    const target = path.join(out, rel.slice('webview/'.length));
    mkdirSync(path.dirname(target), { recursive: true, mode: 0o700 });
    writeFileSync(target, archive.read(rel), { mode: 0o600 });
    count++;
  }
  if (!count || !existsSync(path.join(out, 'index.html'))) throw new Error('webview/index.html not found in app.asar');
  const st = statSync(asarFile);
  writeFileSync(path.join(out, '.mmab-source.json'), `${JSON.stringify({
    appId: 'codex', version, asarSize: st.size, asarMtimeMs: Math.round(st.mtimeMs), files: count,
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
    process.stdout.write(`Extracted ${r.files} webview files for Codex ${r.version}\n`
      + `resources: data:codex/${r.version}/webview\n--assets-dir ${r.out}\n`);
  } catch (e) {
    process.stderr.write(`extract failed: ${e.message}\n`);
    process.exit(1);
  }
}
