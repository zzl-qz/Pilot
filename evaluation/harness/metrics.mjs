/** Repeated, externally accepted task evaluation. Missing observations stay unknown. */
export const GATE_MIN_TASKS = 20;
export const GATE_MIN_REPEATS = 3;
const succeeded = r => r.success === true && r.acceptanceRan === true
  && (r.schemaVersion !== 2 || (r.acceptancePassed === true && r.terminalStatus === 'COMPLETED'
    && r.reportedComplete === true && r.scenarioCovered === true));
const keyOf = r => `${r.taskId}|${r.model ?? 'unknown'}`;
const number = value => typeof value === 'number' && Number.isFinite(value) && value >= 0;
const priced = r => r.costKnown === true && number(r.costUsd);
const mean = values => values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
const rate = (rows, predicate) => rows.length ? rows.filter(predicate).length / rows.length : null;
const observedMean = (rows, key) => mean(rows.map(r => r[key]).filter(number));
const byTask = rows => {
  const groups = new Map();
  for (const row of rows) { const key = keyOf(row); if (!groups.has(key)) groups.set(key, []); groups.get(key).push(row); }
  return groups;
};

export function groupKey(record) {
  return [record.model ?? 'unknown', record.promptVersion ?? '-', record.toolVersion ?? '-'].join('|');
}

/** Nearest-rank percentile; zero is a valid observed duration, missing is not. */
export function percentile(values, p) {
  const sorted = values.filter(number).sort((a, b) => a - b);
  return sorted.length ? sorted[Math.max(0, Math.ceil(p * sorted.length) - 1)] : null;
}

function observations(rows) {
  const knownCosts = rows.filter(priced);
  const totalCostUsd = rows.length && knownCosts.length === rows.length
    ? knownCosts.reduce((sum, r) => sum + r.costUsd, 0) : null;
  const successes = rows.filter(succeeded).length;
  const completionObserved = rows.filter(r => typeof r.reportedComplete === 'boolean' || typeof r.terminalStatus === 'string');
  const completed = completionObserved.filter(r => r.reportedComplete === true || r.terminalStatus === 'COMPLETED');
  const checkedCompletions = completed.filter(r => r.acceptanceRan === true);
  const falseCompletions = checkedCompletions.filter(r => typeof r.acceptancePassed === 'boolean' ? !r.acceptancePassed : !succeeded(r));
  const takeoverObserved = rows.filter(r => typeof r.takeover === 'boolean');
  // 故障注入观测：没有注入次数观测的尝试不算覆盖；注入次数为零也是有效观测。
  const faultRows = rows.filter(r => typeof r.faultScenario === 'string' && r.faultScenario);
  const injectedObserved = faultRows.filter(r => Number.isInteger(r.faultInjectedCount));
  return {
    totalCostUsd,
    knownCostUsd: knownCosts.reduce((sum, r) => sum + r.costUsd, 0),
    pricedRuns: knownCosts.length,
    avgCostUsd: mean(knownCosts.map(r => r.costUsd)),
    costPerSuccessfulTaskUsd: totalCostUsd !== null && successes > 0 ? totalCostUsd / successes : null,
    // Compatibility field: normal run completion followed by failed external acceptance.
    // It does not inspect the answer and must not be described as a dishonest success claim.
    falseCompletionMetric: 'normal_completion_external_acceptance_failure',
    falseCompletionLabel: '正常收尾但外部验收失败率',
    falseCompletionCount: falseCompletions.length,
    falseCompletionRate: checkedCompletions.length ? falseCompletions.length / checkedCompletions.length : null,
    completionVerificationCoverage: completed.length ? checkedCompletions.length / completed.length : null,
    incompleteRate: rate(completionObserved, r => !(r.reportedComplete === true || r.terminalStatus === 'COMPLETED')),
    scenarioCoverage: rate(rows.filter(r => typeof r.scenarioCovered === 'boolean'), r => r.scenarioCovered),
    takeoverRate: rate(takeoverObserved, r => r.takeover === true),
    takeoverObservedRuns: takeoverObserved.length,
    avgDurationSeconds: observedMean(rows, 'durationSeconds'),
    p50DurationSeconds: percentile(rows.map(r => r.durationSeconds), 0.5),
    p95DurationSeconds: percentile(rows.map(r => r.durationSeconds), 0.95),
    avgExecutionSeconds: observedMean(rows, 'executionSeconds'),
    p95ExecutionSeconds: percentile(rows.map(r => r.executionSeconds), 0.95),
    avgModelRequests: observedMean(rows, 'modelRequestCount'),
    avgRework: observedMean(rows, 'rework'),
    avgToolCalls: observedMean(rows, 'toolCallCount'),
    avgFailedCommands: observedMean(rows, 'failedCommandCount'),
    avgModelRetries: observedMean(rows, 'modelRetryCount'),
    inputTokens: rows.reduce((sum, r) => sum + (number(r.inputTokens) ? r.inputTokens : 0), 0),
    outputTokens: rows.reduce((sum, r) => sum + (number(r.outputTokens) ? r.outputTokens : 0), 0),
    cachedInputTokens: rows.reduce((sum, r) => sum + (number(r.cachedInputTokens) ? r.cachedInputTokens : 0), 0),
    cacheHitRate: cacheHitRate(rows),
    faultScenarioRuns: faultRows.length,
    faultInjectionCoverage: injectedObserved.length
      ? injectedObserved.filter(r => r.faultInjectedCount >= 1).length / injectedObserved.length : null,
    injectedFaultCount: injectedObserved.reduce((sum, r) => sum + r.faultInjectedCount, 0),
    // 故障存活率：带故障注入的尝试里同时满足正常收尾+外部验收通过+场景覆盖的比例。
    faultSurvivalRate: faultRows.length ? faultRows.filter(succeeded).length / faultRows.length : null,
    faultProxyStops: faultRows.filter(r => r.faultProxyStopped === true).length
  };
}

