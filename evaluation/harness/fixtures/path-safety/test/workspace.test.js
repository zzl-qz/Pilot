const test = require('node:test');
const assert = require('node:assert/strict');
const { resolve, sep } = require('node:path');

const { resolveInside } = require('../src/workspace.js');

const root = resolve(__dirname, '..', 'sandbox');

test('正常子路径放行', () => {
  const target = resolveInside(root, 'src/main.js');
  assert.equal(target, resolve(root, 'src', 'main.js'));
});

test('.. 逃出工作区时返回 null', () => {
  assert.equal(resolveInside(root, '../secret.txt'), null);
  assert.equal(resolveInside(root, 'a/../../secret.txt'), null);
});

test('深层 .. 组合也要挡住', () => {
  assert.equal(resolveInside(root, 'src/./../../outside'), null);
});

test('绝对路径逃出工作区时返回 null', () => {
  const outside = sep === '\\' ? 'C:\\Windows\\System32\\config' : '/etc/passwd';
  assert.equal(resolveInside(root, outside), null);
});

test('恰好等于工作区根目录是允许的', () => {
  assert.equal(resolveInside(root, '.'), root);
});

test('返回 null 而不是抛异常', () => {
  assert.doesNotThrow(() => resolveInside(root, '../..'));
});
