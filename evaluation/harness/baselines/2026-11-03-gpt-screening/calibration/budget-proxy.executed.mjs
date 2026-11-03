import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { realpathSync } from 'node:fs';
import { timingSafeEqual } from 'node:crypto';

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const finite = (value, name, min, max) => {
  const n = Number(value);
  if (!Number.isFinite(n) || n < min || n > max) throw new Error(`Invalid ${name}`);
  return n;
};

export function configFromEnv(env = process.env) {
  return {
    upstream: env.PILOT_EVAL_UPSTREAM,
    apiKey: env.PILOT_EVAL_API_KEY,
    localToken: env.PILOT_EVAL_LOCAL_TOKEN || '',
    port: finite(env.PILOT_EVAL_PROXY_PORT ?? 18084, 'port', 0, 65535),
    maxQuota: finite(env.PILOT_EVAL_MAX_QUOTA, 'maxQuota', 0, Number.MAX_SAFE_INTEGER),
    inputQuotaPerMillion: finite(env.PILOT_EVAL_INPUT_QUOTA_PER_MILLION, 'inputPrice', Number.MIN_VALUE, Number.MAX_SAFE_INTEGER),
    outputQuotaPerMillion: finite(env.PILOT_EVAL_OUTPUT_QUOTA_PER_MILLION, 'outputPrice', Number.MIN_VALUE, Number.MAX_SAFE_INTEGER),
    inputMarginTokens: finite(env.PILOT_EVAL_INPUT_MARGIN_TOKENS ?? 16384, 'inputMargin', 1024, 1000000),
    maxOutputTokens: finite(env.PILOT_EVAL_MAX_OUTPUT_TOKENS ?? 4096, 'outputTokens', 128, 4096),
    timeoutMs: finite(env.PILOT_EVAL_PROXY_TIMEOUT_MS ?? 90000, 'timeout', 1, 90000),
    postQueryDelayMs: 200,
    log: row => process.stdout.write(JSON.stringify(row) + '\n')
  };
}

