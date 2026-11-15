import assert from 'node:assert/strict';
import test from 'node:test';
import { locateQueueTarget, queueEntryKey } from '../src/lib/queue.js';

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