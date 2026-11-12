import test from 'node:test';
import assert from 'node:assert/strict';
import { produce } from 'immer';
import { initialThread, applyEvent, restoreLiveOutput } from '../src/thread-reducer.js';
import { eventTimestamp, parseTimestamp } from '../src/lib/tool-meta.js';

const start = Date.parse('2026-11-12T00:00:00Z');
const iso = seconds => new Date(start + seconds * 1000).toISOString();
const turn = state => state.thread.rows.findLast(row => row.type === 'turn');
function session() {
  let state = { thread: initialThread(), ui: {}, timeline: [], pendingInputs: [], queue: [], pendingApprovals: {}, agents: {} };
  let sequence = 0;
  return {
    get state() { return state; },
    event(type, payload, seconds) {
      state = produce(state, draft => applyEvent(draft, { type, payload, sequence: ++sequence, runId: 'r', timestamp: iso(seconds) }, { live: true }));
    },
    restore(snapshot) {
      state = produce(state, draft => restoreLiveOutput(draft, { runId: 'r', previewId: 'p', previewOffset: 3, baseSequence: state.thread.cursor,
        historyRevision: 0, complete: true, text: '', reasoning: '思考', ...snapshot }));
    }
  };
}

test('重新进入和重复恢复使用服务端起点，正文开始后恢复已结束的计时', () => {
  const original = session(); original.event('run.started', { input: '任务' }, 0);
  original.event('assistant.reasoning', { text: '思考' }, 10);
  const reopened = session(); reopened.event('run.started', { input: '任务' }, 0);
  reopened.restore({ reasoningStartedAt: iso(10) });
  assert.equal(turn(reopened.state).thinking.startedAt, turn(original.state).thinking.startedAt);
  reopened.restore({ reasoningStartedAt: iso(10) });
  assert.equal(turn(reopened.state).thinking.startedAt, start + 10000);
  assert.equal(turn(reopened.state).thinking.text, '思考');
  reopened.restore({ reasoningStartedAt: iso(10), reasoningFinishedAt: iso(80), text: '正文' });
  assert.equal(turn(reopened.state).thinking.live, false);
  assert.equal(turn(reopened.state).thinking.finishedAt, start + 80000);
});

test('重试保留起点，后续推理重新变为活动状态，结束事件固定终点', () => {
  const s = session(); s.event('run.started', { input: '任务' }, 0);
  s.event('assistant.reasoning', { text: '旧' }, 10);
  s.event('assistant.reasoning', { reset: true }, 20);
  s.event('assistant.reasoning', { text: '新' }, 30);
  assert.equal(turn(s.state).thinking.startedAt, start + 10000);
  assert.equal(turn(s.state).thinking.text, '新');
  s.event('assistant.preview', { text: '正文' }, 40);
  assert.equal(turn(s.state).thinking.live, false);
  s.event('assistant.delta', { text: '正文' }, 50);
  s.event('assistant.reasoning', { text: '下一步' }, 60);
  assert.equal(turn(s.state).thinking.live, true);
  assert.equal(turn(s.state).thinking.finishedAt, null);
  assert.equal(turn(s.state).thinking.startedAt, start + 10000);
  s.event('run.cancelled', { reason: '结束' }, 90);
  assert.equal(turn(s.state).thinking.live, false);
  assert.equal(turn(s.state).thinking.finishedAt, start + 90000);
});

test('正文入库后仍恢复计时元数据，新运行不继承旧起点', () => {
  const s = session(); s.event('run.started', { input: '任务' }, 0);
  s.event('assistant.delta', { text: '正文' }, 60);
  s.restore({ reasoning: '', reasoningStartedAt: iso(10), reasoningFinishedAt: iso(50) });
  assert.equal(turn(s.state).thinking.startedAt, start + 10000);
  assert.equal(turn(s.state).thinking.live, false);
  s.event('run.completed', { summary: '完成', changedFiles: [], checks: [] }, 70);
  s.event('run.started', { input: '新任务' }, 90);
  s.event('assistant.reasoning', { text: '新思考' }, 100);
  assert.equal(turn(s.state).thinking.startedAt, start + 100000);
});

test('旧服务端缺失或无效时间不会伪造当前时间作为起点', () => {
  const s = session(); s.event('run.started', { input: '任务' }, 0);
  s.restore({ reasoningStartedAt: 'invalid', text: '正文' });
  assert.equal(turn(s.state).thinking.startedAt, undefined);
  assert.equal(turn(s.state).thinking.live, false);
  assert.equal(parseTimestamp(undefined), undefined);
  assert.equal(eventTimestamp({ timestamp: 0 }), 0);
  assert.equal(eventTimestamp({ timestamp: start }), start);
  assert.equal(eventTimestamp({ timestamp: iso(0) }), start);
});
