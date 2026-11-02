#!/usr/bin/env node
/**
 * 启动前询问是否重新拉取前端依赖。
 *
 * 挂在 Maven 的 validate 阶段（exec-maven-plugin + inheritIo），所以无论是
 * start.cmd / start.sh 还是 IDE 里直接跑 spring-boot:run，都会先经过这里。
 *
 * 三种输入形态：
 *   - TTY（命令行窗口）：按 1 / 2 立即生效，不用回车；
 *   - 非 TTY 但可写（IDE 运行控制台）：打印菜单后最多等 5 秒，输入 2 回车即安装；
 *   - 完全不可交互（CI、重定向、EOF）：立即跳过，绝不阻塞构建。
 *
 * 依赖目录缺失时不提问，直接装——否则后面 npm run build 必然失败。
 *
 * 去重：问过之后写 .pilot/frontend-deps-ask.stamp，一分钟内不再问。
 * 这条标记与 Java 侧的 PilotServerApplication 启动检查共用——mvn spring-boot:run
 * 会先走 validate（这里）再拉起 JVM，没有它同一次启动会被问两遍。
 */
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FRONTEND_DIR = path.join(ROOT, 'pilot-web', 'frontend');
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

function install() {
  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
  process.stdout.write(`${ok}正在更新前端依赖…${reset}\n\n`);
  const result = spawnSync(npm, ['install'], {
    cwd: FRONTEND_DIR,
    stdio: 'inherit',
    shell: process.platform === 'win32'
  });
  if (result.error || result.status !== 0) {
    process.stdout.write(`\n${warn}依赖更新失败，继续用现有依赖启动。${reset}\n\n`);
    return false;
  }
  process.stdout.write(`\n${ok}依赖已更新${reset}\n\n`);
  return true;
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
  if (!existsSync(path.join(FRONTEND_DIR, 'package.json'))) {
    return;
  }

  // 依赖缺失：没得选
  if (!existsSync(path.join(FRONTEND_DIR, 'node_modules'))) {
    process.stdout.write(`\n  ${dim}Pilot 启动检查：前端依赖缺失，先安装${reset}\n\n`);
    install();
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

  process.stdout.write('\n');
  install();
}

try {
  await main();
} finally {
  process.exit(0);
}
