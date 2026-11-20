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

const compactions = display.rows.filter(r => r.type === 'compaction-group');
assert.equal(compactions.length, 1, '连续压缩记录合并为一条');
assert.equal(compactions[0].items.length, 2, '合并组内保留全部 2 条压缩摘要');

const turns = display.rows.filter(r => r.type === 'turn');
assert.equal(turns.length, 1, '同一任务下无回复穿插的连续过程回合合并为一个回合');
assert.equal(turns[0].tools.length, 2, '合并后保留全部工具调用');
assert.equal(turns[0].reply.text, '全部完成', '保留最终回复正文');
assert.deepEqual(turns[0].runIds, ['r1', 'r2'], '保留合并后的 runIds');

console.log('PASS thread-display.test.mjs');
