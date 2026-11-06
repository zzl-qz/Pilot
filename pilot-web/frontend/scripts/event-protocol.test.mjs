import test from 'node:test';
import assert from 'node:assert/strict';
import { produce } from 'immer';
import { EVENT_TYPES } from '../src/event-types.js';
import { initialThread, applyEvent } from '../src/thread-reducer.js';

const start = ['run.started', { input: '任务' }];
const agent = ['subagent.started', { agentId: 'a', task: '子任务', name: '检查' }];
const approval = ['approval.required', { callId: 'c', name: 'write_file', argsSummary: '文件' }];
const tool = ['tool.started', { callId: 'c', name: 'read_file', summary: '文件' }];
const arbitration = ['arbitration.required', { arbitrationId: 'arb', kind: 'conflict', taskId: 't', options: ['保留'], report: '冲突报告' }];
const turn = state => state.thread.rows.findLast(row => row.type === 'turn');
const cases = [
  ['run.started', start[1], [], state => { assert.equal(state.running, true); assert.equal(state.thread.rows[0].text, '任务'); }],
  ['run.usage', { inputTokens: 100, outputTokens: 10, cachedInputTokens: 50, contextTokens: 20, contextLimit: 100, modelRequests: 1, exact: true }, [start], state => { assert.equal(state.runUsage.cachedInputTokens, 50); assert.equal(state.contextStatus.percent, 20); }],
  ['model.request', { report: { id: 'request', purpose: 'main', startedAt: 1 } }, [start], state => assert.equal(state.latestContextReport.id, 'request')],
  ['execution.updated', { mode: 'DIRECT', phase: 'EXECUTING' }, [], state => assert.equal(state.thread.rows.length, 0)],
  ['assistant.delta', { text: '正式内容' }, [start], state => assert.equal(turn(state).reply.text, '正式内容')],
  ['assistant.preview', { text: '预览' }, [start], state => { assert.equal(turn(state).reply.preview, '预览'); assert.equal(state.thread.cursor, 1); }],
  ['assistant.reasoning', { text: '推理' }, [start], state => assert.equal(turn(state).thinking.text, '推理')],
  ['context.compacted', { coveredMessages: 2, charactersBefore: 200, charactersAfter: 30, summary: '摘要' }, [], state => assert.equal(state.thread.rows[0].summary, '摘要')],
  ['plan.updated', { plan: [{ step: '验证', status: 'pending' }] }, [start], state => assert.equal(state.planItems[0].step, '验证')],
  ['run.steered', { input: '新方向', attachments: [], imagePositions: [] }, [start], state => assert.equal(state.thread.rows.at(-1).steered, true)],
  ['run.continued', { attempt: 1, maxAttempts: 3, reason: '继续' }, [start, ['run.failed', { code: 'MAX_STEPS', message: '暂停' }]], state => { assert.equal(state.running, true); assert.equal(state.thread.rows.find(row => row.type === 'result').continued, true); }],
  ['run.retrying', { attempt: 1, delayMs: 100, reason: '重试' }, [start], state => assert.ok(state.modelRetryUntil > 0)],
  ['subagent.started', agent[1], [start], state => assert.equal(state.agents.a.status, 'running')],
  ['subagent.finished', { agentId: 'a', summary: '真实协议结论', ok: true }, [start, agent], state => { assert.equal(state.agents.a.status, 'done'); assert.equal(state.agents.a.result, '真实协议结论'); }],
  ['task.updated', { taskId: 't', title: '任务契约', status: 'running', detail: '进行中' }, [], state => assert.equal(state.task.taskId, 't')],
  ['multi.budget', { dispatchId: 'd', status: 'stopped', maxTotalTokens: 100, maxDispatchSeconds: 20, usedTokens: 100, estimatedTokens: 10, reservedTokens: 0, inFlightRequests: 0, cancellationRequested: true }, [], state => assert.equal(state.timeline[0].status, '软停止')],
  ['sandbox.diff', { sandboxId: 'box', agentId: 'a', taskId: 't', path: 'a.ts', added: 1, removed: 0 }, [], state => assert.equal(state.thread.rows[0].sandboxId, 'box')],
  ['arbitration.required', arbitration[1], [], state => { assert.equal(state.thread.rows[0].detail, '冲突报告'); assert.ok(state.pendingArbitrations.arb); }],
  ['arbitration.resolved', { arbitrationId: 'arb', choice: '保留' }, [arbitration], state => { assert.equal(state.thread.rows[0].resolution, '保留'); assert.equal(state.pendingArbitrations.arb, undefined); }],
  ['tool.started', tool[1], [start], state => assert.equal(turn(state).tools[0].status, 'run')],
  ['approval.required', approval[1], [start], state => { assert.ok(state.pendingApprovals.c); assert.equal(turn(state).tools[0].status, 'wait'); }],
  ['approval.resolved', { callId: 'c', decision: 'APPROVED' }, [start, approval], state => { assert.equal(state.pendingApprovals.c, undefined); assert.equal(turn(state).tools[0].status, 'run'); }],
  ['tool.completed', { callId: 'c', resultSummary: '内容', latencyMs: 10 }, [start, tool], state => { assert.equal(turn(state).tools[0].status, 'ok'); assert.equal(turn(state).tools[0].body, '内容'); }],
  ['run.failed', { code: 'MODEL_ERROR', message: '失败', retryable: false }, [start, tool], state => { assert.equal(state.lastOutcome, 'MODEL_ERROR'); assert.equal(turn(state).tools[0].status, 'interrupted'); }],
  ['run.cancelled', { reason: '取消' }, [start], state => { assert.equal(state.running, false); assert.equal(state.queueHeld, true); }],
  ['run.completed', { summary: '完成', changedFiles: [], checks: [] }, [start], state => { assert.equal(state.lastOutcome, 'completed'); assert.equal(state.thread.rows.at(-1).title, '执行结束'); }]
];
test('协议回归覆盖每个已注册的 SSE 类型且没有重复', () => {
  assert.deepEqual(cases.map(item => item[0]).toSorted(), [...EVENT_TYPES].sort());
});
for (const [type, payload, before, verify] of cases) {
  for (const live of [true, false]) test(`${type} 在${live ? '实时' : '回放'}中保持业务结果`, () => {
    let state = { thread: initialThread(), ui: { notice: null }, timeline: [], pendingInputs: [], queue: [], pendingApprovals: {}, pendingArbitrations: {}, agents: {}, running: false, runUsage: null, contextStatus: null };
    let sequence = 0;
    for (const [name, data] of [...before, [type, payload]]) {
      const event = { type: name, payload: data, sequence: ++sequence, runId: 'r', timestamp: '2026-11-06T00:00:00Z' };
      state = produce(state, draft => applyEvent(draft, event, { live }));
    }
    verify(state); assert.equal(state.thread.windowEvents.at(-1).type, type);
  });
}
test('未知历史事件保留游标和原记录而不新增可见行', () => {
  const state = { thread: initialThread(), ui: {}, timeline: [] };
  applyEvent(state, { type: 'future.event', sequence: 7, payload: {} }, { live: false });
  assert.equal(state.thread.cursor, 7); assert.equal(state.thread.windowEvents[0].type, 'future.event'); assert.equal(state.thread.rows.length, 0);
});
for (const decision of ['APPROVED', 'DENIED']) test(`真实工具审批事件顺序在 Immer 中保持列表与索引一致：${decision}`, () => {
  let state = { thread: initialThread(), ui: {}, timeline: [], pendingInputs: [], queue: [], pendingApprovals: {}, agents: {} };
  let sequence = 0;
  const apply = ([type, payload]) => {
    state = produce(state, draft => applyEvent(draft, { type, payload, sequence: ++sequence, runId: 'r' }, { live: true }));
  };
  apply(start); apply(tool); apply(approval);
  assert.ok(turn(state).tools.every(row => row.status === 'wait'));
  apply(['approval.resolved', { callId: 'c', decision }]);
  assert.ok(turn(state).tools.every(row => row.status === (decision === 'APPROVED' ? 'run' : 'error')));
  apply(['tool.completed', { callId: 'c', resultSummary: decision === 'APPROVED' ? '内容' : 'ERROR: 用户拒绝', latencyMs: 10 }]);
  assert.ok(turn(state).tools.every(row => row.status === (decision === 'APPROVED' ? 'ok' : 'error')));
  assert.equal(turn(state).toolIndex.c.body, turn(state).tools[0].body);
});
