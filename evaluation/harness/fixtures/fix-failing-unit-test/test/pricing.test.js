const test = require('node:test');
const assert = require('node:assert/strict');

const { orderTotalCents } = require('../src/pricing.js');

// 2 件 1000 分 = 2000；无折扣；税 8% → 2160
test('总价按数量累加', () => {
  assert.equal(orderTotalCents([{ priceCents: 1000, quantity: 2 }], 0), 2160);
});

// 1 件 999 分，会员 10% 折 → 899.1；税 8% → 971.028 → 971
test('会员折扣在税前生效', () => {
  assert.equal(orderTotalCents([{ priceCents: 999, quantity: 1 }], 0.1), 971);
});

test('空订单是 0 分，不产生税', () => {
  assert.equal(orderTotalCents([], 0.1), 0);
});
