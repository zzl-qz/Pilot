import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startBudgetProxy, configFromEnv } from './proxy.mjs';

async function setup(t, overrides = {}) {
  const state = { total: 1000, queries: 0, chats: 0, failUsage: false, zeroCharge: false,
    charge: 100, body: null, headers: null, chatStatus: 200, chatHook: null, usageHook: null, redirect: false, otherVisits: 0, responseHook: null, invalidUsage: false };
  const upstream = http.createServer(async (req, res) => {
    if (req.url === '/api/usage/token/') {
      state.queries++;
      if (state.usageHook) await state.usageHook();
      if (state.failUsage) { res.writeHead(503); res.end('unavailable'); return; }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(state.invalidUsage ? 'SECRET_BODY_DO_NOT_LOG' : JSON.stringify({ data: { total_used: state.total } })); return;
    }
    if (req.url !== '/v1/chat/completions') { state.otherVisits++; res.writeHead(404); res.end(); return; }
    state.chats++;
    let text = ''; for await (const chunk of req) text += chunk;
    state.body = JSON.parse(text); state.headers = req.headers;
    if (state.chatHook) await state.chatHook();
    if (!state.zeroCharge) state.total += state.charge;
    if (state.redirect) { res.writeHead(302, { location: '/redirect-target' }); res.end(); return; }
    if (state.responseHook) { await state.responseHook(req, res); return; }
    res.writeHead(state.chatStatus, { 'content-type': 'text/event-stream' });
    res.write('data: {"choices":[{"delta":{"content":"hello"}}]}\n\n');
    res.end('data: [DONE]\n\n');
  });
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { upstream.close(resolve); upstream.closeAllConnections(); }));
  const logs = [];
  const proxy = await startBudgetProxy({ upstream: `http://127.0.0.1:${upstream.address().port}/ignored-base`,
    apiKey: 'FAKE_UPSTREAM_KEY', localToken: 'FAKE_LOCAL_TOKEN', port: 0, maxQuota: 100000,
    inputQuotaPerMillion: 1000000, outputQuotaPerMillion: 1000000,
    inputMarginTokens: 1024, maxOutputTokens: 4096, timeoutMs: 2000, postQueryDelayMs: 1,
    log: row => logs.push(row), ...overrides });
  t.after(() => proxy.close());
  const url = `http://127.0.0.1:${proxy.port}`;
  const headers = { authorization: 'Bearer FAKE_LOCAL_TOKEN', 'content-type': 'application/json' };
  const post = (body = { model: 'mock-model', messages: [{ role: 'user', content: 'mock input' }], stream: true }, extra = {}) =>
    fetch(`${url}/v1/chat/completions`, { method: 'POST', headers: { ...headers, cookie: 'DO_NOT_FORWARD' }, body: JSON.stringify(body), ...extra });
  const budget = async () => (await fetch(`${url}/budget`, { headers })).json();
  return { state, proxy, logs, url, headers, post, budget };
}

async function consume(response) { try { return await response.text(); } catch { return 'stream_aborted'; } }

test('缓冲后保持SSE原样、固定同源路径、输出cap和实际额度更新，不转发cookie或本地token', async t => {
  const { state, proxy, logs, post, budget } = await setup(t);
  const response = await post({ model: 'mock-model', messages: [{ role: 'user', content: '中文' }], stream: true,
    max_tokens: 99999, max_completion_tokens: 99999 });
  assert.equal(response.status, 200);
  assert.equal(await response.text(), 'data: {"choices":[{"delta":{"content":"hello"}}]}\n\ndata: [DONE]\n\n');
  assert.equal(state.body.max_completion_tokens, 4096); assert.equal(state.body.max_tokens, undefined);
  assert.equal(state.headers.authorization, 'Bearer FAKE_UPSTREAM_KEY'); assert.equal(state.headers.cookie, undefined);
  assert.deepEqual(await budget(), { initialUsed: 1000, lastUsed: 1100, spent: 100, maxQuota: 100000,
    remaining: 99900, locked: false, busy: false, requests: 1 });
  assert.equal(proxy.server.address().address, '127.0.0.1');
  assert.ok(logs.every(row => Object.keys(row).sort().join(',') === 'elapsedMs,reserved,status,used'));
  assert.equal(JSON.stringify(logs).includes('FAKE'), false); assert.equal(JSON.stringify(logs).includes('mock input'), false);
});

test('余额不能覆盖预留则402，未发送上游chat', async t => {
  const { state, post, budget } = await setup(t, { maxQuota: 100 });
  const response = await post(); assert.equal(response.status, 402); await consume(response);
  assert.equal(state.chats, 0); assert.equal((await budget()).requests, 0);
});

