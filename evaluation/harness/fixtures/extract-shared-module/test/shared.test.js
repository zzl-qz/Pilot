const test = require('node:test');
const assert = require('node:assert/strict');

const { invoiceTotal } = require('../src/invoice.js');
const { receiptTotal } = require('../src/receipt.js');

const order = { items: [{ name: '键盘', priceCents: 39900, quantity: 1 }] };

test('发票与收据的金额格式必须一致', () => {
  assert.equal(invoiceTotal(order), receiptTotal(order));
});

test('金额为 0 时也按两位小数显示', () => {
  assert.equal(invoiceTotal({ items: [{ name: '赠品', priceCents: 0, quantity: 1 }] }), '¥0.00');
});

test('格式化逻辑抽到了共享模块', () => {
  let shared = null;
  try {
    shared = require('../src/money.js');
  } catch {
    assert.fail('应把金额格式化抽到 src/money.js');
  }
  assert.equal(typeof shared.formatCents, 'function', '共享模块应暴露 formatCents');
  assert.equal(shared.formatCents(39900), '¥399.00');
});

test('改共享模块一处，两处调用同时生效', () => {
  const shared = require('../src/money.js');
  assert.equal(shared.formatCents(123456), invoiceTotal({ items: [{ name: 'x', priceCents: 123456, quantity: 1 }] }));
  assert.equal(shared.formatCents(123456), receiptTotal({ items: [{ name: 'x', priceCents: 123456, quantity: 1 }] }));
});
