const test = require('node:test');
const assert = require('node:assert/strict');

const ledger = require('../src/ledger.js');

const items = [{ name: '键盘', priceCents: 39900, quantity: 1 }];

test('合计 = 小计 - 折扣 + 税 + 运费', () => {
  // 小计 399.00；折扣 10% = 39.90；税 8% = 28.73；未满 500 元运费 12.00
  // 期望 399.00 - 39.90 + 28.73 + 12.00 = 399.83
  assert.equal(ledger.formatTotal(items, 0.1, 0.08, 50000), '¥399.83');
});

test('满额免运费时合计不含运费', () => {
  // 399.00 - 39.90 + 28.73 = 387.83
  assert.equal(ledger.formatTotal(items, 0.1, 0.08, 10000), '¥387.83');
});

test('折扣大于小计时合计可以为负，符号位置正确', () => {
  const small = [{ name: '折扣项', priceCents: 10000, quantity: 1 }];
  // 小计 100.00；折扣 150% = 150.00；税 8% = -4.00；未达免运费门槛 → 运费 12.00
  // 期望 100.00 - 150.00 - 4.00 + 12.00 = -42.00
  assert.equal(ledger.formatTotal(small, 1.5, 0.08, 50000), '-¥42.00');
});

test('其余格式函数行为保持不变', () => {
  assert.equal(ledger.formatMoney(39900), '¥399.00');
  assert.equal(ledger.formatMoney(-500), '-¥5.00');
  assert.equal(ledger.formatQuantity(1), '1 件');
  assert.equal(ledger.formatQuantity(3), '3 件');
  assert.equal(ledger.formatPercent(0.1234), '12.3%');
  assert.equal(ledger.formatOrderNo(42), 'NO.00000042');
  assert.equal(ledger.formatStatus('paid'), '已支付');
  assert.equal(ledger.formatCustomerName('  阿七  '), '阿七');
  assert.equal(ledger.formatCustomerName(''), '散客');
  assert.equal(ledger.formatEmpty(), '（无明细）');
  assert.equal(ledger.formatPhone('13812345678'), '138****5678');
  assert.equal(ledger.formatAddress({ city: '杭州', street: '文一西路' }), '杭州 文一西路');
});

test('整张账单仍然渲染得出来', () => {
  const bill = ledger.formatBill({
    id: 7,
    title: '测试账单',
    customer: '阿七',
    address: { city: '杭州', street: '文一西路' },
    items,
    discountRatio: 0.1,
    taxRatio: 0.08,
    freeAboveCents: 50000,
    status: 'paid',
    channel: 'web',
    payMethod: 'wallet'
  });
  assert.ok(bill.includes('合计 ¥399.83'), bill);
});
