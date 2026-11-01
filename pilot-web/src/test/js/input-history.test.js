import test from 'node:test';
import assert from 'node:assert/strict';

import { historyKey, loadInputHistory, rememberInput, stepHistory } from '../../../frontend/src/lib/input-history.js';

function storage() {
  const map = new Map();
  return {
    map,
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => map.set(key, String(value)),
    removeItem: (key) => map.delete(key)
  };
}

test('按会话分别存，空会话 ID 不写', () => {
  const s = storage();
  assert.equal(historyKey('ses_1'), 'pilot.inputHistory.ses_1');
  assert.equal(historyKey(null), null);
  assert.equal(rememberInput(s, null, '不会存'), null);
  assert.equal(s.map.size, 0);
});

test('相同内容移到末尾，不重复堆积', () => {
  const s = storage();
  rememberInput(s, 'ses_1', '第一句');
  rememberInput(s, 'ses_1', '第二句');
  rememberInput(s, 'ses_1', '第一句');
  assert.deepEqual(loadInputHistory(s, 'ses_1'), ['第二句', '第一句']);
});

test('超过上限只留最近 100 条', () => {
  const s = storage();
  for (let index = 0; index < 105; index += 1) {
    rememberInput(s, 'ses_1', `第 ${index} 条`);
  }
  const history = loadInputHistory(s, 'ses_1');
  assert.equal(history.length, 100);
  assert.equal(history[history.length - 1], '第 104 条');
  assert.equal(history[0], '第 5 条');
});

test('坏数据不抛异常，当成空历史', () => {
  const s = storage();
  s.setItem('pilot.inputHistory.ses_1', '{不是 JSON');
  assert.deepEqual(loadInputHistory(s, 'ses_1'), []);
});

test('↑ 从草稿进历史，翻到底再 ↓ 把草稿还回来', () => {
  const history = ['A', 'B'];
  const first = stepHistory(history, -1, 'up', '正在写的草稿');
  assert.deepEqual(first, { index: 1, text: 'B' });
  const second = stepHistory(history, first.index, 'up', '正在写的草稿');
  assert.deepEqual(second, { index: 0, text: 'A' });
  // 已经是最早一条，继续往上不动
  assert.deepEqual(stepHistory(history, 0, 'up', '草稿'), { index: 0, text: 'A' });
  const back = stepHistory(history, 0, 'down', '正在写的草稿');
  assert.deepEqual(back, { index: 1, text: 'B' });
  assert.deepEqual(stepHistory(history, 1, 'down', '正在写的草稿'), { index: -1, text: '正在写的草稿' });
});

test('没有历史时不动草稿', () => {
  assert.deepEqual(stepHistory([], -1, 'up', '草稿'), { index: -1, text: null });
});