export function aggregate(records) {
  const groups = new Map();
  for (const record of records) {
    const key = groupKey(record);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(record);
  }
  return [...groups.entries()].map(([key, items]) => ({
    key, model: items[0].model ?? 'unknown', promptVersion: items[0].promptVersion ?? '-',
    toolVersion: items[0].toolVersion ?? '-', tasks: items.length, uniqueTasks: new Set(items.map(keyOf)).size,
    successRate: rate(items, succeeded), regressionRate: rate(items, r => r.regression === true),
    ...observations(items)
  }));
}

/** No cache usage observation is different from observed zero hits. */
export function cacheHitRate(items) {
  let input = 0, cached = 0;
  for (const item of items) {
    const details = item.usageDetails;
    const observed = details ? Number(details.observedCacheRequests) > 0 : item.cacheReadObserved === true;
    const denominator = Number(details?.observedCacheInputTokens ?? item.inputTokens);
    const numerator = Number(details?.cacheReadTokens ?? item.cachedInputTokens);
    if (!observed || !Number.isFinite(denominator) || denominator <= 0 || !Number.isFinite(numerator)) continue;
    input += denominator; cached += Math.min(denominator, Math.max(0, numerator));
  }
  return input > 0 ? cached / input : null;
}

/** One-sided exact test of a lower candidate success probability, conditional on total successes. */
export function regressionProbability(baseline, current) {
  const a = baseline.filter(succeeded).length, b = current.filter(succeeded).length;
  const n = baseline.length, m = current.length, total = a + b;
  const logChoose = (size, count) => {
    if (count < 0 || count > size) return -Infinity;
    let value = 0;
    for (let i = 1; i <= Math.min(count, size - count); i++) value += Math.log(size - i + 1) - Math.log(i);
    return value;
  };
  let probability = 0;
  for (let candidateSuccess = Math.max(0, total - n); candidateSuccess <= Math.min(b, m, total); candidateSuccess++) {
    probability += Math.exp(logChoose(m, candidateSuccess) + logChoose(n, total - candidateSuccess) - logChoose(n + m, total));
  }
  return Math.min(1, probability);
}

function taskComparisons(baseline, current, options = {}) {
  const old = byTask(baseline), next = byTask(current);
  const keys = [...old.keys()].filter(key => next.has(key));
  // Multiple per-task comparisons must not manufacture significance as the suite grows.
  const alpha = (options.alpha ?? 0.05) / Math.max(1, keys.length);
  const minRepeats = options.minRepeats ?? GATE_MIN_REPEATS;
  return keys.map(key => {
    const a = old.get(key), b = next.get(key);
    const baselineRate = rate(a, succeeded), currentRate = rate(b, succeeded);
    const enough = a.length >= minRepeats && b.length >= minRepeats;
    const probability = regressionProbability(a, b);
    const decrease = currentRate < baselineRate;
    return { key, baselineRuns: a.length, currentRuns: b.length, baselineRate, currentRate,
      probability, alpha, enough, decrease, regression: enough && decrease && probability <= alpha + 1e-12,
      improved: enough && currentRate > baselineRate };
  });
}

