'use strict';
function buildProfile(user = {}) {
  return {
    name: user?.name,
    city: user?.address?.city ?? '未填写',
    recentOrders: Array.isArray(user?.orders) ? user.orders.slice(0, 3) : [],
    loyaltyPoints: user?.loyalty?.points ?? 0
  };
}
module.exports = { buildProfile };
