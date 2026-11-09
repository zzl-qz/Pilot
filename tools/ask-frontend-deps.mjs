#!/usr/bin/env node
/**
 * 启动前检查锁定的前端依赖，依赖已就绪时保留手动更新入口。
 *
 * 挂在 Maven 的 validate 阶段（exec-maven-plugin + inheritIo），所以无论是
 * start.cmd / start.sh 还是 IDE 里直接跑 spring-boot:run，都会先经过这里。
 *
 * 必需依赖缺失或版本过旧时直接执行 npm ci，CI 和 IDE 也会执行；
 * 不可交互的构建只跳过可选询问。安装失败则停止构建，避免后续出现一连串编译错误。
 *
 * 去重：问过之后写 .pilot/frontend-deps-ask.stamp，一分钟内不再问。
 * 这条标记与 Java 侧的 PilotServerApplication 启动检查共用——mvn spring-boot:run
 * 会先走 validate（这里）再拉起 JVM，没有它同一次启动会被问两遍。
 */
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FRONTEND_DIR = path.join(ROOT, 'pilot-web', 'frontend');
// 源码启动器会传入已验证的 npm CLI；直接从 Maven 运行时使用 PATH。
const npmCli = process.argv[2];
const WAIT_MS = 5000;
const STAMP = path.join(ROOT, '.pilot', 'frontend-deps-ask.stamp');
const STAMP_FRESH_MS = 60_000;

const esc = '\u001b';
const dim = `${esc}[2m`;
const link = `${esc}[4;36m`;
const ok = `${esc}[32m`;
const warn = `${esc}[33m`;
const reset = `${esc}[0m`;

const NO = 1;
const YES = 2;

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

function install(mode) {
  const useWindowsShim = !npmCli && process.platform === 'win32';
  const npm = npmCli ? process.execPath : useWindowsShim ? 'npm.cmd' : 'npm';
  const args = [mode, '--include=dev', '--prefer-offline', '--no-audit', '--no-fund'];
  if (process.env.PILOT_OFFLINE?.toLowerCase() === 'true') args.push('--offline');
  const result = spawnSync(npm, npmCli ? [npmCli, ...args] : args, {
    cwd: FRONTEND_DIR,
    stdio: 'inherit',
    shell: useWindowsShim,
    windowsHide: true
  });
  if (result.error) throw new Error(`无法执行 npm ${mode}：${result.error.message}`);
  if (result.status !== 0) {
    throw new Error(`npm ${mode} 未完成（${result.signal || `退出码 ${result.status}`}）。`);
  }
  const remaining = dependencyIssue();
  if (remaining) throw new Error(`npm ${mode} 完成后依赖仍未就绪：${remaining}`);
  process.stdout.write(`\n${ok}前端依赖已就绪${reset}\n\n`);
}

/** 记下「刚刚问过」，与 Java 侧共用同一条标记。 */
function stampAsked() {
  try {
    mkdirSync(path.dirname(STAMP), { recursive: true });
    writeFileSync(STAMP, new Date().toISOString());
  } catch {
    // 标记写不下去只是少了去重，不影响启动
  }
}

function askedRecently() {
  try {
    if (!existsSync(STAMP)) {
      return false;
    }
    const age = Date.now() - statSync(STAMP).mtimeMs;
    return age >= 0 && age < STAMP_FRESH_MS;
  } catch {
    return false;
  }
}

function printMenu(interactive) {
  process.stdout.write(
    `\n  ${dim}Pilot 启动检查${reset}\n`
    + `  ${dim}前端依赖：pilot-web/frontend${reset}\n\n`
    + `  ${link}[1] 否，直接启动${reset}\n`
    + `  ${link}[2] 是，拉取最新依赖（npm install）${reset}\n\n`
    + (interactive
      ? `  ${dim}按 1 / 2 立即选择，回车默认「否」${reset}\n`
      : `  ${dim}输入 2 后回车即更新，${WAIT_MS / 1000} 秒内无输入则直接启动${reset}\n`)
    + `  ${dim}>${reset} `
  );
}

function readKeyChoice() {
  return new Promise((resolve) => {
    const stdin = process.stdin;
    if (typeof stdin.setRawMode === 'function') {
      stdin.setRawMode(true);
    }
    stdin.resume();
    stdin.setEncoding('utf8');

    const cleanup = () => {
      stdin.removeListener('data', onData);
      if (typeof stdin.setRawMode === 'function') {
        stdin.setRawMode(false);
      }
      stdin.pause();
    };

    const onData = (chunk) => {
      if (chunk === '\u0003' || chunk === '\u0004') {
        cleanup();
        process.stdout.write('\n');
        process.exit(130);
      }
      if (chunk === '2') {
        cleanup();
        resolve(YES);
        return;
      }
      if (chunk === '\r' || chunk === '\n' || chunk === '1' || chunk === '\u001b') {
        cleanup();
        resolve(NO);
      }
    };

    stdin.on('data', onData);
  });
}

function readPipedChoice(waitMs) {
  return new Promise((resolve) => {
    const stdin = process.stdin;
    let settled = false;
    const finish = (value) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      stdin.removeListener('data', onData);
      stdin.removeListener('end', onEnd);
      stdin.pause();
      resolve(value);
    };
    const timer = setTimeout(() => finish(NO), waitMs);
    const onData = (chunk) => {
      const text = String(chunk).trim().toLowerCase();
      finish(text.startsWith('2') || text === 'y' || text === 'yes' ? YES : NO);
    };
    const onEnd = () => finish(NO);

    stdin.resume();
    stdin.setEncoding('utf8');
    stdin.on('data', onData);
    stdin.on('end', onEnd);
  });
}

async function main() {
  const issue = dependencyIssue();
  if (issue) {
    process.stdout.write(`\nPilot 启动检查：${issue}，正在按锁文件安装前端依赖（npm ci）…\n\n`);
    try {
      install('ci');
    } catch (error) {
      throw new Error(`${error.message}\n请在 ${FRONTEND_DIR} 成功运行 npm ci --include=dev 后重试。`);
    }
    stampAsked();
    return;
  }

  // CI、IDEA 构建控制台、管道：没有真正的终端，不要等输入。
  // IDEA 会把 stdin 接进来且一直不关，等输入会让这次启动停住。
  if (process.env.CI === 'true' || process.env.PILOT_SKIP_DEPS_ASK === '1' || !process.stdin.isTTY) {
    return;
  }

  // 同一次启动已经问过（例如 Maven 之后紧接着起来的 JVM）：不再重复问
  if (askedRecently()) {
    process.stdout.write(`  ${dim}Pilot 启动检查：刚刚已确认过前端依赖，跳过询问${reset}\n`);
    return;
  }

  const interactive = Boolean(process.stdin.isTTY && process.stdout.isTTY);
  printMenu(interactive);

  const choice = interactive ? await readKeyChoice() : await readPipedChoice(WAIT_MS);
  process.stdout.write('\n');
  stampAsked();

  if (choice === NO) {
    process.stdout.write(`${dim}跳过依赖更新，继续启动${reset}\n\n`);
    return;
  }

  process.stdout.write(`\n${ok}正在更新前端依赖…${reset}\n\n`);
  try {
    install('install');
  } catch (error) {
    const remaining = dependencyIssue();
    if (remaining) throw new Error(`${error.message} 前端依赖仍未就绪：${remaining}`);
    process.stderr.write(`\n${warn}${error.message} 继续使用已就绪的前端依赖。${reset}\n\n`);
  }
}

try {
  await main();
} catch (error) {
  process.stderr.write(`Pilot 启动检查失败：${error.message}\n`);
  process.exitCode = 1;
}
