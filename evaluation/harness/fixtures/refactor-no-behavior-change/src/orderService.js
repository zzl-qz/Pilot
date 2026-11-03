'use strict';

// 价格计算直接写在下单服务里，每加一种会员就要改这个函数。
function priceFor(order) {
  if (order.type === 'vip') {
    return Math.round(order.base * 0.8);
  }
  if (order.type === 'member') {
    return Math.round(order.base * 0.9);
  }
  if (order.type === 'employee') {
    return Math.round(order.base * 0.5);
  }
  return Math.round(order.base);
}

module.exports = { priceFor };
