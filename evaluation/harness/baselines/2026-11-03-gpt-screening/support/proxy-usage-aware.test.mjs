import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startBudgetProxy, configFromEnv, reportedUsage } from './proxy-usage-aware.mjs';

async function setup(t, overrides = {}) {
  const state = { total: 1000, queries: 0, chats: 0, failUsage: false, zeroCharge: false,
    charge: 100, body: null, headers: null, chatStatus: 200, chatHook: null, usageHook: null, redirect: false, otherVisits: 0, responseHook: null, invalidUsage: false, usageStatus: 200, retryAfter: '251' };
  const upstream = http.createServer(async (req, res) => {
    if (req.url === '/api/usage/token/') {
      state.queries++;
      if (state.usageHook) await state.usageHook();
      if (state.usageStatus === 429) { res.writeHead(429, { 'retry-after': state.retryAfter }); res.end(); return; }
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
    inputMarginTokens: 1024, maxOutputTokens: 4096, timeoutMs: 2000, usageIntervalMs: 60000,
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

const waitDue = async budget => { const ms = (await budget()).nextUsageAt - Date.now() + 3; await new Promise(resolve => setTimeout(resolve, Math.max(0, ms))); };
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
  await symlink(fileURLToPath(new URL('./proxy-usage-aware.mjs', import.meta.url)), entry, 'file');
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
  const state = await response.json();
  assert.equal(state.initialUsed, 321); assert.equal(state.lastUsed, 321);
  assert.equal(state.pendingReserved, 0); assert.equal(state.remaining, 100000);
  assert.equal(state.locked, false); assert.equal(state.requests, 0);
  assert.ok(state.nextUsageAt > Date.now());
  assert.equal(stderr, '');
});



test('SSE字节/输出cap保持，auth隔离，成功请求整笔预留占预算而非即时核账', async t => {
  const { state, post, budget, proxy, logs } = await setup(t);
  const response = await post({ model: 'mock', messages: [{ role: 'user', content: '中文' }], stream: true, max_tokens: 99999 });
  assert.equal(response.status, 200);
  assert.equal(await response.text(), 'data: {"choices":[{"delta":{"content":"hello"}}]}\n\ndata: [DONE]\n\n');
  assert.equal(state.body.max_completion_tokens, 4096); assert.equal(state.body.max_tokens, undefined);
  assert.equal(state.headers.authorization, 'Bearer FAKE_UPSTREAM_KEY'); assert.equal(state.headers.cookie, undefined);
  const b = await budget(); assert.equal(b.initialUsed, 1000); assert.equal(b.lastUsed, 1000); assert.equal(b.spent, 0);
  assert.ok(b.pendingReserved > 0); assert.equal(b.remaining, 100000 - b.pendingReserved); assert.equal(b.busy, false);
  assert.equal(state.queries, 1); assert.equal(proxy.server.address().address, '127.0.0.1');
  assert.equal(JSON.stringify(logs).includes('FAKE'), false);
  const next = await post(); assert.equal(next.status, 200); await next.text();
});

test('未到查询间隔连续请求不打额度API，到期只查一次且不释放过量预留', async t => {
  const { state, post, budget } = await setup(t, { usageIntervalMs: 100 });
  await consume(await post()); const one = await budget();
  await consume(await post()); await consume(await post());
  const three = await budget(); assert.equal(state.queries, 1); assert.equal(three.pendingReserved, one.pendingReserved * 3);
  await waitDue(budget); await consume(await post());
  const four = await budget(); assert.equal(state.queries, 2); assert.equal(four.spent, 300);
  assert.equal(four.spent + four.pendingReserved, one.pendingReserved * 4);
  assert.equal(four.remaining, 100000 - one.pendingReserved * 4);
});

test('初始余额不足直接402，不支付也不追加额度查询', async t => {
  const { state, post, budget } = await setup(t, { maxQuota: 100 });
  assert.equal((await post()).status, 402); assert.equal(state.queries, 1); assert.equal(state.chats, 0);
  assert.equal((await budget()).pendingReserved, 0);
});

test('未清算debt使余额不足时等到due再查，仅实际增量冲账仍不足则402', async t => {
  const { state, post, budget } = await setup(t, { maxQuota: 6000, usageIntervalMs: 50 });
  await consume(await post()); const before = await budget();
  assert.ok(before.pendingReserved > 5000);
  const response = await post(); assert.equal(response.status, 402);
  const after = await budget(); assert.equal(state.chats, 1); assert.equal(state.queries, 2);
  assert.equal(after.spent, 100); assert.equal(after.pendingReserved, before.pendingReserved - 100);
  assert.equal(after.remaining, before.remaining);
});

test('等待查询间隔和90秒上游期限分离，等待期间仍单并发', async t => {
  const { state, post, budget, logs } = await setup(t, { maxQuota: 6000, usageIntervalMs: 100, timeoutMs: 30 });
  await consume(await post());
  const waiting = post();
  while (!logs.some(row => row.status === 'waiting_for_accounting')) await new Promise(resolve => setTimeout(resolve, 1));
  assert.equal((await post()).status, 429);
  assert.equal((await waiting).status, 402); assert.equal((await budget()).locked, false);
  assert.equal(state.chats, 1);
});

test('查询失败立刻锁死，不重试查询或继续付费', async t => {
  const { state, post, budget, logs } = await setup(t, { usageIntervalMs: 10 });
  state.failUsage = true; await waitDue(budget);
  assert.equal((await post()).status, 503); state.failUsage = false;
  assert.equal((await post()).status, 503); assert.equal(state.queries, 2); assert.equal(state.chats, 0);
  assert.equal((await budget()).locked, true);
  const error = logs.find(row => row.status === 'request_failed');
  assert.equal(error.stage, 'pre_usage'); assert.equal(error.causeCode, 'USAGE_UNAVAILABLE');
});

test('429记录Retry-After冷却并锁死，无重试或绕过限流', async t => {
  const { state, post, budget, logs } = await setup(t, { usageIntervalMs: 10 });
  state.usageStatus = 429; await waitDue(budget); const now = Date.now();
  assert.equal((await post()).status, 503);
  const b = await budget(); assert.equal(b.locked, true); assert.ok(b.rateLimitedUntil >= now + 251000);
  assert.equal(logs.find(row => row.causeCode === 'USAGE_RATE_LIMITED').retryAfterSeconds, 251);
  state.usageStatus = 200; assert.equal((await post()).status, 503);
  assert.equal(state.queries, 2); assert.equal(state.chats, 0);
});

test('额度倒退或未被预留覆盖的扣费都锁死', async t => {
  for (const total of [999, 1001]) {
    await t.test(String(total), async t => {
      const { state, post, budget } = await setup(t, { usageIntervalMs: 10 });
      state.total = total; await waitDue(budget);
      assert.equal((await post()).status, 503); assert.equal(state.chats, 0); assert.equal((await budget()).locked, true);
    });
  }
});

test('到期查出实际扣费超过预留锁死，不继续第二个chat', async t => {
  const { state, post, budget } = await setup(t, { usageIntervalMs: 10 }); state.charge = 20000;
  await consume(await post()); await waitDue(budget);
  assert.equal((await post()).status, 503); assert.equal(state.chats, 1);
  assert.equal((await budget()).spent, 20000); assert.equal((await budget()).locked, true);
});

test('延迟或零扣费不视作免费，未证实debt持续占预算', async t => {
  const { state, post, budget } = await setup(t, { maxQuota: 6000, usageIntervalMs: 10 }); state.zeroCharge = true;
  await consume(await post()); const debt = (await budget()).pendingReserved;
  assert.equal((await post()).status, 402); assert.equal((await budget()).pendingReserved, debt);
  assert.equal((await budget()).spent, 0); assert.equal(state.chats, 1);
});

test('上游HTTP失败保留整笔债务立即锁死，且不会触发额外核账', async t => {
  const { state, post, budget } = await setup(t); state.chatStatus = 500;
  assert.equal((await post()).status, 500); const b = await budget();
  assert.equal(b.locked, true); assert.ok(b.pendingReserved > 0); assert.equal(state.queries, 1);
  assert.equal((await post()).status, 503); assert.equal(state.chats, 1);
});

test('重定向禁止跟随，未知扣费保留预留并锁死', async t => {
  const { state, post, budget } = await setup(t); state.redirect = true;
  assert.equal((await post()).status, 503); assert.equal(state.otherVisits, 0);
  assert.equal((await budget()).locked, true); assert.ok((await budget()).pendingReserved > 0); assert.equal(state.queries, 1);
});

test('固定baseline计入启动前已花额度，启动只查询一次', async t => {
  const { state, post, budget } = await setup(t, { baselineQuota: 500, maxQuota: 600 });
  const b = await budget(); assert.equal(b.initialUsed, 500); assert.equal(b.lastUsed, 1000);
  assert.equal(b.spent, 500); assert.equal(b.remaining, 100);
  assert.equal((await post()).status, 402); assert.equal(state.queries, 1); assert.equal(state.chats, 0);
});

test('CLI默认120秒节流，最小60秒；baseline只能非负有限数字', () => {
  const env = { PILOT_EVAL_UPSTREAM: 'http://127.0.0.1', PILOT_EVAL_API_KEY: 'FAKE',
    PILOT_EVAL_MAX_QUOTA: '1', PILOT_EVAL_INPUT_QUOTA_PER_MILLION: '1', PILOT_EVAL_OUTPUT_QUOTA_PER_MILLION: '1' };
  assert.equal(configFromEnv(env).usageIntervalMs, 120000);
  assert.equal(configFromEnv({ ...env, PILOT_EVAL_BASELINE_QUOTA: '45209239' }).baselineQuota, 45209239);
  assert.throws(() => configFromEnv({ ...env, PILOT_EVAL_USAGE_INTERVAL_MS: '59999' }));
  assert.throws(() => configFromEnv({ ...env, PILOT_EVAL_BASELINE_QUOTA: '-1' }));
});


const usageResponse = usage => JSON.stringify({ choices: [], usage });
const usageSSE = usages => usages.map(usage => `data: ${usageResponse(usage)}\n\n`).join('') + 'data: [DONE]\n\n';
const validUsage = (prompt_tokens = 100, completion_tokens = 10) => ({ prompt_tokens, completion_tokens, total_tokens: prompt_tokens + completion_tokens });

test('严格解析JSON及SSE累计usage，各字段取最大且不叠加', () => {
  assert.deepEqual(reportedUsage(usageResponse(validUsage()), 'application/json'), { promptTokens: 100, completionTokens: 10 });
  assert.deepEqual(reportedUsage(usageSSE([null, validUsage(100, 10), validUsage(120, 5)]), 'text/event-stream; charset=utf-8'), { promptTokens: 120, completionTokens: 10 });
  assert.equal(reportedUsage(usageResponse(null), 'application/json'), null);
  assert.equal(reportedUsage('data: {"choices":[]}\n\ndata: [DONE]\n\n', 'text/event-stream'), null);
});

test('负数、非整数、过大、矛盾总量/别名/明细或非法结构均拒绝', () => {
  const invalid = [
    { prompt_tokens: -1, completion_tokens: 1 }, { prompt_tokens: '100', completion_tokens: 1 },
    { prompt_tokens: 1.5, completion_tokens: 1 }, { prompt_tokens: 1, completion_tokens: 4097 },
    { prompt_tokens: Number.MAX_SAFE_INTEGER, completion_tokens: 1 }, { prompt_tokens: 100 },
    { ...validUsage(), total_tokens: 111 }, { ...validUsage(), input_tokens: 99 },
    { ...validUsage(), output_tokens: 11 }, { ...validUsage(), prompt_tokens_details: { cached_tokens: 101 } },
    { ...validUsage(), completion_tokens_details: { reasoning_tokens: -1 } },
    { ...validUsage(), completion_tokens_details: [] }, {}, [], false
  ];
  for (const usage of invalid) assert.throws(() => reportedUsage(usageResponse(usage), 'application/json'), /reported_usage_invalid/);
  assert.throws(() => reportedUsage('{broken', 'application/json'), /reported_usage_invalid/);
  assert.throws(() => reportedUsage(usageSSE([validUsage(), { ...validUsage(), completion_tokens: -1 }]), 'text/event-stream'), /reported_usage_invalid/);
});

test('SSE缺DONE、终态后多余数据、error响应均不可释放预留', () => {
  assert.throws(() => reportedUsage(`data: ${usageResponse(validUsage())}\n\n`, 'text/event-stream'), /response_incomplete/);
  assert.throws(() => reportedUsage(usageSSE([validUsage()]) + 'data: {}\n\n', 'text/event-stream'), /response_incomplete/);
  assert.throws(() => reportedUsage('{"error":{"message":"SECRET_BODY"}}', 'application/json'), /upstream_response_error/);
});

test('有效SSE usage按全价2/6缩减当前请求预留，缓存折扣不降低费用', async t => {
  const { state, post, budget } = await setup(t, { initialPendingQuota: 500, inputQuotaPerMillion: 2000000, outputQuotaPerMillion: 6000000 });
  const payload = usageSSE([{ ...validUsage(100, 10), prompt_tokens_details: { cached_tokens: 100 } }, validUsage(120, 5)]);
  state.responseHook = async (_req, res) => { res.writeHead(200, { 'content-type': 'text/event-stream' }); res.end(payload); };
  const response = await post(); assert.equal(response.status, 200); assert.equal(await response.text(), payload);
  const b = await budget(); assert.equal(b.pendingReserved, 500 + 120 * 2 + 10 * 6);
  assert.equal(b.spent, 0); assert.equal(state.queries, 1); assert.equal(b.locked, false);
  const next = await post(); assert.equal(next.status, 200); await next.text();
  assert.equal((await budget()).pendingReserved, 500 + 300 * 2);
});

test('合法JSON usage缩减当前请求，固定baseline与跨进程pending共同占预算', async t => {
  const { state, post, budget } = await setup(t, { baselineQuota: 500, initialPendingQuota: 300,
    inputQuotaPerMillion: 2000000, outputQuotaPerMillion: 6000000 });
  state.responseHook = async (_req, res) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(usageResponse(validUsage())); };
  const response = await post(); assert.equal(response.status, 200); await response.text();
  const b = await budget(); assert.equal(b.spent, 500); assert.equal(b.pendingReserved, 560); assert.equal(b.remaining, 98940);
});

test('无usage保留完整当前预留，已有跨进程pending不变', async t => {
  const { post, budget, logs } = await setup(t, { initialPendingQuota: 300 });
  await consume(await post()); const b = await budget();
  const original = logs.find(row => row.status === 'request_complete').reserved;
  assert.equal(b.pendingReserved, 300 + original); assert.ok(original > 5000);
});

test('后续非法usage锁死且不泄露DONE，不因前面的合法usage部分减债', async t => {
  const { state, post, budget, logs } = await setup(t, { initialPendingQuota: 300 });
  state.responseHook = async (_req, res) => { res.writeHead(200, { 'content-type': 'text/event-stream' }); res.end(usageSSE([validUsage(), { ...validUsage(), completion_tokens: -1 }])); };
  const response = await post(); assert.equal(response.status, 503); assert.equal((await response.text()).includes('[DONE]'), false);
  const error = logs.find(row => row.status === 'request_failed');
  assert.equal(error.causeCode, 'REPORTED_USAGE_INVALID');
  assert.equal((await budget()).pendingReserved, 300 + error.reserved); assert.equal((await budget()).locked, true);
  assert.equal((await post()).status, 503); assert.equal(state.chats, 1);
});

test('报告的token上界超过原预留则锁死，同时保留已知更高债务', async t => {
  const { state, post, budget } = await setup(t);
  state.responseHook = async (_req, res) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(usageResponse(validUsage(100000, 1))); };
  assert.equal((await post()).status, 503); const b = await budget();
  assert.equal(b.pendingReserved, 100001); assert.equal(b.locked, true); assert.equal(b.remaining, 0);
});