test('单并发：在途第二个请求直接429，不排队或转发', async t => {
  const { state, post } = await setup(t);
  let entered, release;
  const started = new Promise(resolve => { entered = resolve; });
  const blocked = new Promise(resolve => { release = resolve; });
  state.chatHook = async () => { entered(); await blocked; };
  const first = post(); await started;
  const second = await post(); assert.equal(second.status, 429); await consume(second);
  assert.equal(state.chats, 1);
  release(); assert.equal((await first).status, 200); await consume(await first);
});

test('请求前用量查询失败锁死；恢复接口也不会自动继续消费', async t => {
  const { state, post, budget } = await setup(t);
  state.failUsage = true;
  assert.equal((await post()).status, 503); assert.equal(state.chats, 0);
  state.failUsage = false;
  assert.equal((await post()).status, 503); assert.equal((await budget()).locked, true);
});

test('额度倒退及两次请求间发生其他扣费都锁死', async t => {
  for (const total of [999, 1001]) {
    await t.test(String(total), async t => {
      const { state, post, budget } = await setup(t); state.total = total;
      assert.equal((await post()).status, 503); assert.equal(state.chats, 0);
      assert.equal((await budget()).locked, true);
    });
  }
});

test('每次实际扣费更新；累计加预留超过预算时拒绝后续', async t => {
  const { state, post, budget } = await setup(t, { maxQuota: 6000 });
  state.charge = 1000;
  const first = await post(); assert.equal(first.status, 200); await first.text();
  assert.equal((await budget()).spent, 1000);
  assert.equal((await post()).status, 402); assert.equal(state.chats, 1);
});

test('上游HTTP报错后即使扣费已确认也锁死', async t => {
  const { state, post, budget } = await setup(t); state.chatStatus = 500;
  const first = await post(); assert.equal(first.status, 500); await consume(first);
  const accounting = await budget(); assert.equal(accounting.spent, 100); assert.equal(accounting.locked, true);
  assert.equal((await post()).status, 503); assert.equal(state.chats, 1);
});

test('实际扣费超过预留锁死，即使总预算尚未耗尽', async t => {
  const { state, post, budget } = await setup(t); state.charge = 20000;
  await consume(await post());
  assert.equal((await budget()).locked, true); assert.equal((await budget()).spent, 20000);
  assert.equal((await post()).status, 503); assert.equal(state.chats, 1);
});

test('迟延记账最多查3次；始终零增量锁死，不能当免费请求', async t => {
  const { state, post, budget } = await setup(t); state.zeroCharge = true;
  await consume(await post());
  assert.equal(state.queries, 5, '初始化1次+请求前1次+请求后3次');
  assert.equal((await budget()).locked, true); assert.equal((await post()).status, 503);
});

test('迟延记账在第三次确认可继续，并使用实测额度', async t => {
  const { state, post, budget } = await setup(t); state.zeroCharge = true;
  state.usageHook = async () => { if (state.queries === 5) state.total = 1050; };
  assert.equal((await consume(await post())).includes('[DONE]'), true);
  assert.equal((await budget()).spent, 50); assert.equal((await budget()).locked, false);
});

test('请求后usage失败也锁死，最终异常不会悄悄重试chat', async t => {
  const { state, post, budget } = await setup(t);
  state.chatHook = async () => { state.failUsage = true; };
  await consume(await post()); assert.equal((await budget()).locked, true);
  assert.equal((await post()).status, 503); assert.equal(state.chats, 1);
});

test('本地auth、只读budget及路径限制，不接受远端多模态/多候选', async t => {
  const { state, url, headers, post, budget } = await setup(t);
  assert.equal((await fetch(`${url}/budget`)).status, 401);
  assert.equal((await fetch(`${url}/v1/models`, { headers })).status, 404);
  const count = state.queries; await budget(); assert.equal(state.queries, count, '读budget不查询远端');
  assert.equal((await post({ messages: [{ role: 'user', content: [{ type: 'image_url', image_url: { url: 'https://example.invalid/a.png' } }] }] })).status, 400);
  assert.equal((await post({ messages: [{ role: 'user', content: 'hello' }], n: 2 })).status, 400);
  assert.equal(state.chats, 0);
});

