import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 构建前端并清理上一版产物。
 *
 * 构建期间保留旧 assets，成功后清理。
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const output = path.resolve(root, '../src/main/resources/static');
const assets = path.join(output, 'assets');
const stamp = path.resolve(root, '../../.pilot/frontend-build.sha256');
const lock = path.resolve(root, '../../.pilot/frontend-build.lock');

function fingerprint(base, entries) {
  const hash = createHash('sha256');
  function include(name) {
    const location = path.join(base, name);
    if (!fs.existsSync(location)) {
      hash.update(JSON.stringify([name, 'missing']));
    } else if (fs.statSync(location).isDirectory()) {
      for (const child of fs.readdirSync(location).sort()) include(name + '/' + child);
    } else {
      const content = createHash('sha256').update(fs.readFileSync(location)).digest('hex');
      hash.update(JSON.stringify([name, content]));
    }
  }
  for (const entry of entries) include(entry);
  return hash.digest('hex');
}

const sourceFingerprint = () => JSON.stringify([
  process.version,
  process.platform,
  process.arch,
  Object.entries(process.env).filter(([name]) => /^(VITE_|NODE_ENV$)/.test(name)).sort(),
  fingerprint(root, ['src', 'public', 'index.html', 'package.json', 'package-lock.json', 'vite.config.js',
    'tsconfig.json', 'scripts/build.mjs', 'scripts/typecheck.mjs',
    '.env', '.env.local', '.env.production', '.env.production.local'])
]);
const outputFingerprint = () => fingerprint(output, [
  'index.html', 'assets', ...(fs.existsSync(path.join(root, 'public')) ? fs.readdirSync(path.join(root, 'public')).sort() : [])
]);

fs.mkdirSync(path.dirname(stamp), { recursive: true });
try {
  fs.closeSync(fs.openSync(lock, 'wx'));
} catch (error) {
  if (error.code !== 'EEXIST') throw error;
  throw new Error(`另一个前端构建正在使用此目录。请等它结束后重试。若所有构建进程均已退出，请删除 ${lock} 后重试。`);
}
process.on('exit', () => fs.rmSync(lock, { force: true }));
process.on('SIGINT', () => process.exit(130));
process.on('SIGTERM', () => process.exit(143));

const deps = spawnSync(process.execPath, [path.resolve(root, '../../tools/ask-frontend-deps.mjs')], {
  cwd: root, stdio: 'inherit'
});
if (deps.error) throw deps.error;
if (deps.status !== 0) process.exit(deps.status ?? 1);

const source = sourceFingerprint();
if (process.argv.includes('--if-needed') && fs.existsSync(stamp)
    && fs.readFileSync(stamp, 'utf8') === source + '\n' + outputFingerprint()) {
  console.log('前端产物与当前源码一致，跳过构建。');
  process.exit(0);
}

fs.rmSync(stamp, { force: true });
console.log('正在根据当前源码构建前端…');
await import('./typecheck.mjs');

const listAssets = () => (fs.existsSync(assets) ? fs.readdirSync(assets) : []);

const startedAt = Date.now();
const vite = path.join(root, 'node_modules', 'vite', 'bin', 'vite.js');
// 直接用 Node 启动 CLI，三个平台都不经过 shell，项目路径含空格也不会被拆开。
const result = spawnSync(process.execPath, [vite, 'build'], { cwd: root, stdio: 'inherit' });
if (result.error) {
  console.error(result.error.message);
  process.exit(1);
}
if (result.status !== 0) process.exit(result.status ?? 1);

// vite 不会清空 outDir，所以上一版的产物会一直堆在这儿：按修改时间把「本次没产出的」删掉。
// 删的是构建开始之前就存在的文件，构建期间旧文件还在，打开着的页面不会 404 白屏。
for (const name of listAssets()) {
  const file = path.join(assets, name);
  try {
    if (fs.statSync(file).mtimeMs < startedAt - 1000) {
      fs.rmSync(file, { force: true });
    }
  } catch {
    // 文件刚好被清理掉，忽略
  }
}

if (source !== sourceFingerprint()) {
  throw new Error('构建期间前端文件发生了变化，请重新启动。');
}
if (!fs.existsSync(path.join(output, 'index.html')) || listAssets().length === 0) {
  throw new Error('前端构建未生成完整资源，请检查构建日志。');
}
fs.writeFileSync(stamp, source + '\n' + outputFingerprint());
