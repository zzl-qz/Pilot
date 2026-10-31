import test from "node:test";
import assert from "node:assert/strict";

import { aggregate, compare, summarize } from "./metrics.mjs";

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
