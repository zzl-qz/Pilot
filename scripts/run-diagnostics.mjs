#!/usr/bin/env node
// 运行诊断报告：只读聚合 .pilot 里已有的 run 事件、快照与任务契约，用于复盘长任务表现。
// 不请求模型、不写文件；字段缺失一律输出「未观测」，绝不用 0 顶替。
//
// 用法：
//   node scripts/run-diagnostics.mjs <runId|sessionId> [--json]
//   node scripts/run-diagnostics.mjs --latest-failed [--root <工作区>]
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { fileURLToPath } from 'node:url';

export const UNOBSERVED = '未观测';
export const EXIT_OK = 0;
export const EXIT_NOT_FOUND = 2;
export const EXIT_CORRUPT = 3;
export const EXIT_USAGE = 64;

const MS_PER_SECOND = 1000;
const MS_PER_MINUTE = 60 * MS_PER_SECOND;
const MS_PER_HOUR = 60 * MS_PER_MINUTE;
const TOP_REPEATED_GROUPS = 5;
const CHECK_LINES_SHOWN = 5;
const CHECK_LINE_WIDTH = 72;

const EVENT = {
  usage: 'run.usage',
  plan: 'plan.updated',
  continued: 'run.continued',
  ended: 'run.ended',
  completed: 'run.completed',
  failed: 'run.failed',
  cancelled: 'run.cancelled',
  modelRequest: 'model.request',
  toolStarted: 'tool.started',
  toolCompleted: 'tool.completed'
};

// 与 core 的 RunOutcome.fromCode 同一套映射：用历史失败码反推终态。
const OUTCOME_BY_CODE = {
  TASK_INCOMPLETE: 'PARTIAL',
  PARTIAL: 'PARTIAL',
  BUDGET_EXHAUSTED: 'BUDGET_LIMITED'
};

// 工具结果分类只认已落库的确定标记，不做语义猜测。
const BASELINE_BLOCKED = '无法建立命令前文件基线';
const FAILED_STATE = /状态:\s*FAILED/;
const ERROR_LINE = /^\s*ERROR:/m;
const EXIT_CODE = /退出码:\s*(-?\d+)/;
const BUDGET_SEGMENT_REASON = /预算分段/;

const RUN_COLUMNS = 'id, session_id, status, started_at, finished_at';

/** 输入不存在、不可读或内容损坏。调用方据此给出退出码和明确提示。 */
export class DiagnosticError extends Error {
  constructor(message, kind) {
    super(message);
    this.name = 'DiagnosticError';
    this.kind = kind;
  }
}

/** 报告主体：只读拼接各处已有数据，不做任何推断性补零。 */
export function buildReport(options = {}) {
  const root = resolve(options.root ?? defaultRoot());
  const problems = [];
  const dbFile = join(root, '.pilot', 'pilot.db');
  if (!existsSync(dbFile)) throw new DiagnosticError(`找不到数据目录：${dbFile}`, 'not-found');
  const db = openDatabase(dbFile);
  try {
    const run = locateRun(db, options, problems);
    const events = loadEvents(db, run.id);
    const contract = readContract(root, run.session_id, problems);
    const snapshot = readSnapshot(root, run.id, problems);
    const execution = contract?.execution ?? null;
    return assemble({ db, run, events, contract, execution, snapshot, problems });
  } finally {
    db.close();
  }
}

function defaultRoot() {
  return resolve(fileURLToPath(new URL('..', import.meta.url)));
}

function openDatabase(file) {
  try {
    return new DatabaseSync(file, { readOnly: true });
  } catch (error) {
    throw new DiagnosticError(`无法以只读方式打开 ${file}：${error.message}`, 'corrupt');
  }
}

/** 查询容忍旧库缺表：缺表记成问题，字段按未观测处理。 */
function query(db, problems, sql, label, ...params) {
  try {
    return db.prepare(sql).all(...params);
  } catch (error) {
    problems.push(`${label} 读取失败：${error.message}`);
    return [];
  }
}

