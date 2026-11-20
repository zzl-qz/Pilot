#!/usr/bin/env node
/**
 * Pilot 自举训练隔离运行器控制脚本（跨平台：macOS / Linux / Windows）。
 *
 * 职责：
 * - 管理 ~/pilot-dogfood/runner（分支 df/runner）构建、资源同步、安全重启与桌面窗口保持
 * - 显式绑定 PILOT_DATA_DIR=/Users/smt/Desktop/pilot/.pilot（可通过环境变量覆盖），严禁复制或打印密钥
 * - 重启前强制校验活跃会话，存在非 dogfood: 前缀的用户活跃 run 时拒绝重启
 * - 管理每个任务独立的 ~/pilot-dogfood/ws-<taskId> worktree（分支 df/<taskId>）
 */

import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { setTimeout as delay } from 'node:timers/promises';

const IS_WIN = process.platform === 'win32';
const IS_MAC = process.platform === 'darwin';

const MAIN_REPO = path.resolve(
  process.env.PILOT_MAIN_REPO || path.join(os.homedir(), 'Desktop', 'pilot')
);
const DOGFOOD_ROOT = path.resolve(
  process.env.PILOT_DOGFOOD_ROOT || path.join(os.homedir(), 'pilot-dogfood')
);
const RUNNER_DIR = path.resolve(
  process.env.PILOT_RUNNER_DIR || path.join(DOGFOOD_ROOT, 'runner')
);
const DATA_DIR = path.resolve(
  process.env.PILOT_DATA_DIR || path.join(MAIN_REPO, '.pilot')
);
const PORT = Number(process.env.PILOT_PORT || 9999);
const BASE_URL = `http://127.0.0.1:${PORT}`;
const PID_FILE = path.join(DOGFOOD_ROOT, 'runner.pid');
const LOG_FILE = path.join(DOGFOOD_ROOT, 'runner.log');
const JAR_REL = path.join('pilot-server', 'target', 'pilot-server-0.0.1-SNAPSHOT.jar');

