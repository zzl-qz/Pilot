#!/usr/bin/env node
/**
 * 流式故障注入代理：夹在 Pilot 服务与真实模型上游之间。
 *
 * 与预算代理不同，这里**不缓冲整包响应**：收到上游 SSE 分片立即原样转发，
 * 才能在流中途注入真实故障。注入模式（每个请求只注入一种）：
 *   mid-eof   转发首个分片后直接断开下游连接，不发 [DONE]——对准「流提前 EOF 被当成完成」。
 *   stall     转发响应头与首个分片后静默 stallMs 再断开——对准「收到响应头后 body 卡在半开连接」。
 *   http-503  不打上游，直接回 503（可重试服务端错误）。
 *   reset     收到请求后立刻销毁连接（连接重置）。
 *   none      干净转发（对照组）。
 *
 * 触发节奏：mode != none 时，每第 N 个请求注入一次（(index+1) % every === 0），
 * 其余请求干净转发——被注入的请求应当被客户端按可重试错误处理后继续，任务仍能完成。
 *
 * 控制接口（供 runner 在每次尝试前设置与读取）：
 *   POST /fault/reset   清零计数（保留配置与 stopped 状态）。
 *   POST /fault/config  { mode, every, stallMs, maxRequests }。
 *   GET  /fault/state   请求/注入/用量/花费估算快照。
 *
 * 硬停：maxRequests > 0 时，超出后一律 402 fault_budget_stop（stopped 粘滞，reset 不清除）；
 * 配置单价与 maxQuota 后，按上游 usage 分片累计的 token 花费估算达到上限同样停。
 * 估算只统计有 usage 观测的请求；被注入与缺 usage 的请求计入 requestsWithoutUsage，不当零。
 *
 * 上游地址：PILOT_FAULT_UPSTREAM（缺省取 BASE_URL）填**origin**（协议+主机+端口），
 * 代理按请求原路径转发；把 Pilot 的模型端点指向 http://127.0.0.1:<port><BASE_URL 的路径>。
 * 鉴权：PILOT_FAULT_API_KEY（缺省取 API_KEY）只用于上游，不写入任何日志或状态。
 */
import http from 'node:http';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { realpathSync } from 'node:fs';

export const FAULT_MODES = new Set(['none', 'mid-eof', 'stall', 'http-503', 'reset']);
const ERROR_NAMES = new Set(['Error', 'TypeError', 'AbortError', 'TimeoutError']);
const ERROR_CODES = new Set(['ECONNRESET', 'ECONNREFUSED', 'ETIMEDOUT', 'ENOTFOUND', 'EPIPE',
  'UND_ERR_SOCKET', 'UND_ERR_CONNECT_TIMEOUT', 'UND_ERR_HEADERS_TIMEOUT', 'UND_ERR_ABORTED']);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const finite = (value, name, min, max) => {
  const n = Number(value);
  if (!Number.isFinite(n) || n < min || n > max) throw new Error(`Invalid ${name}`);
  return n;
};

/** 只记录错误类别，绝不记录消息、堆叠、头或 URL——它们可能带密钥。 */
const diagnostics = error => ({
  errorName: error && ERROR_NAMES.has(error.name) ? error.name : error ? 'UnknownError' : null,
  causeCode: [error?.cause?.code, error?.code].find(code => ERROR_CODES.has(code)) ?? null
});

export function configFromEnv(env = process.env) {
  const upstream = env.PILOT_FAULT_UPSTREAM ?? env.BASE_URL ?? '';
  return {
    upstream,
    apiKey: env.PILOT_FAULT_API_KEY ?? env.API_KEY ?? '',
    localToken: env.PILOT_FAULT_LOCAL_TOKEN || '',
    port: finite(env.PILOT_FAULT_PROXY_PORT ?? 18086, 'port', 0, 65535),
    mode: env.PILOT_FAULT_MODE || 'none',
    every: finite(env.PILOT_FAULT_EVERY ?? 3, 'every', 1, 1000),
    stallMs: finite(env.PILOT_FAULT_STALL_MS ?? 70_000, 'stallMs', 1, 600_000),
    maxRequests: finite(env.PILOT_FAULT_MAX_REQUESTS ?? 0, 'maxRequests', 0, 1_000_000),
    inputQuotaPerMillion: finite(env.PILOT_FAULT_INPUT_QUOTA_PER_MILLION ?? 0, 'inputPrice', 0, Number.MAX_SAFE_INTEGER),
    outputQuotaPerMillion: finite(env.PILOT_FAULT_OUTPUT_QUOTA_PER_MILLION ?? 0, 'outputPrice', 0, Number.MAX_SAFE_INTEGER),
    maxQuota: finite(env.PILOT_FAULT_MAX_QUOTA ?? 0, 'maxQuota', 0, Number.MAX_SAFE_INTEGER),
    log: row => process.stdout.write(JSON.stringify(row) + '\n')
  };
}

