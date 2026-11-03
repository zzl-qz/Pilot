'use strict';

const { priceCents } = require('./pricing.js');

// 账单渲染依赖 pricing.js，但那个文件在一次清理中被误删了。
function renderBill(items) {
  return items.map((item) => `${item.name}: ${priceCents(item)} 分`).join('\n');
}

module.exports = { renderBill };
