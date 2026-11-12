import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { prepareDevelopmentHost } from './brand-host.mjs';

// 真实桌面壳 + 真实 sidecar，独立端点、用户目录和本地页面，不连接用户工作台。
const root = fileURLToPath(new URL('.', import.meta.url));
const { _electron } = createRequire(new URL('../pilot-web/frontend/package.json', import.meta.url))('playwright-core');
const temp = await mkdtemp(join(tmpdir(), 'pilot-browser-viewport-'));
const probe = createServer();
await new Promise(resolve => probe.listen(0, '127.0.0.1', resolve));
const cdpPort = probe.address().port;
await new Promise(resolve => probe.close(resolve));
const site = createServer((_req, res) => {
  res.writeHead(200, { 'content-type': 'text/html;charset=utf-8' });
  res.end('<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><h1>视口验证</h1><button id="test">可见按钮</button><script>console.error("viewport-fixture")</script>');
});
await new Promise(resolve => site.listen(0, '127.0.0.1', resolve));
const env = { ...process.env, PILOT_DESKTOP_SMOKE: '1', PILOT_BROWSER_HOME: temp,
  PILOT_DESKTOP_CDP_PORT: String(cdpPort) };
delete env.ELECTRON_RUN_AS_NODE;
let desktop, sidecar;
const pending = new Map();
let sequence = 0;
function command(session, op, args = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => { pending.delete(id); reject(Error(`Timeout ${op}`)); }, 30000);
    pending.set(id, { resolve, reject, timer });
    sidecar.stdin.write(JSON.stringify({ id, session, op, ...args }) + '\n');
  });
}
try {
  desktop = await _electron.launch({ executablePath: prepareDevelopmentHost(),
    args: [root, `--user-data-dir=${join(temp, 'profile')}`], env });
  await desktop.firstWindow();
  const info = JSON.parse(await readFile(join(temp, 'desktop-browser.json'), 'utf8'));
  const control = async (path, body) => fetch(`http://127.0.0.1:${info.controlPort}${path}`, {
    method: body ? 'POST' : 'GET', headers: { 'content-type': 'application/json', 'x-pilot-token': info.token },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(5000)
  });
  sidecar = spawn(process.execPath, [fileURLToPath(new URL('../pilot-core/src/main/resources/browser/browser-sidecar.mjs', import.meta.url))], {
    cwd: temp, env: { ...env, NODE_PATH: join(root, '../pilot-web/frontend/node_modules'),
      PILOT_DESKTOP_ENDPOINT: join(temp, 'desktop-browser.json') }, stdio: ['pipe', 'pipe', 'pipe']
  });
  let stderr = '';
  sidecar.stderr.on('data', chunk => { stderr += chunk; });
  sidecar.on('exit', code => {
    for (const { reject, timer } of pending.values()) { clearTimeout(timer); reject(Error(`Sidecar exit ${code}: ${stderr}`)); }
    pending.clear();
  });
  createInterface({ input: sidecar.stdout }).on('line', line => {
    const result = JSON.parse(line), request = pending.get(result.id);
    if (!request) return;
    clearTimeout(request.timer);pending.delete(result.id);
    if (result.ok) request.resolve(result); else request.reject(Error(result.error));
  });
  const url = `http://127.0.0.1:${site.address().port}/`;
  await command('A', 'navigate', { url });
  const initial = JSON.parse((await command('A', 'inspect')).text);
  assert.equal(initial.viewport.width, 1280, '隐藏标签首次打开即有桌面视口');
  await command('A', 'screenshot', { path: join(temp, 'hidden-first.png') });
  assert.match((await command('A', 'console')).text, /viewport-fixture/, '首次桌面页面确实监听控制台');
  await command('B', 'navigate', { url });
  const state = await (await control('/tabs')).json();
  const a = state.tabs.find(tab => tab.sessionId === 'A');
  const b = state.tabs.find(tab => tab.sessionId === 'B');
  assert(a && b);
  for (const width of [1440, 768, 390]) {
    await command('A', 'resize', { width, height: 840 });
    const inspected = JSON.parse((await command('A', 'inspect', { selector: '#test' })).text);
    assert.equal(inspected.viewport.width, width);
    assert.equal(inspected.viewport.height, 840);
    assert(inspected.element.rect.width > 0);
    await command('A', 'screenshot', { path: join(temp, `desktop-${width}.png`) });
    assert.equal(JSON.parse((await command('B', 'inspect')).text).viewport.width, 1280,
      '非活动会话调整视口不能影响活动会话');
  }
  await assert.rejects(command('A', 'switch_tab', { tabId: b.id }), /不属于当前会话/);
  await assert.rejects(command('A', 'close_tab', { tabId: b.id }), /不属于当前会话/);
  await command('A', 'console', { clear: true });
  await command('A', 'navigate', { url });
  assert.match((await command('A', 'console')).text, /viewport-fixture/, '新标签也监听控制台');
  const opened = await (await control('/tabs')).json();
  const nextA = opened.tabs.find(tab => tab.sessionId === 'A' && tab.id !== a.id);
  assert(nextA && nextA.targetId !== a.targetId, '同 URL 使用不同 TargetID');
  await command('A', 'resize', { width: 1440, height: 840 });
  await command('A', 'switch_tab', { tabId: a.id });
  assert.equal(JSON.parse((await command('A', 'inspect')).text).viewport.width, 390, '切回同地址旧标签必须读取旧页面');
  await command('A', 'resize', { width: 768, height: 840 });
  const switched = await (await control('/tabs')).json();
  assert.equal(switched.tabs.find(tab => tab.id === a.id).emulated.width, 768);
  assert.equal(switched.tabs.find(tab => tab.id === nextA.id).emulated.width, 1440);
  assert.equal((await control('/emulate', { sessionId: 'missing', width: 0, height: 0 })).status, 404);
  assert.equal((await control('/emulate', { sessionId: 'A', width: 0, height: 0 })).status, 200);
  const after = await (await control('/tabs')).json();
  assert.equal(after.tabs.find(tab => tab.id === a.id).emulated, null);
  assert.equal(after.tabs.find(tab => tab.id === nextA.id).emulated, null, '完成会话收回其全部标签视口');
  assert.deepEqual(after.tabs.find(tab => tab.id === b.id).emulated, { width: 1280, height: 800 },
    '完成 A 只收回 A 的模拟视口');
  console.log('PASS 首次隐藏截图 / 三种视口 / 只读布局诊断 / 两会话隔离 / 同地址多标签 / 切换后调整 / 控制台监听 / 全标签恢复');
  console.log('Screenshots:', temp);
} finally {
  sidecar?.kill();
  await desktop?.close();
  await new Promise(resolve => site.close(resolve));
}