function readJavaMajor(javaHome) {
  if (!javaHome || !fs.existsSync(javaHome)) return 0;
  const rel = path.join(javaHome, 'release');
  if (fs.existsSync(rel)) {
    const m = fs.readFileSync(rel, 'utf8').match(/JAVA_VERSION="(\d+)/);
    if (m) return Number(m[1]);
  }
  return 0;
}

function resolveJavaHome() {
  if (process.env.PILOT_JAVA_HOME && readJavaMajor(process.env.PILOT_JAVA_HOME) >= 25) {
    return process.env.PILOT_JAVA_HOME;
  }
  if (process.env.JAVA_HOME && readJavaMajor(process.env.JAVA_HOME) >= 25) {
    return process.env.JAVA_HOME;
  }
  if (IS_MAC) {
    const macJdk25 = path.join(
      os.homedir(),
      'Library',
      'Java',
      'JavaVirtualMachines',
      'openjdk-25.jdk',
      'Contents',
      'Home'
    );
    if (readJavaMajor(macJdk25) >= 25) return macJdk25;
    const probe = spawnSync('/usr/libexec/java_home', ['-v', '25'], { encoding: 'utf8' });
    if (probe.status === 0 && probe.stdout.trim()) return probe.stdout.trim();
  }
  return process.env.JAVA_HOME || '';
}

function javaBinary() {
  const home = resolveJavaHome();
  if (home) {
    const candidate = path.join(home, 'bin', IS_WIN ? 'java.exe' : 'java');
    if (fs.existsSync(candidate)) return candidate;
  }
  return IS_WIN ? 'java.exe' : 'java';
}

function runChecked(cmd, args, options = {}) {
  const res = spawnSync(cmd, args, {
    stdio: 'inherit',
    ...options,
  });
  if (res.error) throw res.error;
  if (res.status !== 0) {
    throw new Error(`命令失败 (exit=${res.status}): ${cmd} ${args.join(' ')}`);
  }
}

function runCapture(cmd, args, options = {}) {
  const res = spawnSync(cmd, args, {
    encoding: 'utf8',
    ...options,
  });
  return res;
}

function cloneOrCopy(src, dst) {
  if (!fs.existsSync(src)) {
    console.warn(`[sync-assets] 跳过不存在的源路径: ${src}`);
    return false;
  }
  fs.mkdirSync(path.dirname(dst), { recursive: true });
  if (fs.existsSync(dst)) {
    fs.rmSync(dst, { recursive: true, force: true });
  }
  if (IS_MAC) {
    const res = spawnSync('cp', ['-cR', src, dst], { stdio: 'pipe' });
    if (res.status === 0) return true;
  }
  fs.cpSync(src, dst, { recursive: true, force: true });
  return true;
}

function syncAssets({ force = false, skipStatic = false } = {}) {
  const items = [
    ...(skipStatic
      ? []
      : [
          { rel: path.join('pilot-web', 'src', 'main', 'resources', 'static', 'index.html'), always: false },
          { rel: path.join('pilot-web', 'src', 'main', 'resources', 'static', 'assets'), always: false },
        ]),
    { rel: path.join('pilot-web', 'frontend', 'node_modules'), always: false },
    { rel: path.join('pilot-desktop', 'node_modules'), always: false },
    { rel: path.join('pilot-desktop', 'out'), always: false },
  ];
  for (const item of items) {
    const src = path.join(MAIN_REPO, item.rel);
    const dst = path.join(RUNNER_DIR, item.rel);
    if (!force && !item.always && fs.existsSync(dst)) {
      console.log(`[sync-assets] 已存在跳过: ${item.rel}`);
      continue;
    }
    if (cloneOrCopy(src, dst)) {
      console.log(`[sync-assets] 已同步: ${item.rel}`);
    }
  }
  const st = runCapture('git', ['-C', RUNNER_DIR, 'status', '--short']);
  const dirty = (st.stdout || '')
    .split('\n')
    .filter((line) => line.includes('pilot-web/src/main/resources/static/') || line.includes('pilot-desktop/'));
  if (dirty.length > 0) {
    throw new Error(`[sync-assets] 意外污染 git status:\n${dirty.join('\n')}`);
  }
}

function buildFrontendIfAvailable() {
  const feDir = path.join(RUNNER_DIR, 'pilot-web', 'frontend');
  if (!fs.existsSync(path.join(feDir, 'node_modules'))) {
    return false;
  }
  const npmCmd = IS_WIN ? 'npm.cmd' : 'npm';
  console.log(`[build] 在 ${RUNNER_DIR} 构建最新前端 UI 产物...`);
  runChecked(npmCmd, ['--prefix', feDir, 'run', 'build'], { cwd: RUNNER_DIR });
  return true;
}

function runV1Verify() {
  syncAssets();
  const env = { ...process.env };
  const javaHome = resolveJavaHome();
  if (javaHome) env.JAVA_HOME = javaHome;
  const cmd = IS_WIN ? 'mvnw.cmd' : 'sh';
  const args = IS_WIN
    ? ['-pl', 'pilot-core,pilot-server', '-DskipFrontend=true', 'test', 'spotless:check', 'checkstyle:check']
    : ['./mvnw', '-pl', 'pilot-core,pilot-server', '-DskipFrontend=true', 'test', 'spotless:check', 'checkstyle:check'];
  console.log(`[v1-verify] 在 ${RUNNER_DIR} 执行全量 V1（pilot-core + pilot-server 全量单测 + spotless + checkstyle + quality-gate）...`);
  runChecked(cmd, args, { cwd: RUNNER_DIR, env });
  runChecked(process.execPath, [path.join('scripts', 'quality-gate.mjs')], { cwd: RUNNER_DIR, env });
  const headSha = (runCapture('git', ['-C', RUNNER_DIR, 'rev-parse', 'HEAD']).stdout || '').trim();
  const stamp = path.join(DOGFOOD_ROOT, 'v1-verified.json');
  fs.mkdirSync(DOGFOOD_ROOT, { recursive: true });
  fs.writeFileSync(
    stamp,
    JSON.stringify({ verifiedAt: new Date().toISOString(), runnerDir: RUNNER_DIR, headSha }, null, 2) + '\n',
    'utf8'
  );
  console.log(`[v1-verify] 全量 V1 通过并落盘验证锚点: ${headSha}`);
}

async function fetchJson(urlPath, timeoutMs = 4000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const resp = await fetch(`${BASE_URL}${urlPath}`, { signal: controller.signal });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return await resp.json();
  } finally {
    clearTimeout(timer);
  }
}