function locateRun(db, options, problems) {
  const notFound = (message) => new DiagnosticError(message, 'not-found');
  if (options.runId) {
    const row = query(db, problems, `SELECT ${RUN_COLUMNS} FROM runs WHERE id = ?`, 'runs', options.runId)[0];
    if (!row) throw notFound(`找不到 run：${options.runId}`);
    return row;
  }
  if (options.latestFailed) {
    const row = query(db, problems,
      `SELECT ${RUN_COLUMNS} FROM runs WHERE status IN ('FAILED', 'INTERRUPTED') ORDER BY started_at DESC LIMIT 1`,
      'runs')[0];
    if (!row) throw notFound('没有失败或中断的 run');
    return row;
  }
  const sessionId = options.sessionId;
  if (!sessionId) throw notFound('需要 runId 或 sessionId');
  if (!query(db, problems, 'SELECT id FROM sessions WHERE id = ?', 'sessions', sessionId)[0]) {
    throw notFound(`找不到会话：${sessionId}`);
  }
  const row = query(db, problems, `SELECT ${RUN_COLUMNS} FROM runs WHERE session_id = ? ORDER BY started_at DESC LIMIT 1`,
    'runs', sessionId)[0];
  if (!row) throw notFound(`会话 ${sessionId} 还没有运行记录`);
  return row;
}

function loadEvents(db, runId) {
  const rows = query(db, [], 'SELECT sequence, created_at, type, payload FROM events WHERE run_id = ? ORDER BY sequence',
    'events', runId);
  return rows.map((row) => {
    try {
      return { sequence: row.sequence, at: row.created_at, type: row.type, data: JSON.parse(row.payload) };
    } catch (error) {
      throw new DiagnosticError(
        `事件 JSON 损坏：run=${runId} sequence=${row.sequence} type=${row.type}：${error.message}`, 'corrupt');
    }
  });
}

function readContract(root, sessionId, problems) {
  const file = join(root, '.pilot', 'tasks', sessionId, 'current.json');
  if (!existsSync(file)) {
    problems.push(`任务契约未观测：${file} 不存在`);
    return null;
  }
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    problems.push(`任务契约 JSON 损坏，已忽略：${file}：${error.message}`);
    return null;
  }
}

function readSnapshot(root, runId, problems) {
  const dir = join(root, '.pilot', 'snapshots', runId);
  if (!existsSync(dir)) {
    problems.push(`快照缺失：${dir}（改动文件与检查项回退到事件与任务契约）`);
    return { dir, changes: null };
  }
  const file = join(dir, 'changes.json');
  if (!existsSync(file)) {
    problems.push(`快照缺少 changes.json：${dir}`);
    return { dir, changes: null };
  }
  try {
    return { dir, changes: JSON.parse(readFileSync(file, 'utf8')) };
  } catch (error) {
    problems.push(`快照 changes.json JSON 损坏，已忽略：${file}：${error.message}`);
    return { dir, changes: null };
  }
}

function assemble({ db, run, events, contract, execution, snapshot, problems }) {
  const terminal = summarizeTerminal(run, events);
  const plan = resolvePlan(db, run.session_id, events, problems);
  return {
    runId: run.id,
    sessionId: run.session_id,
    taskId: contract?.taskId ?? null,
    workspace: snapshot.changes?.workspace ?? execution?.workspace ?? null,
    runStatus: run.status ?? null,
    outcome: terminal,
    timing: {
      startedAt: run.started_at ?? null,
      finishedAt: run.finished_at ?? null,
      durationMs: durationOf(run.started_at, run.finished_at)
    },
    models: summarizeModels(events),
    plan: plan.summary,
    planCounts: plan.counts,
    continuation: summarizeContinuation(events, execution),
    changes: summarizeChanges(snapshot, terminal, execution),
    checks: summarizeChecks(db, run.id, terminal, execution, problems),
    tools: summarizeTools(db, run.id, events, problems),
    remaining: resolveRemaining(terminal, plan),
    nextActions: resolveNextActions(db, run.id, terminal, execution, problems),
    sources: summarizeSources(events, snapshot, execution),
    problems
  };
}

