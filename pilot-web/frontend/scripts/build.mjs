import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 构建前端并清理上一版产物。
 *
 * <p>只删「本次没有产出、但之前存在」的 assets 文件：既不让 static/assets 越堆越大，
 * 又保证构建期间旧文件还在（正在打开的页面不会因为一次构建而 404 白屏）。
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const assets = path.resolve(root, '../src/main/resources/static/assets');

const listAssets = () => (fs.existsSync(assets) ? fs.readdirSync(assets) : []);

const startedAt = Date.now();
const vite = path.join(root, 'node_modules', '.bin', process.platform === 'win32' ? 'vite.cmd' : 'vite');
// Windows 上 .cmd 必须走 shell 才能执行
const result = spawnSync(vite, ['build'], { cwd: root, stdio: 'inherit', shell: process.platform === 'win32' });
if (result.error) {
  console.error(result.error.message);
  process.exit(1);
}

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

process.exit(result.status ?? 0);
