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
 *   cachedInputTokens: number,  // 命中缓存的输入 token（与 inputTokens 同口径）；端点不返回时为 0
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
    outputTokens: items.reduce((sum, item) => sum + (Number(item.outputTokens) || 0), 0),
    cachedInputTokens: items.reduce((sum, item) => sum + (Number(item.cachedInputTokens) || 0), 0),
    // 命中率只在有真实用量观测的记录上算：全是估算的记录没有缓存字段，算出来会虚低
    cacheHitRate: cacheHitRate(items)
  }));
}

/** 缓存命中率（0~1）；没有任何输入或没有缓存观测时为 0。 */
export function cacheHitRate(items) {
  const known = items.filter((item) => item.usageExact !== false && Number(item.inputTokens) > 0);
  const input = known.reduce((sum, item) => sum + (Number(item.inputTokens) || 0), 0);
  if (input <= 0) return 0;
  return known.reduce((sum, item) => sum + Math.min(Number(item.inputTokens), Math.max(0, Number(item.cachedInputTokens) || 0)), 0) / input;
}

/**
 * 与基线比较：回归 = 基线里成功、本次失败的任务。
 * 只比较同一 taskId，避免任务集变化被误读成能力下降。
 */
export function compare(baselineRecords, currentRecords) {
  const baselineSuccess = new Map();
  for (const record of baselineRecords) {
    const key = `${record.taskId}|${record.model ?? "unknown"}`;
    baselineSuccess.set(key, baselineSuccess.get(key) === true || (record.success === true && record.acceptanceRan === true));
  }
  return currentRecords.map((record) => {
    const key = `${record.taskId}|${record.model ?? "unknown"}`;
    const wasSuccessful = baselineSuccess.get(key);
    return {
      ...record,
      regression: wasSuccessful === true && !(record.success === true && record.acceptanceRan === true),
      improved: wasSuccessful === false && record.success === true && record.acceptanceRan === true
    };
  });
}

export function summarize(records) {
  return {
    total: records.length,
    successRate: rate(records, (item) => item.success === true && item.acceptanceRan === true),
    regressions: records.filter((item) => item.regression === true).length,
    takeovers: records.filter((item) => item.takeover === true).length,
    costUsd: records.reduce((sum, item) => sum + (Number(item.costUsd) || 0), 0),
    cacheHitRate: cacheHitRate(records)
  };
}

/** 能判定「没有退化」所需的最小可比任务数；低于这个数，通过率差异基本落在噪音里。 */
export const GATE_MIN_TASKS = 20;

/**
 * 优化门禁：判断一次「省钱优化」能不能合入。
 *
 * <p>唯一要防的是「为了省钱把能力也省掉了」，所以判定顺序是能力优先、成本其次：
 * <ol>
 *   <li>任何任务从「基线里成功过」变成「本次失败」——直接拒绝。这是逐任务的 Pareto 判定，
 *       不看平均分：平均分能掩盖「放弃几个难题换整体好看」的作弊解。</li>
 *   <li>通过率下降、验收执行率下降、接管率上升——拒绝。验收执行率单独看，是因为
 *       「少跑一次验收命令」既省钱又不会被通过率发现，是最隐蔽的一种退化。</li>
 *   <li>成本上升——拒绝。</li>
 *   <li>可比任务数不足——判「证据不足」。这一条不算通过：样本太小的时候，
 *       「看不出退化」不等于「没有退化」。</li>
 * </ol>
 *
 * 基线的能力判定取「曾经成功过」而不是「最后一次」：同一任务跑多次时，
 * 只要基线做到过一次，本次做不到就算退化。
 */
