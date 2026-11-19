import test from 'node:test';
import assert from 'node:assert/strict';
const { parseAsk, questionChoice } = await import('../src/lib/tool-meta.ts');

test('点选和自行填写都会变成交给当前任务的答案', () => {
  const ask = parseAsk('ASK\n用哪种方案\n保留\u001f删除');
  assert.equal(ask.question, '用哪种方案');
  assert.deepEqual(ask.options, ['保留', '删除']);
  assert.equal(questionChoice('保留', ''), '保留');
  assert.equal(questionChoice('保留', '我自己写的方案'), '我自己写的方案');
  assert.equal(parseAsk('普通参数'), null);
});
