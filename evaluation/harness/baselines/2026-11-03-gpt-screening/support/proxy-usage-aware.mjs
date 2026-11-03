import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { realpathSync } from 'node:fs';
import { timingSafeEqual } from 'node:crypto';

const finite = (value, name, min, max) => {
  const n = Number(value);
  if (!Number.isFinite(n) || n < min || n > max) throw new Error(`Invalid ${name}`);
  return n;
};

const errorNames = new Set(['Error', 'TypeError', 'SyntaxError', 'RangeError', 'AbortError', 'TimeoutError', 'AggregateError']);
const errorCodes = new Set(['ECONNRESET', 'ECONNREFUSED', 'ETIMEDOUT', 'ENOTFOUND', 'EAI_AGAIN', 'EPIPE',
  'UND_ERR_SOCKET', 'UND_ERR_CONNECT_TIMEOUT', 'UND_ERR_HEADERS_TIMEOUT', 'UND_ERR_BODY_TIMEOUT', 'UND_ERR_ABORTED',
  'ERR_TLS_CERT_ALTNAME_INVALID', 'CERT_HAS_EXPIRED', 'UNABLE_TO_VERIFY_LEAF_SIGNATURE', 'DEPTH_ZERO_SELF_SIGNED_CERT']);
const guardCodes = new Set(['usage_timeout', 'usage_unavailable', 'usage_invalid', 'usage_rollback',
  'charge_unconfirmed', 'charge_exceeded_reservation', 'budget_exceeded', 'response_timeout', 'response_too_large', 'usage_rate_limited', 'reported_usage_invalid', 'upstream_response_error', 'response_incomplete']);
// Never log exception messages, stack, headers or arbitrary cause values; fetch errors may contain secrets/URLs.
const diagnostics = (error, stage) => {
  const candidates = [error?.cause?.code, error?.code, error?.cause?.errors?.[0]?.code];
  const code = candidates.find(value => errorCodes.has(value) || (typeof value === 'string' && /^HTTP_[1-5][0-9]{2}$/.test(value)));
  return { stage, errorName: error ? (errorNames.has(error.name) ? error.name : 'UnknownError') : null,
    causeCode: code ?? (guardCodes.has(error?.message) ? error.message.toUpperCase() : error ? 'UNKNOWN' : null),
    ...(Number.isSafeInteger(error?.retryAfterSeconds) ? { retryAfterSeconds: error.retryAfterSeconds } : {}) };
};

const safeQuota = (value, name) => {
  if ((typeof value !== 'number' && typeof value !== 'string') || (typeof value === 'string' && !/^\d+$/.test(value))) throw new Error(`Invalid ${name}`);
  const amount = Number(value);
  if (!Number.isSafeInteger(amount) || amount < 0) throw new Error(`Invalid ${name}`);
  return amount;
};

// Conditional accounting: this assumes the provider's cumulative token counts include
// every billable token and configured rates cover the channel's highest full price.
// Cache discounts never lower this estimate. It is not an unconditional spending cap.
export function reportedUsage(bytes, contentType, maxOutputTokens = 4096) {
  const mime = (contentType || '').split(';')[0].trim().toLowerCase();
  let maximum = null;
  const invalid = () => { throw new Error('reported_usage_invalid'); };
  const count = value => Number.isSafeInteger(value) && value >= 0;
  const inspect = value => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) invalid();
    if (value.error !== undefined && value.error !== null) throw new Error('upstream_response_error');
    if (value.usage === undefined || value.usage === null) return;
    const u = value.usage;
    if (!u || typeof u !== 'object' || Array.isArray(u)) invalid();
    const p = u.prompt_tokens, c = u.completion_tokens;
    if (!count(p) || !count(c) || c > maxOutputTokens || !count(p + c)) invalid();
    if (u.total_tokens !== undefined && (!count(u.total_tokens) || u.total_tokens !== p + c)) invalid();
    if (u.input_tokens !== undefined && u.input_tokens !== p) invalid();
    if (u.output_tokens !== undefined && u.output_tokens !== c) invalid();
    for (const [key, limit, fields] of [
      ['prompt_tokens_details', p, ['cached_tokens', 'audio_tokens']],
      ['completion_tokens_details', c, ['reasoning_tokens', 'audio_tokens', 'accepted_prediction_tokens', 'rejected_prediction_tokens']]
    ]) {
      const detail = u[key];
      if (detail === undefined || detail === null) continue;
      if (typeof detail !== 'object' || Array.isArray(detail)) invalid();
      for (const field of fields) if (detail[field] !== undefined && (!count(detail[field]) || detail[field] > limit)) invalid();
    }
    maximum = { promptTokens: Math.max(maximum?.promptTokens ?? 0, p), completionTokens: Math.max(maximum?.completionTokens ?? 0, c) };
    if (!count(maximum.promptTokens + maximum.completionTokens)) invalid();
  };
  const text = Buffer.from(bytes).toString('utf8');
  if (mime === 'application/json' || mime.endsWith('+json')) {
    let value; try { value = JSON.parse(text); } catch { invalid(); }
    inspect(value);
  } else if (mime === 'text/event-stream') {
    let finished = false;
    for (const event of text.split(/\r?\n\r?\n/)) {
      const data = event.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n').trim();
      if (!data) continue;
      if (finished) throw new Error('response_incomplete');
      if (data === '[DONE]') { finished = true; continue; }
      let value; try { value = JSON.parse(data); } catch { invalid(); }
      inspect(value);
    }
    if (!finished) throw new Error('response_incomplete');
  }
  return maximum;
}

