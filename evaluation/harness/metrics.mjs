/**
 * 评估指标聚合。
 *
 * 单次运行说明不了模型是否稳定：必须看固定任务集上的成功率、回归率、成本、耗时、
 * 返工次数和人工接管率，并按模型/提示词/工具版本分组比较。
 *
 * 记录格式（runner.mjs 写出，一行一条）：
 * {
 *   taskId, model, promptVersion, toolVersion, runId, sessionId,
 *   success: boolean,          // 外部验收命令是否通过
 *   acceptanceRan: boolean,    // 是否真的执行了验收命令（没有执行就不算成功）
 *   durationSeconds: number,
 *   rework: number,            // 同一任务内失败重试/返工次数
 *   takeover: boolean,         // 是否需要人工接管（中断后继续、手工改代码）
 *   costUsd: number,           // 未知填 0 并置 costKnown=false
 *   costKnown: boolean,
 *   inputTokens: number, outputTokens: number,
 *   notes?: string
 * }
 */

export function groupKey(record) {
  return [record.model ?? "unknown", record.promptVersion ?? "-", record.toolVersion ?? "-"].join("|");
}

function mean(values) {
  if (values.length === 0) return 0;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function rate(records, predicate) {
  if (records.length === 0) return 0;
  return records.filter(predicate).length / records.length;
}

export function aggregate(records) {
  const groups = new Map();
  for (const record of records) {
    const key = groupKey(record);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(record);
  }
  return [...groups.entries()].map(([key, items]) => ({
    key,
    model: items[0].model ?? "unknown",
    promptVersion: items[0].promptVersion ?? "-",
    toolVersion: items[0].toolVersion ?? "-",
    tasks: items.length,
    // 「成功」必须有外部验收证据；只凭模型自述完成的记录一律不算
    successRate: rate(items, (item) => item.success === true && item.acceptanceRan === true),
    regressionRate: rate(items, (item) => item.regression === true),
    avgDurationSeconds: mean(items.map((item) => Number(item.durationSeconds) || 0)),
    avgRework: mean(items.map((item) => Number(item.rework) || 0)),
    takeoverRate: rate(items, (item) => item.takeover === true),
    totalCostUsd: items.reduce((sum, item) => sum + (Number(item.costUsd) || 0), 0),
    avgCostUsd: mean(items.filter((item) => item.costKnown !== false).map((item) => Number(item.costUsd) || 0)),
    inputTokens: items.reduce((sum, item) => sum + (Number(item.inputTokens) || 0), 0),
    outputTokens: items.reduce((sum, item) => sum + (Number(item.outputTokens) || 0), 0)
  }));
}

/**
 * 与基线比较：回归 = 基线里成功、本次失败的任务。
 * 只比较同一 taskId，避免任务集变化被误读成能力下降。
 */
export function compare(baselineRecords, currentRecords) {
  const baselineSuccess = new Map();
  for (const record of baselineRecords) {
    const key = `${record.taskId}|${record.model ?? "unknown"}`;
    baselineSuccess.set(key, record.success === true && record.acceptanceRan === true);
  }
  return currentRecords.map((record) => {
    const key = `${record.taskId}|${record.model ?? "unknown"}`;
    const wasSuccessful = baselineSuccess.get(key);
    return {
      ...record,
      regression: wasSuccessful === true && !(record.success === true && record.acceptanceRan === true),
      improved: wasSuccessful === false && record.success === true
    };
  });
}

export function summarize(records) {
  return {
    total: records.length,
    successRate: rate(records, (item) => item.success === true && item.acceptanceRan === true),
    regressions: records.filter((item) => item.regression === true).length,
    takeovers: records.filter((item) => item.takeover === true).length,
    costUsd: records.reduce((sum, item) => sum + (Number(item.costUsd) || 0), 0)
  };
}
