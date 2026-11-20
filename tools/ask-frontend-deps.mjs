#!/usr/bin/env node
/** 根据锁文件补齐前端依赖。已有正确版本时直接退出。 */
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FRONTEND_DIR = path.join(ROOT, 'pilot-web', 'frontend');
// 源码启动器会传入已验证的 npm CLI；直接从 Maven 运行时使用 PATH。
const npmCli = process.argv[2];
function readJson(file) {
  return JSON.parse(readFileSync(path.join(FRONTEND_DIR, file), 'utf8'));
}

function dependencyIssue() {
  const manifest = readJson('package.json');
  const { packages } = readJson('package-lock.json');
  const root = packages?.[''];
  if (!root) throw new Error('前端 package-lock.json 缺少依赖清单，请恢复仓库中的锁文件。');

  for (const group of ['dependencies', 'devDependencies']) {
    const declared = Object.entries(manifest[group] ?? {});
    const locked = root[group] ?? {};
    if (declared.length !== Object.keys(locked).length || declared.some(([name, range]) =>
      locked[name] !== range || !packages[`node_modules/${name}`]?.version)) {
      throw new Error('前端 package.json 与 package-lock.json 不一致，请在 pilot-web/frontend 更新锁文件后重试。');
    }
  }

  for (const [location, pinned] of Object.entries(packages)) {
    // Rollup、esbuild 等为各系统和架构分发不同的可选依赖。
    if (!location || pinned.optional) continue;
    if (!location.startsWith('node_modules/') || location.includes('\\') || location.split('/').includes('..') || !pinned.version) {
      throw new Error(`前端锁文件包含无效的依赖条目：${location}`);
    }
    let installed;
    try {
      installed = readJson(`${location}/package.json`);
    } catch (error) {
      if (error.code === 'ENOENT' || error.code === 'ENOTDIR' || error instanceof SyntaxError) {
        return `${location} 缺失或安装不完整`;
      }
      throw error;
    }
    if (installed?.version !== pinned.version) {
      return `${location} 当前版本为 ${installed?.version ?? '未知'}，锁定版本为 ${pinned.version}`;
    }
  }
  return null;
}

function install() {
  const useWindowsShim = !npmCli && process.platform === 'win32';
  const npm = npmCli ? process.execPath : useWindowsShim ? 'npm.cmd' : 'npm';
  const args = ['ci', '--include=dev', '--prefer-offline', '--no-audit', '--no-fund'];
  if (process.env.PILOT_OFFLINE?.toLowerCase() === 'true') args.push('--offline');
  const result = spawnSync(npm, npmCli ? [npmCli, ...args] : args, {
    cwd: FRONTEND_DIR,
    stdio: 'inherit',
    shell: useWindowsShim,
    windowsHide: true
  });
  if (result.error) throw new Error(`无法执行 npm ci：${result.error.message}`);
  if (result.status !== 0) {
    throw new Error(`npm ci 未完成（${result.signal || `退出码 ${result.status}`}）。`);
  }
  const remaining = dependencyIssue();
  if (remaining) throw new Error(`npm ci 完成后依赖仍未就绪：${remaining}`);
  process.stdout.write('前端依赖已就绪。\n');
}

try {
  const issue = dependencyIssue();
  if (issue) {
    console.log(`Pilot 启动检查：${issue}，正在按锁文件安装前端依赖（npm ci）…`);
    try {
      install();
    } catch (error) {
      throw new Error(`${error.message}\n请在 ${FRONTEND_DIR} 成功运行 npm ci --include=dev 后重试。`);
    }
  }
} catch (error) {
  console.error(`Pilot 启动检查失败：${error.message}`);
  process.exitCode = 1;
}
