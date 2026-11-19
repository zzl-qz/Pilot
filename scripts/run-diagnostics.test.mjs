// 运行诊断报告的回归测试：用临时工作区造最小 .pilot 数据，验证报告聚合、缺失字段与边界错误。
// 断言重点是「缺数据必须写未观测」，不是「填 0 好看」。
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { buildReport, classifyToolResult, DiagnosticError, renderReport } from './run-diagnostics.mjs';

const script = fileURLToPath(new URL('./run-diagnostics.mjs', import.meta.url));
const RUN = 'run_test-0001';
const SESSION = 'ses_test-0001';

const RUN_STARTED = '2026-11-18T10:00:00.000Z';
const RUN_FINISHED = '2026-11-18T10:05:30.000Z';

/** 建一个临时工作区：.pilot/pilot.db + 可选快照与任务契约。 */
function workspace(t, { run = {}, events = [], snapshot, contract, journal = [], observations = [], checkpoints = [] } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'pilot run diagnostics '));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const pilot = join(root, '.pilot');
  mkdirSync(pilot, { recursive: true });
  const db = new DatabaseSync(join(pilot, 'pilot.db'));
  const schema = [
    'CREATE TABLE sessions (id TEXT PRIMARY KEY)',
    'CREATE TABLE runs (id TEXT PRIMARY KEY, session_id TEXT, status TEXT, started_at TEXT, finished_at TEXT)',
    'CREATE TABLE events (run_id TEXT, session_id TEXT, sequence INTEGER, type TEXT, payload TEXT, created_at TEXT)',
    'CREATE TABLE plan_items (session_id TEXT, ord INTEGER, step TEXT, status TEXT, note TEXT)',
    'CREATE TABLE tool_observations (run_id TEXT, call_id TEXT, fingerprint TEXT, tool TEXT, result TEXT, created_at TEXT)',
    'CREATE TABLE tool_call_journal (run_id TEXT, tool TEXT, checks TEXT)',
    'CREATE TABLE run_checkpoints (run_id TEXT, next_action TEXT)'
  ];
  for (const sql of schema) db.exec(sql);
  db.prepare('INSERT INTO sessions (id) VALUES (?)').run(SESSION);
  db.prepare('INSERT INTO runs (id, session_id, status, started_at, finished_at) VALUES (?, ?, ?, ?, ?)')
    .run(RUN, SESSION, run.status ?? 'FAILED', run.startedAt ?? RUN_STARTED, run.finishedAt ?? RUN_FINISHED);
  const insert = db.prepare(
    'INSERT INTO events (run_id, session_id, sequence, type, payload, created_at) VALUES (?, ?, ?, ?, ?, ?)');
  events.forEach((event, index) => {
    insert.run(RUN, SESSION, index + 1, event.type, typeof event.payload === 'string' ? event.payload
      : JSON.stringify(event.payload), event.at ?? RUN_STARTED);
  });
  for (const row of journal) {
    db.prepare('INSERT INTO tool_call_journal (run_id, tool, checks) VALUES (?, ?, ?)')
      .run(RUN, row.tool ?? 'run_command', row.checks ?? '[]');
  }
  for (const row of observations) {
    db.prepare('INSERT INTO tool_observations (run_id, call_id, fingerprint, tool, result, created_at) '
      + 'VALUES (?, ?, ?, ?, ?, ?)').run(RUN, row.callId, row.fingerprint, row.tool, row.result, RUN_STARTED);
  }
  for (const row of checkpoints) {
    db.prepare('INSERT INTO run_checkpoints (run_id, next_action) VALUES (?, ?)').run(RUN, row.nextAction);
  }
  db.close();
  if (snapshot) {
    const dir = join(pilot, 'snapshots', RUN);
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, 'changes.json'), typeof snapshot === 'string' ? snapshot : JSON.stringify(snapshot));
  }
  if (contract) {
    const dir = join(pilot, 'tasks', SESSION);
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, 'current.json'), typeof contract === 'string' ? contract : JSON.stringify(contract));
  }
  return root;
}

