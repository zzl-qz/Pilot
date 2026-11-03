const test = require('node:test');
const assert = require('node:assert/strict');

const { outline } = require('../src/outline.js');

test('返回对象数组：带层级与行号', () => {
  const result = outline('# 标题\n正文\n## 小节');
  assert.deepEqual(result, [
    { level: 1, text: '标题', line: 1 },
    { level: 2, text: '小节', line: 3 }
  ]);
});

test('行号从 1 开始，按原文计数', () => {
  const result = outline('前言\n\n### 第三节\n\n# 第一节');
  assert.deepEqual(result, [
    { level: 3, text: '第三节', line: 3 },
    { level: 1, text: '第一节', line: 5 }
  ]);
});

test('代码块里的 # 不算标题', () => {
  const result = outline('# 真标题\n```\n# 注释\n```\n## 真小节');
  assert.deepEqual(result.map((item) => item.text), ['真标题', '真小节']);
});

test('没有标题时返回空数组', () => {
  assert.deepEqual(outline('只有正文'), []);
});

test('导出名仍然是 outline', () => {
  assert.equal(typeof outline, 'function');
});