/** A single failed sample is an observation requiring replication, not a proven regression. */
export function compare(baselineRecords, currentRecords, options = {}) {
  const pairs = new Map(taskComparisons(baselineRecords, currentRecords, options).map(r => [r.key, r]));
  return currentRecords.map(record => {
    const result = pairs.get(keyOf(record));
    return { ...record, regression: result?.regression ?? false, improved: result?.improved ?? false,
      regressionSuspected: result?.decrease ?? false, baselineSuccessRate: result?.baselineRate ?? null,
      currentTaskSuccessRate: result?.currentRate ?? null };
  });
}

export function summarize(records) {
  const stats = observations(records);
  return { total: records.length, uniqueTasks: new Set(records.map(keyOf)).size,
    successRate: rate(records, succeeded),
    regressions: new Set(records.filter(r => r.regression).map(keyOf)).size,
    suspectedRegressions: new Set(records.filter(r => r.regressionSuspected).map(keyOf)).size,
    takeovers: stats.takeoverObservedRuns ? records.filter(r => r.takeover === true).length : null,
    costUsd: stats.totalCostUsd, ...stats };
}

/** Schema-v2 records are produced by one fresh session per trial, not by relabeling a run. */
function invalidRecordIssues(baseline, current) {
  const issues = [];
  for (const [label, records] of [['基线', baseline], ['候选', current]]) {
    const sessions = new Set();
    for (const r of records) {
      if (r.schemaVersion !== 2) continue; // Legacy records remain readable but fail comparability checks.
      if (typeof r.sessionId !== 'string' || !r.sessionId.trim()) {
        if (!r.evaluationError) issues.push(`${label} ${r.taskId} 缺少实际会话身份，不能证明独立运行`);
      } else if (sessions.has(r.sessionId)) {
        issues.push(`${label}重复使用会话 ${r.sessionId}，修改 attempt 不能伪装独立运行`);
      } else sessions.add(r.sessionId);
      if (r.evaluationError) {
        if (r.success === true || r.acceptanceRan === true) issues.push(`${label} ${r.taskId} 的执行器错误记录不能同时宣称验收成功`);
        continue;
      }
      const booleanFields = ['success', 'acceptanceRan', 'acceptancePassed', 'reportedComplete', 'scenarioCovered'];
      if (booleanFields.some(key => typeof r[key] !== 'boolean') || typeof r.terminalStatus !== 'string') {
        issues.push(`${label} ${r.taskId} 缺少完整结果或场景覆盖字段`);
        continue;
      }
      const normalEnd = r.terminalStatus === 'COMPLETED';
      const expectedSuccess = normalEnd && r.acceptanceRan && r.acceptancePassed && r.scenarioCovered;
      if (r.reportedComplete !== normalEnd || (r.acceptancePassed && !r.acceptanceRan)
          || r.success !== expectedSuccess) {
        issues.push(`${label} ${r.taskId} 的 success、终态、外部验收或场景覆盖字段互相矛盾`);
      }
      // 故障记录必须有请求数观测；注入次数缺失只能说明观测不完整，不能断言注入为零。
      if (typeof r.faultScenario === 'string' && r.faultScenario && !Number.isInteger(r.faultRequestCount)) {
        issues.push(`${label} ${r.taskId} 的故障注入记录缺少请求数观测`);
      }
    }
  }
  return [...new Set(issues)];
}

