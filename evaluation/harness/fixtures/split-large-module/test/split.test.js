const test = require('node:test');
const assert = require('node:assert/strict');

const store = require('../src/store.js');

test('拆分后 store.js 仍然能当门面用', () => {
  store.addUser({ id: 'u1', name: '阿七', active: true });
  store.addOrder({ id: 'o1', userId: 'u1', status: 'paid' });
  assert.equal(store.getUser('u1').name, '阿七');
  assert.equal(store.ordersOf('u1').length, 1);
  assert.equal(store.cancelOrder('o1'), true);
});

test('用户逻辑拆到了 src/users.js', () => {
  let users = null;
  try {
    users = require('../src/users.js');
  } catch {
    assert.fail('应把用户逻辑拆到 src/users.js');
  }
  assert.equal(typeof users.addUser, 'function');
  assert.equal(typeof users.deactivateUser, 'function');
});

test('订单逻辑拆到了 src/orders.js', () => {
  let orders = null;
  try {
    orders = require('../src/orders.js');
  } catch {
    assert.fail('应把订单逻辑拆到 src/orders.js');
  }
  assert.equal(typeof orders.addOrder, 'function');
  assert.equal(typeof orders.ordersOf, 'function');
});

test('两个模块各自独立可测', () => {
  const orders = require('../src/orders.js');
  orders.addOrder({ id: 'x1', userId: 'ghost', status: 'paid' });
  assert.equal(orders.getOrder('x1').status, 'paid');
  assert.equal(orders.cancelOrder('nope'), false, '不存在的订单取消返回 false');
});
