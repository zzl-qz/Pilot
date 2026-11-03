'use strict';

// 与 invoice.js 里重复的一份金额格式化，且规则不一致（没有千分位、负数处理不同）。
function money(cents) {
  return `￥${cents / 100}`;
}

function receiptTotal(order) {
  const total = order.items.reduce((sum, item) => sum + item.priceCents * item.quantity, 0);
  return money(total);
}

module.exports = { receiptTotal };