/** Strict comparisons require recorded conditions, not merely matching user-provided labels. */
function comparisonIssues(baseline, current) {
  const issues = [];
  const fields = ['fixtureVersion', 'taskVersion', 'environmentVersion', 'modelConfigHash', 'promptHash', 'runtimeHash'];
  const all = [...baseline, ...current];
  if (all.some(r => r.schemaVersion !== 2 || fields.some(key => typeof r[key] !== 'string' || !r[key].trim())
      || !r.model || r.model === 'unknown' || r.configurationChanged)) {
    issues.push('缺少完整版本/环境元数据，或运行中模型配置变化；旧记录可查看但不能用于严格对照');
  }
  for (const [label, records] of [['基线', baseline], ['候选', current]]) {
    for (const field of ['promptHash', 'runtimeHash', 'environmentVersion', 'modelConfigHash']) {
      if (new Set(records.map(r => r[field])).size > 1) issues.push(`${label}混合了不同 ${field}，应分别评估`);
    }
    for (const [key, rows] of byTask(records)) {
      const attempts = rows.map(r => r.attempt);
      if (attempts.some(n => !Number.isInteger(n) || n < 1) || new Set(attempts).size !== attempts.length) {
        issues.push(`${label} ${key} 缺少独立 attempt，或同次运行重复入账`);
      }
    }
  }
  // 故障注入条件必须一致：故障组与干净对照、或不同故障方案之间都不可比。
  const faultSet = list => new Set(list.filter(r => r.faultScenario).map(r => `${r.faultScenario}@${r.faultEvery ?? '?'}`));
  for (const [label, records] of [['基线', baseline], ['候选', current]]) {
    if (faultSet(records).size > 1) issues.push(`${label}混合了不同故障注入方案，应分别评估或分别报告`);
  }
  const baselineFaults = faultSet(baseline), currentFaults = faultSet(current);
  if (baselineFaults.size && currentFaults.size) {
    if (![...baselineFaults].every(fault => currentFaults.has(fault))) issues.push('两组故障注入方案不一致，不能作为同条件对照');
  } else if (currentFaults.size && !baselineFaults.size) {
    issues.push('候选带故障注入而基线没有，两组不可比；应建立同故障方案的基线');
  } else if (baselineFaults.size && !currentFaults.size) {
    issues.push('基线带故障注入而候选没有，两组不可比');
  }
  const old = byTask(baseline), next = byTask(current);
  for (const [key, rows] of old) {
    if (!next.has(key)) continue;
    const paired = [...rows, ...next.get(key)];
    for (const field of ['fixtureVersion', 'taskVersion', 'environmentVersion', 'modelConfigHash']) {
      if (new Set(paired.map(r => r[field])).size > 1) issues.push(`${key} 的 ${field} 不一致，不能把不同条件当成提示词收益`);
    }
  }
  return issues;
}

/** Baseline self-validation is not an A/B comparison and cannot prove candidate improvement. */
export function validateBaseline(records, options = {}) {
  return evaluateGate(records, records, options, true);
}

export function gate(baselineRecords, currentRecords, options = {}) {
  return evaluateGate(baselineRecords, currentRecords, options, false);
}

