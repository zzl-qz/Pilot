import assert from 'node:assert/strict';
import { mkdtemp, mkdir, cp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { startIsolatedServer, waitFor } from './smoke-helpers.mjs';

const root = path.resolve(fileURLToPath(new URL('../..', import.meta.url)));
const directory = await mkdtemp(path.join(tmpdir(), 'pilot-environment-中文 with spaces-'));
const libraries = path.join(directory, 'mobile-bridge/lib'); await mkdir(libraries, { recursive: true });
const token = randomUUID();
const env = { ...process.env, PILOT_BOOTSTRAP_DIR: path.join(directory, 'bootstrap'), PILOT_OFFLINE: 'true' }; delete env.PILOT_BOOTSTRAP_PREPARED;
let server;
try {
  server = await startIsolatedServer({
    serverJar: path.join(root, 'pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar'),
    cwd: directory, dataDir: path.join(directory, 'data'), workspace: directory, mobileLibraries: libraries, token, env,
    extraArgs: ['--pilot.mobile.provision=true', '--pilot.mobile.relay=https://127.0.0.1:9', '--pilot.desktop.launch=false', '--pilot.desktop.provision=false', '--pilot.browser.enabled=false'],
  });
  const base = server.baseUrl;
  async function request(route, method = 'GET', extra = {}) {
    const r = await fetch(base + route, { method, headers: { 'X-Pilot-Token': token, 'Content-Type': 'application/json', ...extra }, ...(method === 'POST' ? { body: '{}' } : {}) });
    return { status: r.status, body: await r.json() };
  }
  const waitState = predicate => waitFor(() => request('/api/mobile/connection'), predicate, {
    timeoutMs: 20000, intervalMs: 100,
    onTimeout: last => `环境状态未能收敛（HTTP ${last?.status}，阶段 ${last?.body?.environment?.state || 'unknown'}）`,
  });
  const failed = await waitState(result => result.body?.environment?.state === 'failed');
  assert.equal(failed.status, 200); assert.equal(failed.body.running, false);
  assert.ok(!JSON.stringify(failed.body).includes(token) && !JSON.stringify(failed.body).includes(directory));
  assert.equal((await request('/api/health')).status, 200, '连接能力缺失不能阻止整个应用提供服务');
  assert.equal((await request('/api/mobile/connection/repair', 'POST', { Origin: 'https://evil.example' })).status, 403, '跨来源不能触发下载或系统安装');
  await cp(path.join(root, 'pilot-mobile/bridge/build/install/bridge/lib'), libraries, { recursive: true });
  assert.equal((await request('/api/mobile/connection/repair', 'POST')).status, 200);
  assert.equal((await request('/api/mobile/connection/repair', 'POST')).status, 200);
  const ready = await waitState(result => result.body?.running === true);
  assert.equal(ready.body.version, 2);
  console.log('PASS: 真实后台异步环境检查、缺组件时应用继续运行、无凭证回显、同源修复限制、显式修复后原生验证及连接桥启动；无模型调用，无外网依赖');
} finally {
  await server?.stop();
  await rm(directory, { recursive: true, force: true });
}
