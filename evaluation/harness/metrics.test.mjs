import test from "node:test";
import assert from "node:assert/strict";

import { aggregate, compare, gate, summarize } from "./metrics.mjs";

function record(overrides = {}) {
  return {
    taskId: "t1",
    model: "m1",
    promptVersion: "P1",
    toolVersion: "T1",
    success: true,
    acceptanceRan: true,
    durationSeconds: 100,
    rework: 1,
    takeover: false,
    costUsd: 0.1,
    costKnown: true,
    inputTokens: 1000,
    outputTokens: 200,
    ...overrides
  };
}

test("模型自述成功但没跑验收命令，不算成功", () => {
  const [group] = aggregate([record({ success: true, acceptanceRan: false }), record()]);
  assert.equal(group.successRate, 0.5);
});

test("按模型/提示词/工具版本分组", () => {
  const groups = aggregate([
    record(),
    record({ model: "m2" }),
    record({ promptVersion: "P2" })
  ]);
  assert.equal(groups.length, 3);
  assert.ok(groups.every((group) => group.tasks === 1));
});

test("回归只在基线成功、本次失败时计数", () => {
  const baseline = [record({ taskId: "t1", success: true }), record({ taskId: "t2", success: false })];
  const current = compare(baseline, [
    record({ taskId: "t1", success: false }),
    record({ taskId: "t2", success: true })
  ]);

  assert.equal(current[0].regression, true);
  assert.equal(current[1].regression, false);
  assert.equal(current[1].improved, true);
});

test("成本、耗时、返工与接管率都进入汇总", () => {
  const summary = summarize([
    record({ durationSeconds: 100, rework: 2, costUsd: 0.2, takeover: false }),
    record({ taskId: "t2", durationSeconds: 200, rework: 0, costUsd: 0.4, takeover: true })
  ]);

  assert.equal(summary.total, 2);
  assert.equal(summary.successRate, 1);
  assert.equal(summary.takeovers, 1);
  assert.equal(Number(summary.costUsd.toFixed(2)), 0.6);

  const [group] = aggregate([
    record({ durationSeconds: 100, rework: 2 }),
    record({ taskId: "t2", durationSeconds: 200, rework: 0 })
  ]);
  assert.equal(group.avgDurationSeconds, 150);
  assert.equal(group.avgRework, 1);
  assert.equal(group.takeoverRate, 0);
});

test("未知成本的记录不拉低平均成本", () => {
  const [group] = aggregate([
    record({ costUsd: 0.2, costKnown: true }),
    record({ taskId: "t2", costUsd: 0, costKnown: false })
  ]);
  assert.equal(Number(group.avgCostUsd.toFixed(2)), 0.2);
});

test("缓存命中率按总输入口径计算", () => {
  const [group] = aggregate([
    record({ inputTokens: 1000, cachedInputTokens: 800 }),
    record({ taskId: "t2", inputTokens: 1000, cachedInputTokens: 0 })
  ]);
  assert.equal(group.cachedInputTokens, 800);
  assert.equal(group.cacheHitRate, 0.4);
});

test("没有用量观测时命中率是 0，不是 NaN", () => {
  const [group] = aggregate([record({ inputTokens: 0, outputTokens: 0 })]);
  assert.equal(group.cacheHitRate, 0);
  assert.equal(summarize([record({ inputTokens: 0, outputTokens: 0 })]).cacheHitRate, 0);
});

function tasks(count, overrides = {}) {
  return Array.from({ length: count }, (_, index) => record({ taskId: `t${index}`, ...overrides }));
}

test("一个任务从成功变失败就拒绝，哪怕整体通过率没降", () => {
  const current = tasks(3).map((item, index) => (index === 0 ? { ...item, success: false } : item));
  const decision = gate(tasks(3), current, { minTasks: 3 });

  assert.equal(decision.verdict, "reject");
  assert.equal(decision.metrics.regressions, 1);
});

test("能力不变且成本下降，才允许合入", () => {
  const baseline = tasks(3, { costUsd: 0.2 });
  const current = tasks(3, { costUsd: 0.1, inputTokens: 500, cachedInputTokens: 400 });
  const decision = gate(baseline, current, { minTasks: 3 });

  assert.equal(decision.verdict, "pass");
  assert.equal(decision.reasons.length, 0);
});

test("靠少跑验收命令来省钱会被拦下", () => {
  const current = tasks(3).map((item) => ({ ...item, acceptanceRan: false, costUsd: 0.01 }));
  const decision = gate(tasks(3), current, { minTasks: 3 });

  assert.equal(decision.verdict, "reject");
  assert.ok(decision.reasons.some((reason) => reason.includes("验收")), decision.reasons.join("｜"));
});

test("样本不足时判证据不足，不算通过", () => {
  const decision = gate(tasks(6), tasks(6));

  assert.equal(decision.verdict, "insufficient", "6 个任务的差异全在噪音里，不能据此说没有退化");
  assert.equal(decision.reasons.length, 1);
});

test("基线只要成功过一次，本次失败就算退化", () => {
  const baseline = [record({ taskId: "t1", success: true }), record({ taskId: "t1", success: false })];
  const decision = gate(baseline, [record({ taskId: "t1", success: false })], { minTasks: 1 });

  assert.equal(decision.verdict, "reject");
  assert.equal(decision.metrics.regressions, 1);
});

test("成本上升即使通过率提升也要拦下", () => {
  const current = tasks(3, { costUsd: 0.9 });
  const decision = gate(tasks(3, { costUsd: 0.2 }), current, { minTasks: 3 });

  assert.equal(decision.verdict, "reject");
  assert.ok(decision.reasons.some((reason) => reason.includes("成本上升")));
});

test("没有价格表时用输入 token 当成本代理", () => {
  const baseline = tasks(3, { inputTokens: 1000, costKnown: false });
  const current = tasks(3, { inputTokens: 2000, costKnown: false });
  const decision = gate(baseline, current, { minTasks: 3 });

  assert.equal(decision.verdict, "reject", "价格表做出来之前，token 变多就是变贵");
  assert.ok(decision.reasons.some((reason) => reason.includes("输入 token 上升")));
});

test("输入 token 小幅波动不算变贵", () => {
  const baseline = tasks(3, { inputTokens: 1000, costKnown: false });
  const current = tasks(3, { inputTokens: 1030, costKnown: false });

  assert.equal(gate(baseline, current, { minTasks: 3 }).verdict, "pass");
});
