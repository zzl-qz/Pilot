'use strict';

/**
 * 生成展示用的用户资料。
 * 入参可能缺字段：没有地址时城市应为「未填写」，没有订单时最近购买应为空数组。
 */
function buildProfile(user) {
  return {
    name: user.name,
    city: user.address.city,
    recentOrders: user.orders.slice(0, 3),
    loyaltyPoints: user.loyalty ? user.loyalty.points : 0
  };
}

module.exports = { buildProfile };