/** 一次运行的终态 + 它自带的交付信息（改动、检查、未完成项、下一步）。 */
function summarizeTerminal(run, events) {
  const ended = lastOf(events, EVENT.ended);
  const completed = ended ? null : lastOf(events, EVENT.completed);
  const failed = ended || completed ? null : lastOf(events, EVENT.failed);
  const cancelled = ended || completed || failed ? null : lastOf(events, EVENT.cancelled);
  const info = outcomeOf(ended, completed, failed, cancelled)
    ?? (run.status === 'RUNNING' ? stopOf('RUNNING', null, '运行尚未结束', null, 'runs.status')
      : stopOf(null, null, '', null, UNOBSERVED));
  const data = (ended ?? completed)?.data ?? {};
  return {
    ...info,
    changedFiles: listOrNull(data.changedFiles),
    checks: listOrNull(data.checks),
    openSteps: listOrNull(data.openSteps),
    blockedSteps: listOrNull(data.blockedSteps),
    nextActions: listOrNull(data.nextActions)
  };
}

function listOrNull(values) {
  return Array.isArray(values) ? values.map(String) : null;
}

function outcomeOf(ended, completed, failed, cancelled) {
  if (ended) {
    return stopOf(ended.data?.outcome ?? null, ended.data?.code ?? null, ended.data?.summary ?? '',
      boolOrNull(ended.data?.resumable), EVENT.ended);
  }
  if (completed) {
    return stopOf('COMPLETED', 'COMPLETED', completed.data?.summary ?? '', false, EVENT.completed);
  }
  if (failed) {
    const data = failed.data ?? {};
    return stopOf(outcomeOfCode(data.code), data.code ?? null, data.message ?? '', boolOrNull(data.retryable),
      EVENT.failed);
  }
  if (cancelled) return stopOf('CANCELLED', 'CANCELLED', cancelled.data?.reason ?? '', true, EVENT.cancelled);
  return null;
}

function stopOf(outcome, code, summary, resumable, source) {
  return { outcome, code, summary, resumable, source };
}

export function outcomeOfCode(code) {
  if (!code) return 'COMPLETED';
  const key = String(code).trim().toUpperCase();
  return key === 'COMPLETED' ? 'COMPLETED' : OUTCOME_BY_CODE[key] ?? 'FAILED';
}

function durationOf(startedAt, finishedAt) {
  const start = Date.parse(startedAt ?? '');
  const end = Date.parse(finishedAt ?? '');
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return null;
  return end - start;
}

function summarizeModels(events) {
  const usages = events.filter((event) => event.type === EVENT.usage);
  const last = usages.at(-1)?.data ?? null;
  const reports = events.filter((event) => event.type === EVENT.modelRequest)
    .map((event) => event.data?.report).filter(Boolean);
  const inputTokens = number(last?.inputTokens);
  const outputTokens = number(last?.outputTokens);
  const cachedInputTokens = number(last?.cachedInputTokens);
  // 端点不返回缓存用量时命中率是「未知」，不能报 0%。
  const cacheObserved = (cachedInputTokens !== null && cachedInputTokens > 0)
    || reports.some((report) => report.usage?.cacheReadObserved === true);
  const generationMs = reports.length
    ? reports.reduce((sum, report) => sum + (number(report.durationMs) ?? 0), 0) : null;
  const contextPeak = usages.reduce((peak, event) => Math.max(peak, number(event.data?.contextTokens) ?? 0), 0);
  const observed = number(last?.modelRequests);
  return {
    requests: observed ?? (reports.length || null),
    requestsSource: observed !== null ? 'run.usage 末次累计快照'
      : reports.length ? 'model.request 事件计数' : UNOBSERVED,
    inputTokens,
    outputTokens,
    totalTokens: inputTokens === null && outputTokens === null ? null : (inputTokens ?? 0) + (outputTokens ?? 0),
    cachedInputTokens: cacheObserved ? cachedInputTokens : null,
    cacheHitRate: cacheObserved && inputTokens ? cachedInputTokens / inputTokens : null,
    generationMs,
    tokensPerSecond: outputTokens !== null && generationMs ? outputTokens / (generationMs / MS_PER_SECOND) : null,
    contextPeak: contextPeak > 0 ? contextPeak : null,
    contextLimit: number(last?.contextLimit),
    exact: typeof last?.exact === 'boolean' ? last.exact : null
  };
}