async function checkSafe({ allowDogfoodActive = false } = {}) {
  let sessions;
  try {
    sessions = await fetchJson('/api/sessions', 3000);
  } catch {
    console.log(`[check-safe] 端口 ${PORT} 当前无响应，允许启动/重启。`);
    return { safe: true, activeSessions: [] };
  }
  const list = Array.isArray(sessions) ? sessions : sessions?.sessions || [];
  const blocking = [];
  const activeDogfood = [];
  for (const s of list) {
    let activeRunId = null;
    if (s.status === 'RUNNING') {
      activeRunId = 'RUNNING';
    }
    try {
      const act = await fetchJson(`/api/sessions/${encodeURIComponent(s.id)}/runs/active`, 2000);
      if (act && act.runId) activeRunId = act.runId;
    } catch {
      // ignore per-session query glitch
    }
    if (!activeRunId) continue;
    const title = String(s.title || '');
    const entry = { id: s.id, title, runId: activeRunId };
    if (title.startsWith('dogfood:')) {
      activeDogfood.push(entry);
    } else {
      blocking.push(entry);
    }
  }
  if (blocking.length > 0) {
    console.error('[check-safe] 检测到非 dogfood: 前缀的用户活跃会话，拒绝重启！');
    for (const b of blocking) {
      console.error(`  - ${b.id} | title="${b.title}" | runId=${b.runId}`);
    }
    return { safe: false, activeSessions: blocking };
  }
  if (!allowDogfoodActive && activeDogfood.length > 0) {
    console.error('[check-safe] 检测到运行中的 dogfood 会话（如确认中断请传 --allow-dogfood-active）：');
    for (const d of activeDogfood) {
      console.error(`  - ${d.id} | title="${d.title}" | runId=${d.runId}`);
    }
    return { safe: false, activeSessions: activeDogfood };
  }
  console.log(`[check-safe] 通过（活跃非 dogfood 会话: 0，活跃 dogfood 会话: ${activeDogfood.length}）。`);
  return { safe: true, activeSessions: activeDogfood };
}

function findListeningPids(port) {
  if (IS_WIN) {
    const res = runCapture('netstat', ['-ano', '-p', 'TCP']);
    const pids = new Set();
    for (const line of (res.stdout || '').split(/\r?\n/)) {
      const m = line.match(new RegExp(`:${port}\\s+.*LISTENING\\s+(\\d+)`, 'i'));
      if (m) pids.add(Number(m[1]));
    }
    return [...pids].filter((p) => p > 0 && p !== process.pid);
  }
  const res = runCapture('lsof', ['-ti', `tcp:${port}`, '-sTCP:LISTEN']);
  if (res.status !== 0 || !res.stdout) return [];
  return res.stdout
    .trim()
    .split(/\s+/)
    .map(Number)
    .filter((p) => Number.isInteger(p) && p > 0 && p !== process.pid);
}

function isPidAlive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function readDesktopEndpoint() {
  const candidates = [
    path.join(DATA_DIR, 'browser', 'desktop-browser.json'),
    path.join(RUNNER_DIR, '.pilot', 'browser', 'desktop-browser.json'),
  ];
  for (const file of candidates) {
    if (!fs.existsSync(file)) continue;
    try {
      const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
      const pid = Number(raw.pid || 0);
      if (isPidAlive(pid)) {
        return {
          pid,
          controlPort: Number(raw.controlPort || 0),
          alive: true,
          file,
        };
      }
    } catch {
      // ignore malformed endpoint file
    }
  }
  return null;
}

async function stopPidGracefully(pid, label) {
  if (!isPidAlive(pid)) return;
  console.log(`[stop] 正在温和停止 ${label} (PID ${pid})...`);
  try {
    if (IS_WIN) {
      spawnSync('taskkill', ['/PID', String(pid), '/T'], { stdio: 'ignore' });
    } else {
      process.kill(pid, 'SIGTERM');
    }
  } catch {
    return;
  }
  for (let i = 0; i < 20; i++) {
    if (!isPidAlive(pid)) return;
    await delay(500);
  }
  console.log(`[stop] ${label} (PID ${pid}) 超时未退出，强制结束...`);
  try {
    if (IS_WIN) {
      spawnSync('taskkill', ['/F', '/PID', String(pid), '/T'], { stdio: 'ignore' });
    } else {
      process.kill(pid, 'SIGKILL');
    }
  } catch {
    // already gone
  }
}

