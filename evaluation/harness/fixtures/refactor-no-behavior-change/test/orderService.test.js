const test = require('node:test');
const assert = require('node:assert/strict');

const { priceFor } = require('../src/orderService.js');

test('重构后价格行为不变', () => {
  assert.equal(priceFor({ type: 'vip', base: 1000 }), 800);
  assert.equal(priceFor({ type: 'member', base: 1000 }), 900);
  assert.equal(priceFor({ type: 'employee', base: 1000 }), 500);
  assert.equal(priceFor({ type: 'guest', base: 1000 }), 1000, '未知类型按原价');
});

test('价格策略抽到了独立模块', () => {
  let strategies = null;
  try {
    strategies = require('../src/pricingStrategies.js');
  } catch {
    assert.fail('应把价格策略抽到 src/pricingStrategies.js');
  }
  assert.ok(strategies && typeof strategies.forType === 'function', '策略模块应暴露 forType');
  assert.equal(strategies.forType('vip')({ base: 1000 }), 800);
});

test('新增策略不需要改 orderService', () => {
  const service = require('../src/orderService.js');
  assert.equal(typeof service.registerStrategy, 'function', '应提供注册入口');
  service.registerStrategy('student', (order) => Math.round(order.base * 0.5));
  assert.equal(service.priceFor({ type: 'student', base: 1000 }), 500);
});

test('未知类型仍然退回原价，不会抛异常', () => {
  assert.equal(priceFor({ type: 'nope', base: 333 }), 333);
});
