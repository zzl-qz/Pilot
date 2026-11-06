// 生产入口 + 真实 Jar/SSE + 本地模拟模型；全程独立数据，不使用外部服务或个人会话。
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, realpath, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';
const root = fileURLToPath(new URL('../../../', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-packaged-session-'));
await mkdir(join(output, '中文 空格工作区')); const workspace = await realpath(join(output, '中文 空格工作区'));
const requests = [], held = new Set(); let mode = 'ok', text = '真实 SSE 回归完成。';
const upstream = createServer(async (request, response) => {
  try {
    const chunks = []; for await (const chunk of request) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString() || '{}'); requests.push(body);
    if (mode === 'fail') { response.writeHead(401, { 'content-type': 'application/json' }); response.end(JSON.stringify({ error: { message: 'fixture authentication rejected' } })); return; }
    if (!body.stream) { response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: text }, finish_reason: 'stop' }], usage: { prompt_tokens: 100, completion_tokens: 20 } })); return; }
    response.writeHead(200, { 'content-type': 'text/event-stream' });
    response.write('data: ' + JSON.stringify({ choices: [{ index: 0, delta: { content: text }, finish_reason: null }] }) + '\n\n');
    if (mode === 'hold') { held.add(response); response.once('close', () => held.delete(response)); return; }
    response.end('data: ' + JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } }) + '\n\ndata: [DONE]\n\n');
  } catch (error) { response.writeHead(500); response.end(error.message); }
});
await new Promise(accept => upstream.listen(0, '127.0.0.1', accept));
const modelEndpoint = `http://127.0.0.1:${upstream.address().port}/v1`;
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
let child, browser, page, logs = '';
const browserRequests = [], browserErrors = [];
const report = { platform: process.platform, success: false, cases: [] };
try {
  child = spawn(java, ['--enable-native-access=ALL-UNNAMED', '-jar', resolve(root, 'pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar'),
    '--server.address=127.0.0.1', '--server.port=0', `--pilot.data.dir=${join(output, 'data')}`, `--pilot.agent.workspace=${workspace}`,
    '--pilot.runtime.provision=false', '--pilot.desktop.launch=false', '--pilot.web-search.local=false', '--pilot.browser.enabled=false',
    '--pilot.model.capability-discovery.enabled=false', '--pilot.agent.goal-mode-enabled=false', '--pilot.agent.completion-checks=false',
    '--pilot.agent.permission-mode=READ_ONLY', '--pilot.model.model=fixture-model', '--pilot.model.api-key=fixture-only', `--pilot.model.endpoint=${modelEndpoint}`, '--pilot.security.mode=local'],
  { cwd: output, stdio: ['ignore', 'pipe', 'pipe'] });
  const base = await new Promise((accept, reject) => {
    const timer = setTimeout(() => reject(Error('生产测试服务启动超时')), 30000);
    const capture = chunk => { logs += chunk.toString(); const port = logs.match(/Tomcat started on port (\d+)/)?.[1]; if (port) { clearTimeout(timer); accept(`http://127.0.0.1:${port}`); } };
    child.stdout.on('data', capture); child.stderr.on('data', capture);
    child.once('error', error => { clearTimeout(timer); reject(error); }); child.once('exit', code => { clearTimeout(timer); reject(Error(`服务退出 ${code}`)); });
  });
  browser = await chromium.launch({ channel: 'chrome', headless: true }); page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.setDefaultTimeout(20000); const errors = []; page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (request.url().includes('/api/')) browserRequests.push({ method: request.method(), path: new URL(request.url()).pathname }); });
  page.on('console', message => { if (message.type() === 'error') browserErrors.push(message.text()); });
  await page.goto(base); await page.getByRole('heading', { name: '从一个任务开始', exact: true }).waitFor();
  await page.waitForLoadState('networkidle');
  const input = page.getByRole('textbox', { name: '消息输入框', exact: true });
  await input.fill('请只回复一句测试文字'); await page.locator('#send').click();
  await page.locator('.msg-assistant').filter({ hasText: text }).waitFor();
  await page.waitForFunction(() => document.querySelector('#run-state .run-state-label')?.textContent === '就绪');
  const sessions = await page.evaluate(async () => (await fetch('/api/sessions')).json());
  assert.equal(sessions.length, 1); const id = sessions[0].sessionId || sessions[0].id;
  const history = () => page.evaluate(async id => (await fetch(`/api/sessions/${id}/history?limit=200`)).json(), id);
  const initial = await history(); const types = new Set(initial.events.map(event => event.type));
  for (const type of ['run.started', 'assistant.delta', 'model.request', 'run.usage', 'run.completed']) assert.ok(types.has(type), type);
  assert.ok(requests.every(request => request.stream === true)); assert.equal(requests[0].model, 'fixture-model');
  report.cases.push('真实入口初始化、惰性创建、消息提交、模型流、SSE、用量、完成');
  await page.reload(); await page.locator('.msg-assistant').filter({ hasText: text }).waitFor();
  assert.equal(await page.locator('.msg-user').count(), 1); report.cases.push('刷新后从真实历史重建且不重复消息');
  mode = 'fail'; await input.fill('模拟上游失败'); await page.locator('#send').click();
  await page.locator('.turn-card.is-error').waitFor();
  assert.ok((await history()).events.some(event => event.type === 'run.failed' && event.payload.code === 'MODEL_ERROR'));
  report.cases.push('真实上游 401 转为失败事件与可见错误');
  mode = 'ok'; text = '服务恢复后的真实回复。'; await input.fill('请恢复正常回复'); await page.locator('#send').click();
  await page.locator('.msg-assistant').filter({ hasText: text }).waitFor();
  await page.waitForFunction(() => document.querySelector('#run-state .run-state-label')?.textContent === '就绪'); report.cases.push('失败后再次发送与正常完成');
  mode = 'hold'; text = '等待用户停止的流式内容'; await input.fill('验证正在运行时停止'); await page.locator('#send').click();
  await page.locator('.msg-assistant').filter({ hasText: text }).waitFor();
  await page.waitForFunction(() => document.querySelector('#send')?.getAttribute('aria-label') === '停止当前任务');
  await page.locator('#send').click();
  await page.waitForFunction(async id => (await (await fetch(`/api/sessions/${id}/history?limit=200`)).json()).events.some(event => event.type === 'run.cancelled'), id);
  await page.getByText('运行已取消', { exact: true }).first().waitFor();
  await page.waitForFunction(() => document.querySelector('#send')?.getAttribute('aria-label') === '发送任务'); report.cases.push('真实长流取消、终态与控件复位');
  for (const width of [1440, 768, 390]) { await page.setViewportSize({ width, height: 900 }); assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)); }
  assert.deepEqual(errors, []); await page.screenshot({ path: join(output, 'production-session.png') }); report.success = true;
  console.log('PASS production Jar + real session/SSE:', report.cases.join('；')); console.log('Evidence:', output);
} catch (error) {
  if (page) { await page.screenshot({ path: join(output, 'failure.png') }).catch(() => {}); await writeFile(join(output, 'failure.txt'), await page.locator('body').innerText().catch(() => '')).catch(() => {});
    await writeFile(join(output, 'failure.json'), JSON.stringify({ browserRequests, browserErrors, upstreamRequests: requests.length, controls: await page.locator('#send').evaluate(el => el.outerHTML).catch(() => '') }, null, 2)); }
  throw error;
} finally {
  await browser?.close(); for (const response of held) response.destroy(); upstream.closeAllConnections(); await new Promise(accept => upstream.close(accept));
  if (child && child.exitCode === null) { child.kill(); await new Promise(accept => { const timer = setTimeout(() => { child.kill('SIGKILL'); accept(); }, 5000); child.once('exit', () => { clearTimeout(timer); accept(); }); }); }
  await writeFile(join(output, 'server.log'), logs.replaceAll('fixture-only', '[test-key]')); await writeFile(join(output, 'report.json'), JSON.stringify(report, null, 2));
}
