import assert from 'node:assert/strict';
import { buildThreadDisplay } from '../src/lib/thread-display.ts';

const emptyReply = () => ({ text: '', preview: '', live: false, needsBreak: false, breakReason: '', committed: '' });

const raw = [
  { type: 'user', key: 'u1', sequence: 1, text: '执行长任务', attachments: [], imagePositions: [], start: 1, end: 1 },
  { type: 'plan', key: 'p1', start: 2, end: 2, items: [{ step: '步骤1', status: 'in_progress' }] },
  { type: 'turn', key: 't1', runIds: ['r1'], start: 3, end: 4, tools: [{ callId: 'c1', name: 'read_file', args: 'a.ts', status: 'ok' }], toolIndex: {}, thinking: null, reply: emptyReply(), stats: { modelRequests: 2 }, agents: {} },
  { type: 'compaction', key: 'cmp1', start: 5, end: 5, coveredMessages: 10, charactersBefore: 50000, charactersAfter: 12000, summary: '摘要1' },
  { type: 'compaction', key: 'cmp2', start: 6, end: 6, coveredMessages: 8, charactersBefore: 48000, charactersAfter: 11000, summary: '摘要2' },
  { type: 'plan', key: 'p2', start: 7, end: 7, items: [{ step: '步骤1', status: 'completed' }, { step: '步骤2', status: 'in_progress' }] },
  { type: 'turn', key: 't2', runIds: ['r2'], start: 8, end: 9, tools: [{ callId: 'c2', name: 'run_command', args: 'npm test', status: 'ok' }], toolIndex: {}, thinking: null, reply: { ...emptyReply(), text: '全部完成' }, stats: { modelRequests: 5 }, agents: {} }
];

const display = buildThreadDisplay(raw);
assert.equal(display.pinnedPlan?.key, 'p2', '只固定展示最新一条执行计划');
assert.equal(display.rows.some(r => r.type === 'plan'), false, '消息流中不再重复渲染计划卡');

const afterNewQuestion = buildThreadDisplay([
  ...raw,
  { type: 'user', key: 'u2', sequence: 10, text: '查看缓存命中率', attachments: [], imagePositions: [], start: 10, end: 10 }
]);
assert.equal(afterNewQuestion.pinnedPlan, null, '新问题之后没有计划更新时，不显示上一轮计划');

const compactions = display.rows.filter(r => r.type === 'compaction-group');
assert.equal(compactions.length, 1, '连续压缩记录合并为一条');
assert.equal(compactions[0].items.length, 2, '合并组内保留全部 2 条压缩摘要');

const turns = display.rows.filter(r => r.type === 'turn');
assert.equal(turns.length, 1, '同一任务下无回复穿插的连续过程回合合并为一个回合');
assert.equal(turns[0].tools.length, 2, '合并后保留全部工具调用');
assert.equal(turns[0].reply.text, '全部完成', '保留最终回复正文');
assert.deepEqual(turns[0].runIds, ['r1', 'r2'], '保留合并后的 runIds');

// 回归验证：turn -> cmp -> turn -> cmp -> turn -> cmp -> turn 交替出现时，所有压缩记录必须收成 1 条，绝不堆叠多条分隔线
const interleaved = [
  { type: 'user', key: 'u1', sequence: 1, text: '长任务', attachments: [], imagePositions: [], start: 1, end: 1 },
  { type: 'turn', key: 't1', runIds: ['r1'], start: 2, end: 3, tools: [{ callId: 'a1', name: 'read_file', args: 'a', status: 'ok' }], toolIndex: {}, thinking: null, reply: emptyReply(), stats: null, agents: {} },
  { type: 'compaction', key: 'c1', start: 4, end: 4, coveredMessages: 5, charactersBefore: 1000, charactersAfter: 200, summary: 's1' },
  { type: 'turn', key: 't2', runIds: ['r1'], start: 5, end: 6, tools: [{ callId: 'a2', name: 'read_file', args: 'b', status: 'ok' }], toolIndex: {}, thinking: null, reply: emptyReply(), stats: null, agents: {} },
  { type: 'compaction', key: 'c2', start: 7, end: 7, coveredMessages: 5, charactersBefore: 1000, charactersAfter: 200, summary: 's2' },
  { type: 'turn', key: 't3', runIds: ['r1'], start: 8, end: 9, tools: [{ callId: 'a3', name: 'read_file', args: 'c', status: 'ok' }], toolIndex: {}, thinking: null, reply: emptyReply(), stats: null, agents: {} },
  { type: 'compaction', key: 'c3', start: 10, end: 10, coveredMessages: 5, charactersBefore: 1000, charactersAfter: 200, summary: 's3' },
  { type: 'turn', key: 't4', runIds: ['r1'], start: 11, end: 12, tools: [{ callId: 'a4', name: 'run_command', args: 'test', status: 'ok' }], toolIndex: {}, thinking: null, reply: emptyReply(), stats: null, agents: {} }
];
const interleavedDisplay = buildThreadDisplay(interleaved);
const interleavedCmps = interleavedDisplay.rows.filter(r => r.type === 'compaction-group');
assert.equal(interleavedCmps.length, 1, '交替压缩与过程回合后，相邻压缩必须再次合并为 1 条分隔线');
assert.equal(interleavedCmps[0].items.length, 3, '保留全部 3 次压缩计数');
assert.equal(interleavedDisplay.rows.filter(r => r.type === 'turn').length, 1, '4 段过程回合合并为 1 个操作组');
assert.equal(interleavedDisplay.rows.find(r => r.type === 'turn').tools.length, 4, '合并保留全部 4 项工具');

// 回归验证：过程回合上下都被压缩夹击且后面只有 result 时，尾部压缩并入前置压缩，不再上下双线夹击
const sandwiched = [
  { type: 'user', key: 'u1', sequence: 1, text: '长任务', attachments: [], imagePositions: [], start: 1, end: 1 },
  { type: 'compaction', key: 'c1', start: 2, end: 2, coveredMessages: 16, charactersBefore: 50000, charactersAfter: 10000, summary: 's1' },
  { type: 'turn', key: 't1', runIds: ['r1'], start: 3, end: 4, tools: [{ callId: 'a1', name: 'run_command', args: 'test', status: 'ok' }], toolIndex: {}, thinking: null, reply: emptyReply(), stats: null, agents: {} },
  { type: 'compaction', key: 'c2', start: 5, end: 5, coveredMessages: 2, charactersBefore: 20000, charactersAfter: 8000, summary: 's2' },
  { type: 'result', key: 'res1', start: 6, end: 6, tone: 'warn', title: '运行已取消', detail: null, changedFiles: [], runIds: ['r1'] }
];
const sandwichedDisplay = buildThreadDisplay(sandwiched);
const sandCmps = sandwichedDisplay.rows.filter(r => r.type === 'compaction-group');
assert.equal(sandCmps.length, 1, '尾部压缩并入前置压缩，不上下夹击操作组');
assert.equal(sandCmps[0].items.length, 2, '保留合并后的压缩总数');

console.log('PASS thread-display.test.mjs');
