#!/usr/bin/env node
/**
 * 把补跑批次的结果并回主批次，并按并集重算汇总。
 * 用法：node evaluation/replay/merge.mjs --into r2 --from r3
 */
import { readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('.', import.meta.url)).replace(/[\\/]$/, '');
const repo = resolve(HERE, '../..');

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : null;
}

const into = arg('into');
const from = arg('from');
if (!into || !from) throw new Error('需要 --into 与 --from');
const runDir = batch => join(repo, '.pilot-eval', 'replay-runs', batch);
const readJsonl = async path => (await readFile(path, 'utf8')).split('\n').filter(Boolean).map(line => JSON.parse(line));

const target = await readJsonl(join(runDir(into), 'results.jsonl'));
const incoming = await readJsonl(join(runDir(from), 'results.jsonl'));
const byId = new Map(target.map(record => [record.id, record]));
for (const record of incoming) byId.set(record.id, { ...record, mergedFrom: from });
const merged = [...byId.values()].sort((a, b) => a.id.localeCompare(b.id, undefined, { numeric: true }));
await writeFile(join(runDir(into), 'results.jsonl'), merged.map(r => JSON.stringify(r)).join('\n') + '\n', 'utf8');

const num = v => (Number.isFinite(v) ? v : 0);
const passed = merged.filter(r => r.passed).length;
const completed = merged.filter(r => r.status === 'COMPLETED');
const summary = {
  batch: into,
  generatedAt: new Date().toISOString(),
  mergedFrom: [into, from],
  model: (await (await fetch('http://127.0.0.1:9999/api/settings')).json()).model ?? null,
  tasks: merged.length,
  passed,
  passAt1: merged.length ? Math.round((passed / merged.length) * 1000) / 10 : null,
  falseCompletions: merged.filter(r => r.falseCompletion).length,
  totalRunSeconds: merged.reduce((a, r) => a + num(r.runSeconds), 0),
  runs: merged.map(r => ({
    id: r.id, tier: r.tier, status: r.status, passed: r.passed,
    failureCode: r.failureCode, runSeconds: r.runSeconds, sessionId: r.sessionId,
    requests: r.diagnostics?.modelRequests ?? null,
    inputTokens: r.diagnostics?.inputTokens ?? null,
    cachedInputTokens: r.diagnostics?.cachedInputTokens ?? null,
    outputTokens: r.diagnostics?.outputTokens ?? null
  }))
};
await writeFile(join(runDir(into), 'summary.json'), JSON.stringify(summary, null, 2), 'utf8');
console.log(`合并完成: ${passed}/${merged.length} 通过 (${summary.passAt1}%), 正常收尾 ${completed.length}`);
