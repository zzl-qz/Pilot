const test = require('node:test');
const assert = require('node:assert/strict');

const { paginate } = require('../src/paginate.js');

const items = [1, 2, 3, 4, 5, 6, 7];

test('第一页取满 pageSize 条', () => {
  assert.deepEqual(paginate(items, 1, 3), [1, 2, 3]);
});

test('第二页从正确的下标开始，不丢第一条', () => {
  assert.deepEqual(paginate(items, 2, 3), [4, 5, 6]);
});

test('最后一页只返回剩余项', () => {
  assert.deepEqual(paginate(items, 3, 3), [7]);
});

test('越界页返回空数组而不是抛异常', () => {
  assert.deepEqual(paginate(items, 99, 3), []);
});
