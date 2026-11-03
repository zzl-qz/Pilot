import test from 'node:test';
import assert from 'node:assert/strict';
import { aggregate, cacheHitRate, compare, gate, percentile, regressionProbability, summarize, validateBaseline } from './metrics.mjs';

let sessionSequence = 0;
function record(overrides = {}) {
  const result = { schemaVersion: 2, taskId: 't1', model: 'm1', requestedModel: 'm1', promptVersion: 'P1', toolVersion: 'T1',
    fixtureVersion: 'fixture', taskVersion: 'task', environmentVersion: 'environment', modelConfigHash: 'model',
    promptHash: 'prompt', runtimeHash: 'runtime', attempt: 1,
    sessionId: `session-${++sessionSequence}`, scenarioCovered: true,
    success: true, acceptanceRan: true, acceptancePassed: true, reportedComplete: true, terminalStatus: 'COMPLETED',
    durationSeconds: 100, rework: null, takeover: null, costUsd: 0.1, costKnown: true,
    inputTokens: 1000, outputTokens: 200, cacheReadObserved: true, ...overrides };
  if (overrides.success === false && !Object.hasOwn(overrides, 'acceptancePassed')) result.acceptancePassed = false;
  if (overrides.acceptanceRan === false) { result.success = false; result.acceptancePassed = false; }
  return result;
}
function tasks(count, overrides = {}, repeats = 3) {
  return Array.from({ length: repeats }, (_, attempt) => Array.from({ length: count }, (_, index) =>
    record({ taskId: `t${index}`, attempt: attempt + 1, ...overrides }))).flat();
}