function cli(args) {
  const result = spawnSync(process.execPath, ['--disable-warning=ExperimentalWarning', script, ...args],
    { encoding: 'utf8' });
  return { code: result.status, stdout: result.stdout, stderr: result.stderr };
}

const fullEvents = [
  { type: 'run.started', payload: { input: '实现想法库' } },
  {
    type: 'run.usage',
    payload: { modelRequests: 3, inputTokens: 1200, outputTokens: 300, cachedInputTokens: 600, contextTokens: 900,
      contextLimit: 4000, exact: false }
  },
  {
    type: 'model.request',
    payload: { report: { durationMs: 2000, usage: {} } }
  },
  {
    type: 'plan.updated',
    payload: {
      plan: [
        { step: '读代码', status: 'completed' },
        { step: '写实现', status: 'completed' },
        { step: '跑验证', status: 'in_progress' },
        { step: '浏览器回归', status: 'blocked', note: '浏览器不可用' }
      ],
      explanation: '实现完成，验证受阻'
    }
  },
  { type: 'run.continued', payload: { attempt: 1, maxAttempts: 2, reason: '计划仍有未完成项，继续执行' } },
  { type: 'run.continued', payload: { attempt: 2, maxAttempts: 2, reason: '预算分段 2/8：上下文触顶后继续' } },
  { type: 'tool.started', payload: { callId: 'call_1', name: 'run_command', summary: 'npm test' } },
  { type: 'tool.completed', payload: { callId: 'call_1', resultSummary: 'ok', latencyMs: 1200 } },
  {
    type: 'run.ended',
    payload: {
      outcome: 'BLOCKED',
      code: 'BLOCKED',
      summary: '浏览器回归被环境阻塞',
      changedFiles: ['web/a.ts'],
      checks: ['node --test（通过）'],
      planDone: 2,
      planTotal: 4,
      openSteps: ['跑验证'],
      blockedSteps: ['浏览器回归'],
      nextActions: ['装好浏览器后重跑回归'],
      resumable: true
    }
  }
];

const fullSnapshot = {
  runId: RUN,
  workspace: 'E:\\Pilot',
  files: [{ path: 'web/a.ts', status: 'M', added: 12, removed: 3 }],
  added: 12,
  removed: 3,
  truncated: false,
  error: null
};

const fullContract = {
  taskId: 'task_1',
  execution: { budgetSegments: 2, nextAction: '装好浏览器后重跑回归', changedPaths: ['web/a.ts'] }
};

test('有效 run：聚合事件、快照与契约，并算出可核对的比例', (t) => {
  const root = workspace(t, { events: fullEvents, snapshot: fullSnapshot, contract: fullContract });
  const report = buildReport({ root, runId: RUN });

  assert.equal(report.outcome.outcome, 'BLOCKED');
  assert.equal(report.outcome.code, 'BLOCKED');
  assert.equal(report.outcome.source, 'run.ended');
  assert.equal(report.timing.durationMs, 330_000);
  assert.equal(report.taskId, 'task_1');
  assert.equal(report.workspace, 'E:\\Pilot');

  assert.equal(report.models.requests, 3);
  assert.equal(report.models.totalTokens, 1500);
  assert.equal(report.models.cacheHitRate, 0.5);
  assert.equal(report.models.tokensPerSecond, 150);
  assert.equal(report.models.contextPeak, 900);

  assert.deepEqual(report.planCounts, { total: 4, done: 2, open: 1, blocked: 1, skipped: 0, inProgress: 1, pending: 0,
    other: 0 });
  assert.equal(report.continuation.autoContinues, 2);
  assert.equal(report.continuation.budgetSegmentContinues, 1);
  assert.equal(report.continuation.budgetSegments, 2);

  assert.equal(report.changes.total, 1);
  assert.equal(report.changes.added, 12);
  assert.deepEqual(report.checks.items, ['node --test（通过）']);
  assert.deepEqual(report.remaining.openSteps, ['跑验证']);
  assert.deepEqual(report.remaining.blockedSteps, ['浏览器回归']);
  assert.deepEqual(report.nextActions.items, ['装好浏览器后重跑回归']);
  assert.deepEqual(report.problems, []);

  assert.equal(cli([RUN, '--root', root]).code, 0);
  const text = renderReport(report);
  assert.match(text, /BLOCKED \/ BLOCKED/);
  assert.match(text, /缓存命中 600（50\.0%）/);
  assert.match(text, /total 4 \/ done 2 \/ open 1 \/ blocked 1/);
  assert.match(text, /自动继续 2 次（其中预算分段 1 次）/);
});