export function configFromEnv(env = process.env) {
  return {
    upstream: env.PILOT_EVAL_UPSTREAM,
    apiKey: env.PILOT_EVAL_API_KEY,
    localToken: env.PILOT_EVAL_LOCAL_TOKEN || '',
    port: finite(env.PILOT_EVAL_PROXY_PORT ?? 18084, 'port', 0, 65535),
    initialPendingQuota: safeQuota(env.PILOT_EVAL_INITIAL_PENDING_QUOTA ?? 0, 'initialPendingQuota'),
    baselineQuota: env.PILOT_EVAL_BASELINE_QUOTA === undefined ? undefined : finite(env.PILOT_EVAL_BASELINE_QUOTA, 'baselineQuota', 0, Number.MAX_SAFE_INTEGER),
    maxQuota: finite(env.PILOT_EVAL_MAX_QUOTA, 'maxQuota', 0, Number.MAX_SAFE_INTEGER),
    inputQuotaPerMillion: finite(env.PILOT_EVAL_INPUT_QUOTA_PER_MILLION, 'inputPrice', Number.MIN_VALUE, Number.MAX_SAFE_INTEGER),
    outputQuotaPerMillion: finite(env.PILOT_EVAL_OUTPUT_QUOTA_PER_MILLION, 'outputPrice', Number.MIN_VALUE, Number.MAX_SAFE_INTEGER),
    inputMarginTokens: finite(env.PILOT_EVAL_INPUT_MARGIN_TOKENS ?? 16384, 'inputMargin', 1024, 1000000),
    maxOutputTokens: finite(env.PILOT_EVAL_MAX_OUTPUT_TOKENS ?? 4096, 'outputTokens', 128, 4096),
    timeoutMs: finite(env.PILOT_EVAL_PROXY_TIMEOUT_MS ?? 90000, 'timeout', 1, 90000),
    usageIntervalMs: finite(env.PILOT_EVAL_USAGE_INTERVAL_MS ?? 120000, 'usageInterval', 60000, 3600000),
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
  // The exported factory allows short intervals only for localhost mock tests; CLI min is 60 seconds.
  const usageIntervalMs = finite(config.usageIntervalMs ?? 120000, 'usageInterval', 1, 3600000);
  const chatUrl = new URL('/v1/chat/completions', upstream.origin);
  const usageUrl = new URL('/api/usage/token/', upstream.origin);
  const authorization = `Bearer ${config.apiKey}`;
  if (!Number.isSafeInteger(config.maxQuota)) throw new Error('Invalid maxQuota');
  let initialUsed, lastUsed, halted = false, busy = false, reserved = 0, requests = 0, pendingReserved = safeQuota(config.initialPendingQuota ?? 0, 'initialPendingQuota'), nextUsageAt = 0, rateLimitedUntil = 0;
  const spent = () => initialUsed === undefined ? 0 : lastUsed - initialUsed;
  const log = (status, started = Date.now(), details = {}) => config.log?.({ status, elapsedMs: Date.now() - started, used: spent(), reserved, ...details });
  const halt = (status, started, error, stage = 'pre_usage') => { halted = true; log(status, started, diagnostics(error, stage)); };
  async function queryUsage(deadline = Date.now() + 10000) {
    const remaining = Math.min(10000, deadline - Date.now());
    if (remaining <= 0) throw new Error('usage_timeout');
    const response = await fetch(usageUrl, { method: 'GET', headers: { Authorization: authorization },
      redirect: 'error', signal: AbortSignal.timeout(Math.max(1, Math.ceil(remaining))) });
    if (response.status === 429) {
      const header = response.headers.get('retry-after') || '';
      const seconds = /^\d+$/.test(header) ? Number(header) : Math.ceil((Date.parse(header) - Date.now()) / 1000);
      const retryAfterSeconds = Number.isFinite(seconds) ? Math.min(86400, Math.max(0, seconds)) : Math.ceil(usageIntervalMs / 1000);
      rateLimitedUntil = Date.now() + retryAfterSeconds * 1000;
      throw Object.assign(new Error('usage_rate_limited'), { retryAfterSeconds });
    }
    if (!response.ok) throw new Error('usage_unavailable');
    const data = await response.json();
    const used = data?.data?.total_used ?? data?.total_used;
    if (!Number.isSafeInteger(used) || used < 0 || Date.now() >= deadline) throw new Error('usage_invalid');
    return used;
  }
  lastUsed = await queryUsage();
  initialUsed = config.baselineQuota ?? lastUsed;
  if (!Number.isSafeInteger(initialUsed) || initialUsed < 0 || initialUsed > lastUsed) throw new Error('Invalid baseline quota');
  nextUsageAt = Date.now() + usageIntervalMs;
  const remaining = () => Math.max(0, config.maxQuota - spent() - pendingReserved);
  async function reconcile() {
    const observed = await queryUsage();
    if (observed < lastUsed) throw new Error('usage_rollback');
    const delta = observed - lastUsed;
    lastUsed = observed;
    nextUsageAt = Date.now() + usageIntervalMs;
    // Counter increments only settle that many reserved units. A total counter cannot
    // prove that every request has been billed, so unused reservation is never recycled.
    const previousDebt = pendingReserved;
    pendingReserved = Math.max(0, pendingReserved - delta);
    if (delta > previousDebt) throw new Error('charge_exceeded_reservation');
  }
  async function waitUntilDue(res) {
    if (res.destroyed) return false;
    return new Promise(resolve => {
      const done = value => { clearTimeout(timer); res.off('close', closed); resolve(value); };
      const closed = () => done(false);
      const timer = setTimeout(() => done(true), Math.max(0, nextUsageAt - Date.now()));
      res.once('close', closed);
    });
  }
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
    remaining: remaining(), locked: halted, busy, requests, pendingReserved, nextUsageAt, rateLimitedUntil });
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
    let controller, deadline, stage = 'pre_usage';
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
      let reconciled = false;
      if (Date.now() >= nextUsageAt) { await reconcile(); reconciled = true; }
      if (reserved > remaining() && !reconciled && pendingReserved > 0) {
        // Waiting for the accounting interval is separate from the upstream 90s deadline.
        log('waiting_for_accounting', started);
        if (!await waitUntilDue(res)) return;
        await reconcile();
      }
      if (reserved > remaining()) {
        log('budget_rejected', started); reply(res, 402, 'budget_reservation_exceeded'); return;
      }
      deadline = Date.now() + timeoutMs;
      controller = new AbortController();
      const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(timeoutMs)]);
      const onClose = () => { if (!res.writableFinished) controller.abort(); };
      res.once('close', onClose);
      // Unknown billing after any dispatch keeps this full reservation and latches the guard.
      pendingReserved += reserved; requests++;
      let response;
      try {
        stage = 'upstream_headers';
        response = await fetch(chatUrl, { method: 'POST', headers: { Authorization: authorization,
          'content-type': 'application/json' }, body: serialized, redirect: 'error', signal });
        if (!response.ok) halt('upstream_error', started, Object.assign(new Error(), { code: `HTTP_${response.status}` }), stage);
        // Buffer the entire response; a partial/unknown result never releases its reservation.
        // This evaluation measures total latency, not token streaming latency.
        stage = 'upstream_body';
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
        const responseBuffer = Buffer.concat(responseChunks);
        if (response.ok) {
          const usage = reportedUsage(responseBuffer, response.headers.get('content-type'), maxOutputTokens);
          if (usage) {
            const numerator = usage.promptTokens * config.inputQuotaPerMillion + usage.completionTokens * config.outputQuotaPerMillion;
            if (!Number.isSafeInteger(numerator) || numerator < 0) throw new Error('reported_usage_invalid');
            const upper = Math.ceil(numerator / 1_000_000);
            if (upper > reserved) {
              if (Number.isSafeInteger(pendingReserved + upper - reserved)) pendingReserved += upper - reserved;
              throw new Error('charge_exceeded_reservation');
            }
            // Change this request only, never recycle unrelated debt or bill-vs-estimate differences.
            pendingReserved -= reserved - upper;
            reserved = upper;
            log('request_usage_accounted', started);
          }
        }
        log(halted ? 'stopped_with_reservation' : 'request_complete', started);
        // fetch decodes content encoding; do not forward stale length/encoding headers.
        // Release only the in-flight marker; pendingReserved remains charged against the budget.
        reserved = 0; busy = false;
        res.writeHead(response.status, { 'content-type': response.headers.get('content-type') || 'application/json',
          'cache-control': 'no-store' });
        res.end(responseBuffer);
      } finally { res.off('close', onClose); }
    } catch (error) {
      // Any unknown result may already be billed; never retry upstream automatically.
      halt(['usage_rollback', 'charge_unconfirmed', 'charge_exceeded_reservation', 'budget_exceeded'].includes(error?.message)
        ? error.message : 'request_failed', started, error, stage);
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
  startBudgetProxy(configFromEnv()).catch(error => {
    process.stderr.write(JSON.stringify({ status: 'startup_failed', elapsedMs: 0, used: 0, reserved: 0, ...diagnostics(error, 'pre_usage') }) + '\n');
    process.exitCode = 1;
  });
}