test('usage之后账单增量仅冲debt，账单差额仍不回收', async t => {
  const { state, post, budget } = await setup(t, { initialPendingQuota: 500, usageIntervalMs: 30,
    inputQuotaPerMillion: 2000000, outputQuotaPerMillion: 6000000 });
  state.responseHook = async (_req, res) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(usageResponse(validUsage())); };
  await consume(await post()); const before = await budget(); assert.equal(before.pendingReserved, 760);
  await waitDue(budget); await consume(await post()); const after = await budget();
  assert.equal(after.spent, 100); assert.equal(after.pendingReserved, 920);
  assert.equal(after.spent + after.pendingReserved, 500 + 260 * 2);
});

test('INITIAL_PENDING_QUOTA默认0，仅接受非负安全整数', async t => {
  const env = { PILOT_EVAL_UPSTREAM: 'http://127.0.0.1', PILOT_EVAL_API_KEY: 'FAKE',
    PILOT_EVAL_MAX_QUOTA: '1', PILOT_EVAL_INPUT_QUOTA_PER_MILLION: '1', PILOT_EVAL_OUTPUT_QUOTA_PER_MILLION: '1' };
  assert.equal(configFromEnv(env).initialPendingQuota, 0);
  assert.equal(configFromEnv({ ...env, PILOT_EVAL_INITIAL_PENDING_QUOTA: '379944' }).initialPendingQuota, 379944);
  assert.equal(configFromEnv({ ...env, PILOT_EVAL_INITIAL_PENDING_QUOTA: String(Number.MAX_SAFE_INTEGER) }).initialPendingQuota, Number.MAX_SAFE_INTEGER);
  for (const bad of ['', ' ', '-1', '1.5', 'NaN', 'Infinity', '9007199254740992', false]) assert.throws(() => configFromEnv({ ...env, PILOT_EVAL_INITIAL_PENDING_QUOTA: bad }));
});