function resolvePlan(db, sessionId, events, problems) {
  const latest = events.filter((event) => event.type === EVENT.plan).at(-1)?.data;
  if (latest && Array.isArray(latest.plan)) {
    const items = latest.plan.map(normalizeItem);
    return { summary: planOf(EVENT.plan, latest.explanation, items), counts: countPlan(items) };
  }
  const rows = query(db, problems, 'SELECT step, status, note FROM plan_items WHERE session_id = ? ORDER BY ord',
    'plan_items', sessionId);
  if (!rows.length) return { summary: planOf(UNOBSERVED, '', null), counts: null };
  const items = rows.map(normalizeItem);
  return {
    summary: planOf('plan_items（会话当前计划；本 run 没有 plan.updated）', '', items),
    counts: countPlan(items)
  };
}

function planOf(source, explanation, items) {
  return { source, explanation: explanation ?? '', items };
}

function normalizeItem(item) {
  return { step: String(item?.step ?? ''), status: String(item?.status ?? ''), note: String(item?.note ?? '') };
}

function countPlan(items) {
  const counts = { total: items.length, done: 0, open: 0, blocked: 0, skipped: 0, inProgress: 0, pending: 0, other: 0 };
  for (const { status } of items) {
    if (status === 'completed') counts.done += 1;
    else if (status === 'in_progress') {
      counts.inProgress += 1;
      counts.open += 1;
    } else if (status === 'pending') {
      counts.pending += 1;
      counts.open += 1;
    } else if (status === 'blocked') counts.blocked += 1;
    else if (status === 'skipped') counts.skipped += 1;
    else counts.other += 1;
  }
  return counts;
}

function summarizeContinuation(events, execution) {
  const continued = events.filter((event) => event.type === EVENT.continued).map((event) => event.data ?? {});
  const budgetSegments = number(execution?.budgetSegments);
  return {
    autoContinues: continued.length,
    budgetSegmentContinues: continued.filter((data) => BUDGET_SEGMENT_REASON.test(String(data.reason ?? ''))).length,
    events: continued,
    budgetSegments,
    budgetSegmentsSource: execution ? '任务契约 execution.budgetSegments' : UNOBSERVED
  };
}

function summarizeChanges(snapshot, terminal, execution) {
  const changes = snapshot.changes;
  if (changes && Array.isArray(changes.files)) {
    return changesOf('快照 changes.json', changes.files.map(fileOf), {
      added: number(changes.added),
      removed: number(changes.removed),
      truncated: boolOrNull(changes.truncated),
      error: changes.error ?? null
    });
  }
  if (terminal.changedFiles?.length) {
    return changesOf(`${terminal.source}.changedFiles`, terminal.changedFiles.map(pathOnly));
  }
  if (Array.isArray(execution?.changedPaths) && execution.changedPaths.length) {
    return changesOf('任务契约 execution.changedPaths', execution.changedPaths.map(pathOnly));
  }
  return changesOf(UNOBSERVED, []);
}

function changesOf(source, files, totals = {}) {
  return {
    source,
    total: source === UNOBSERVED ? null : files.length,
    added: totals.added ?? null,
    removed: totals.removed ?? null,
    truncated: totals.truncated ?? null,
    error: totals.error ?? null,
    files
  };
}

function fileOf(file) {
  return {
    path: String(file?.path ?? ''),
    status: String(file?.status ?? ''),
    added: number(file?.added),
    removed: number(file?.removed)
  };
}

/** 只有路径、没有增删行数时，明细保持未观测而不是填 0。 */
function pathOnly(path) {
  return { path: String(path), status: '', added: null, removed: null };
}

function summarizeChecks(db, runId, terminal, execution, problems) {
  if (terminal.checks?.length) return { source: `${terminal.source}.checks`, items: terminal.checks };
  const rows = query(db, problems, 'SELECT checks FROM tool_call_journal WHERE run_id = ?', 'tool_call_journal.checks',
    runId);
  const items = [];
  let damaged = false;
  for (const row of rows) {
    try {
      const parsed = JSON.parse(row.checks ?? '[]');
      if (Array.isArray(parsed)) items.push(...parsed.map(String));
      else damaged = true;
    } catch {
      damaged = true;
    }
  }
  if (items.length) return { source: 'tool_call_journal.checks', items };
  // 任务契约把带退出码的验证命令记在 execution.evidence[].check，旧 run 只有它。
  const evidence = (execution?.evidence ?? []).map((item) => item?.check).filter(Boolean).map(String);
  if (evidence.length) return { source: '任务契约 execution.evidence[].check', items: evidence };
  if (damaged) return { source: 'tool_call_journal.checks（部分内容损坏，已跳过）', items: [] };
  return { source: UNOBSERVED, items: null };
}