async function stopCurrentServer({ closeDesktop = false } = {}) {
  const pids = new Set(findListeningPids(PORT));
  if (fs.existsSync(PID_FILE)) {
    const saved = Number(fs.readFileSync(PID_FILE, 'utf8').trim());
    if (isPidAlive(saved)) pids.add(saved);
  }
  for (const pid of pids) {
    await stopPidGracefully(pid, `端口 ${PORT} 服务`);
  }
  for (let i = 0; i < 20; i++) {
    if (findListeningPids(PORT).length === 0) break;
    await delay(500);
  }
  if (closeDesktop) {
    const ep = readDesktopEndpoint();
    if (ep && ep.alive) {
      await stopPidGracefully(ep.pid, '旧 Electron 桌面窗口');
      await delay(500);
    }
  }
}

function buildRunner({ buildFrontend = true } = {}) {
  syncAssets({ skipStatic: buildFrontend });
  if (buildFrontend && !buildFrontendIfAvailable()) {
    syncAssets({ skipStatic: false });
  }
  const env = { ...process.env };
  const javaHome = resolveJavaHome();
  if (javaHome) env.JAVA_HOME = javaHome;
  const cmd = IS_WIN ? 'mvnw.cmd' : 'sh';
  const args = IS_WIN
    ? ['-pl', 'pilot-core,pilot-server', '-am', '-DskipTests', '-DskipFrontend=true', 'package']
    : ['./mvnw', '-pl', 'pilot-core,pilot-server', '-am', '-DskipTests', '-DskipFrontend=true', 'package'];
  console.log(`[build] 在 ${RUNNER_DIR} 构建 runner jar...`);
  runChecked(cmd, args, { cwd: RUNNER_DIR, env });
  const jarPath = path.join(RUNNER_DIR, JAR_REL);
  if (!fs.existsSync(jarPath)) {
    throw new Error(`[build] 未找到打包产物: ${jarPath}`);
  }
  console.log(`[build] 完成: ${jarPath}`);
}

function sanitizeEndpointHost(urlStr) {
  try {
    const u = new URL(urlStr);
    return `${u.protocol}//${u.host}${u.pathname}`;
  } catch {
    return '(invalid-url)';
  }
}

async function startRunner({ dumpRequests = true, desktop = true, forceReopenDesktop = false, allowDogfoodActive = false } = {}) {
  const safety = await checkSafe({ allowDogfoodActive });
  if (!safety.safe) process.exit(2);

  const jarPath = path.join(RUNNER_DIR, JAR_REL);
  if (!fs.existsSync(jarPath)) {
    throw new Error(`未找到 runner jar: ${jarPath}，请先执行 runner-ctl.mjs build`);
  }

  // 确保桌面窗口可执行资源就绪
  syncAssets();
  await stopCurrentServer({ closeDesktop: forceReopenDesktop });

  fs.mkdirSync(DOGFOOD_ROOT, { recursive: true });
  const outFd = fs.openSync(LOG_FILE, 'a');
  fs.writeSync(
    outFd,
    `\n===== runner start ${new Date().toISOString()} (desktop=${desktop}, dump=${dumpRequests}) =====\n`
  );

  const env = { ...process.env };
  const javaHome = resolveJavaHome();
  if (javaHome) env.JAVA_HOME = javaHome;
  env.PILOT_DATA_DIR = DATA_DIR;
  env.PILOT_BROWSER_HOME = path.join(DATA_DIR, 'browser');
  env.PILOT_FRONTEND_DEPS_PROMPT = 'false';
  env.PILOT_DESKTOP_LAUNCH = desktop ? 'true' : 'false';
  env.PILOT_DEBUG_DUMP_MODEL_REQUESTS = dumpRequests ? 'true' : 'false';
  env.PILOT_WORKSPACE = RUNNER_DIR;
  env.SERVER_PORT = String(PORT);

  const child = spawn(javaBinary(), ['-jar', jarPath], {
    cwd: RUNNER_DIR,
    env,
    detached: true,
    stdio: ['ignore', outFd, outFd],
  });
  child.unref();
  fs.closeSync(outFd);
  fs.writeFileSync(PID_FILE, String(child.pid), 'utf8');
  console.log(`[start] 已拉起 runner (PID ${child.pid})，日志: ${LOG_FILE}`);

  // 等待服务就绪并校验设置（严禁输出密钥）
  let settings = null;
  for (let i = 0; i < 60; i++) {
    if (!isPidAlive(child.pid)) {
      throw new Error(`runner 启动中途退出 (PID ${child.pid})，请检查 ${LOG_FILE}`);
    }
    try {
      settings = await fetchJson('/api/settings', 2000);
      break;
    } catch {
      await delay(1000);
    }
  }
  if (!settings) {
    throw new Error(`runner 在 60s 内未就绪，请检查 ${LOG_FILE}`);
  }

  let desktopStatus = 'disabled';
  if (desktop) {
    for (let i = 0; i < 20; i++) {
      const ep = readDesktopEndpoint();
      if (ep && ep.alive) {
        desktopStatus = `alive(pid=${ep.pid}, port=${ep.controlPort})`;
        break;
      }
      await delay(1000);
    }
    if (desktopStatus === 'disabled') desktopStatus = 'not-detected-within-20s';
  }

  const headSha = (runCapture('git', ['-C', RUNNER_DIR, 'rev-parse', '--short', 'HEAD']).stdout || '').trim();
  const report = {
    status: 'READY',
    pid: child.pid,
    port: PORT,
    runnerDir: RUNNER_DIR,
    runnerSha: headSha,
    dataDir: DATA_DIR,
    dumpModelRequests: dumpRequests,
    desktopLaunch: desktop,
    desktopWindow: desktopStatus,
    model: settings.model || settings.modelName || '',
    endpoint: sanitizeEndpointHost(settings.endpoint || settings.modelEndpoint || ''),
    modelApiKeySet: Boolean(settings.apiKeySet ?? settings.modelApiKeySet),
    permissionMode: settings.permissionMode || '',
  };
  console.log(JSON.stringify(report, null, 2));
  if (!report.modelApiKeySet) {
    throw new Error('校验失败：modelApiKeySet 为 false，可能未命中本机 PILOT_DATA_DIR！');
  }
}

