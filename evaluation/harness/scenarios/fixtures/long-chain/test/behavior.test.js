const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { ids, keys } = require('../src/index.js');

const CASES = [
  ['  HeLLo  World ', 'hello world'],
  ['A\t B', 'a b'],
  ['  MiXed\tCASE  Input ', 'mixed case input']
];

for (const id of ids) {
  test(id + ' 的 key 达成统一规范', () => {
    const mod = require('../src/' + id + '.js');
    for (const [input, expected] of CASES) {
      assert.equal(mod.key(input), expected, id + ' 对 ' + JSON.stringify(input) + ' 的结果不符合规范');
      assert.equal(keys[id](input), expected, '聚合入口 index.js 的 ' + id + ' 不符合规范');
    }
    assert.equal(typeof mod.legacyFormatter, 'function', id + ' 不得删除 legacyFormatter');
    assert.equal(mod.legacyFormatter('x'), '[x]', id + ' 的 legacyFormatter 行为必须保持');
  });
}