function summarizeTools(db, runId, events, problems) {
  const observations = query(db, problems,
    'SELECT call_id, fingerprint, tool, result FROM tool_observations WHERE run_id = ?', 'tool_observations', runId);
  const failures = {};
  for (const observation of observations) {
    const kind = classifyToolResult(observation.result);
    if (kind !== 'ok') failures[kind] = (failures[kind] ?? 0) + 1;
  }
  const groups = new Map();
  for (const observation of observations) {
    const key = observation.fingerprint ?? observation.call_id;
    const group = groups.get(key) ?? { tool: String(observation.tool ?? ''), count: 0 };
    group.count += 1;
    groups.set(key, group);
  }
  const repeated = [...groups.values()].filter((group) => group.count > 1)
    .sort((left, right) => right.count - left.count);
  return {
    calls: observations.length || null,
    started: events.filter((event) => event.type === EVENT.toolStarted).length || null,
    completed: events.filter((event) => event.type === EVENT.toolCompleted).length || null,
    failureTotal: observations.length ? Object.values(failures).reduce((sum, value) => sum + value, 0) : null,
    failures,
    repeatedGroups: observations.length ? repeated.length : null,
    repeatedExtra: observations.length ? repeated.reduce((sum, group) => sum + group.count - 1, 0) : null,
    repeatedTop: repeated.slice(0, TOP_REPEATED_GROUPS)
  };
}

/** 工具结果分类：只按结果文本里确定出现过的标记分桶。 */
export function classifyToolResult(result) {
  const text = typeof result === 'string' ? result : '';
  if (!text.trim()) return 'empty-result';
  if (text.includes(BASELINE_BLOCKED)) return 'blocked-baseline';
  const exit = EXIT_CODE.exec(text);
  if (FAILED_STATE.test(text) || (exit && Number(exit[1]) !== 0)) return 'command-failed';
  if (ERROR_LINE.test(text)) return 'tool-error';
  return 'ok';
}

function resolveRemaining(terminal, plan) {
  const items = plan.summary.items ?? [];
  const planOpen = plan.summary.items
    ? items.filter((item) => item.status === 'pending' || item.status === 'in_progress').map((item) => item.step) : null;
  const planBlocked = plan.summary.items
    ? items.filter((item) => item.status === 'blocked').map((item) => item.step) : null;
  const source = terminal.openSteps ? `${EVENT.ended}.openSteps/blockedSteps`
    : plan.summary.items ? `计划来源：${plan.summary.source}` : UNOBSERVED;
  return { openSteps: terminal.openSteps ?? planOpen, blockedSteps: terminal.blockedSteps ?? planBlocked, source };
}

function resolveNextActions(db, runId, terminal, execution, problems) {
  if (terminal.nextActions?.length) return { source: `${EVENT.ended}.nextActions`, items: terminal.nextActions };
  if (typeof execution?.nextAction === 'string' && execution.nextAction) {
    return { source: '任务契约 execution.nextAction', items: [execution.nextAction] };
  }
  const rows = query(db, problems, 'SELECT next_action FROM run_checkpoints WHERE run_id = ?', 'run_checkpoints', runId);
  const items = rows.map((row) => row.next_action).filter(Boolean).map(String);
  if (items.length) return { source: 'run_checkpoints.next_action', items };
  return { source: terminal.outcome === 'RUNNING' ? '运行中，暂无下一步' : UNOBSERVED, items: null };
}

function summarizeSources(events, snapshot, execution) {
  return {
    events: countByType(events),
    snapshot: snapshot.changes ? 'changes.json' : (existsSync(snapshot.dir) ? '快照目录缺少 changes.json' : '快照缺失'),
    contract: execution ? 'tasks/<sessionId>/current.json' : UNOBSERVED
  };
}

function countByType(events) {
  const counts = {};
  for (const event of events) counts[event.type] = (counts[event.type] ?? 0) + 1;
  return counts;
}

function lastOf(events, type) {
  for (let index = events.length - 1; index >= 0; index -= 1) {
    if (events[index].type === type) return events[index];
  }
  return null;
}

