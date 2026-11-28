import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockerJavaProbeArgs, resolveJava } from './smoke-helpers.mjs';

const exec = promisify(execFile);
const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const libraries = path.join(root, 'bridge/build/install/bridge/lib');
const source = path.join(root, 'scripts/DirectQrSmoke.java');
const java = resolveJava();
const flags = process.argv.includes('--direct') ? ['-Dpilot.qr.direct=true'] : [];
const result = await exec(java, [...flags, '--enable-native-access=ALL-UNNAMED', '-cp', path.join(libraries, '*'), source], { timeout: 30000, maxBuffer: 1024 * 1024 });
console.log(result.stdout.trim());
if (process.argv.includes('--container')) {
  const isolated = await exec('docker', dockerJavaProbeArgs({
    network: 'none',
    mounts: [`${libraries}:/probe:ro`, `${source}:/DirectQrSmoke.java:ro`],
    jvmArgs: flags,
    entryArgs: ['/DirectQrSmoke.java'],
  }), { timeout: 40000, maxBuffer: 1024 * 1024 });
  console.log('Linux 完全断网容器：' + isolated.stdout.trim());
}