/** 第 index 个（0 起）请求在当前配置下是否注入。纯函数，供测试与审计。 */
export function shouldInject(requestIndex, every, mode) {
  return mode !== 'none' && Number.isInteger(requestIndex) && requestIndex >= 0
    && Number.isInteger(every) && every >= 1 && (requestIndex + 1) % every === 0;
}

export function validateConfigPatch(patch, current) {
  const next = { mode: current.mode, every: current.every, stallMs: current.stallMs, maxRequests: current.maxRequests };
  let touched = false;
  for (const key of Object.keys(patch)) {
    if (!['mode', 'every', 'stallMs', 'maxRequests'].includes(key)) throw new Error(`config 不支持字段：${key}`);
    touched = true;
  }
  if (!touched) throw new Error('config 至少设置 mode/every/stallMs/maxRequests 之一');
  if (patch.mode !== undefined) {
    if (!FAULT_MODES.has(patch.mode)) throw new Error(`未知故障模式：${patch.mode}`);
    next.mode = patch.mode;
  }
  if (patch.every !== undefined) next.every = finite(patch.every, 'every', 1, 1000);
  if (patch.stallMs !== undefined) next.stallMs = finite(patch.stallMs, 'stallMs', 1, 600_000);
  if (patch.maxRequests !== undefined) next.maxRequests = finite(patch.maxRequests, 'maxRequests', 0, 1_000_000);
  return next;
}