test('sessionId 入口取该会话最近一次 run', (t) => {
  const root = workspace(t, { events: fullEvents, contract: fullContract });
  const report = buildReport({ root, sessionId: SESSION });
  assert.equal(report.runId, RUN);
});

test('旧 run 没有新事件：字段写未观测，不填 0', (t) => {
  const root = workspace(t, {
    events: [
      { type: 'run.started', payload: { input: '旧任务' } },
      { type: 'run.failed', payload: { code: 'INTERNAL_ERROR', message: '执行失败：boom', retryable: true } }
    ]
  });
  const report = buildReport({ root, runId: RUN });

  assert.equal(report.outcome.outcome, 'FAILED');
  assert.equal(report.outcome.code, 'INTERNAL_ERROR');
  assert.equal(report.models.requests, null);
  assert.equal(report.models.totalTokens, null);
  assert.equal(report.models.cacheHitRate, null);
  assert.equal(report.models.tokensPerSecond, null);
  assert.equal(report.planCounts, null);
  assert.equal(report.plan.source, '未观测');
  assert.equal(report.continuation.budgetSegments, null);
  assert.equal(report.changes.total, null);
  assert.equal(report.checks.items, null);
  assert.equal(report.tools.calls, null);

  const text = renderReport(report);
  assert.match(text, /模型\s+: 未观测/);
  assert.match(text, /计划\s+: 未观测/);
  assert.match(text, /改动\s+: 未观测/);
  assert.match(text, /快照缺失/);
});

test('用量没有缓存字段时命中率是未观测，不是 0%', (t) => {
  const root = workspace(t, {
    events: [
      { type: 'run.usage', payload: { modelRequests: 2, inputTokens: 800, outputTokens: 200, contextTokens: 500,
        contextLimit: 4000, exact: true } },
      { type: 'run.completed', payload: { summary: '完成' } }
    ]
  });
  const report = buildReport({ root, runId: RUN });
  assert.equal(report.models.inputTokens, 800);
  assert.equal(report.models.cachedInputTokens, null);
  assert.equal(report.models.cacheHitRate, null);
  assert.match(renderReport(report), /命中率未观测/);
});

test('工具失败分类与重复调用次数', (t) => {
  const root = workspace(t, {
    events: [{ type: 'run.completed', payload: { summary: '完成' } }],
    observations: [
      { callId: 'c1', fingerprint: 'f1', tool: 'run_command', result: '状态: COMPLETED\n退出码: 0' },
      { callId: 'c2', fingerprint: 'f1', tool: 'run_command', result: '状态: COMPLETED\n退出码: 0' },
      { callId: 'c3', fingerprint: 'f2', tool: 'run_command', result: '状态: FAILED\n退出码: 1' },
      { callId: 'c4', fingerprint: 'f3', tool: 'run_command', result: 'ERROR: 无法建立命令前文件基线，命令未执行' },
      { callId: 'c5', fingerprint: 'f4', tool: 'grep', result: '  ' }
    ]
  });
  const report = buildReport({ root, runId: RUN });
  assert.deepEqual(report.tools.failures, { 'empty-result': 1, 'command-failed': 1, 'blocked-baseline': 1 });
  assert.equal(report.tools.failureTotal, 3);
  assert.equal(report.tools.repeatedGroups, 1);
  assert.equal(report.tools.repeatedExtra, 1);
  assert.equal(classifyToolResult('状态: COMPLETED\n退出码: 0'), 'ok');
  assert.equal(classifyToolResult('状态: COMPLETED\n退出码: 3'), 'command-failed');
  assert.equal(classifyToolResult('ERROR: 找不到文件'), 'tool-error');
});

