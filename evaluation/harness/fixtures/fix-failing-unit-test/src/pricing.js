'use strict';

// 订单总价（分）：小计 = 单价 × 数量，再打会员折扣，最后加税并四舍五入到分。
const TAX_RATE = 0.08;

function orderTotalCents(items, memberDiscount) {
  let subtotal = 0;
  for (const item of items) {
    subtotal += item.priceCents;
  }
  const discounted = subtotal * (1 - (memberDiscount ?? 0));
  return Math.round(discounted * (1 + TAX_RATE));
}

module.exports = { orderTotalCents, TAX_RATE };
