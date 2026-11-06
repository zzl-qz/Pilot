// 隔离 Jar 验收：只用临时目录和随机端口，不调用模型或操作用户会话。
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';
const root = fileURLToPath(new URL('../../../', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-local-security-'));
const workspace = join(output, 'workspace'); await mkdir(workspace);
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const jar = resolve(root, process.env.PILOT_SECURITY_TEST_JAR || 'pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar');
const expectedDenied = process.env.PILOT_SECURITY_EXPECT_VULNERABLE === 'true' ? 204 : 403;
let logs = '', browser, child, attacker;
try {
  child = spawn(java, ['--enable-native-access=ALL-UNNAMED', '-jar', jar, '--server.address=127.0.0.1', '--server.port=0',
    `--pilot.data.dir=${join(output, 'data')}`, `--pilot.agent.workspace=${workspace}`,
    '--pilot.runtime.provision=false', '--pilot.desktop.launch=false', '--pilot.mobile.mode=direct',
    '--pilot.web-search.local=false', '--pilot.model.api-key=', '--pilot.model.capability-discovery.enabled=false',
    '--pilot.model.endpoint=http://127.0.0.1:9/v1/chat/completions', '--pilot.security.mode=local'],
    { cwd: output, stdio: ['ignore', 'pipe', 'pipe'] });
  const base = await new Promise((accept, reject) => {
    const timeout = setTimeout(() => reject(new Error('测试服务启动超时')), 30000);
    const capture = chunk => {
      logs += chunk.toString();
      const match = logs.match(/Tomcat started on port (\d+)/);
      if (match) { clearTimeout(timeout); accept(`http://127.0.0.1:${match[1]}`); }
    };
    child.stdout.on('data', capture); child.stderr.on('data', capture);
    child.once('error', error => { clearTimeout(timeout); reject(error); });
    child.once('exit', code => { clearTimeout(timeout); reject(new Error(`测试服务退出 ${code}`)); });
  });
  // 命令行无 Origin、原生同源页面都保持可用。
  assert.equal((await fetch(base + '/api/sessions')).status, 200);
  browser = await chromium.launch({ channel: 'chrome', headless: true,
    args: ['--no-proxy-server', '--host-resolver-rules=MAP rebound.test 127.0.0.1'] });
  const page = await browser.newPage();
  await page.goto(base);
  assert.equal(await page.evaluate(async () => (await fetch('/api/access/login', { method: 'POST' })).status), 204);
  assert.equal(await page.evaluate(async () => (await fetch('/api/sessions')).status), 200);
  // 真实浏览器发出无预检的跨源 POST。Playwright 检查线上的 HTTP 响应，而非只检查 JS 的 CORS 错误。
  attacker = createServer((request, response) => { response.writeHead(200, { 'content-type': 'text/html' }); response.end('<!doctype html><title>Cross-origin fixture</title>'); });
  await new Promise(done => attacker.listen(0, '127.0.0.1', done));
  const attackBase = `http://127.0.0.1:${attacker.address().port}`;
  await page.goto(attackBase);
  const response = page.waitForResponse(r => r.url() === base + '/api/access/login' && r.request().method() === 'POST');
  await page.evaluate(url => fetch(url, { method: 'POST', mode: 'no-cors', body: 'fixture' }).then(() => true), base + '/api/access/login');
  const crossOriginStatus = (await response).status();
  assert.equal(crossOriginStatus, expectedDenied);
  // 模拟攻击者域名解析到回环：浏览器实际发送陌生 Host。请求本身是同源，CORS 不能代替 Host 检查。
  const rebound = base.replace('127.0.0.1', 'rebound.test');
  const reboundStatus = (await page.goto(rebound + '/api/sessions')).status();
  assert.equal(reboundStatus, expectedDenied === 204 ? 200 : 403);
  const report = { mode: expectedDenied === 204 ? 'vulnerability-reproduced' : 'fixed',
    cli: 200, sameOrigin: 204, crossOriginPost: crossOriginStatus, reboundHost: reboundStatus };
  await writeFile(join(output, 'result.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
  console.log('Evidence:', output);
} finally {
  await browser?.close();
  if (attacker) await new Promise(done => attacker.close(done));
  if (child && child.exitCode === null) {
    child.kill();
    await new Promise(done => { const timer = setTimeout(() => { child.kill('SIGKILL'); done(); }, 5000); child.once('exit', () => { clearTimeout(timer); done(); }); });
  }
  await writeFile(join(output, 'server.log'), logs);
}
