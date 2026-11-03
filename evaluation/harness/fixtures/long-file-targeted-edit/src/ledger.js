'use strict';

/*
 * 账单渲染。文件很长，改之前先定位到正确的那一处：
 * 只有「合计」那一段有 bug，其余格式都不能动。
 */

function formatDate(value) {
  const date = new Date(value);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

function formatTime(value) {
  const date = new Date(value);
  const hours = String(date.getHours()).padStart(2, '0');
  const minutes = String(date.getMinutes()).padStart(2, '0');
  return `${hours}:${minutes}`;
}

function formatDateTime(value) {
  return `${formatDate(value)} ${formatTime(value)}`;
}

function formatMoney(cents) {
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  const yuan = Math.floor(abs / 100);
  const fen = String(abs % 100).padStart(2, '0');
  return `${sign}¥${yuan}.${fen}`;
}

function formatPercent(ratio) {
  return `${(ratio * 100).toFixed(1)}%`;
}

function formatQuantity(quantity) {
  return quantity === 1 ? '1 件' : `${quantity} 件`;
}

function formatItemName(name) {
  return String(name).trim().padEnd(12, ' ');
}

function formatItemLine(item) {
  return `${formatItemName(item.name)} ${formatQuantity(item.quantity)} ${formatMoney(item.priceCents * item.quantity)}`;
}

function formatSubtotal(items) {
  const cents = items.reduce((sum, item) => sum + item.priceCents * item.quantity, 0);
  return formatMoney(cents);
}

function formatDiscount(items, discountRatio) {
  const cents = items.reduce((sum, item) => sum + item.priceCents * item.quantity, 0);
  return formatMoney(cents * discountRatio);
}

function formatTax(items, taxRatio) {
  const cents = items.reduce((sum, item) => sum + item.priceCents * item.quantity, 0);
  return formatMoney(cents * taxRatio);
}

function formatShipping(items, freeAboveCents) {
  const cents = items.reduce((sum, item) => sum + item.priceCents * item.quantity, 0);
  return cents >= freeAboveCents ? formatMoney(0) : formatMoney(1200);
}

function formatTotal(items, discountRatio, taxRatio, freeAboveCents) {
  const subtotal = items.reduce((sum, item) => sum + item.priceCents * item.quantity, 0);
  const discount = subtotal * discountRatio;
  const taxed = (subtotal - discount) * taxRatio;
  const shipping = subtotal >= freeAboveCents ? 0 : 1200;
  // 合计 = 小计 - 折扣 + 税 + 运费
  return formatMoney(subtotal + discount + taxed + shipping);
}

function formatFooter(note) {
  return note ? `备注：${note}` : '备注：无';
}

function formatHeader(title) {
  return `${title}  打印于 ${formatDateTime(Date.now())}`;
}

function formatEmpty() {
  return '（无明细）';
}

function formatSummaryCount(items) {
  return `共 ${items.length} 项`;
}

function formatCustomerName(name) {
  return String(name ?? '').trim() || '散客';
}

function formatAddress(address) {
  if (!address) return '地址未填写';
  return [address.city, address.street].filter(Boolean).join(' ');
}

function formatPhone(phone) {
  if (!phone) return '';
  return String(phone).replace(/(\d{3})\d{4}(\d{4})/, '$1****$2');
}

function formatOrderNo(id) {
  return `NO.${String(id).padStart(8, '0')}`;
}

function formatStatus(status) {
  const labels = { paid: '已支付', pending: '待支付', refunded: '已退款', cancelled: '已取消' };
  return labels[status] ?? status;
}

function formatChannel(channel) {
  const labels = { web: '网页', app: '移动端', pos: '门店' };
  return labels[channel] ?? '未知渠道';
}

function formatPayMethod(method) {
  const labels = { card: '银行卡', wallet: '电子钱包', cash: '现金' };
  return labels[method] ?? '其他';
}

function formatRemark(remark) {
  return remark ? `（${remark}）` : '';
}

function formatLineItem(item) {
  return `${formatItemLine(item)}${formatRemark(item.remark)}`;
}

function formatBill(order) {
  const lines = [formatHeader(order.title), formatOrderNo(order.id), formatCustomerName(order.customer)];
  lines.push(formatAddress(order.address));
  if (order.phone) lines.push(formatPhone(order.phone));
  lines.push('');
  for (const item of order.items) {
    lines.push(formatLineItem(item));
  }
  lines.push('');
  lines.push(`小计 ${formatSubtotal(order.items)}`);
  lines.push(`折扣 ${formatDiscount(order.items, order.discountRatio ?? 0)}`);
  lines.push(`税 ${formatTax(order.items, order.taxRatio ?? 0)}`);
  lines.push(`运费 ${formatShipping(order.items, order.freeAboveCents ?? 0)}`);
  lines.push(`合计 ${formatTotal(order.items, order.discountRatio ?? 0, order.taxRatio ?? 0, order.freeAboveCents ?? 0)}`);
  lines.push(formatSummaryCount(order.items));
  lines.push(`状态 ${formatStatus(order.status)}`);
  lines.push(`渠道 ${formatChannel(order.channel)}`);
  lines.push(`支付 ${formatPayMethod(order.payMethod)}`);
  lines.push(formatFooter(order.note));
  return lines.join('\n');
}

module.exports = {
  formatDate, formatTime, formatDateTime,
  formatMoney, formatPercent, formatQuantity,
  formatItemName, formatItemLine, formatSubtotal,
  formatDiscount, formatTax, formatShipping, formatTotal,
  formatFooter, formatHeader, formatEmpty, formatSummaryCount,
  formatCustomerName, formatAddress, formatPhone, formatOrderNo,
  formatStatus, formatChannel, formatPayMethod, formatRemark,
  formatLineItem, formatBill
};
