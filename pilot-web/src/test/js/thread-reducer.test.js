import test from 'node:test';
import assert from 'node:assert/strict';

import { applyEvent, initialThread } from '../../../frontend/src/thread-reducer.js';

/** reducer 是纯函数，直接喂一个最小 draft（与 store 根状态同名同形）即可。 */
function draft() {
  return {
    thread: initialThread(),
    ui: { attentionKind: 'idle', browserDot: false, notice: null },
    timeline: [],
    streamCard: null,
    pendingInputs: [],
    queue: [],
    pendingApprovals: {},
    pendingArbitrations: {},
    agents: {},
    running: false,
    cancelling: false,
    runId: null,
    lastRunId: null,
    runUsage: null,
    lastOutcome: '',
    queueHeld: false,
    liveOutputTokens: 0,
    modelRetryUntil: 0,
    contextStatus: null,
    task: null,
    planItems: [],
    turnRuns: [],
    runChangesFetch: null,
    promoteBlank: null
  };
}

const turnOf = (state) => state.thread.rows.find((row) => row.type === 'turn');

test('预览只是临时文本，正式增量到达后不重复拼接', () => {
  const state = draft();
  applyEvent(state, { type: 'assistant.preview', payload: { text: '你' } }, { live: true });
  applyEvent(state, { type: 'assistant.preview', payload: { text: '好' } }, { live: true });
  assert.equal(turnOf(state).reply.text, '');
  assert.equal(turnOf(state).reply.preview, '你好');

  applyEvent(state, { type: 'assistant.delta', payload: { text: '你好，我来看看' } }, { live: true });
  assert.equal(turnOf(state).reply.text, '你好，我来看看');
  assert.equal(turnOf(state).reply.preview, '');
});

test('瞬时事件不推进游标，落库事件才推进', () => {
  const state = draft();
  applyEvent(state, { type: 'assistant.preview', sequence: 7, payload: { text: 'x' } }, { live: true });
  applyEvent(state, { type: 'assistant.reasoning', sequence: 7, payload: { text: 'y' } }, { live: true });
  assert.equal(state.thread.cursor, 0, '预览/思考不代表有过新事件');

  applyEvent(state, { type: 'assistant.delta', sequence: 7, payload: { text: 'z' } }, { live: true });
  assert.equal(state.thread.cursor, 7);
});

test('思考块在正文出现后收起，摘要显示当前动作', () => {
  const state = draft();
  applyEvent(state, { type: 'assistant.reasoning', payload: { text: '我先看看目录' } }, { live: true });
  applyEvent(state, { type: 'tool.started', payload: { callId: 'c1', name: 'shell', summary: 'ls' } }, { live: true });
  const thinking = turnOf(state).thinking;
  assert.equal(thinking.live, true);
  assert.ok(thinking.action, '工具开始时记下当前动作');

  applyEvent(state, { type: 'assistant.delta', payload: { text: '看完了' } }, { live: true });
  assert.equal(turnOf(state).thinking.live, false, '正文出来了，思考块要收');
});

test('暂停/中断不是失败：标题与可继续按钮按失败码区分', () => {
  const paused = draft();
  applyEvent(paused, { type: 'run.failed', sequence: 9, runId: 'r1', payload: { code: 'MAX_STEPS', message: '步数触顶' } }, { live: true });
  const pausedResult = paused.thread.rows.find((row) => row.type === 'result');
  assert.match(pausedResult.title, /已暂停/);
  assert.equal(pausedResult.tone, 'warn');
  assert.equal(pausedResult.resumable, true);
  assert.equal(pausedResult.errorCode, 'MAX_STEPS');

  const needInput = draft();
  applyEvent(needInput, { type: 'run.failed', sequence: 3, payload: { code: 'NEED_INPUT', message: '等你确认' } }, { live: true });
  const inputResult = needInput.thread.rows.find((row) => row.type === 'result');
  assert.match(inputResult.title, /需要输入/);
  assert.equal(inputResult.resumable, false, '需要的是新信息，不是原样重跑');
});

test('计划进度用完成数/总数，不再是空串', () => {
  const state = draft();
  applyEvent(state, {
    type: 'plan.updated',
    sequence: 5,
    payload: {
      plan: [
        { step: '检查工作区', status: 'completed' },
        { step: '跑测试', status: 'in_progress' },
        { step: '总结', status: 'pending' }
      ]
    }
  }, { live: true });
  assert.equal(state.thread.rows.find((row) => row.type === 'plan').items.length, 3);
  const card = state.timeline[state.timeline.length - 1];
  assert.match(card.title, /计划 1\/3/);
});

test('活动流有上限，长任务不会无限增长', () => {
  const state = draft();
  for (let index = 0; index < 360; index += 1) {
    applyEvent(state, {
      type: 'tool.completed', sequence: index + 1,
      payload: { callId: `c${index}`, latencyMs: 1, resultSummary: 'ok' }
    }, { live: true });
  }
  assert.ok(state.timeline.length <= 300, `活动流应被裁剪，实际 ${state.timeline.length}`);
});