test('模型自述成功但未执行外部验收不算成功', () => {
  assert.equal(aggregate([record({ acceptanceRan: false }), record()])[0].successRate, 0.5);
});
test('模型/提示/工具版本分组并区分唯一任务与运行数', () => {
  assert.equal(aggregate([record(), record({ model: 'm2' }), record({ promptVersion: 'P2' })]).length, 3);
  const [group] = aggregate(tasks(2));
  assert.equal(group.tasks, 6); assert.equal(group.uniqueTasks, 2);
});
test('成本缺失不冒充零成本或完整总成本；成功成本包含失败尝试', () => {
  const [group] = aggregate([record({ costUsd: 0.2 }), record({ success: false, acceptancePassed: false, costUsd: 0.4 })]);
  assert.equal(group.costPerSuccessfulTaskUsd, 0.6000000000000001);
  assert.equal(group.falseCompletionRate, 0.5);
  const partial = summarize([record({ costUsd: 0.2 }), record({ costKnown: false, costUsd: null })]);
  assert.equal(partial.costUsd, null); assert.equal(partial.costPerSuccessfulTaskUsd, null);
  assert.equal(partial.avgCostUsd, 0.2); assert.equal(partial.pricedRuns, 1);
  assert.equal(summarize([record({ costKnown: false, costUsd: null })]).avgCostUsd, null);
});
test('没有成功任务时成功成本未知，失败不是免费成功', () => {
  assert.equal(summarize([record({ success: false })]).costPerSuccessfulTaskUsd, null);
});
test('正常收尾但验收失败率仅使用正常收尾且执行了独立验收的样本，并报告覆盖率', () => {
  const summary = summarize([record({ success: false, acceptancePassed: false }),
    record({ reportedComplete: false, terminalStatus: 'CANCELLED', success: false }),
    record({ acceptanceRan: false, success: false })]);
  assert.equal(summary.falseCompletionRate, 1);
  assert.equal(summary.completionVerificationCoverage, 0.5);
  assert.equal(summary.incompleteRate, 1 / 3);
  assert.equal(summary.takeoverRate, null); assert.equal(summary.takeovers, null);
  assert.equal(summary.avgRework, null);
  assert.equal(summarize([{ success: false, acceptanceRan: false }]).falseCompletionRate, null);
});
test('只使用观测值计算耗时分位数、工具失败和重试；零是有效观测', () => {
  const [group] = aggregate([record({ durationSeconds: 0, toolCallCount: 0, failedCommandCount: 0, modelRetryCount: 0 }),
    record({ durationSeconds: 200, toolCallCount: 4, failedCommandCount: 2, modelRetryCount: 2 }),
    record({ durationSeconds: null })]);
  assert.equal(group.avgDurationSeconds, 100); assert.equal(group.p50DurationSeconds, 0);
  assert.equal(group.p95DurationSeconds, 200); assert.equal(group.avgToolCalls, 2);
  assert.equal(group.avgFailedCommands, 1); assert.equal(group.avgModelRetries, 1);
  assert.equal(percentile([], 0.5), null);
});
test('缓存命中率缺失不能稀释观测，异常缓存数有界', () => {
  assert.equal(cacheHitRate([record({ cachedInputTokens: 800 }), record({ cachedInputTokens: 0 })]), 0.4);
  assert.equal(cacheHitRate([record({ inputTokens: 0 })]), null);
  assert.equal(cacheHitRate([record({ cachedInputTokens: 2000 })]), 1);
  assert.equal(cacheHitRate([record({ cachedInputTokens: 500 }), record({ cacheReadObserved: false })]), 0.5);
});
test('单次成功到失败仅标为可疑，不能断言退化或放行', () => {
  const old = [record()], next = [record({ success: false })];
  assert.equal(compare(old, next)[0].regression, false);
  assert.equal(compare(old, next)[0].regressionSuspected, true);
  assert.equal(gate(old, next, { minTasks: 1 }).verdict, 'insufficient');
});
test('历史偶然成功不会导致未来任意一次失败被判回归', () => {
  const old = tasks(1, {}, 3).map((r, i) => ({ ...r, success: i === 0, acceptancePassed: i === 0 }));
  const next = tasks(1, { success: false }, 3);
  assert.equal(gate(old, next, { minTasks: 1 }).verdict, 'insufficient');
  assert.equal(compare(old, next)[0].regression, false);
});
test('重复一致退化被拒绝；逐任务检查不会被其他任务进步掩盖', () => {
  const old = tasks(2, {}, 6).map(r => ({ ...r, success: r.taskId === 't0', acceptancePassed: r.taskId === 't0' }));
  const next = tasks(2, {}, 6).map(r => ({ ...r, success: r.taskId === 't1', acceptancePassed: r.taskId === 't1' }));
  const decision = gate(old, next, { minTasks: 2 });
  assert.equal(decision.verdict, 'reject'); assert.equal(decision.metrics.regressions, 1);
  assert.ok(regressionProbability(tasks(1, {}, 3), tasks(1, { success: false }, 3)) <= 0.05 + 1e-12);
});
test('重复样本稳定、版本可比且成本下降可通过，不声称普遍保证', () => {
  const decision = gate(tasks(20, { costUsd: 0.2 }), tasks(20, { costUsd: 0.1, promptHash: 'prompt-new', promptVersion: 'P2' }));
  assert.equal(decision.verdict, 'pass'); assert.equal(decision.comparisonScope, 'prompt');
  assert.equal(decision.metrics.pairedTasks, 20);
});
test('单项重复20次不能冒充20个任务，样本不足不能放行', () => {
  assert.equal(gate(tasks(1, {}, 20), tasks(1, {}, 20)).verdict, 'insufficient');
  assert.equal(gate(tasks(20, {}, 1), tasks(20, {}, 1)).verdict, 'insufficient');
});
test('旧记录可汇总但缺版本元数据不能进入严格对照', () => {
  const legacy = tasks(20).map(({ schemaVersion, fixtureVersion, ...r }) => r);
  assert.equal(aggregate(legacy)[0].successRate, 1);
  assert.equal(gate(legacy, legacy).verdict, 'insufficient');
});
test('环境、fixture、任务合同或模型配置不同不可比', () => {
  for (const key of ['environmentVersion', 'fixtureVersion', 'taskVersion', 'modelConfigHash']) {
    const decision = gate(tasks(20), tasks(20, { [key]: 'changed' }));
    assert.equal(decision.verdict, 'insufficient', key);
  }
  assert.equal(gate(tasks(20), tasks(20, { runtimeHash: 'new' })).comparisonScope, 'combined');
  assert.equal(gate(tasks(20), tasks(20, { configurationChanged: true })).verdict, 'insufficient');
});
test('重复入账、混合提示版本、重复次数不配对不能制造证据', () => {
  const duplicated = tasks(20);
  assert.equal(gate(tasks(20), [...duplicated, ...duplicated]).verdict, 'reject');
  assert.equal(gate(tasks(20), tasks(20, {}, 4)).verdict, 'insufficient');
  const mixed = tasks(20); mixed[0].promptHash = 'other';
  assert.equal(gate(tasks(20), mixed).verdict, 'insufficient');
});
test('缺任务、dry-run和验收执行率下降必须拦下', () => {
  assert.equal(gate(tasks(21), tasks(20)).verdict, 'reject');
  assert.equal(gate(tasks(20), tasks(20, { dryRun: true })).verdict, 'reject');
  assert.equal(gate(tasks(20), tasks(20, { acceptanceRan: false })).verdict, 'reject');
});
test('部分价格未知使用完整token代理，不能只比较便宜子集', () => {
  const next = tasks(20, { costKnown: false, costUsd: null, inputTokens: 2000 });
  next[0] = { ...next[0], costKnown: true, costUsd: 0.001 };
  assert.equal(gate(tasks(20), next).verdict, 'reject');
  assert.equal(gate(tasks(20), tasks(20, { costKnown: false, costUsd: null, inputTokens: null })).verdict, 'insufficient');
});
test('平均成本按每次运行比较，降低重跑次数不能掩盖涨价', () => {
  assert.equal(gate(tasks(20, {}, 6), tasks(20, { costUsd: 0.2 })).verdict, 'reject');
  assert.equal(gate(tasks(20), tasks(20, { costUsd: 0.103 })).verdict, 'pass');
});