function evaluateGate(baselineRecords, currentRecords, options, baselineOnly) {
  const minTasks = options.minTasks ?? GATE_MIN_TASKS, minRepeats = options.minRepeats ?? GATE_MIN_REPEATS;
  const baseline = byTask(baselineRecords), current = byTask(currentRecords);
  const missing = [...baseline.keys()].filter(key => !current.has(key));
  const added = [...current.keys()].filter(key => !baseline.has(key));
  const pairs = taskComparisons(baselineRecords, currentRecords, { ...options, minRepeats });
  const shared = new Set(pairs.map(r => r.key));
  const old = baselineRecords.filter(r => shared.has(keyOf(r))), next = currentRecords.filter(r => shared.has(keyOf(r)));
  const balancedRate = (rows, predicate) => mean([...byTask(rows).values()].map(items => rate(items, predicate)));
  const baselineSuccessRate = balancedRate(old, succeeded), currentSuccessRate = balancedRate(next, succeeded);
  const baselineAcceptance = balancedRate(old, r => r.acceptanceRan === true), currentAcceptance = balancedRate(next, r => r.acceptanceRan === true);
  const baselineStats = observations(old), currentStats = observations(next);
  const failures = invalidRecordIssues(baselineRecords, currentRecords), comparabilityIssues = comparisonIssues(baselineRecords, currentRecords);
  if (!baselineOnly) {
    const baselineSessions = new Set(baselineRecords.filter(r => r.schemaVersion === 2 && typeof r.sessionId === 'string').map(r => r.sessionId));
    const reused = new Set(currentRecords.filter(r => r.schemaVersion === 2 && baselineSessions.has(r.sessionId)).map(r => r.sessionId));
    if (reused.size) failures.push(`基线与候选复用了 ${reused.size} 个会话，跨组复制记录不构成两次独立运行`);
  }
  const insufficient = [...comparabilityIssues];
  if ([...baselineRecords, ...currentRecords].some(r => r.dryRun)) failures.push('dry-run 记录不能用于能力门禁');
  if (missing.length) failures.push(`本次缺少 ${missing.length} 个基线任务：${missing.join('、')}`);
  if (added.length) insufficient.push(`本次新增 ${added.length} 个任务没有基线，应单独报告`);
  if ([...old, ...next].some(r => r.scenarioCovered === false)) insufficient.push('存在必需场景未触发的样本，例如任务在中途引导投递前结束');
  if (currentAcceptance < baselineAcceptance) failures.push('验收执行率下降，不能通过省略验收制造优化');
  const regressions = pairs.filter(p => p.regression);
  if (!insufficient.length && regressions.length) failures.push(`重复对照显示 ${regressions.length} 个任务显著退化：${regressions.map(p => p.key).join('、')}（单侧精确检验并校正多重比较）`);
  const suspected = pairs.filter(p => p.decrease && !p.regression);
  if (suspected.length) insufficient.push(`${suspected.length} 个任务成功率下降但证据不足，需增加重复评测，不能判定无退化`);
  if (shared.size < minTasks) insufficient.push(`可比任务 ${shared.size} 个，低于 ${minTasks} 个下限`);
  if (pairs.some(p => !p.enough)) insufficient.push(`每个任务两组至少需要 ${minRepeats} 次独立运行`);
  if (pairs.some(p => p.baselineRuns !== p.currentRuns)) insufficient.push('两组重复次数不一致，需补齐配对样本');
  if (baselineAcceptance !== 1 || currentAcceptance !== 1) insufficient.push('并非所有样本都有外部验收结果');
  if (baselineSuccessRate === 0 || currentSuccessRate === 0) insufficient.push('没有成功任务，无法建立可用的能力或成功成本基线');
  const costComparable = old.length > 0 && [...old, ...next].every(priced);
  const perTaskMeanTotal = (rows, field) => [...byTask(rows).values()].reduce((sum, items) => sum + mean(items.map(r => r[field])), 0);
  const baselineCost = costComparable ? perTaskMeanTotal(old, 'costUsd') : null;
  const currentCost = costComparable ? perTaskMeanTotal(next, 'costUsd') : null;
  const tokenComparable = old.length > 0 && [...old, ...next].every(r => number(r.inputTokens));
  const baselineInputTokens = tokenComparable ? perTaskMeanTotal(old, 'inputTokens') : null;
  const currentInputTokens = tokenComparable ? perTaskMeanTotal(next, 'inputTokens') : null;
  const tolerance = options.inputTokenTolerance ?? 0.05;
  if (!comparabilityIssues.length && costComparable && currentCost > baselineCost * (1 + (options.costTolerance ?? 0.05)) + 1e-9) failures.push(`每任务平均成本上升：$${baselineCost.toFixed(3)} → $${currentCost.toFixed(3)}`);
  else if (!comparabilityIssues.length && !costComparable && tokenComparable && currentInputTokens > baselineInputTokens * (1 + tolerance)) failures.push(`输入 token 上升 ${baselineInputTokens} → ${currentInputTokens}，超过代理指标容差`);
  else if (!costComparable && !tokenComparable) insufficient.push('缺少完整成本及 token 观测，不能作效率结论');
  if (!comparabilityIssues.length && baselineStats.falseCompletionRate !== null && currentStats.falseCompletionRate !== null
      && currentStats.falseCompletionRate > baselineStats.falseCompletionRate) {
    insufficient.push('正常收尾但外部验收失败率上升，需复核任务结果和终态语义；该指标不判断答复是否声称成功');
  }
  if (baselineStats.takeoverRate !== null && currentStats.takeoverRate !== null && currentStats.takeoverRate > baselineStats.takeoverRate) insufficient.push('已观测人工接管率上升，需复核原因');
  const scope = baselineOnly ? 'baseline_validation'
    : new Set([...baselineRecords, ...currentRecords].map(r => r.runtimeHash)).size === 1 ? 'prompt' : 'combined';
  return { verdict: failures.length ? 'reject' : insufficient.length ? 'insufficient' : 'pass', reasons: [...failures, ...insufficient],
    comparisonScope: scope, taskComparisons: pairs,
    metrics: { pairedTasks: shared.size, minTasks, minRepeats, regressions: regressions.length,
      suspectedRegressions: suspected.length, improvements: pairs.filter(p => p.improved).length,
      baselineSuccessRate, currentSuccessRate, baselineAcceptance, currentAcceptance,
      baselineTakeover: baselineStats.takeoverRate, currentTakeover: currentStats.takeoverRate,
      baselineCostUsd: baselineCost, currentCostUsd: currentCost, costComparable,
      baselineInputTokens, currentInputTokens,
      baselineCostPerSuccessUsd: baselineStats.costPerSuccessfulTaskUsd, currentCostPerSuccessUsd: currentStats.costPerSuccessfulTaskUsd,
      baselineFalseCompletionRate: baselineStats.falseCompletionRate, currentFalseCompletionRate: currentStats.falseCompletionRate,
      baselineCacheHitRate: cacheHitRate(old), currentCacheHitRate: cacheHitRate(next) } };
}