async function printStatus() {
  const pids = findListeningPids(PORT);
  const headSha = (runCapture('git', ['-C', RUNNER_DIR, 'rev-parse', '--short', 'HEAD']).stdout || '').trim();
  const ep = readDesktopEndpoint();
  let settings = null;
  try {
    settings = await fetchJson('/api/settings', 2500);
  } catch {
    settings = null;
  }
  console.log(
    JSON.stringify(
      {
        port: PORT,
        listeningPids: pids,
        runnerDir: RUNNER_DIR,
        runnerSha: headSha,
        dataDir: DATA_DIR,
        desktopWindow: ep ? { pid: ep.pid, alive: ep.alive } : null,
        httpReachable: Boolean(settings),
        model: settings ? settings.model || settings.modelName || '' : null,
        endpoint: settings ? sanitizeEndpointHost(settings.endpoint || settings.modelEndpoint || '') : null,
        modelApiKeySet: settings ? Boolean(settings.apiKeySet ?? settings.modelApiKeySet) : null,
      },
      null,
      2
    )
  );
}

function wsCreate(taskId, baseRef = 'df/runner') {
  if (!/^[A-Za-z0-9._-]+$/.test(taskId)) {
    throw new Error(`非法 taskId: ${taskId}`);
  }
  const branch = `df/${taskId}`;
  const wsPath = path.join(DOGFOOD_ROOT, `ws-${taskId}`);
  if (fs.existsSync(wsPath)) {
    throw new Error(`目标 worktree 已存在: ${wsPath}`);
  }
  runChecked('git', ['-C', RUNNER_DIR, 'config', 'extensions.worktreeConfig', 'true']);
  runChecked('git', ['-C', RUNNER_DIR, 'worktree', 'add', '--no-checkout', '-b', branch, wsPath, baseRef]);
  runChecked('git', ['-C', wsPath, 'sparse-checkout', 'init', '--no-cone']);
  runChecked('git', ['-C', wsPath, 'sparse-checkout', 'set', '--no-cone', '/*', '!/evaluation/dogfood/']);
  runChecked('git', ['-C', wsPath, 'checkout', branch]);
  const leaked = path.join(wsPath, 'evaluation', 'dogfood');
  if (fs.existsSync(leaked)) {
    throw new Error(`worktree 隔离校验失败：仍存在 ${leaked}`);
  }
  const feModulesSrc = path.join(MAIN_REPO, 'pilot-web', 'frontend', 'node_modules');
  const feModulesDst = path.join(wsPath, 'pilot-web', 'frontend', 'node_modules');
  if (fs.existsSync(feModulesSrc) && !fs.existsSync(feModulesDst)) {
    cloneOrCopy(feModulesSrc, feModulesDst);
  }
  const baseSha = (runCapture('git', ['-C', wsPath, 'rev-parse', 'HEAD']).stdout || '').trim();
  console.log(JSON.stringify({ taskId, branch, workspace: wsPath, baseSha }, null, 2));
}

