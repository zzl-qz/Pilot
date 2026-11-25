#!/usr/bin/env node
/**
 * 把一批回放结果汇总成 Markdown 报告。
 * 用法：node evaluation/replay/report.mjs --batch r1
 */
import { readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('.', import.meta.url)).replace(/[\\/]$/, '');
const repo = resolve(HERE, '../..');

const batchIndex = process.argv.indexOf('--batch');
const batch = batchIndex > 0 ? process.argv[batchIndex + 1] : null;
if (!batch) throw new Error('需要 --batch <批次名>');

const dir = join(repo, '.pilot-eval', 'replay-runs', batch);
const summary = JSON.parse(await readFile(join(dir, 'summary.json'), 'utf8'));
const records = (await readFile(join(dir, 'results.jsonl'), 'utf8')).split('\n').filter(Boolean).map(line => JSON.parse(line));

const pct = (n, d) => (d ? `${Math.round((n / d) * 1000) / 10}%` : '—');
const percentile = (values, p) => {
  const list = values.filter(v => Number.isFinite(v)).sort((a, b) => a - b);
  if (!list.length) return null;
  return list[Math.min(list.length - 1, Math.floor((p / 100) * list.length))];
};
const num = v => (Number.isFinite(v) ? v : 0);

const completed = records.filter(r => r.status === 'COMPLETED');
const passed = records.filter(r => r.passed);
const falseCompletion = records.filter(r => r.falseCompletion);
const runSeconds = records.map(r => r.runSeconds);
const totalInput = records.reduce((a, r) => a + num(r.diagnostics?.inputTokens), 0);
const totalCached = records.reduce((a, r) => a + num(r.diagnostics?.cachedInputTokens), 0);
const totalOutput = records.reduce((a, r) => a + num(r.diagnostics?.outputTokens), 0);
const totalRequests = records.reduce((a, r) => a + num(r.diagnostics?.modelRequests), 0);

const byTier = ['S', 'M', 'L'].map(tier => {
  const list = records.filter(r => r.tier === tier);
  return { tier, total: list.length, passed: list.filter(r => r.passed).length };
});

const rows = records
  .slice()
  .sort((a, b) => (a.tier.localeCompare(b.tier) || a.id.localeCompare(b.id, undefined, { numeric: true })))
  .map(r => `| ${r.id} | ${r.status} | ${r.passed ? '✅' : '❌'} | ${r.diagnostics?.modelRequests ?? '—'} | ${r.diagnostics?.inputTokens ? Math.round(r.diagnostics.inputTokens / 1000) + 'k' : '—'} | ${r.runSeconds} | ${r.sessionId ?? '—'} |`);

const md = `# 回放评测结果 · ${batch}

- 生成时间：${summary.generatedAt}
- 模型：\`${summary.model ?? '未知'}\`
- 任务：${records.length} 个（S ${byTier[0].total} / M ${byTier[1].total} / L ${byTier[2].total}）
- 验收口径：运行终态 COMPLETED **且** 注入隐藏用例后测试通过

## 汇总

| 指标 | 值 |
| --- | --- |
| pass@1 | **${pct(passed.length, records.length)}**（${passed.length}/${records.length}） |
| 正常收尾 | ${completed.length} / ${records.length} |
| 误报完成率 | **${pct(falseCompletion.length, completed.length)}**（${falseCompletion.length}/${completed.length}） |
| 分档通过 | S ${byTier[0].passed}/${byTier[0].total}　M ${byTier[1].passed}/${byTier[1].total}　L ${byTier[2].passed}/${byTier[2].total} |
| 耗时 p50 / p95 | ${percentile(runSeconds, 50)}s / ${percentile(runSeconds, 95)}s |
| 模型请求总数 | ${totalRequests}（平均 ${Math.round(totalRequests / records.length)} 次/任务） |
| 输入 token | ${totalInput.toLocaleString()} |
| 其中缓存命中 | ${totalCached.toLocaleString()}（${pct(totalCached, totalInput)}） |
| 未命中输入 token | ${(totalInput - totalCached).toLocaleString()} |
| 输出 token | ${totalOutput.toLocaleString()} |
| 每成功任务未命中输入 | ${passed.length ? Math.round((totalInput - totalCached) / passed.length).toLocaleString() : '—'} |

## 逐任务

| 任务 | 终态 | 通过 | 模型请求 | 输入 token | 耗时(s) | 会话 |
| --- | --- | --- | --- | --- | --- | --- |
${rows.join('\n')}

## 边界

- 任务来自本仓库的历史提交，只代表这一种工作类型，不能外推为通用编码能力。
- 每个任务只跑一次，不能据此得出统计显著性；需要对照时必须固定任务集与版本。
- 验收按模块跑测试，通过表示该用例与既有用例在候选代码上通过，不代表人工评审通过。
`;

const out = join(HERE, `results-${batch}.md`);
await writeFile(out, md, 'utf8');
console.log(`报告写入 ${out}`);