function number(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function boolOrNull(value) {
  return typeof value === 'boolean' ? value : null;
}

// ---------------------------------------------------------------- 文本渲染

/** 紧凑文本报告：每行一个主题，缺失值统一写「未观测」。 */
export function renderReport(report) {
  const { outcome, timing, models, planCounts, continuation, changes, checks, tools, remaining, nextActions } = report;
  const lines = [
    '运行诊断报告',
    `run        : ${report.runId}`,
    `session    : ${report.sessionId}`,
    `task       : ${show(report.taskId)}`,
    `workspace  : ${show(report.workspace)}`,
    `终态       : ${show(outcome.outcome)}${outcome.code ? ` / ${outcome.code}` : ''}（runs.status=${show(report.runStatus)}，来源 ${outcome.source}）`,
    `摘要       : ${outcome.summary ? outcome.summary : UNOBSERVED}`,
    `时间       : ${show(timing.startedAt)} → ${show(timing.finishedAt)}，耗时 ${show(timing.durationMs, formatDuration)}`,
    `模型       : ${renderModels(models)}`,
    `计划       : ${renderPlan(planCounts, report.plan)}`,
    `续跑       : 自动继续 ${continuation.autoContinues} 次（其中预算分段 ${continuation.budgetSegmentContinues} 次）；预算段数 ${show(continuation.budgetSegments)}（${continuation.budgetSegmentsSource}）`,
    `改动       : ${renderChanges(changes)}`,
    `检查       : ${renderChecks(checks)}`,
    `工具       : ${renderTools(tools)}`,
    `剩余项     : ${renderSteps(remaining)}`,
    `下一步     : ${nextActions.items === null ? UNOBSERVED : `${nextActions.items.join('；')}（${nextActions.source}）`}`
  ];
  const planLines = (report.plan.items ?? []).map((item) => `  - [${item.status}] ${item.step}${item.note ? `（${item.note}）` : ''}`);
  if (planLines.length) lines.push('计划明细   :', ...planLines);
  lines.push(`数据来源   : 事件 ${renderCounts(report.sources.events)}；快照 ${report.sources.snapshot}；契约 ${report.sources.contract}`);
  if (report.problems.length) {
    lines.push('数据问题   :');
    for (const problem of report.problems) lines.push(`  - ${problem}`);
  }
  return `${lines.join('\n')}\n`;
}

function renderModels(models) {
  if (models.requests === null && models.inputTokens === null) return UNOBSERVED;
  const cache = models.cacheHitRate === null
    ? `${show(models.cachedInputTokens, formatInt)}（命中率${UNOBSERVED}，端点未上报缓存用量）`
    : `${formatInt(models.cachedInputTokens)}（${formatPercent(models.cacheHitRate)}）`;
  return [
    `请求 ${show(models.requests, formatInt)} 次（${models.requestsSource}）`,
    `输入 ${show(models.inputTokens, formatInt)} / 输出 ${show(models.outputTokens, formatInt)} / 合计 ${show(models.totalTokens, formatInt)} token`,
    `缓存命中 ${cache}`,
    `tok/s ${models.tokensPerSecond === null ? UNOBSERVED : models.tokensPerSecond.toFixed(2)}（生成 ${show(models.generationMs, formatDuration)}）`,
    `上下文峰值 ${show(models.contextPeak, formatInt)} / ${show(models.contextLimit, formatInt)}`,
    models.exact === null ? null : `用量口径 ${models.exact ? 'exact' : 'exact=false（含本地估算）'}`
  ].filter(Boolean).join('；');
}

function renderPlan(counts, plan) {
  if (!counts) return `${UNOBSERVED}（${plan.source}）`;
  const skipped = counts.skipped ? ` / skipped ${counts.skipped}` : '';
  return `total ${counts.total} / done ${counts.done} / open ${counts.open} / blocked ${counts.blocked}${skipped}`
    + `（来源 ${plan.source}）`;
}

function renderChanges(changes) {
  if (changes.total === null) return UNOBSERVED;
  const size = changes.added === null ? '' : `，+${changes.added} -${changes.removed}`;
  const names = changes.files.slice(0, 8).map((file) => file.path).join('、');
  const more = changes.files.length > 8 ? ` 等 ${changes.files.length} 个` : '';
  return `${changes.total} 个文件${size}（${changes.source}）${names ? `：${names}${more}` : ''}`;
}

function renderChecks(checks) {
  if (checks.items === null) return UNOBSERVED;
  const shown = checks.items.slice(0, CHECK_LINES_SHOWN).map((item) => truncate(item, CHECK_LINE_WIDTH));
  const more = checks.items.length > CHECK_LINES_SHOWN ? `；等 ${checks.items.length} 项` : '';
  const body = shown.length ? `：${shown.join('；')}${more}` : '';
  return `${checks.items.length} 项（${checks.source}）${body}`;
}

function truncate(text, width) {
  return text.length > width ? `${text.slice(0, width)}…` : text;
}

function renderTools(tools) {
  if (tools.calls === null) {
    return tools.started === null ? UNOBSERVED
      : `started ${tools.started} / completed ${show(tools.completed, formatInt)}（工具观察记录${UNOBSERVED}）`;
  }
  const failures = Object.entries(tools.failures).map(([kind, count]) => `${kind} ${count}`).join('、') || '无';
  const repeated = tools.repeatedGroups ? `；重复调用 ${tools.repeatedGroups} 组，多出 ${tools.repeatedExtra} 次` : '；重复调用 0 组';
  const top = tools.repeatedTop.map((group) => `${group.tool}×${group.count}`).join('、');
  return `${tools.calls} 次已记录（started ${tools.started} / completed ${tools.completed}）；失败 ${tools.failureTotal}（${failures}）${repeated}${top ? `：${top}` : ''}`;
}

function renderSteps(remaining) {
  return `open ${renderList(remaining.openSteps)} / blocked ${renderList(remaining.blockedSteps)}（${remaining.source}）`;
}

function renderList(items) {
  if (items === null) return UNOBSERVED;
  return items.length ? items.join('；') : '无';
}

function renderCounts(counts) {
  const entries = Object.entries(counts);
  return entries.length ? entries.map(([type, count]) => `${type}×${count}`).join(' ') : UNOBSERVED;
}

function show(value, format = String) {
  return value === null || value === undefined ? UNOBSERVED : format(value);
}

function formatInt(value) {
  return typeof value === 'number' ? value.toLocaleString('en-US') : String(value);
}

function formatPercent(ratio) {
  return `${(ratio * 100).toFixed(1)}%`;
}

function formatDuration(ms) {
  if (typeof ms !== 'number') return UNOBSERVED;
  if (ms < MS_PER_SECOND) return `${ms}ms`;
  if (ms < MS_PER_MINUTE) return `${(ms / MS_PER_SECOND).toFixed(1)}s`;
  if (ms < MS_PER_HOUR) {
    return `${Math.floor(ms / MS_PER_MINUTE)}m${Math.round((ms % MS_PER_MINUTE) / MS_PER_SECOND)}s`;
  }
  return `${Math.floor(ms / MS_PER_HOUR)}h${Math.round((ms % MS_PER_HOUR) / MS_PER_MINUTE)}m`;
}

// ---------------------------------------------------------------- 命令行

export function parseArgs(argv) {
  const options = { json: false, latestFailed: false, root: undefined, runId: undefined, sessionId: undefined };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--json') options.json = true;
    else if (arg === '--latest-failed') options.latestFailed = true;
    else if (arg === '--root') options.root = argv[++index];
    else if (arg.startsWith('-')) throw new DiagnosticError(`未知参数：${arg}`, 'usage');
    else if (arg.startsWith('run_')) options.runId = arg;
    else options.sessionId = arg;
  }
  if (!options.runId && !options.sessionId && !options.latestFailed) {
    throw new DiagnosticError('需要 runId、sessionId 或 --latest-failed', 'usage');
  }
  return options;
}

function exitCodeOf(kind) {
  if (kind === 'usage') return EXIT_USAGE;
  if (kind === 'corrupt') return EXIT_CORRUPT;
  return EXIT_NOT_FOUND;
}

function main(argv) {
  try {
    const options = parseArgs(argv);
    const report = buildReport(options);
    process.stdout.write(options.json ? `${JSON.stringify(report, null, 2)}\n` : renderReport(report));
    return EXIT_OK;
  } catch (error) {
    const known = error instanceof DiagnosticError;
    process.stderr.write(`错误：${known ? error.message : `内部错误：${error.message}`}\n`);
    return known ? exitCodeOf(error.kind) : 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  process.exitCode = main(process.argv.slice(2));
}