function wsRemove(taskId, { keepBranch = false } = {}) {
  if (!/^[A-Za-z0-9._-]+$/.test(taskId)) {
    throw new Error(`非法 taskId: ${taskId}`);
  }
  const branch = `df/${taskId}`;
  const wsPath = path.join(DOGFOOD_ROOT, `ws-${taskId}`);
  if (fs.existsSync(wsPath)) {
    runChecked('git', ['-C', RUNNER_DIR, 'worktree', 'remove', '--force', wsPath]);
  }
  if (!keepBranch) {
    const br = runCapture('git', ['-C', RUNNER_DIR, 'branch', '-D', branch]);
    if (br.status !== 0 && !String(br.stderr).includes('not found')) {
      throw new Error(br.stderr);
    }
  }
  console.log(`[ws-remove] 已清理 ${wsPath}${keepBranch ? '（保留分支）' : ` 与分支 ${branch}`}`);
}

async function main() {
  const args = process.argv.slice(2);
  const cmd = args[0];
  const flags = new Set(args.slice(1).filter((a) => a.startsWith('--')));
  const positional = args.slice(1).filter((a) => !a.startsWith('--'));

  if (!cmd || cmd === '--help' || cmd === '-h') {
    console.log(
      [
        '用法: node runner-ctl.mjs <command> [options]',
        '  sync-assets [--force]                               同步依赖与桌面壳到 runner/',
        '  v1-verify                                           在 runner/ 强制执行全量 V1（pilot-core+pilot-server test + spotless + checkstyle + quality-gate）',
        '  check-safe [--allow-dogfood-active]                 检查是否有非 dogfood: 用户活跃会话',
        '  build [--no-frontend-build]                         构建最新前端并打包 pilot-server jar',
        '  restart [--no-dump] [--no-desktop] [--reopen-desktop] [--allow-dogfood-active]',
        '  rebuild-restart [同上]                              build + restart',
        '  status                                              查看端口、进程、模型设置与桌面窗口状态',
        '  ws-create <taskId> [baseRef]                        创建 ~/pilot-dogfood/ws-<taskId> (分支 df/<taskId>)',
        '  ws-remove <taskId> [--keep-branch]                  清理任务 worktree 与临时分支',
      ].join('\n')
    );
    return;
  }

  switch (cmd) {
    case 'sync-assets':
      syncAssets({ force: flags.has('--force') });
      break;
    case 'v1-verify':
      runV1Verify();
      break;
    case 'check-safe': {
      const r = await checkSafe({ allowDogfoodActive: flags.has('--allow-dogfood-active') });
      if (!r.safe) process.exit(2);
      break;
    }
    case 'build':
      buildRunner({ buildFrontend: !flags.has('--no-frontend-build') });
      break;
    case 'restart':
      await startRunner({
        dumpRequests: !flags.has('--no-dump'),
        desktop: !flags.has('--no-desktop'),
        forceReopenDesktop: flags.has('--reopen-desktop'),
        allowDogfoodActive: flags.has('--allow-dogfood-active'),
      });
      break;
    case 'rebuild-restart':
      buildRunner({ buildFrontend: !flags.has('--no-frontend-build') });
      await startRunner({
        dumpRequests: !flags.has('--no-dump'),
        desktop: !flags.has('--no-desktop'),
        forceReopenDesktop: flags.has('--reopen-desktop'),
        allowDogfoodActive: flags.has('--allow-dogfood-active'),
      });
      break;
    case 'status':
      await printStatus();
      break;
    case 'ws-create':
      if (!positional[0]) throw new Error('缺少 taskId');
      wsCreate(positional[0], positional[1] || 'df/runner');
      break;
    case 'ws-remove':
      if (!positional[0]) throw new Error('缺少 taskId');
      wsRemove(positional[0], { keepBranch: flags.has('--keep-branch') });
      break;
    default:
      throw new Error(`未知命令: ${cmd}`);
  }
}

main().catch((err) => {
  console.error(`[runner-ctl] 错误: ${err.message || err}`);
  process.exit(1);
});