export function gate(baselineRecords, currentRecords, options = {}) {
  const minTasks = options.minTasks ?? GATE_MIN_TASKS;
  const succeeded = (item) => item.success === true && item.acceptanceRan === true;
  const keyOf = (record) => `${record.taskId}|${record.model ?? "unknown"}`;

  const everSucceeded = new Map();
  for (const record of baselineRecords) {
    const key = keyOf(record);
    if (succeeded(record)) {
      everSucceeded.set(key, true);
    } else if (!everSucceeded.has(key)) {
      everSucceeded.set(key, false);
    }
  }

  const paired = [];
  for (const record of currentRecords) {
    const key = keyOf(record);
    if (everSucceeded.has(key)) {
      paired.push({ record, wasSuccessful: everSucceeded.get(key) === true });
    }
  }
  const regressions = paired.filter((item) => item.wasSuccessful && !succeeded(item.record));
  const improvements = paired.filter((item) => !item.wasSuccessful && succeeded(item.record));

  const baselineSuccessRate = rate(baselineRecords, succeeded);
  const currentSuccessRate = rate(currentRecords, succeeded);
  const baselineAcceptance = rate(baselineRecords, (item) => item.acceptanceRan === true);
  const currentAcceptance = rate(currentRecords, (item) => item.acceptanceRan === true);
  const baselineTakeover = rate(baselineRecords, (item) => item.takeover === true);
  const currentTakeover = rate(currentRecords, (item) => item.takeover === true);

  // 成本按同一任务的一次运行比较，不能用减少重跑次数制造降本。
  const currentKeys = new Set(currentRecords.map(keyOf));
  const sharedKeys = new Set([...everSucceeded.keys()].filter(key => currentKeys.has(key)));
  const missing = [...everSucceeded.keys()].filter(key => !currentKeys.has(key));
  const baselineComparable = baselineRecords.filter(item => sharedKeys.has(keyOf(item)));
  const currentComparable = currentRecords.filter(item => sharedKeys.has(keyOf(item)));
  const perTaskTotal = (records, field) => {
    const groups = new Map();
    for (const item of records) {
      const key = keyOf(item);
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(Math.max(0, Number(item[field]) || 0));
    }
    return [...groups.values()].reduce((sum, values) => sum + mean(values), 0);
  };
  const priced = item => item.costKnown === true && Number.isFinite(item.costUsd) && item.costUsd >= 0;
  const baselineCost = perTaskTotal(baselineComparable.filter(priced), 'costUsd');
  const currentCost = perTaskTotal(currentComparable.filter(priced), 'costUsd');
  const baselineInputTokens = perTaskTotal(baselineComparable, 'inputTokens');
  const currentInputTokens = perTaskTotal(currentComparable, 'inputTokens');
  const costComparable = sharedKeys.size > 0 && baselineComparable.every(priced) && currentComparable.every(priced);
  const tolerance = options.inputTokenTolerance ?? 0.05;

  const reasons = [];
  if ([...baselineRecords, ...currentRecords].some(item => item.dryRun === true)) reasons.push("dry-run 记录不能用于能力门禁");
  if (missing.length) reasons.push(`本次缺少 ${missing.length} 个基线任务：${missing.join('、')}，不能跳过任务后宣称无退化`);
  if (regressions.length > 0) {
    reasons.push(`${regressions.length} 个任务从成功变成失败：${regressions.map((item) => item.record.taskId).join("、")}`);
  }
  if (currentSuccessRate < baselineSuccessRate) {
    reasons.push(`通过率下降：${pct(baselineSuccessRate)} → ${pct(currentSuccessRate)}`);
  }
  if (currentAcceptance < baselineAcceptance) {
    reasons.push(`验收执行率下降：${pct(baselineAcceptance)} → ${pct(currentAcceptance)}（少跑验收既省钱又不会拉低通过率，必须单独看）`);
  }
  if (currentTakeover > baselineTakeover) {
    reasons.push(`人工接管率上升：${pct(baselineTakeover)} → ${pct(currentTakeover)}`);
  }
  // 价格表（T0.2）做出来之前 costUsd 恒为未知，这时用输入 token 当成本代理：
  // 缓存优化如果失效，最先涨的就是 inputTokens，不能因为还没做价格表就放过去。
  if (costComparable) {
    if (currentCost > baselineCost + 1e-9) {
      reasons.push(`成本上升：$${baselineCost.toFixed(3)} → $${currentCost.toFixed(3)}`);
    }
  } else if (currentInputTokens > baselineInputTokens * (1 + tolerance)) {
    reasons.push(`输入 token 上升 ${baselineInputTokens} → ${currentInputTokens}` +
      `（还没有价格表，先用 token 当成本代理；涨幅超过 ${Math.round(tolerance * 100)}% 视为变贵）`);
  }

  let verdict = "pass";
  if (reasons.length > 0) {
    verdict = "reject";
  } else if (sharedKeys.size < minTasks) {
    verdict = "insufficient";
    reasons.push(`可比任务 ${sharedKeys.size} 个，低于 ${minTasks} 个的下限：样本太小，通过率差异落在噪音里，不能据此断定「没有退化」`);
  }

  return {
    verdict,
    reasons,
    metrics: {
      pairedTasks: sharedKeys.size,
      minTasks,
      regressions: regressions.length,
      improvements: improvements.length,
      baselineSuccessRate,
      currentSuccessRate,
      baselineAcceptance,
      currentAcceptance,
      baselineTakeover,
      currentTakeover,
      baselineCostUsd: baselineCost,
      currentCostUsd: currentCost,
      costComparable,
      baselineInputTokens,
      currentInputTokens,
      baselineCacheHitRate: cacheHitRate(baselineRecords),
      currentCacheHitRate: cacheHitRate(currentRecords)
    }
  };
}

function pct(value) {
  return `${Math.round((Number(value) || 0) * 100)}%`;
}