test('run 不存在时给出明确错误', (t) => {
  const root = workspace(t, { events: fullEvents });
  assert.throws(() => buildReport({ root, runId: 'run_missing' }), (error) => {
    assert.ok(error instanceof DiagnosticError);
    assert.equal(error.kind, 'not-found');
    assert.match(error.message, /找不到 run：run_missing/);
    return true;
  });
  const result = cli(['run_missing', '--root', root]);
  assert.equal(result.code, 2);
  assert.match(result.stderr, /错误：找不到 run：run_missing/);
});

test('会话不存在、缺少参数时也返回明确错误', (t) => {
  const root = workspace(t, { events: fullEvents });
  assert.throws(() => buildReport({ root, sessionId: 'ses_missing' }), /找不到会话：ses_missing/);
  assert.equal(cli(['--root', root]).code, 64);
  assert.equal(cli(['ses_missing', '--root', root]).code, 2);
});

test('事件 JSON 损坏时报错，不静默当空', (t) => {
  const root = workspace(t, { events: [{ type: 'run.started', payload: '{"input": 未闭合' }] });
  assert.throws(() => buildReport({ root, runId: RUN }), (error) => {
    assert.equal(error.kind, 'corrupt');
    assert.match(error.message, /事件 JSON 损坏/);
    return true;
  });
  const result = cli([RUN, '--root', root]);
  assert.equal(result.code, 3);
  assert.match(result.stderr, /事件 JSON 损坏/);
});

test('快照与契约损坏时退回事件数据并记录问题', (t) => {
  const root = workspace(t, {
    events: [
      { type: 'run.ended', payload: { outcome: 'PARTIAL', code: 'TASK_INCOMPLETE', changedFiles: ['web/a.ts'],
        checks: ['npm test'], openSteps: ['收尾'], blockedSteps: [], nextActions: ['继续'], planDone: 1, planTotal: 2 } }
    ],
    snapshot: '{ 坏掉的 JSON',
    contract: 'not json either'
  });
  const report = buildReport({ root, runId: RUN });
  assert.equal(report.changes.source, 'run.ended.changedFiles');
  assert.equal(report.changes.total, 1);
  assert.equal(report.changes.added, null);
  assert.equal(report.taskId, null);
  assert.equal(report.problems.length, 2);
  assert.ok(report.problems.some((problem) => problem.includes('快照 changes.json JSON 损坏')));
  assert.ok(report.problems.some((problem) => problem.includes('任务契约 JSON 损坏')));
  assert.equal(cli([RUN, '--root', root]).code, 0);
});

test('--latest-failed 挑最近一次失败或中断的 run', (t) => {
  const root = workspace(t, { events: fullEvents });
  const report = buildReport({ root, latestFailed: true });
  assert.equal(report.runId, RUN);
  const result = cli(['--latest-failed', '--root', root, '--json']);
  assert.equal(result.code, 0);
  assert.equal(JSON.parse(result.stdout).runId, RUN);
});

test('旧 run 没有 checks 字段时用任务契约的 evidence，长列表截断显示', (t) => {
  const checks = Array.from({ length: 7 }, (_, index) => `node --test part-${index} → exit 0`);
  const root = workspace(t, {
    events: [{ type: 'run.failed', payload: { code: 'TASK_INCOMPLETE', message: '计划仍有未完成项' } }],
    contract: { taskId: 'task_2', execution: { evidence: checks.map((check) => ({ check, exitCode: 0 })) } }
  });
  const report = buildReport({ root, runId: RUN });
  assert.equal(report.checks.source, '任务契约 execution.evidence[].check');
  assert.equal(report.checks.items.length, 7);
  const text = renderReport(report);
  assert.match(text, /检查\s+: 7 项（任务契约 execution\.evidence\[\]\.check）：node --test part-0 → exit 0/);
  assert.match(text, /等 7 项/);
  assert.doesNotMatch(text, /part-6/);
});