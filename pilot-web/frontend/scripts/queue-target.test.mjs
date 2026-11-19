import assert from 'node:assert/strict';
import test from 'node:test';
import { locateQueueTarget, queueBlockReason, queueEntryKey, queueHeader, queueLane, textWithoutParkedMark } from '../src/lib/queue.js';

const image = (name) => ({ name, url: `/api/sessions/s/attachments/${name}` });
const entry = (text, attachments = [], imagePositions = []) => ({ content: text, text, attachments, imagePositions });

test('正文相同但附件不同的两条消息有不同指纹', () => {
  const first = entry('按图检查', [image('one.png')], [0]);
  const second = entry('按图检查', [image('two.png')], [0]);
  assert.notEqual(queueEntryKey(first), queueEntryKey(second));
});

test('队首被自动取走后，编辑目标跟着原消息下标前移，而不是继续指向 0', () => {
  const edited = entry('按图检查', [image('second.png')], [0]);
  const target = { index: 0, key: queueEntryKey(edited) };
  // 刷新后原第一条已发送，第二条挤到下标 0
  const queue = [edited];
  assert.equal(locateQueueTarget(queue, target), 0);
});

test('目标被删除时返回 -1，不能退化成按下标改到别的消息', () => {
  const edited = entry('按图检查', [image('one.png')]);
  const other = entry('按图检查', [image('two.png')]);
  const target = { index: 0, key: queueEntryKey(edited) };
  assert.equal(locateQueueTarget([other], target), -1);
  assert.equal(locateQueueTarget([], target), -1);
});

test('目标被移动时按唯一指纹找到新位置', () => {
  const a = entry('A');
  const b = entry('B');
  const target = { index: 0, key: queueEntryKey(a) };
  assert.equal(locateQueueTarget([b, a], target), 1);
});

test('两条完全相同的消息移动后不猜目标，保持下标或明确失败', () => {
  const a = entry('同文');
  const b = entry('同文');
  const target = { index: 0, key: queueEntryKey(a) };
  assert.equal(queueEntryKey(a), queueEntryKey(b));
  // 下标 0 仍是同内容，继续改它（内容等价）
  assert.equal(locateQueueTarget([b, a], target), 0);
  // 下标 0 换成别的内容且指纹不唯一时不做猜测
  assert.equal(locateQueueTarget([entry('别的')], target), -1);
});

test('附件顺序和图片位置参与指纹，正文变化后原目标不再匹配', () => {
  const withPositions = entry('图', [image('a.png')], [0, 2]);
  const noPositions = entry('图', [image('a.png')], [0]);
  assert.notEqual(queueEntryKey(withPositions), queueEntryKey(noPositions));
  const target = { index: 0, key: queueEntryKey(withPositions) };
  assert.equal(locateQueueTarget([{ ...withPositions, content: '改过', text: '改过' }], target), -1);
});
test('队列标题展示服务端等待原因与暂停文案', () => {
  assert.deepEqual(queueHeader({ count: 2, held: false }), { title: '2 条排队', meta: '这轮结束后按顺序发送' });
  assert.match(queueHeader({ count: 1, held: true }).meta, /已暂停/);
  assert.equal(queueHeader({ count: 1, held: true, waitingReason: '等待本轮结束' }).meta, '等待本轮结束');
  assert.equal(queueHeader({ count: 1, held: false, waitingReason: '等待验收' }).meta, '等待验收');
  assert.equal(queueHeader({ count: 0, held: true }), null);
});

test('范围外、没写对象和收尾提交不会自动发送', () => {
  assert.equal(queueLane('遗留（不在本次范围） | 能力页仍失败'), 'parked');
  assert.equal(queueLane('这里点击之后为啥是这样，不太行吧，修复一下'), 'blocked');
  assert.equal(queueLane('命名有点奇怪'), 'blocked');
  assert.equal(queueLane('做完记得按照功能拆分多个commit进行提交并推送'), 'wrapup');
  assert.equal(queueLane('点「关闭预览」之后外层也关了，希望只关大图'), 'send');
  assert.equal(queueLane('把删除改成一个 x'), 'send');
  assert.equal(queueLane(''), 'send');
  assert.match(queueBlockReason('这里点击之后为啥是这样，不太行吧，修复一下'), /哪个按钮/);
  assert.match(queueBlockReason('命名有点奇怪'), /哪一个名字/);
  assert.equal(textWithoutParkedMark('遗留（不在本次范围） | 能力页仍失败'), '能力页仍失败');
});