export async function startBudgetProxy(config) {
  const upstream = new URL(config.upstream);
  if (upstream.username || upstream.password || !config.apiKey || /[\r\n]/.test(config.apiKey)) throw new Error('Invalid credentials');
  if (upstream.protocol !== 'https:' && !(upstream.protocol === 'http:' && ['127.0.0.1', '[::1]', 'localhost'].includes(upstream.hostname))) throw new Error('HTTPS required');
  for (const key of ['maxQuota', 'inputQuotaPerMillion', 'outputQuotaPerMillion']) finite(config[key], key, key === 'maxQuota' ? 0 : Number.MIN_VALUE, Number.MAX_SAFE_INTEGER);
  const maxOutputTokens = config.maxOutputTokens ?? 4096;
  if (!Number.isInteger(maxOutputTokens) || maxOutputTokens < 128 || maxOutputTokens > 4096) throw new Error('Invalid output limit');
  // Extra allowance accounts for observed provider-side prompt additions; it is not a proof of an upper bound.
  const inputMarginTokens = finite(config.inputMarginTokens ?? 16384, 'inputMargin', 1024, 1000000);
  if (!Number.isInteger(inputMarginTokens)) throw new Error('Invalid input margin');
  const timeoutMs = finite(config.timeoutMs ?? 90000, 'timeout', 1, 90000);
  const delayMs = finite(config.postQueryDelayMs ?? 200, 'postQueryDelay', 0, 1000);
  const chatUrl = new URL('/v1/chat/completions', upstream.origin);
  const usageUrl = new URL('/api/usage/token/', upstream.origin);
  const authorization = `Bearer ${config.apiKey}`;
  if (!Number.isSafeInteger(config.maxQuota)) throw new Error('Invalid maxQuota');
  let initialUsed, lastUsed, halted = false, busy = false, reserved = 0, requests = 0;
  const spent = () => initialUsed === undefined ? 0 : lastUsed - initialUsed;
  const log = (status, started = Date.now()) => config.log?.({ status, elapsedMs: Date.now() - started, used: spent(), reserved });
  const halt = (status, started) => { halted = true; log(status, started); };
  async function queryUsage(deadline = Date.now() + 10000) {
    const remaining = Math.min(10000, deadline - Date.now());
    if (remaining <= 0) throw new Error('usage_timeout');
    const response = await fetch(usageUrl, { method: 'GET', headers: { Authorization: authorization },
      redirect: 'error', signal: AbortSignal.timeout(Math.max(1, Math.ceil(remaining))) });
    if (!response.ok) throw new Error('usage_unavailable');
    const data = await response.json();
    const used = data?.data?.total_used ?? data?.total_used;
    if (!Number.isSafeInteger(used) || used < 0 || Date.now() >= deadline) throw new Error('usage_invalid');
    return used;
  }
  initialUsed = lastUsed = await queryUsage();
  const reply = (res, status, code) => {
    if (res.headersSent) { res.destroy(); return; }
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify({ error: { type: 'budget_guard', code, message: code } }));
  };
  const authorized = req => {
    if (!config.localToken) return true;
    const actual = Buffer.from(req.headers.authorization || ''), expected = Buffer.from(`Bearer ${config.localToken}`);
    return actual.length === expected.length && timingSafeEqual(actual, expected);
  };
  const budgetState = () => ({ initialUsed, lastUsed, spent: spent(), maxQuota: config.maxQuota,
    remaining: Math.max(0, config.maxQuota - spent()), locked: halted, busy, requests });
  const server = http.createServer(async (req, res) => {
    if (!authorized(req)) return reply(res, 401, 'local_auth_required');
    if (req.method === 'GET' && req.url === '/budget') {
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      res.end(JSON.stringify(budgetState())); return;
    }
    if (req.method !== 'POST' || req.url !== '/v1/chat/completions') return reply(res, 404, 'unsupported_route');
    if (halted) return reply(res, 503, 'budget_guard_stopped');
    if (busy) { log('busy'); return reply(res, 429, 'single_request_only'); }
    busy = true;
    const started = Date.now();
    let sent = false, confirmed = false, preUsed = lastUsed, controller, deadline, postQueries = 0;
    try {
      const chunks = []; let bytes = 0;
      for await (const chunk of req) {
        bytes += chunk.length;
        if (bytes > 2 * 1024 * 1024) { reply(res, 413, 'request_too_large'); return; }
        chunks.push(chunk);
      }
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { reply(res, 400, 'invalid_json'); return; }
      // Remote/multimodal inputs cannot be bounded by inline UTF-8 bytes.
      if (!body || !Array.isArray(body.messages) || !body.messages.length || body.messages.some(m => !m ||
          !(typeof m.content === 'string' || m.content === null || m.content === undefined ||
            (Array.isArray(m.content) && m.content.every(c => c?.type === 'text' && typeof c.text === 'string')))) ||
          (body.n !== undefined && body.n !== 1) || body.audio ||
          (body.modalities && (!Array.isArray(body.modalities) || body.modalities.some(m => m !== 'text'))) ||
          (body.tools && (!Array.isArray(body.tools) || body.tools.some(tool => tool?.type !== 'function')))) {
        reply(res, 400, 'text_single_completion_required'); return;
      }
      body.max_completion_tokens = maxOutputTokens;
      delete body.max_tokens; delete body.max_output_tokens;
      const serialized = JSON.stringify(body);
      reserved = Math.ceil(((Buffer.byteLength(serialized, 'utf8') + inputMarginTokens) * config.inputQuotaPerMillion
        + maxOutputTokens * config.outputQuotaPerMillion) / 1_000_000);
      if (!Number.isSafeInteger(reserved) || reserved <= 0) { halt('reservation_invalid', started); reply(res, 503, 'reservation_invalid'); return; }
      let observed;
      try { observed = await queryUsage(); } catch { halt('usage_unavailable', started); reply(res, 503, 'usage_unavailable'); return; }
      if (observed < lastUsed) { halt('usage_rollback', started); reply(res, 503, 'usage_rollback'); return; }
      if (observed !== lastUsed) { lastUsed = observed; halt('usage_changed_between_requests', started); reply(res, 503, 'usage_changed_between_requests'); return; }
      preUsed = observed;
      if (spent() >= config.maxQuota || reserved > config.maxQuota - spent()) {
        log('budget_rejected', started); reply(res, 402, 'budget_reservation_exceeded'); return;
      }
      deadline = Date.now() + timeoutMs;
      controller = new AbortController();
      const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(timeoutMs)]);
      const onClose = () => { if (!res.writableFinished) controller.abort(); };
      res.once('close', onClose);
      sent = true; requests++;
      let response;
      try {
        response = await fetch(chatUrl, { method: 'POST', headers: { Authorization: authorization,
          'content-type': 'application/json' }, body: serialized, redirect: 'error', signal });
        if (!response.ok) halt('upstream_error', started);
        // Hold even SSE [DONE] until billing is confirmed: clients may close/start the next
        // request as soon as they see a terminal event. This eval measures total latency.
        const responseChunks = []; let responseBytes = 0;
        if (response.body) {
          const reader = response.body.getReader();
          try {
            while (true) {
              const { done, value } = await reader.read();
              if (signal.aborted || Date.now() >= deadline) throw new Error('response_timeout');
              if (done) break;
              responseBytes += value.byteLength;
              if (responseBytes > 8 * 1024 * 1024) { controller.abort(); throw new Error('response_too_large'); }
              responseChunks.push(Buffer.from(value));
            }
          } finally { reader.releaseLock(); }
        }
        for (let attempt = 0; attempt < 3; attempt++) {
          postQueries++;
          const value = await queryUsage(deadline);
          if (value < preUsed) throw new Error('usage_rollback');
          lastUsed = value;
          if (value > preUsed) { confirmed = true; break; }
          if (attempt < 2) await delay(Math.min(delayMs, Math.max(0, deadline - Date.now())));
        }
        if (!confirmed) throw new Error('charge_unconfirmed');
        if (lastUsed - preUsed > reserved) throw new Error('charge_exceeded_reservation');
        if (spent() > config.maxQuota) throw new Error('budget_exceeded');
        log(halted ? 'stopped_after_charge' : 'request_complete', started);
        // fetch decodes content encoding; do not forward stale length/encoding headers.
        // Release the reservation before exposing a successful terminal response.
        reserved = 0; busy = false;
        res.writeHead(response.status, { 'content-type': response.headers.get('content-type') || 'application/json',
          'cache-control': 'no-store' });
        res.end(Buffer.concat(responseChunks));
      } finally { res.off('close', onClose); }
    } catch (error) {
      // Any unknown result may already be billed; never retry upstream automatically.
      halt(['usage_rollback', 'charge_unconfirmed', 'charge_exceeded_reservation', 'budget_exceeded'].includes(error?.message)
        ? error.message : 'request_failed', started);
      if (sent && !confirmed && postQueries < 3) {
        try {
          postQueries++;
          const value = await queryUsage(deadline ?? Date.now() + 10000);
          if (value >= lastUsed) lastUsed = value;
          log('stopped_after_accounting', started);
        } catch { /* Stay stopped even if final accounting cannot be fetched. */ }
      }
      reply(res, 503, 'budget_guard_stopped');
    } finally { controller?.abort(); reserved = 0; busy = false; }
  });
  server.requestTimeout = 10000;
  server.headersTimeout = 10000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(config.port ?? 0, '127.0.0.1', resolve); });
  log('ready');
  return { server, port: server.address().port, state: () => ({ used: spent(), reserved, busy, halted }),
    close: () => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }) };
}

// Node resolves the module path, but argv can retain a symlink (e.g. macOS /var → /private/var).
let isMain = false;
try { isMain = !!process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url)); } catch { /* Imported modules need no CLI entry. */ }
if (isMain) {
  startBudgetProxy(configFromEnv()).catch(() => {
    process.stderr.write(JSON.stringify({ status: 'startup_failed', elapsedMs: 0, used: 0, reserved: 0 }) + '\n');
    process.exitCode = 1;
  });
}
