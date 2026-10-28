import test from 'node:test';
import assert from 'node:assert/strict';
import {
  commandStatusLabel,
  isCommandActive,
  mergeCommandLog
} from '../../main/resources/static/lib/command-tasks.js';

const empty = () => ({ text: '', offset: 0, trimmed: 0, truncated: false, eof: false });

test('命令运行中和停止中保持活跃，终态不再可停止', () => {
  assert.equal(isCommandActive('RUNNING'), true);
  assert.equal(isCommandActive(' stopping '), true);
  for (const status of ['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'INTERRUPTED', 'OUTPUT_LIMIT', undefined]) {
    assert.equal(isCommandActive(status), false);
  }
  assert.equal(commandStatusLabel('STOPPING'), '停止中');
  assert.equal(commandStatusLabel('INTERRUPTED'), '已中断');
  assert.equal(commandStatusLabel('future-status'), '未知状态');
});

test('连续分片使用服务端码点游标，不用 UTF-16 字符串长度推算', () => {
  const first = mergeCommandLog(empty(), { offset: 0, nextOffset: 2, text: 'A😀', eof: false });
  assert.equal(first.offset, 2);
  const second = mergeCommandLog(first, { offset: 2, nextOffset: 3, text: 'B', eof: true });
  assert.equal(second.text, 'A😀B');
  assert.equal(second.offset, 3);
  assert.equal(second.eof, true);
  assert.equal(first.text, 'A😀', '合并不修改原状态');
});

test('重复和重叠响应按码点去重，包括 emoji', () => {
  const first = mergeCommandLog(empty(), { offset: 0, nextOffset: 3, text: 'A😀B' });
  assert.deepEqual(mergeCommandLog(first, { offset: 0, nextOffset: 3, text: 'A😀B' }), first);
  const overlap = mergeCommandLog(first, { offset: 1, nextOffset: 5, text: '😀BCD' });
  assert.equal(overlap.text, 'A😀BCD');
  assert.equal(overlap.offset, 5);
});

test('晚到的旧响应不回退游标、内容或 EOF，仍保留服务端截断标记', () => {
  const current = { ...empty(), text: 'abc', offset: 3, eof: true };
  const merged = mergeCommandLog(current, { offset: 0, nextOffset: 2, text: 'ab', eof: false, truncated: true });
  assert.equal(merged.text, 'abc');
  assert.equal(merged.offset, 3);
  assert.equal(merged.eof, true);
  assert.equal(merged.truncated, true);
});

test('空的最新响应可标记 EOF，重复响应不能清除 EOF，新数据可以清除', () => {
  const current = { ...empty(), text: 'ab', offset: 2 };
  const ended = mergeCommandLog(current, { offset: 2, nextOffset: 2, text: '', eof: true });
  assert.equal(ended.eof, true);
  assert.equal(mergeCommandLog(ended, { offset: 0, nextOffset: 2, text: 'ab', eof: false }).eof, true);
  const continued = mergeCommandLog(ended, { offset: 2, nextOffset: 3, text: 'c', eof: false });
  assert.equal(continued.text, 'abc');
  assert.equal(continued.eof, false);
});

test('游标出现缺口时不拼接不连续日志，并按码点记录被省略内容', () => {
  const current = { ...empty(), text: 'A😀', offset: 4, trimmed: 2 };
  const merged = mergeCommandLog(current, { offset: 7, nextOffset: 9, text: '后续', eof: false });
  assert.equal(merged.text, '后续');
  assert.equal(merged.offset, 9);
  assert.equal(merged.trimmed, 7);
});

test('UI 长度限制保留末尾且不会留下孤立的低代理项', () => {
  const merged = mergeCommandLog(empty(), { offset: 0, nextOffset: 3, text: 'A😀B', truncated: true }, 2);
  assert.equal(merged.text, 'B');
  assert.equal(merged.trimmed, 2);
  assert.equal(merged.offset, 3);
  assert.equal(merged.truncated, true);
  const fullPair = mergeCommandLog(empty(), { offset: 0, nextOffset: 2, text: 'A😀' }, 2);
  assert.equal(fullPair.text, '😀');
  assert.equal(fullPair.trimmed, 1);
});

test('截断后继续追加仍按服务端游标去重并累加省略数量', () => {
  const first = mergeCommandLog(empty(), { offset: 0, nextOffset: 6, text: 'abcdef' }, 4);
  const second = mergeCommandLog(first, { offset: 4, nextOffset: 8, text: 'efgh' }, 4);
  assert.equal(second.text, 'efgh');
  assert.equal(second.trimmed, 4);
  assert.equal(second.offset, 8);
  assert.equal(mergeCommandLog(second, { offset: 8, nextOffset: 8, text: '', eof: true }, 0).text, '');
});

test('缺失或无效游标不破坏已显示内容', () => {
  const current = { ...empty(), text: 'abc', offset: 3 };
  assert.deepEqual(mergeCommandLog(current, null), current);
  assert.deepEqual(mergeCommandLog(current, { offset: 4, nextOffset: 2, text: 'x' }), current);
});
