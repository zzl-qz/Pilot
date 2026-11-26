import test from 'node:test';
import assert from 'node:assert/strict';
import { produce } from 'immer';
import { initialThread, applyEvent, settleStoppedWork, prependHistoryPage, appendHistoryPage } from '../src/thread-reducer.js';
import { buildThreadDisplay } from '../src/lib/thread-display.js';

const start = Date.parse('2026-11-20T13:00:00Z');
const iso = (seconds) => new Date(start + seconds * 1000).toISOString();

function emptyState() {
  return {
    thread: initialThread(), ui: {}, timeline: [], pendingInputs: [], queue: [],
    pendingApprovals: {}, pendingArbitrations: {}, agents: {}, running: false, runUsage: null
  };
}

function event(type, payload, sequence) {
  return { type, payload, sequence, runId: 'r1', timestamp: iso(sequence) };
}

/** run.started 落在更早那一页，之后跨了几页才有 run.completed。 */
const earlierPage = [
  { type: 'user.message', payload: { text: '长任务' }, sequence: 1, timestamp: iso(1) },
  event('run.started', { input: '长任务' }, 2),
  event('model.request', { report: { id: 'q1', purpose: 'main', startedAt: start + 2000 } }, 3),
  event('run.usage', { modelRequests: 1, inputTokens: 1000, cachedInputTokens: 800 }, 4)
];
const laterPage = [
  event('run.usage', { modelRequests: 2, inputTokens: 2000, cachedInputTokens: 1600 }, 5),
  event('run.completed', { summary: '完成', changedFiles: [], checks: [] }, 5000)
];

/** 先落更晚那一页（等价于首次进入会话看到的窗口），再上滑把更早一页拼到前面。 */
function newerRoot() {
  return produce(emptyState(), (draft) => {
    draft.thread.replaying = true;
    for (const item of laterPage) applyEvent(draft, item, { live: false });
    if (!draft.running) settleStoppedWork(draft, start + 5000 * 1000);
    draft.thread.replaying = false;
  });
}

function replay(root = newerRoot()) {
  return produce(root, (draft) => prependHistoryPage(draft, earlierPage));
}

test('上滑翻页后，拆到更早一页的 run.started 仍然决定耗时起点', () => {
  const root = replay();
  const turns = root.thread.rows.filter((row) => row.type === 'turn');
  assert.equal(turns.length, 2, '同一段运行被翻页拆成两段回合');
  assert.equal(turns[0].stats.startedAt, start + 2000, '更早一段取真实 run.started，不取本页首个事件时间');
  assert.equal(turns[1].stats.finishedAt, start + 5000000, '更晚一段保留 run.completed 的终点');
});

test('翻页拼接后展示出的耗时等于真实时长，不再塌成 0:00', () => {
  const display = buildThreadDisplay(replay().thread.rows);
  const turn = display.rows.find((row) => row.type === 'turn');
  const seconds = Math.floor((turn.stats.finishedAt - turn.stats.startedAt) / 1000);
  assert.equal(seconds, 4998, '显示时长按 run.started → run.completed 计算');
});

test('翻页重放不把隔离区的中间用量写回根状态', () => {
  const base = newerRoot();
  assert.deepEqual(replay(base).runUsage, base.runUsage, '根用量只由真实事件推进，不被翻页重放改写');
});

test('停止收尾清空实时预览、运行中子代理与仲裁状态', () => {
  const state = produce(emptyState(), draft => {
    draft.thread.rows = [{ type: 'turn', reply: { live: true, preview: '残留预览' }, thinking: { live: true }, tools: [], runIds: [] }];
    draft.agents = { a: { status: 'running' }, b: { status: 'completed' } };
    draft.pendingArbitrations = { a: { id: 'pending' } };
  });
  const stopped = produce(state, draft => settleStoppedWork(draft, start));
  assert.equal(stopped.thread.rows[0].reply.live, false);
  assert.equal(stopped.thread.rows[0].reply.preview, '');
  assert.equal(stopped.agents.a.status, 'stopped');
  assert.equal(stopped.agents.b.status, 'completed');
  assert.deepEqual(stopped.pendingArbitrations, {});
});

test('运行中翻页回放没有历史 runId 时不借用根会话的当前 runId', () => {
  const root = produce(emptyState(), draft => {
    draft.running = true;
    draft.runId = 'current-run';
  });
  const replayed = produce(root, draft => appendHistoryPage(draft, [{
    type: 'assistant.delta', sequence: 9, timestamp: iso(9), payload: { text: '旧回复' }
  }]));
  const turn = replayed.thread.rows.find(row => row.type === 'turn');
  assert.ok(turn);
  assert.deepEqual(turn.runIds, []);
  assert.equal(replayed.runId, 'current-run');
});