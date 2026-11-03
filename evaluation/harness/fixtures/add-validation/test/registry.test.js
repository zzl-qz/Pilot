const test = require('node:test');
const assert = require('node:assert/strict');

const { register } = require('../src/registry.js');

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

test('邮箱格式非法时返回统一错误结构', () => {
  const result = register([], { email: 'not-an-email', name: '阿七' });
  assert.equal(result.ok, false);
  assert.deepEqual(result.errors, [{ field: 'email', code: 'INVALID_EMAIL' }]);
});

test('邮箱重复时返回 DUPLICATE_EMAIL', () => {
  const users = [{ id: 1, email: 'a@b.com', name: '甲' }];
  const result = register(users, { email: 'a@b.com', name: '乙' });
  assert.equal(result.ok, false);
  assert.deepEqual(result.errors, [{ field: 'email', code: 'DUPLICATE_EMAIL' }]);
});

test('格式和重复同时命中时两条错误都返回', () => {
  const users = [{ id: 1, email: 'a@b.com', name: '甲' }];
  const result = register(users, { email: 'a@b.com', name: '' });
  assert.equal(result.ok, false);
  assert.ok(result.errors.some((item) => item.code === 'DUPLICATE_EMAIL'));
  assert.ok(result.errors.some((item) => item.code === 'INVALID_NAME'), '缺少名字也要报错');
});

test('合法输入写入成功并返回用户', () => {
  const users = [];
  const result = register(users, { email: 'a@b.com', name: '阿七' });
  assert.equal(result.ok, true);
  assert.equal(users.length, 1);
  assert.equal(result.user.email, 'a@b.com');
});

test('失败时不写入任何用户', () => {
  const users = [];
  register(users, { email: 'bad', name: '阿七' });
  assert.equal(users.length, 0);
  assert.ok(EMAIL.test('a@b.com'));
});
