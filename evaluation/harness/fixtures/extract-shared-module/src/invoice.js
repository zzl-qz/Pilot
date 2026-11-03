'use strict';

function money(cents) {
  return `¥${(cents / 100).toFixed(2)}`;
}

function invoiceLines(order) {
  return order.items.map((item) => `${item.name} ${money(item.priceCents * item.quantity)}`);
}

function invoiceTotal(order) {
  const total = order.items.reduce((sum, item) => sum + item.priceCents * item.quantity, 0);
  return money(total);
}

module.exports = { invoiceLines, invoiceTotal };
