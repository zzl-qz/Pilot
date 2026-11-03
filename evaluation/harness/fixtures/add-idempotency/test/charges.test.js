const test = require('node:test');
const assert = require('node:assert/strict');

const { charge } = require('../src/charges.js');

test('同一个 key 重复提交只扣一次', () => {
  const ledger = [];
  const first = charge(ledger, 'order-1', 500);
  const second = charge(ledger, 'order-1', 500);
  assert.equal(ledger.length, 1, '账本里只能有一条');
  assert.equal(second.charged, false, '第二次不是真的扣款');
  assert.deepEqual(second.entry, first.entry, '第二次返回第一次的结果');
});

test('不同 key 各扣一次', () => {
  const ledger = [];
  charge(ledger, 'order-1', 500);
  charge(ledger, 'order-2', 700);
  assert.equal(ledger.length, 2);
  assert.deepEqual(ledger.map((item) => item.amountCents), [500, 700]);
});

test('同一个 key 传不同金额也不会多扣', () => {
  const ledger = [];
  charge(ledger, 'order-1', 500);
  charge(ledger, 'order-1', 999);
  assert.equal(ledger.length, 1);
  assert.equal(ledger[0].amountCents, 500, '金额以第一次为准');
});

test('空账本首次调用正常扣款', () => {
  const ledger = [];
  const result = charge(ledger, 'order-9', 100);
  assert.equal(result.charged, true);
  assert.equal(ledger.length, 1);
});
