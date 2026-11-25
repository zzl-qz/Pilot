#!/usr/bin/env node
/**
 * 对账：把批次里因连接中断/验收命令问题导致的记录，按会话真实终态与重新验收修正。
 *
 * 用法：node evaluation/replay/reconcile.mjs --batch r2 [--tasks S-01,S-02]
 */
import { spawnSync } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('.', import.meta.url)).replace(/[\\/]$/, '');
const repo = resolve(HERE, '../..');
const TERMINAL = new Set(['COMPLETED', 'INTERRUPTED', 'FAILED', 'CANCELLED', 'PAUSED', 'ARCHIVED']);
const baseUrl = 'http://127.0.0.1:9999';

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

async function api(path) {
  const res = await fetch(`${baseUrl}${path}`, { signal: AbortSignal.timeout(30_000) });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  return res.json();
}

const batch = arg('batch');
if (!batch) throw new Error('需要 --batch');
const only = arg('tasks') ? arg('tasks').split(',').map(s => s.trim()) : null;
const dir = join(repo, '.pilot-eval', 'replay-runs', batch);
const jsonlPath = join(dir, 'results.jsonl');
const records = (await readFile(jsonlPath, 'utf8')).split('\n').filter(Boolean).map(line => JSON.parse(line));

for (const record of records) {
  if (only && !only.includes(record.id)) continue;
  // 没有会话说明这次根本没启动模型，重验只会验到空改动；交给补跑
  if (!record.sessionId) { console.log(`${record.id}: 无会话，跳过（需要补跑）`); continue; }
  const before = `${record.status}/${record.passed}`;

  // 1) 有会话的，以服务端真实终态为准
  if (record.sessionId) {
    try {
      const d = await api(`/api/sessions/${record.sessionId}/diagnostics`);
      if (TERMINAL.has(d.sessionStatus)) {
        record.diagnostics = d;
        record.status = d.sessionStatus;
        record.failureCode = d.failureCode ?? null;
      }
      const active = await api(`/api/sessions/${record.sessionId}/runs/active`);
      if (active.runId !== null) console.log(`${record.id}: 运行仍在进行，跳过`);
    } catch (error) {
      console.log(`${record.id}: 读取会话失败 ${error.message}`);
    }
  }

  // 2) 重新验收（隐藏用例从提交取出并覆盖模型改动过的测试）
  const accept = spawnSync(process.execPath, [
    join(HERE, 'replay.mjs'), 'accept', '--task', record.id, '--workspace', record.workspace, '--repo', repo
  ], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, env: process.env });
  record.acceptExit = accept.status;
  record.acceptOutput = `${accept.stdout ?? ''}${accept.stderr ?? ''}`.split('\n').slice(-12).join('\n');
  record.passed = record.status === 'COMPLETED' && accept.status === 0;
  record.falseCompletion = record.status === 'COMPLETED' && accept.status !== 0;
  record.reconciledAt = new Date().toISOString();
  console.log(`${record.id}: ${before} -> ${record.status}/${record.passed}`);
}

await writeFile(jsonlPath, records.map(r => JSON.stringify(r)).join('\n') + '\n', 'utf8');

// 3) 重算汇总
const num = v => (Number.isFinite(v) ? v : 0);
const passed = records.filter(r => r.passed).length;
const completed = records.filter(r => r.status === 'COMPLETED');
const summary = {
  batch,
  generatedAt: new Date().toISOString(),
  reconciled: true,
  model: (await api('/api/settings')).model ?? null,
  tasks: records.length,
  passed,
  passAt1: records.length ? Math.round((passed / records.length) * 1000) / 10 : null,
  falseCompletions: records.filter(r => r.falseCompletion).length,
  totalRunSeconds: records.reduce((a, r) => a + num(r.runSeconds), 0),
  runs: records.map(r => ({
    id: r.id, tier: r.tier, status: r.status, passed: r.passed,
    failureCode: r.failureCode, runSeconds: r.runSeconds, sessionId: r.sessionId,
    requests: r.diagnostics?.modelRequests ?? null,
    inputTokens: r.diagnostics?.inputTokens ?? null,
    cachedInputTokens: r.diagnostics?.cachedInputTokens ?? null,
    outputTokens: r.diagnostics?.outputTokens ?? null
  }))
};
await writeFile(join(dir, 'summary.json'), JSON.stringify(summary, null, 2), 'utf8');
console.log(`\n对账完成: ${passed}/${records.length} 通过 (${summary.passAt1}%)  正常收尾 ${completed.length}`);
