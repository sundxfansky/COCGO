import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
function run(file, args) {
  const result = spawnSync(file, args, { cwd: web, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
}
if (!existsSync(resolve(web, 'node_modules/vite/bin/vite.js'))) {
  throw Error('Web dependencies are missing. Run npm ci in web/ before building the Android APK.');
}
run(process.execPath, ['scripts/export-schema.mjs']);
run(process.execPath, ['node_modules/vite/bin/vite.js', 'build']);