test('输出限额128–4096且转发timeout不超过90秒', () => {
  const env = { PILOT_EVAL_UPSTREAM: 'http://127.0.0.1', PILOT_EVAL_API_KEY: 'FAKE',
    PILOT_EVAL_MAX_QUOTA: '1', PILOT_EVAL_INPUT_QUOTA_PER_MILLION: '1', PILOT_EVAL_OUTPUT_QUOTA_PER_MILLION: '1' };
  assert.equal(configFromEnv(env).maxOutputTokens, 4096);
  assert.equal(configFromEnv(env).inputMarginTokens, 16384);
  assert.equal(configFromEnv({ ...env, PILOT_EVAL_INPUT_MARGIN_TOKENS: '32768' }).inputMarginTokens, 32768);
  assert.throws(() => configFromEnv({ ...env, PILOT_EVAL_INPUT_MARGIN_TOKENS: '0' }));
  assert.equal(configFromEnv({ ...env, PILOT_EVAL_MAX_OUTPUT_TOKENS: '128' }).maxOutputTokens, 128);
  assert.throws(() => configFromEnv({ ...env, PILOT_EVAL_MAX_OUTPUT_TOKENS: '4097' }));
  assert.throws(() => configFromEnv({ ...env, PILOT_EVAL_PROXY_TIMEOUT_MS: '90001' }));
});


test('不跟随上游重定向，失败后锁死且只确认扣费', async t => {
  const { state, post, budget } = await setup(t); state.redirect = true;
  const response = await post(); assert.equal(response.status, 503); await consume(response);
  assert.equal(state.otherVisits, 0); assert.equal(state.chats, 1);
  assert.equal((await budget()).locked, true); assert.equal((await budget()).spent, 100);
});

test('转发超时后锁死，不在未知扣费状态下继续请求', async t => {
  const { state, post, budget } = await setup(t, { timeoutMs: 30 });
  state.chatHook = async () => { await new Promise(resolve => setTimeout(resolve, 150)); };
  const response = await post(); assert.equal(response.status, 503); await consume(response);
  assert.equal((await budget()).locked, true);
  assert.equal((await post()).status, 503); assert.equal(state.chats, 1);
});

test('启动时无法读取真实额度就不开放代理端口', async t => {
  const upstream = http.createServer((_req, res) => { res.writeHead(503); res.end(); });
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { upstream.close(resolve); upstream.closeAllConnections(); }));
  await assert.rejects(startBudgetProxy({ upstream: `http://127.0.0.1:${upstream.address().port}`,
    apiKey: 'FAKE_KEY', port: 0, maxQuota: 10000, inputQuotaPerMillion: 1000000,
    outputQuotaPerMillion: 1000000, maxOutputTokens: 4096 }), /usage_unavailable/);
});


test('核账确认前不交付终态；客户端收到DONE立即下一请求不会busy', async t => {
  const { state, post, budget } = await setup(t);
  let entered, release;
  const waiting = new Promise(resolve => { entered = resolve; });
  const paused = new Promise(resolve => { release = resolve; });
  state.usageHook = async () => { if (state.queries === 3) { entered(); await paused; } };
  let received = false;
  const first = post().then(response => { received = true; return response; });
  await waiting;
  assert.equal(received, false, '完整SSE不得在核账前发给客户端');
  assert.equal((await budget()).busy, true);
  release();
  const response = await first;
  assert.equal(response.status, 200); assert.ok((await response.text()).includes('[DONE]'));
  const next = await post(); assert.equal(next.status, 200); await next.text();
  assert.equal(state.chats, 2); assert.equal((await budget()).spent, 200);
});

test('核账无法确认时不泄露成功SSE或DONE', async t => {
  const { state, post, budget } = await setup(t); state.zeroCharge = true;
  const response = await post(); assert.equal(response.status, 503);
  assert.equal((await response.text()).includes('[DONE]'), false);
  assert.equal((await budget()).locked, true);
});

test('上游响应超过8MiB锁死并拒绝透传', async t => {
  const { state, post, budget } = await setup(t);
  state.responseHook = async (_req, res) => {
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    res.end('x'.repeat(8 * 1024 * 1024 + 1));
  };
  const response = await post(); assert.equal(response.status, 503);
  assert.ok((await response.text()).length < 500); assert.equal((await budget()).locked, true);
});

test('响应头已到但body超时，不能将不完整成功响应交给客户端', async t => {
  const { state, post, budget } = await setup(t, { timeoutMs: 30 });
  state.responseHook = async (_req, res) => {
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    res.write('data: partial\n\n');
    await new Promise(resolve => setTimeout(resolve, 100)); res.end('data: [DONE]\n\n');
  };
  const response = await post(); assert.equal(response.status, 503);
  assert.equal((await response.text()).includes('partial'), false);
  assert.equal((await budget()).locked, true);
});


