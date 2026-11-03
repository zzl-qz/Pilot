#!/usr/bin/env node
// Offline planning only. Observed discounts are never used to lower the spending guard.
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const quota = (value, label) => {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${label} 必须为非负安全整数`);
  return value;
};
const percentile = (values, p) => values.length ? [...values].sort((a, b) => a - b)[Math.ceil(values.length * p) - 1] : null;

/** 使用原始累计额度基线；新增账单增量最多结算等额预留，绝不因余额稳定而释放未知费用。 */
export function planBudget({ state, latestUsed, records, inputQuotaPerMillion, outputQuotaPerMillion, additionalRuns,
  quotaPerCny = null, headroom = 1.5 }) {
  const initial = quota(state.initialUsed, 'initialUsed'), previous = quota(state.lastUsed, 'lastUsed');
  const used = quota(latestUsed, 'latestUsed'), cap = quota(state.maxQuota, 'maxQuota');
  const reserved = quota(state.pendingReserved, 'pendingReserved'); quota(additionalRuns, 'additionalRuns');
  if (previous < initial || used < previous) throw new Error('额度计数回退或基线不一致，必须核对账单');
  if (![inputQuotaPerMillion, outputQuotaPerMillion].every(v => Number.isFinite(v) && v > 0)
      || !Number.isFinite(headroom) || headroom < 1 || (quotaPerCny != null && (!Number.isFinite(quotaPerCny) || quotaPerCny <= 0)))
    throw new Error('价格、余量或人民币换算单位无效');
  const spent = used - initial, pendingReserved = Math.max(0, reserved - (used - previous));
  const remaining = Math.max(0, cap - spent - pendingReserved);
  const measured = records.filter(r => !r.dryRun && r.usageExact === true
    && Number.isSafeInteger(r.inputTokens) && r.inputTokens >= 0 && Number.isSafeInteger(r.outputTokens) && r.outputTokens >= 0);
  const trialUpperEstimates = measured.map(r => Math.ceil((r.inputTokens * inputQuotaPerMillion
    + r.outputTokens * outputQuotaPerMillion) / 1_000_000));
  if (trialUpperEstimates.some(value => !Number.isSafeInteger(value))) throw new Error('费用计算超出安全整数范围');
  const p95 = percentile(trialUpperEstimates, .95);
  const plannedQuota = p95 == null ? null : Math.ceil(p95 * headroom * additionalRuns);
  if (plannedQuota != null && !Number.isSafeInteger(plannedQuota)) throw new Error('计划费用超出安全整数范围');
  const reasons = [];
  if (state.locked || state.busy) reasons.push('预算保护已锁定或仍有在途请求，先人工核对再启动');
  if (pendingReserved > 0) reasons.push('仍有未结算预留，不能按观测折扣释放');
  if (measured.length < records.length || measured.length < 3) reasons.push('真实完整用量样本不足，预测只供排期');
  if (plannedQuota == null || plannedQuota > remaining) reasons.push('可用保守预算不足以覆盖计划');
  return {
    originalBaseline: initial, latestUsed: used, maxQuota: cap, spent, pendingReserved, remaining,
    spentCny: quotaPerCny == null ? null : spent / quotaPerCny,
    measuredTrials: measured.length, totalTrials: records.length, additionalRuns,
    fullPriceP95PerTrial: p95, headroom, plannedQuota,
    verdict: reasons.length ? 'needs_reconciliation' : 'ready_for_bounded_pilot', reasons,
    note: '离线估算不授权付费请求，不改变保护上限；p95 不是未来费用上界，运行时仍需逐请求预留。'
  };
}

async function main() {
  const files = process.argv.slice(2);
  if (files.length !== 1) throw new Error('用法：node budget-plan.mjs <plan-input.json>；输入含 state、latestUsed、records、价格及 additionalRuns');
  console.log(JSON.stringify(planBudget(JSON.parse(await readFile(files[0], 'utf8'))), null, 2));
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href)
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
