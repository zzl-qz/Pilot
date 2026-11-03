const test = require('node:test');
const assert = require('node:assert/strict');

const { page } = require('../src/cursor.js');

function ids(items) {
  return items.map((item) => item.id);
}

test('第一页返回前 limit 条并给出下一页游标', () => {
  const items = [{ id: 'a' }, { id: 'b' }, { id: 'c' }, { id: 'd' }];
  const first = page(items, null, 2);
  assert.deepEqual(ids(first.items), ['a', 'b']);
  assert.ok(first.nextCursor, '还有下一页时必须给出游标');
});

test('用游标续接，不重复也不遗漏', () => {
  const items = [{ id: 'a' }, { id: 'b' }, { id: 'c' }, { id: 'd' }];
  const first = page(items, null, 2);
  const second = page(items, first.nextCursor, 2);
  assert.deepEqual(ids(second.items), ['c', 'd']);
});

test('翻页期间头部插入新项，下一页仍然不重复不遗漏', () => {
  const items = [{ id: 'a' }, { id: 'b' }, { id: 'c' }, { id: 'd' }];
  const first = page(items, null, 2);
  items.unshift({ id: 'new' });
  const second = page(items, first.nextCursor, 2);
  assert.deepEqual(ids(second.items), ['c', 'd']);
});

test('最后一页的 nextCursor 是 null', () => {
  const items = [{ id: 'a' }, { id: 'b' }];
  const result = page(items, null, 2);
  assert.equal(result.nextCursor, null);
});
