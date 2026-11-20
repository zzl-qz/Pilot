#!/usr/bin/env node
/**
 * Pilot 单步反事实重放工具（V2 验证核心，跨平台）。
 *
 * 读取由 pilot.debug.dump-model-requests=true 落盘的 req-<seq>.json，
 * 支持单一变量替换（系统提示词、工具定义、单条消息或自定义 JS 补丁模块），
 * 直接在内存中读取 <PILOT_DATA_DIR>/pilot.db 中本机已配置的 endpoint 与 api-key
 * （严禁打印、落盘或输出任何密钥或鉴权头），默认采样 5 次并统计目标缺陷触发率对比。
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { DatabaseSync } from 'node:sqlite';
import { pathToFileURL } from 'node:url';

const DEFAULT_MAIN_REPO = path.resolve(
  process.env.PILOT_MAIN_REPO || path.join(os.homedir(), 'Desktop', 'pilot')
);
const DEFAULT_DATA_DIR = path.resolve(
  process.env.PILOT_DATA_DIR || path.join(DEFAULT_MAIN_REPO, '.pilot')
);

function parseArgs(argv) {
  const opts = {
    requestFile: '',
    dataDir: DEFAULT_DATA_DIR,
    repeat: 5,
    concurrency: 2,
    timeoutMs: 120000,
    compareBaseline: false,
    systemReplaceFile: '',
    systemSub: [],
    patchTool: [],
    replaceMessage: [],
    patchModule: '',
    defectToolRegex: '',
    defectTextRegex: '',
    expectToolRegex: '',
    classifierModule: '',
    forceNonStream: true,
    out: '',
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--request' || a === '-r') opts.requestFile = argv[++i] || '';
    else if (a === '--data-dir') opts.dataDir = path.resolve(argv[++i] || DEFAULT_DATA_DIR);
    else if (a === '--repeat' || a === '-n') opts.repeat = Math.max(1, Number(argv[++i] || 5));
    else if (a === '--concurrency' || a === '-c') opts.concurrency = Math.max(1, Number(argv[++i] || 2));
    else if (a === '--timeout-ms') opts.timeoutMs = Math.max(5000, Number(argv[++i] || 120000));
    else if (a === '--compare-baseline') opts.compareBaseline = true;
    else if (a === '--system-replace-file') opts.systemReplaceFile = argv[++i] || '';
    else if (a === '--system-sub') opts.systemSub.push(argv[++i] || '');
    else if (a === '--patch-tool') opts.patchTool.push(argv[++i] || '');
    else if (a === '--replace-message') opts.replaceMessage.push(argv[++i] || '');
    else if (a === '--patch-module') opts.patchModule = argv[++i] || '';
    else if (a === '--defect-tool') opts.defectToolRegex = argv[++i] || '';
    else if (a === '--defect-text') opts.defectTextRegex = argv[++i] || '';
    else if (a === '--expect-tool') opts.expectToolRegex = argv[++i] || '';
    else if (a === '--classifier') opts.classifierModule = argv[++i] || '';
    else if (a === '--keep-stream') opts.forceNonStream = false;
    else if (a === '--out' || a === '-o') opts.out = argv[++i] || '';
    else if (a === '--help' || a === '-h') {
      console.log(
        [
          '用法: node replay.mjs --request <req-N.json> [options]',
          '  --repeat <N>                      重复采样次数（默认 5）',
          '  --concurrency <k>                 并发数（默认 2）',
          '  --compare-baseline                同时跑未改动基线与补丁版本各 N 次并输出缺陷率对比',
          '  --system-replace-file <file>      用文件内容替换 system 提示词',
          '  --system-sub <regex>:::<repl>     对 system 提示词做正则替换（可多次传入）',
          '  --patch-tool <name>:<jsonFile>    替换指定工具的 JSON 定义（可多次传入）',
          '  --replace-message <idx>:<file>    替换 messages[idx].content（可多次传入）',
          '  --patch-module <file.mjs>         自定义补丁模块（export default (body) => newBody）',
          '  --defect-tool <regex>             命中此工具名正则判为触发目标缺陷',
          '  --defect-text <regex>             命中此正文正则判为触发目标缺陷',
          '  --expect-tool <regex>             未命中期望工具名正则判为触发目标缺陷',
          '  --classifier <file.mjs>           自定义判定器（export default (trial) => boolean）',
          '  --out <file.json>                 保存完整脱敏结果 JSON',
        ].join('\n')
      );
      process.exit(0);
    }
  }
  if (!opts.requestFile) throw new Error('必须提供 --request <req-seq.json>');
  return opts;
}

function loadCredentialsInMemory(dataDir, fallbackEndpoint) {
  let endpoint = process.env.PILOT_MODEL_ENDPOINT || '';
  let apiKey = process.env.PILOT_MODEL_API_KEY || '';
  const dbPath = path.join(dataDir, 'pilot.db');
  if ((!endpoint || !apiKey) && fs.existsSync(dbPath)) {
    const db = new DatabaseSync(dbPath, { readOnly: true });
    try {
      const rows = db.prepare("SELECT key, value FROM settings WHERE key IN ('model.endpoint', 'model.api-key')").all();
      for (const row of rows) {
        if (row.key === 'model.endpoint' && !endpoint) endpoint = String(row.value || '').trim();
        if (row.key === 'model.api-key' && !apiKey) apiKey = String(row.value || '').trim();
      }
    } finally {
      db.close();
    }
  }
  if (!endpoint) endpoint = fallbackEndpoint || '';
  if (!endpoint) throw new Error('无法解析模型端点（请检查 PILOT_DATA_DIR/pilot.db 或 PILOT_MODEL_ENDPOINT）');
  return { endpoint, apiKey };
}

function resolvePostUrl(endpoint, dumpedEndpoint) {
  // Prefer dumped path when endpoint is a base URL
  if (dumpedEndpoint && /^https?:\/\//i.test(dumpedEndpoint)) {
    return dumpedEndpoint;
  }
  const clean = endpoint.replace(/\/$/, '');
  if (/\/(chat\/completions|responses|messages)$/.test(clean)) return clean;
  if (clean.endsWith('/v1')) return `${clean}/chat/completions`;
  return `${clean}/v1/chat/completions`;
}

function sanitizeUrl(urlStr) {
  try {
    const u = new URL(urlStr);
    return `${u.protocol}//${u.host}${u.pathname}`;
  } catch {
    return '(invalid-url)';
  }
}

async function applyPatches(baseBody, opts) {
  const body = structuredClone(baseBody);
  if (opts.systemReplaceFile) {
    const newSys = fs.readFileSync(opts.systemReplaceFile, 'utf8');
    if (Array.isArray(body.messages) && body.messages[0]?.role === 'system') {
      body.messages[0].content = newSys;
    } else if (typeof body.system === 'string') {
      body.system = newSys;
    } else if (Array.isArray(body.messages)) {
      body.messages.unshift({ role: 'system', content: newSys });
    }
  }
  for (const sub of opts.systemSub) {
    const idx = sub.indexOf(':::');
    if (idx < 0) throw new Error(`--system-sub 格式应为 <regex>:::<replacement>: ${sub}`);
    const re = new RegExp(sub.slice(0, idx), 'g');
    const repl = sub.slice(idx + 3);
    if (Array.isArray(body.messages) && body.messages[0]?.role === 'system' && typeof body.messages[0].content === 'string') {
      body.messages[0].content = body.messages[0].content.replace(re, repl);
    } else if (typeof body.system === 'string') {
      body.system = body.system.replace(re, repl);
    }
  }
  for (const item of opts.patchTool) {
    const sep = item.indexOf(':');
    if (sep < 0) throw new Error(`--patch-tool 格式应为 <toolName>:<jsonFile>: ${item}`);
    const name = item.slice(0, sep);
    const jsonPath = item.slice(sep + 1);
    const replacement = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
    if (Array.isArray(body.tools)) {
      body.tools = body.tools.map((t) => {
        const tName = t?.function?.name || t?.name;
        return tName === name ? replacement : t;
      });
    }
  }
  for (const item of opts.replaceMessage) {
    const sep = item.indexOf(':');
    if (sep < 0) throw new Error(`--replace-message 格式应为 <index>:<textFile>: ${item}`);
    const index = Number(item.slice(0, sep));
    const textPath = item.slice(sep + 1);
    if (Array.isArray(body.messages) && body.messages[index]) {
      body.messages[index].content = fs.readFileSync(textPath, 'utf8');
    }
  }
  if (opts.patchModule) {
    const mod = await import(pathToFileURL(path.resolve(opts.patchModule)).href);
    const fn = mod.default || mod.patch;
    if (typeof fn !== 'function') throw new Error(`补丁模块缺少 default/patch 函数: ${opts.patchModule}`);
    return await fn(body);
  }
  return body;
}

function parseSseCompletion(rawText) {
  let text = '';
  let reasoning = '';
  let finishReason = null;
  let usage = null;
  const toolAcc = new Map();
  for (const line of rawText.split(/\r?\n/)) {
    if (!line.startsWith('data:')) continue;
    const data = line.slice(5).trim();
    if (!data || data === '[DONE]') continue;
    let obj;
    try {
      obj = JSON.parse(data);
    } catch {
      continue;
    }
    if (obj.usage) usage = obj.usage;
    const choice = obj.choices?.[0];
    if (!choice) continue;
    if (choice.finish_reason) finishReason = choice.finish_reason;
    const delta = choice.delta || {};
    if (typeof delta.content === 'string') text += delta.content;
    if (typeof delta.reasoning_content === 'string') reasoning += delta.reasoning_content;
    if (Array.isArray(delta.tool_calls)) {
      for (const tc of delta.tool_calls) {
        const idx = tc.index ?? 0;
        const cur = toolAcc.get(idx) || { name: '', arguments: '' };
        if (tc.function?.name) cur.name += tc.function.name;
        if (tc.function?.arguments) cur.arguments += tc.function.arguments;
        toolAcc.set(idx, cur);
      }
    }
  }
  const toolCalls = [...toolAcc.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([, v]) => v);
  return { text, reasoning, toolCalls, finishReason, usage };
}

function parseJsonCompletion(obj) {
  // OpenAI Chat Completions
  if (Array.isArray(obj.choices)) {
    const choice = obj.choices[0] || {};
    const msg = choice.message || {};
    const toolCalls = (msg.tool_calls || []).map((tc) => ({
      name: tc.function?.name || tc.name || '',
      arguments: tc.function?.arguments || '',
    }));
    return {
      text: typeof msg.content === 'string' ? msg.content : '',
      reasoning: typeof msg.reasoning_content === 'string' ? msg.reasoning_content : '',
      toolCalls,
      finishReason: choice.finish_reason || null,
      usage: obj.usage || null,
    };
  }
  // Anthropic Messages
  if (Array.isArray(obj.content)) {
    let text = '';
    const toolCalls = [];
    for (const block of obj.content) {
      if (block.type === 'text') text += block.text || '';
      else if (block.type === 'tool_use') {
        toolCalls.push({ name: block.name || '', arguments: JSON.stringify(block.input || {}) });
      }
    }
    return {
      text,
      reasoning: '',
      toolCalls,
      finishReason: obj.stop_reason || null,
      usage: obj.usage || null,
    };
  }
  return { text: JSON.stringify(obj).slice(0, 500), reasoning: '', toolCalls: [], finishReason: null, usage: null };
}

async function runSingleTrial(trialIndex, url, apiKey, payload, timeoutMs) {
  const isAnthropic = /\/messages$/.test(url);
  const headers = { 'Content-Type': 'application/json' };
  if (apiKey) {
    if (isAnthropic) {
      headers['x-api-key'] = apiKey;
      headers['anthropic-version'] = '2023-06-01';
    } else {
      headers.Authorization = `Bearer ${apiKey}`;
    }
  }
  const started = Date.now();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const resp = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
      signal: controller.signal,
    });
    const raw = await resp.text();
    const latencyMs = Date.now() - started;
    if (!resp.ok) {
      return {
        trial: trialIndex,
        ok: false,
        httpStatus: resp.status,
        latencyMs,
        error: raw.slice(0, 400),
        toolCalls: [],
        textSummary: '',
      };
    }
    const contentType = resp.headers.get('content-type') || '';
    const parsed =
      contentType.includes('text/event-stream') || raw.trimStart().startsWith('data:')
        ? parseSseCompletion(raw)
        : parseJsonCompletion(JSON.parse(raw));
    return {
      trial: trialIndex,
      ok: true,
      httpStatus: resp.status,
      latencyMs,
      finishReason: parsed.finishReason,
      toolCalls: parsed.toolCalls.map((t) => ({
        name: t.name,
        argsPreview: String(t.arguments || '').slice(0, 220),
      })),
      textSummary: parsed.text.replace(/\s+/g, ' ').trim().slice(0, 260),
      reasoningChars: parsed.reasoning.length,
      usage: parsed.usage,
    };
  } catch (err) {
    return {
      trial: trialIndex,
      ok: false,
      httpStatus: 0,
      latencyMs: Date.now() - started,
      error: String(err.message || err),
      toolCalls: [],
      textSummary: '',
    };
  } finally {
    clearTimeout(timer);
  }
}

async function buildClassifier(opts) {
  if (opts.classifierModule) {
    const mod = await import(pathToFileURL(path.resolve(opts.classifierModule)).href);
    const fn = mod.default || mod.classify;
    if (typeof fn !== 'function') throw new Error(`分类器模块缺少 default/classify 函数: ${opts.classifierModule}`);
    return fn;
  }
  const defectToolRe = opts.defectToolRegex ? new RegExp(opts.defectToolRegex) : null;
  const defectTextRe = opts.defectTextRegex ? new RegExp(opts.defectTextRegex) : null;
  const expectToolRe = opts.expectToolRegex ? new RegExp(opts.expectToolRegex) : null;
  if (!defectToolRe && !defectTextRe && !expectToolRe) return null;
  return (trial) => {
    if (!trial.ok) return true;
    const names = trial.toolCalls.map((t) => t.name);
    if (defectToolRe && names.some((n) => defectToolRe.test(n))) return true;
    if (defectTextRe && defectTextRe.test(trial.textSummary)) return true;
    if (expectToolRe && !names.some((n) => expectToolRe.test(n))) return true;
    return false;
  };
}

async function runBatch(label, url, apiKey, body, opts, classifier) {
  const payload = structuredClone(body);
  if (opts.forceNonStream && 'stream' in payload) {
    payload.stream = false;
    delete payload.stream_options;
  }
  const trials = new Array(opts.repeat);
  let nextIdx = 0;
  async function worker() {
    while (true) {
      const idx = nextIdx++;
      if (idx >= opts.repeat) break;
      const res = await runSingleTrial(idx + 1, url, apiKey, payload, opts.timeoutMs);
      if (classifier) {
        res.defectTriggered = Boolean(await classifier(res));
      }
      trials[idx] = res;
    }
  }
  await Promise.all(Array.from({ length: Math.min(opts.concurrency, opts.repeat) }, () => worker()));
  const okCount = trials.filter((t) => t.ok).length;
  const defectCount = classifier ? trials.filter((t) => t.defectTriggered).length : null;
  const toolHistogram = {};
  for (const t of trials) {
    const sig = t.toolCalls.length ? t.toolCalls.map((c) => c.name).join('+') : '(no-tool)';
    toolHistogram[sig] = (toolHistogram[sig] || 0) + 1;
  }
  return {
    label,
    repeat: opts.repeat,
    succeeded: okCount,
    defectCount,
    defectRate: defectCount !== null ? defectCount / opts.repeat : null,
    toolHistogram,
    trials,
  };
}

function printBatch(batch) {
  console.log(`\n--- [${batch.label}] repeat=${batch.repeat}, ok=${batch.succeeded}/${batch.repeat} ---`);
  if (batch.defectCount !== null) {
    console.log(`目标缺陷触发率: ${batch.defectCount}/${batch.repeat} (${(batch.defectRate * 100).toFixed(1)}%)`);
  }
  console.log(`工具选择分布: ${JSON.stringify(batch.toolHistogram)}`);
  for (const t of batch.trials) {
    const tools = t.toolCalls.map((c) => `${c.name}(${c.argsPreview.slice(0, 60)})`).join(', ') || '(text-only)';
    const flag = t.defectTriggered === undefined ? '' : t.defectTriggered ? ' [DEFECT]' : ' [OK]';
    console.log(
      `  #${t.trial} ok=${t.ok} ${t.latencyMs}ms finish=${t.finishReason || 'err'}${flag} | tools=${tools}` +
        (t.textSummary ? ` | text="${t.textSummary.slice(0, 80)}"` : '') +
        (t.error ? ` | err=${t.error.slice(0, 100)}` : '')
    );
  }
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const dumped = JSON.parse(fs.readFileSync(opts.requestFile, 'utf8'));
  const baseBody = dumped.body || dumped;
  const { endpoint, apiKey } = loadCredentialsInMemory(opts.dataDir, dumped.endpoint);
  const url = resolvePostUrl(endpoint, dumped.endpoint);
  const classifier = await buildClassifier(opts);

  const hasPatch = Boolean(
    opts.systemReplaceFile ||
      opts.systemSub.length ||
      opts.patchTool.length ||
      opts.replaceMessage.length ||
      opts.patchModule
  );

  const output = {
    capturedAt: new Date().toISOString(),
    sourceRequestFile: path.resolve(opts.requestFile),
    sourceSessionId: dumped.sessionId || '',
    sourceRunId: dumped.runId || '',
    sourceSequence: dumped.sequence ?? null,
    endpoint: sanitizeUrl(url),
    model: baseBody.model || '',
    repeat: opts.repeat,
    results: {},
  };

  if (opts.compareBaseline || !hasPatch) {
    const baseBatch = await runBatch('baseline', url, apiKey, baseBody, opts, classifier);
    output.results.baseline = baseBatch;
    printBatch(baseBatch);
  }

  if (hasPatch) {
    const patchedBody = await applyPatches(baseBody, opts);
    const varBatch = await runBatch('variant', url, apiKey, patchedBody, opts, classifier);
    output.results.variant = varBatch;
    printBatch(varBatch);
  }

  if (output.results.baseline && output.results.variant && output.results.baseline.defectCount !== null) {
    const b = output.results.baseline;
    const v = output.results.variant;
    console.log(
      `\n=== 反事实对比 ===\n基线: ${b.defectCount}/${b.repeat} (${(b.defectRate * 100).toFixed(1)}%) → ` +
        `改动后: ${v.defectCount}/${v.repeat} (${(v.defectRate * 100).toFixed(1)}%)`
    );
  }

  if (opts.out) {
    fs.mkdirSync(path.dirname(path.resolve(opts.out)), { recursive: true });
    fs.writeFileSync(opts.out, JSON.stringify(output, null, 2) + '\n', 'utf8');
    console.log(`已保存脱敏重放结果至: ${path.resolve(opts.out)}`);
  }
}

main().catch((err) => {
  console.error(`[replay] 错误: ${err.message || err}`);
  process.exit(1);
});
