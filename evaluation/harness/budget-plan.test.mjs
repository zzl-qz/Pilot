import test from 'node:test';
import assert from 'node:assert/strict';
import { planBudget } from './budget-plan.mjs';

function input() { return { state: { initialUsed: 1000, lastUsed: 1200, maxQuota: 10000, pendingReserved: 500 },
  latestUsed: 1300, inputQuotaPerMillion: 2000000, outputQuotaPerMillion: 6000000, additionalRuns: 2, quotaPerCny: 500000,
  records: Array.from({ length: 3 }, () => ({ inputTokens: 100, outputTokens: 10, usageExact: true })) }; }

test('原始累计基线和未结算预留不能因新一批评估重置', () => {
  const report = planBudget(input());
  assert.equal(report.spent, 300); assert.equal(report.pendingReserved, 400); assert.equal(report.remaining, 9300);
  assert.equal(report.fullPriceP95PerTrial, 260); assert.equal(report.plannedQuota, 780);
  assert.equal(report.verdict, 'needs_reconciliation');
});
test('稳定计数不代表历史请求已结算', () => {
  const args = input(); args.latestUsed = 1200;
  assert.equal(planBudget(args).pendingReserved, 500);
});
test('已结算预算和完整用量只能支持有界试跑，失败用量也必须计入', () => {
  const args = input(); args.state.pendingReserved = 0; args.records[2] = { inputTokens: 1000, outputTokens: 100, usageExact: true, success: false };
  assert.equal(planBudget(args).fullPriceP95PerTrial, 2600);
  assert.equal(planBudget(args).verdict, 'ready_for_bounded_pilot');
  args.additionalRuns = 10; assert.equal(planBudget(args).verdict, 'needs_reconciliation');
});
test('未知用量与 dry-run 不充当零成本样本，已锁定预算不能被规划器解锁', () => {
  const args = input(); args.state.pendingReserved = 0; args.state.locked = true;
  args.records[0].dryRun = true; args.records[1].usageExact = false;
  const report = planBudget(args); assert.equal(report.measuredTrials, 1); assert.equal(report.verdict, 'needs_reconciliation');
});
test('拒绝回退、负数、异常换算与溢出', () => {
  for (const alter of [a => a.latestUsed = 1199, a => a.state.pendingReserved = -1, a => a.inputQuotaPerMillion = Infinity,
    a => a.quotaPerCny = 0, a => a.headroom = .5, a => a.additionalRuns = .5, a => a.additionalRuns = Number.MAX_SAFE_INTEGER]) {
    const args = input(); alter(args); assert.throws(() => planBudget(args));
  }
});