test('工具行记下开始时间，慢工具能显示已跑秒数', () => {
  const state = draft();
  applyEvent(state, {
    type: 'tool.started', sequence: 1, timestamp: '2026-11-02T00:00:00Z',
    payload: { callId: 'c1', name: 'search_text', summary: 'path=pilot-server' }
  }, { live: true });
  const tool = turnOf(state).tools[0];
  assert.equal(tool.status, 'run');
  assert.ok(tool.startedAt > 0, '要有 startedAt 才能算「执行中 Ns」');
});

test('一轮结束时还没结果的工具收成中断，不再永远转圈', () => {
  const state = draft();
  applyEvent(state, {
    type: 'tool.started', sequence: 1,
    payload: { callId: 'c1', name: 'search_text', summary: 'path=pilot-server' }
  }, { live: true });
  applyEvent(state, {
    type: 'run.completed', sequence: 2, runId: 'run_1',
    payload: { summary: '完成' }
  }, { live: true });

  const tool = turnOf(state).tools[0];
  assert.equal(tool.status, 'interrupted');
  assert.equal(tool.meta, '未返回结果');
  assert.equal(state.running, false);
});

test('一轮结束后用量计时停住，思考也不再显示思考中', () => {
  const state = draft();
  applyEvent(state, {
    type: 'run.usage', sequence: 1, runId: 'run_1', timestamp: '2026-11-02T00:00:00Z',
    payload: { modelRequests: 2, inputTokens: 100, outputTokens: 20, contextTokens: 80, contextLimit: 1000, exact: true }
  }, { live: true });
  applyEvent(state, {
    type: 'assistant.reasoning', sequence: 2, timestamp: '2026-11-02T00:00:01Z',
    payload: { text: '先看目录' }
  }, { live: true });
  applyEvent(state, {
    type: 'run.completed', sequence: 3, runId: 'run_1', timestamp: '2026-11-02T00:01:00Z',
    payload: { summary: '完成' }
  }, { live: true });

  const turn = turnOf(state);
  assert.ok(turn.stats.finishedAt > turn.stats.startedAt);
  assert.equal(turn.thinking.live, false);
  assert.ok(turn.thinking.finishedAt > 0);
});


test('run.usage 带上缓存命中，端点不返回时按 0 处理', () => {
  const state = draft();
  applyEvent(state, {
    type: 'run.usage', sequence: 1, runId: 'run_1', timestamp: '2026-11-02T00:00:00Z',
    payload: { modelRequests: 2, inputTokens: 1000, outputTokens: 20, cachedInputTokens: 800, contextTokens: 80, contextLimit: 1000, exact: true }
  }, { live: true });

  assert.equal(state.runUsage.cachedInputTokens, 800, '缓存命中必须随用量一起进入 turn.stats');
  assert.equal(state.runUsage.inputTokens, 1000);

  const legacy = draft();
  applyEvent(legacy, {
    type: 'run.usage', sequence: 1, runId: 'run_1', timestamp: '2026-11-02T00:00:00Z',
    payload: { modelRequests: 2, inputTokens: 1000, outputTokens: 20, contextTokens: 80, contextLimit: 1000, exact: true }
  }, { live: true });

  assert.equal(legacy.runUsage.cachedInputTokens, 0, '老事件没有该字段时按 0，不能是 NaN');
});


test('引导回放保留正文和图片，后续回复仍属于原运行且显示在引导后', () => {
  const state = draft();
  applyEvent(state, { type: 'run.started', runId: 'r1', sequence: 1, payload: { input: '原任务' } }, { live: true });
  applyEvent(state, { type: 'assistant.preview', runId: 'r1', payload: { text: '旧方向预览' } }, { live: true });
  const image = { name: '图.png', path: 'C:\\attachments\\图.png', mimeType: 'image/png' };
  applyEvent(state, { type: 'run.steered', runId: 'r1', sequence: 2, payload: { input: '请参考图', attachments: [image], imagePositions: [2] } }, { live: true });
  assert.equal(state.running, true);
  const row = state.thread.rows.at(-1);
  assert.equal(row.type, 'user'); assert.deepEqual(row.attachments, [image]); assert.deepEqual(row.imagePositions, [2]);
  assert.equal(state.thread.rows.find(r => r.type === 'turn').reply.preview, '');
  applyEvent(state, { type: 'assistant.delta', runId: 'r1', sequence: 3, payload: { text: '按照新要求继续' } }, { live: true });
  assert.deepEqual(state.thread.rows.at(-1).runIds, ['r1']);
  assert.equal(state.thread.rows.at(-1).reply.text, '按照新要求继续');
});