test('不可比环境的成本差异不能被宣称为优化回归', () => {
  const decision = gate(tasks(20), tasks(20, { environmentVersion: 'other', costUsd: 1 }));
  assert.equal(decision.verdict, 'insufficient');
  assert.equal(decision.reasons.some(r => r.includes('成本上升')), false);
});

test('代码验收通过但引导场景未触发单独标覆盖不足，不混为正常收尾后的验收失败', () => {
  const rows = tasks(20).map((r, i) => i === 0 ? { ...r, success: false, scenarioCovered: false } : r);
  assert.equal(summarize(rows).falseCompletionRate, 0);
  assert.equal(gate(tasks(20), rows).verdict, 'insufficient');
});


test('同一session复制并改attempt不能伪造独立重复样本', () => {
  const forged = tasks(20).map(r => ({ ...r, sessionId: 'one-real-run' }));
  const decision = gate(forged, forged);
  assert.equal(decision.verdict, 'reject');
  assert.ok(decision.reasons.some(reason => reason.includes('修改 attempt')));
});

test('不同task借用同一会话也不能算独立样本', () => {
  const rows = tasks(20); rows[1].sessionId = rows[0].sessionId;
  assert.equal(gate(tasks(20), rows).verdict, 'reject');
});

test('schema2矛盾结果不能凭success=true计入成功或通过门禁', () => {
  const forged = tasks(20).map(r => ({ ...r, acceptancePassed: false, terminalStatus: 'FAILED', reportedComplete: false }));
  assert.equal(aggregate(forged)[0].successRate, 0);
  const result = gate(forged, forged);
  assert.equal(result.verdict, 'reject');
  assert.ok(result.reasons.some(reason => reason.includes('互相矛盾')));
  for (const bad of [
    { acceptanceRan: false, acceptancePassed: true },
    { reportedComplete: false },
    { scenarioCovered: false },
    { scenarioCovered: undefined },
    { sessionId: undefined }
  ]) {
    const rows = tasks(20).map(r => ({ ...r, ...bad }));
    assert.equal(gate(rows, rows).verdict, 'reject', JSON.stringify(bad));
  }
});

test('正常收尾验收失败率上升不能无条件放行，但不等于模型谎称成功', () => {
  const old = tasks(20).map(r => r.attempt === 3 ? { ...r, success: false, acceptancePassed: false,
    terminalStatus: 'FAILED', reportedComplete: false } : r);
  const next = tasks(20).map(r => r.attempt === 3 ? { ...r, success: false, acceptancePassed: false } : r);
  const result = gate(old, next);
  assert.equal(result.metrics.baselineSuccessRate, result.metrics.currentSuccessRate);
  assert.equal(result.verdict, 'insufficient');
  assert.equal(result.metrics.baselineFalseCompletionRate, 0);
  assert.equal(result.metrics.currentFalseCompletionRate, 1 / 3);
  assert.ok(result.reasons.some(reason => reason.includes('终态语义')));
  assert.equal(summarize(next).falseCompletionLabel, '正常收尾但外部验收失败率');
});


test('跨组复制相同session即使修改提示版本或attempt也不是独立运行', () => {
  const baseline = tasks(20);
  const copied = baseline.map(r => ({ ...r, promptVersion: 'P2', promptHash: 'new-prompt', attempt: r.attempt + 3 }));
  const decision = gate(baseline, copied);
  assert.equal(decision.verdict, 'reject');
  assert.ok(decision.reasons.some(reason => reason.includes('跨组复制')));
  assert.equal(gate(baseline, baseline).verdict, 'reject');
});

test('只复用一个跨组session也拒绝，不能用其他独立样本掩盖', () => {
  const baseline = tasks(20), candidate = tasks(20);
  candidate[17].sessionId = baseline[2].sessionId;
  const decision = gate(baseline, candidate);
  assert.equal(decision.verdict, 'reject');
  assert.ok(decision.reasons.some(reason => reason.includes('复用了 1 个会话')));
});

test('基线预检单独验证完整性，不伪装成A/B通过；旧记录仍只可汇总', () => {
  const baseline = tasks(20);
  const checked = validateBaseline(baseline);
  assert.equal(checked.verdict, 'pass');
  assert.equal(checked.comparisonScope, 'baseline_validation');
  const legacy = baseline.map(({ schemaVersion, fixtureVersion, ...r }) => r);
  assert.equal(validateBaseline(legacy).verdict, 'insufficient');
  assert.equal(gate(legacy, legacy).verdict, 'insufficient');
  assert.equal(aggregate(legacy)[0].successRate, 1);
  const duplicate = baseline.map(r => ({ ...r, sessionId: 'same-session' }));
  assert.equal(validateBaseline(duplicate).verdict, 'reject');
});
