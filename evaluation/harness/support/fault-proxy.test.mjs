import test from 'node:test';
import assert from 'node:assert/strict';
import http, { createServer } from 'node:http';

import { configFromEnv, shouldInject, startFaultProxy, validateConfigPatch, FAULT_MODES } from './fault-proxy.mjs';

const API_KEY = 'sk-unit-test-upstream-key';
const SSE_EVENTS = [
  'data: {"choices":[{"delta":{"content":"你"},"finish_reason":null}]}\n\n',
  'data: {"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}\n\n',
  'data: {"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":2}}\n\n',
  'data: [DONE]\n\n'
];

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

/** 模拟真实上游：逐分片流式返回 SSE，并核对鉴权头。 */
async function mockUpstream(t) {
  let hits = 0;
  const server = createServer((req, res) => {
    hits += 1;
    assert.equal(req.headers.authorization, `Bearer ${API_KEY}`, '代理必须原样携带上游鉴权');
    req.resume();
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    (async () => {
      for (const event of SSE_EVENTS) {
        res.write(event);
        await delay(15);
      }
      res.end();
    })();
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  return { origin: `http://127.0.0.1:${server.address().port}`, hits: () => hits };
}

async function startProxy(t, upstream, patch = {}, logs = []) {
  const config = configFromEnv({ PILOT_FAULT_UPSTREAM: upstream, PILOT_FAULT_API_KEY: API_KEY,
    PILOT_FAULT_PROXY_PORT: '0', PILOT_FAULT_MODE: 'none', PILOT_FAULT_EVERY: '1' });
  // patch 直接覆盖 config 字段（mode/every/stallMs/maxRequests/单价/maxQuota）。
  Object.assign(config, patch);
  config.log = row => logs.push(JSON.stringify(row));
  const proxy = await startFaultProxy(config);
  t.after(() => proxy.close());
  return proxy;
}

/** 经代理发起一次 chat 请求；读流失败（被注入）时返回已收到的部分文本。 */
async function call(proxy, body = '{"messages":[{"role":"user","content":"hi"}]}') {
  const response = await fetch(`http://127.0.0.1:${proxy.port}/v1/chat/completions`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body
  });
  let text = '';
  try {
    text = await response.text();
  } catch { /* 连接被注入故障销毁，保留已收到的部分 */ }
  return { status: response.status, text };
}

/**
 * 控制接口直连且不复用连接（agent:false）：注入故障销毁过的 socket 不能
 * 被后续 fetch 从连接池里复用，否则会把注入效应误判成控制接口故障。
 */
function control(proxy, path, init = {}) {
  return new Promise((resolve, reject) => {
    const request = http.request(`http://127.0.0.1:${proxy.port}${path}`, {
      method: init.method ?? 'GET', agent: false,
      headers: init.body ? { 'content-type': 'application/json' } : {}
    }, response => {
      let raw = '';
      response.on('data', chunk => { raw += chunk; });
      response.on('end', () => resolve({ status: response.statusCode, body: raw ? JSON.parse(raw) : null }));
    });
    request.on('error', reject);
    if (init.body) request.write(init.body);
    request.end();
  });
}

const stateOf = async proxy => (await control(proxy, '/fault/state')).body;

test('shouldInject 只在每第 N 个请求触发，none 永不注入', () => {
  assert.equal(shouldInject(0, 3, 'mid-eof'), false);
  assert.equal(shouldInject(1, 3, 'mid-eof'), false);
  assert.equal(shouldInject(2, 3, 'mid-eof'), true);
  assert.equal(shouldInject(5, 3, 'mid-eof'), true);
  assert.equal(shouldInject(0, 1, 'none'), false);
  assert.equal(shouldInject(0, 1, 'stall'), true);
});

test('config 校验：未知模式与未知字段都要拒绝', () => {
  assert.throws(() => validateConfigPatch({ mode: 'boom' }, { mode: 'none', every: 3, stallMs: 100, maxRequests: 0 }), /未知故障模式/);
  assert.throws(() => validateConfigPatch({ every: 0 }, { mode: 'none', every: 3, stallMs: 100, maxRequests: 0 }), /Invalid every/);
  assert.throws(() => validateConfigPatch({ extra: 1 }, { mode: 'none', every: 3, stallMs: 100, maxRequests: 0 }), /不支持字段/);
  assert.deepEqual(validateConfigPatch({ mode: 'stall', stallMs: 250 }, { mode: 'none', every: 3, stallMs: 100, maxRequests: 0 }),
    { mode: 'stall', every: 3, stallMs: 250, maxRequests: 0 });
});

test('干净转发：逐分片转发完整 SSE，usage 被累计，注入为 0', async t => {
  const upstream = await mockUpstream(t);
  const proxy = await startProxy(t, upstream.origin);
  const { status, text } = await call(proxy);
  assert.equal(status, 200);
  assert.equal(text, SSE_EVENTS.join(''), '转发内容必须与上游逐字节一致（含 [DONE]）');
  const state = await stateOf(proxy);
  assert.equal(state.requests, 1);
  assert.equal(state.injectedRequests, 0);
  assert.equal(state.usage.observedRequests, 1);
  assert.equal(state.usage.inputTokens, 11);
  assert.equal(state.usage.outputTokens, 2);
  assert.equal(upstream.hits(), 1);
});

test('mid-eof：转发首分片后断开，客户端收不到 [DONE]', async t => {
  const upstream = await mockUpstream(t);
  const proxy = await startProxy(t, upstream.origin, { mode: 'mid-eof' });
  const { status, text } = await call(proxy);
  assert.equal(status, 200);
  assert.ok(text.includes('你'), '首个分片必须已转发');
  assert.ok(!text.includes('[DONE]'), '不允许出现 [DONE]，否则不是提前断流');
  const state = await stateOf(proxy);
  assert.equal(state.injectedRequests, 1);
  assert.deepEqual(state.faultsByMode, { 'mid-eof': 1 });
  assert.equal(upstream.hits(), 1);
});

test('stall：响应头与首分片之后静默 stallMs 才断开', async t => {
  const upstream = await mockUpstream(t);
  const proxy = await startProxy(t, upstream.origin, { mode: 'stall', stallMs: 250 });
  const started = Date.now();
  const { status, text } = await call(proxy);
  const elapsed = Date.now() - started;
  assert.equal(status, 200);
  assert.ok(text.includes('你'));
  assert.ok(!text.includes('[DONE]'));
  assert.ok(elapsed >= 250, `读取应被拖满 stall 时间，实际 ${elapsed}ms`);
  const state = await stateOf(proxy);
  assert.deepEqual(state.faultsByMode, { stall: 1 });
});

test('http-503：不打上游直接返回可重试 5xx', async t => {
  const upstream = await mockUpstream(t);
  const proxy = await startProxy(t, upstream.origin, { mode: 'http-503' });
  const { status, text } = await call(proxy);
  assert.equal(status, 503);
  assert.ok(text.includes('injected_transient_error'));
  assert.equal(upstream.hits(), 0, '注入 503 不得消耗上游请求');
  const state = await stateOf(proxy);
  assert.deepEqual(state.faultsByMode, { 'http-503': 1 });
});

test('reset：直接销毁连接，客户端请求失败', async t => {
  const upstream = await mockUpstream(t);
  const proxy = await startProxy(t, upstream.origin, { mode: 'reset' });
  await assert.rejects(call(proxy), () => true, '连接被重置时 fetch 必须失败');
  const state = await stateOf(proxy);
  assert.deepEqual(state.faultsByMode, { reset: 1 });
});

test('every=3：只有第 3 个请求被注入，其余干净转发', async t => {
  const upstream = await mockUpstream(t);
  const proxy = await startProxy(t, upstream.origin, { mode: 'mid-eof', every: 3 });
  assert.equal((await call(proxy)).text, SSE_EVENTS.join(''));
  assert.equal((await call(proxy)).text, SSE_EVENTS.join(''));
  const third = await call(proxy);
  assert.ok(!third.text.includes('[DONE]'));
  const state = await stateOf(proxy);
  assert.equal(state.requests, 3);
  assert.equal(state.injectedRequests, 1);
  assert.equal(state.injected[0].index, 2);
});

test('控制接口：config 生效、state 反映、reset 清零但保留配置', async t => {
  const upstream = await mockUpstream(t);
  const proxy = await startProxy(t, upstream.origin);
  const configured = await control(proxy, '/fault/config', { method: 'POST', body: JSON.stringify({ mode: 'http-503', every: 1 }) });
  assert.equal(configured.status, 200);
  assert.equal(configured.body.config.mode, 'http-503');
  assert.equal((await call(proxy)).status, 503);
  const bad = await control(proxy, '/fault/config', { method: 'POST', body: JSON.stringify({ mode: 'boom' }) });
  assert.equal(bad.status, 400, '非法配置返回 400');
  const before = await stateOf(proxy);
  assert.equal(before.requests, 1);
  assert.equal(before.injectedRequests, 1);
  await control(proxy, '/fault/reset', { method: 'POST' });
  const after = await stateOf(proxy);
  assert.equal(after.requests, 0);
  assert.equal(after.injectedRequests, 0);
  assert.equal(after.config.mode, 'http-503', 'reset 保留配置');
});

test('maxRequests 硬停：超出后 402，stopped 粘滞且 reset 不清除', async t => {
  const upstream = await mockUpstream(t);
  const proxy = await startProxy(t, upstream.origin, { maxRequests: 2 });
  assert.equal((await call(proxy)).status, 200);
  assert.equal((await call(proxy)).status, 200);
  assert.equal((await call(proxy)).status, 402);
  assert.equal((await call(proxy)).status, 402);
  let state = await stateOf(proxy);
  assert.equal(state.stopped, true);
  await control(proxy, '/fault/reset', { method: 'POST' });
  state = await stateOf(proxy);
  assert.equal(state.stopped, true, '硬停状态不允许 reset 洗掉');
  assert.equal((await call(proxy)).status, 402);
});

test('token 花费估算达到 maxQuota 同样硬停', async t => {
  const upstream = await mockUpstream(t);
  const proxy = await startProxy(t, upstream.origin, { inputQuotaPerMillion: 1_000_000, outputQuotaPerMillion: 0, maxQuota: 10 });
  assert.equal((await call(proxy)).status, 200);
  let state = await stateOf(proxy);
  assert.equal(state.spendEstimate, 11, 'usage 观测 11 输入 token × 全价');
  assert.equal(state.stopped, true);
  assert.equal((await call(proxy)).status, 402);
});

test('日志与状态不泄漏上游密钥', async t => {
  const upstream = await mockUpstream(t);
  const logs = [];
  const proxy = await startProxy(t, upstream.origin, { mode: 'mid-eof' }, logs);
  await call(proxy);
  const state = JSON.stringify(await stateOf(proxy));
  assert.ok(!logs.join('\n').includes(API_KEY), '日志不得包含上游密钥');
  assert.ok(!state.includes(API_KEY), '状态不得包含上游密钥');
});

test('FAULT_MODES 固定五种模式', () => {
  assert.deepEqual([...FAULT_MODES].sort(), ['http-503', 'mid-eof', 'none', 'reset', 'stall']);
});

test('在触顶前 reset 不能洗掉跨轮请求计数', async t => {
  const upstream = await mockUpstream(t);
  const proxy = await startProxy(t, upstream.origin, { maxRequests: 2 });
  assert.equal((await call(proxy)).status, 200);
  await control(proxy, '/fault/reset', { method: 'POST' });
  assert.equal((await call(proxy)).status, 200);
  await control(proxy, '/fault/reset', { method: 'POST' });
  assert.equal((await call(proxy)).status, 402);
  assert.equal((await stateOf(proxy)).lifetimeRequests, 2);
  assert.equal(upstream.hits(), 2);
});

test('在触顶前 reset 不能洗掉跨轮已观测费用', async t => {
  const upstream = await mockUpstream(t);
  const proxy = await startProxy(t, upstream.origin, { inputQuotaPerMillion: 1_000_000, outputQuotaPerMillion: 0, maxQuota: 20 });
  assert.equal((await call(proxy)).status, 200);
  await control(proxy, '/fault/reset', { method: 'POST' });
  assert.equal((await call(proxy)).status, 200);
  assert.equal((await call(proxy)).status, 402);
  const state = await stateOf(proxy);
  assert.equal(state.spendEstimate, 11);
  assert.equal(state.lifetimeSpendEstimate, 22);
});
