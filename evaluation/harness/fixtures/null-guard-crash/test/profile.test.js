const test = require('node:test');
const assert = require('node:assert/strict');

const { buildProfile } = require('../src/profile.js');

test('缺少地址时不崩溃，城市记未填写', () => {
  const profile = buildProfile({ name: '阿七', orders: [] });
  assert.equal(profile.city, '未填写');
});

test('缺少订单时返回空数组而不是抛异常', () => {
  assert.deepEqual(buildProfile({ name: '阿七' }).recentOrders, []);
});

test('缺少 loyalty 时积分为 0', () => {
  assert.equal(buildProfile({ name: '阿七' }).loyaltyPoints, 0);
});

test('字段齐全时照常取值', () => {
  const profile = buildProfile({
    name: '阿七',
    address: { city: '杭州' },
    orders: [{ id: 1 }, { id: 2 }, { id: 3 }, { id: 4 }],
    loyalty: { points: 12 }
  });
  assert.equal(profile.city, '杭州');
  assert.equal(profile.recentOrders.length, 3, '最近购买最多 3 条');
  assert.equal(profile.loyaltyPoints, 12);
});