test('CLI通过符号链接路径启动真实子进程，并可读取本地budget端点', async t => {
  const upstream = http.createServer((req, res) => {
    assert.equal(req.url, '/api/usage/token/');
    assert.equal(req.headers.authorization, 'Bearer MOCK_UPSTREAM_ONLY');
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ data: { total_used: 321 } }));
  });
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { upstream.close(resolve); upstream.closeAllConnections(); }));
  const directory = await mkdtemp(join(tmpdir(), 'pilot-proxy-launch-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const entry = join(directory, 'linked-proxy.mjs');
  await symlink(fileURLToPath(new URL('./proxy.mjs', import.meta.url)), entry, 'file');
  const portProbe = http.createServer();
  await new Promise(resolve => portProbe.listen(0, '127.0.0.1', resolve));
  const port = portProbe.address().port;
  await new Promise(resolve => portProbe.close(resolve));
  const child = spawn(process.execPath, [entry], { stdio: ['ignore', 'pipe', 'pipe'], env: {
    PILOT_EVAL_UPSTREAM: `http://127.0.0.1:${upstream.address().port}`,
    PILOT_EVAL_API_KEY: 'MOCK_UPSTREAM_ONLY', PILOT_EVAL_LOCAL_TOKEN: 'MOCK_LOCAL_ONLY',
    PILOT_EVAL_PROXY_PORT: String(port), PILOT_EVAL_MAX_QUOTA: '100000',
    PILOT_EVAL_INPUT_QUOTA_PER_MILLION: '1000000', PILOT_EVAL_OUTPUT_QUOTA_PER_MILLION: '1000000'
  } });
  t.after(async () => {
    if (child.exitCode !== null || child.signalCode !== null) return;
    await new Promise(resolve => { child.once('exit', resolve); child.kill(); });
  });
  let stderr = ''; child.stderr.on('data', data => { stderr += data.toString(); });
  await new Promise((resolve, reject) => {
    let stdout = '';
    const cleanup = () => { clearTimeout(timer); child.stdout.off('data', onData); child.off('error', onError); child.off('exit', onExit); };
    const onData = data => { stdout += data.toString(); if (stdout.includes('"status":"ready"')) { cleanup(); resolve(); } };
    const onError = error => { cleanup(); reject(error); };
    const onExit = code => { cleanup(); reject(new Error(`Proxy exited before ready: ${code}; ${stderr}`)); };
    const timer = setTimeout(() => { cleanup(); reject(new Error('Proxy startup timed out')); }, 5000);
    child.stdout.on('data', onData); child.once('error', onError); child.once('exit', onExit);
  });
  const response = await fetch(`http://127.0.0.1:${port}/budget`, {
    headers: { authorization: 'Bearer MOCK_LOCAL_ONLY' }, signal: AbortSignal.timeout(2000)
  });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { initialUsed: 321, lastUsed: 321, spent: 0,
    maxQuota: 100000, remaining: 100000, locked: false, busy: false, requests: 0 });
  assert.equal(stderr, '');
});


test('上游在响应头前或响应体中断流均锁死，并记录准确失败阶段', async t => {
  for (const stage of ['upstream_headers', 'upstream_body']) {
    await t.test(stage, async t => {
      const { state, post, budget, logs } = await setup(t);
      state.responseHook = async (_req, res) => {
        if (stage === 'upstream_body') {
          res.writeHead(200, { 'content-type': 'text/event-stream' });
          res.write('data: SECRET_BODY_DO_NOT_LOG\n\n');
          await new Promise(resolve => setTimeout(resolve, 15));
        }
        res.destroy();
      };
      const response = await post(); assert.equal(response.status, 503); await response.text();
      assert.equal((await budget()).locked, true);
      assert.equal((await post()).status, 503); assert.equal(state.chats, 1);
      const error = logs.find(row => row.status === 'request_failed');
      assert.equal(error.stage, stage); assert.equal(error.errorName, 'TypeError');
      assert.equal(error.causeCode, 'UND_ERR_SOCKET');
      assert.equal(JSON.stringify(logs).includes('SECRET_BODY'), false);
      assert.equal(JSON.stringify(logs).includes('FAKE_'), false);
    });
  }
});

test('用量查询错误区分pre/post阶段，JSON解析错误不会把响应内容写入日志', async t => {
  const { state, post, budget, logs } = await setup(t);
  state.usageHook = async () => { if (state.queries >= 3) state.invalidUsage = true; };
  assert.equal((await post()).status, 503); assert.equal((await budget()).locked, true);
  const error = logs.find(row => row.status === 'request_failed');
  assert.equal(error.stage, 'post_usage'); assert.equal(error.errorName, 'SyntaxError');
  assert.equal(error.causeCode, 'UNKNOWN');
  assert.equal(JSON.stringify(logs).includes('SECRET_BODY'), false);
  assert.equal(JSON.stringify(logs).includes('FAKE_'), false);
});

test('请求前usage失败日志含pre_usage而不记录响应正文', async t => {
  const { state, post, logs } = await setup(t); state.failUsage = true;
  assert.equal((await post()).status, 503);
  const error = logs.find(row => row.status === 'usage_unavailable');
  assert.equal(error.stage, 'pre_usage'); assert.equal(error.errorName, 'Error');
  assert.equal(error.causeCode, 'USAGE_UNAVAILABLE');
});