export async function startFaultProxy(config) {
  const upstream = new URL(config.upstream);
  if (upstream.username || upstream.password || !config.apiKey || /[\r\n]/.test(config.apiKey)) throw new Error('Invalid credentials');
  if (upstream.protocol !== 'https:' && !(upstream.protocol === 'http:'
      && ['127.0.0.1', '[::1]', 'localhost'].includes(upstream.hostname))) throw new Error('HTTPS required unless local');
  const origin = upstream.origin;
  const authorization = `Bearer ${config.apiKey}`;
  let requests = 0, stopped = false;
  let active = { mode: config.mode, every: config.every, stallMs: config.stallMs, maxRequests: config.maxRequests };
  let injected = [], faultsByMode = {};
  let usage = { observedRequests: 0, requestsWithoutUsage: 0, inputTokens: 0, outputTokens: 0 };
  const log = (status, details = {}) => config.log?.({ status, ...details });
  const spendEstimate = () => config.inputQuotaPerMillion > 0 || config.outputQuotaPerMillion > 0
    ? (usage.inputTokens * config.inputQuotaPerMillion + usage.outputTokens * config.outputQuotaPerMillion) / 1_000_000
    : null;
  const state = () => ({
    requests, injectedRequests: injected.length, injected, faultsByMode,
    usage: { ...usage }, spendEstimate: spendEstimate(),
    maxQuota: config.maxQuota, stopped,
    config: { ...active, maxQuota: config.maxQuota,
      inputQuotaPerMillion: config.inputQuotaPerMillion, outputQuotaPerMillion: config.outputQuotaPerMillion }
  });
  const reply = (res, status, code) => {
    if (res.headersSent) { res.destroy(); return; }
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify({ error: { type: 'fault_guard', code, message: code } }));
  };

  async function readBody(req, res) {
    const chunks = []; let bytes = 0;
    for await (const chunk of req) {
      bytes += chunk.length;
      if (bytes > 2 * 1024 * 1024) { reply(res, 413, 'request_too_large'); return null; }
      chunks.push(chunk);
    }
    return Buffer.concat(chunks);
  }

  /** 从 SSE 分片中提取 usage（最后一个 data: 分片可能带 usage）。 */
  function scanUsage(buffer, chunk, collect) {
    buffer += Buffer.from(chunk).toString('utf8');
    const lines = buffer.split('\n');
    buffer = lines.pop() ?? '';
    for (const line of lines) {
      const data = line.startsWith('data:') ? line.slice(5).trim() : '';
      if (!data || data === '[DONE]') continue;
      try {
        const parsed = JSON.parse(data);
        const input = Number(parsed?.usage?.prompt_tokens ?? parsed?.usage?.input_tokens);
        const output = Number(parsed?.usage?.completion_tokens ?? parsed?.usage?.output_tokens);
        if (Number.isFinite(input) && input >= 0 && Number.isFinite(output) && output >= 0) {
          collect.inputTokens = input; collect.outputTokens = output;
        }
      } catch { /* 分片截断或非 JSON 行，忽略 */ }
    }
    return { buffer, collect };
  }

  async function upstreamFetch(path, body, signal) {
    return fetch(new URL(path, origin), { method: 'POST', redirect: 'error', signal,
      headers: { 'content-type': 'application/json', authorization }, body });
  }

  /** 干净转发：流式逐分片回写，不做任何缓冲或改写。 */
  async function passthrough(req, res, path, body, index) {
    const controller = new AbortController();
    const onClose = () => { try { controller.abort(); } catch { /* 已结束 */ } };
    res.once('close', onClose);
    const started = Date.now();
    let chunks = 0, bytes = 0, buffer = '';
    const collect = { inputTokens: 0, outputTokens: 0 };
    try {
      const upstreamResponse = await upstreamFetch(path, body, controller.signal);
      res.writeHead(upstreamResponse.status, {
        'content-type': upstreamResponse.headers.get('content-type') ?? 'application/json',
        'cache-control': 'no-store'
      });
      if (upstreamResponse.body) {
        const reader = upstreamResponse.body.getReader();
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          chunks += 1; bytes += value.byteLength;
          res.write(value);
          const scanned = scanUsage(buffer, value, collect);
          buffer = scanned.buffer;
        }
      }
      res.end();
      if (collect.inputTokens > 0 || collect.outputTokens > 0) {
        usage.observedRequests += 1;
        usage.inputTokens += collect.inputTokens;
        usage.outputTokens += collect.outputTokens;
      } else usage.requestsWithoutUsage += 1;
      const estimate = spendEstimate();
      log('complete', { requestIndex: index, mode: 'none', chunks, bytes,
        inputTokens: collect.inputTokens, outputTokens: collect.outputTokens,
        elapsedMs: Date.now() - started, spendEstimate: estimate });
      if (config.maxQuota > 0 && estimate !== null && estimate >= config.maxQuota) {
        stopped = true;
        log('budget_stop', { requestIndex: index, spendEstimate: estimate, maxQuota: config.maxQuota });
      }
    } catch (error) {
      if (!res.headersSent) reply(res, 502, 'upstream_error');
      else res.destroy();
      usage.requestsWithoutUsage += 1;
      log('upstream_error', { requestIndex: index, ...diagnostics(error) });
    } finally {
      res.off('close', onClose);
      try { controller.abort(); } catch { /* 已结束 */ }
    }
  }

  /** mid-eof：转发首个分片后干净结束响应，但不发 [DONE]——客户端看到「流提前 EOF」。 */
  async function midEof(res, path, body, index) {
    const controller = new AbortController();
    res.once('close', () => { try { controller.abort(); } catch { /* 已结束 */ } });
    try {
      const upstreamResponse = await upstreamFetch(path, body, controller.signal);
      res.writeHead(upstreamResponse.status, {
        'content-type': upstreamResponse.headers.get('content-type') ?? 'text/event-stream',
        'cache-control': 'no-store'
      });
      // 先把响应头与首分片真正刷到 socket 再结束：destroy 不等缓冲冲刷，
      // 会连头部一起丢弃，变成另一种故障（突发重置由 reset 模式单独覆盖）。
      res.flushHeaders();
      if (upstreamResponse.body) {
        const reader = upstreamResponse.body.getReader();
        const { done, value } = await reader.read();
        if (!done && value) await new Promise(resolve => res.write(value, () => resolve()));
      }
      injected.push({ index, mode: 'mid-eof', at: new Date().toISOString() });
      faultsByMode['mid-eof'] = (faultsByMode['mid-eof'] ?? 0) + 1;
      usage.requestsWithoutUsage += 1;
      log('injected', { requestIndex: index, mode: 'mid-eof', forwardedChunks: 1 });
      res.end();
    } catch (error) {
      if (!res.headersSent) reply(res, 502, 'upstream_error');
      else res.destroy();
      log('injection_upstream_error', { requestIndex: index, ...diagnostics(error) });
    } finally {
      try { controller.abort(); } catch { /* 已结束 */ }
    }
  }

  /** stall：转发响应头与首个分片后静默 stallMs 再干净结束，制造半开连接后提前 EOF。 */
  async function stall(res, path, body, index) {
    const controller = new AbortController();
    res.once('close', () => { try { controller.abort(); } catch { /* 已结束 */ } });
    try {
      const upstreamResponse = await upstreamFetch(path, body, controller.signal);
      res.writeHead(upstreamResponse.status, {
        'content-type': upstreamResponse.headers.get('content-type') ?? 'text/event-stream',
        'cache-control': 'no-store'
      });
      res.flushHeaders();
      if (upstreamResponse.body) {
        const reader = upstreamResponse.body.getReader();
        const { done, value } = await reader.read();
        if (!done && value) await new Promise(resolve => res.write(value, () => resolve()));
      }
      injected.push({ index, mode: 'stall', at: new Date().toISOString() });
      faultsByMode['stall'] = (faultsByMode['stall'] ?? 0) + 1;
      usage.requestsWithoutUsage += 1;
      log('injected', { requestIndex: index, mode: 'stall', stallMs: active.stallMs });
      await delay(active.stallMs);
      res.end();
    } catch (error) {
      if (!res.headersSent) reply(res, 502, 'upstream_error');
      else res.destroy();
      log('injection_upstream_error', { requestIndex: index, ...diagnostics(error) });
    } finally {
      try { controller.abort(); } catch { /* 已结束 */ }
    }
  }

  const authorized = req => {
    if (!config.localToken) return true;
    const actual = req.headers['authorization'] ?? '';
    return actual === `Bearer ${config.localToken}`;
  };

  const server = http.createServer(async (req, res) => {
    if (req.url === '/fault/state' && req.method === 'GET') {
      if (!authorized(req)) return reply(res, 401, 'local_auth_required');
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      res.end(JSON.stringify(state())); return;
    }
    if (req.url === '/fault/config' && req.method === 'POST') {
      if (!authorized(req)) return reply(res, 401, 'local_auth_required');
      const body = await readBody(req, res);
      if (body === null) return;
      try {
        active = validateConfigPatch(JSON.parse(body.toString('utf8')), active);
        log('config', { config: { ...active } });
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ config: { ...active } }));
      } catch (error) {
        res.writeHead(400, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ error: { code: 'invalid_config', message: error.message } }));
      }
      return;
    }
    if (req.url === '/fault/reset' && req.method === 'POST') {
      if (!authorized(req)) return reply(res, 401, 'local_auth_required');
      // stopped 是硬停状态，reset 不清除；只能重启代理重新开始。
      requests = 0; injected = []; faultsByMode = {};
      usage = { observedRequests: 0, requestsWithoutUsage: 0, inputTokens: 0, outputTokens: 0 };
      log('reset');
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ requests: 0, stopped }));
      return;
    }
    if (req.url?.startsWith('/fault/')) return reply(res, 404, 'unsupported_route');
    if (req.method !== 'POST') return reply(res, 404, 'unsupported_route');
    if (stopped) { log('stopped_reject'); return reply(res, 402, 'fault_budget_stop'); }
    if (active.maxRequests > 0 && requests >= active.maxRequests) {
      stopped = true; log('budget_stop', { requestIndex: requests, maxRequests: active.maxRequests });
      return reply(res, 402, 'fault_budget_stop');
    }
    const index = requests++;
    const body = await readBody(req, res);
    if (body === null) return;
    if (!shouldInject(index, active.every, active.mode)) {
      await passthrough(req, res, req.url, body, index);
      return;
    }
    if (active.mode === 'http-503') {
      injected.push({ index, mode: 'http-503', at: new Date().toISOString() });
      faultsByMode['http-503'] = (faultsByMode['http-503'] ?? 0) + 1;
      log('injected', { requestIndex: index, mode: 'http-503' });
      return reply(res, 503, 'injected_transient_error');
    }
    if (active.mode === 'reset') {
      injected.push({ index, mode: 'reset', at: new Date().toISOString() });
      faultsByMode['reset'] = (faultsByMode['reset'] ?? 0) + 1;
      log('injected', { requestIndex: index, mode: 'reset' });
      res.destroy(); return;
    }
    if (active.mode === 'mid-eof') return midEof(res, req.url, body, index);
    if (active.mode === 'stall') return stall(res, req.url, body, index);
    reply(res, 500, 'unknown_fault_mode');
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(config.port, '127.0.0.1', resolve); });
  log('ready', { port: server.address().port, upstream: origin });
  return { server, port: server.address().port, state,
    close: () => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }) };
}

let isMain = false;
try { isMain = !!process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url)); } catch { /* 被导入时无需 CLI */ }
if (isMain) {
  startFaultProxy(configFromEnv()).catch(error => {
    process.stderr.write(JSON.stringify({ status: 'startup_failed', ...diagnostics(error) }) + '\n');
    process.exitCode = 1;
  });
}
