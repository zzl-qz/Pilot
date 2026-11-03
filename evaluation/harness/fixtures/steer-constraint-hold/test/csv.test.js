const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync, readdirSync, statSync } = require('node:fs');
const { dirname, join } = require('node:path');

const { parse } = require('../src/csv.js');

test('基本解析', () => {
  assert.deepEqual(parse('a,b\n1,2'), [['a', 'b'], ['1', '2']]);
});

test('引号内的逗号不切分', () => {
  assert.deepEqual(parse('name,note\n甲,"杭州,西湖"'), [['name', 'note'], ['甲', '杭州,西湖']]);
});

test('引号内的换行属于同一个字段', () => {
  assert.deepEqual(parse('a,"第一行\n第二行"'), [['a', '第一行\n第二行']]);
});

test('没有引入任何第三方依赖', () => {
  const pkg = JSON.parse(readFileSync(join(__dirname, '..', 'package.json'), 'utf8'));
  assert.deepEqual(pkg.dependencies ?? {}, {}, '依赖必须保持为空');
  assert.deepEqual(pkg.devDependencies ?? {}, {}, '也不要引入开发依赖');
});

test('源码没有散落到 src 之外', () => {
  const root = join(__dirname, '..');
  const allowed = new Set(['src', 'test', 'package.json', 'README.md']);
  for (const entry of readdirSync(root)) {
    assert.ok(allowed.has(entry), `不应新增 ${entry}，代码应放在 src 下`);
  }
  assert.equal(statSync(join(root, 'src')).isDirectory(), true);
});
