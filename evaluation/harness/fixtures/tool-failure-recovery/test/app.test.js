const test = require('node:test');
const assert = require('node:assert/strict');

let app = null;
let requireError = null;
try {
  app = require('../src/app.js');
} catch (error) {
  requireError = error;
}

test('app.js 能被正常加载（缺失的模块已补回）', () => {
  assert.equal(requireError, null, `加载失败：${requireError && requireError.message}`);
});

test('pricing.js 导出 priceCents', () => {
  const pricing = require('../src/pricing.js');
  assert.equal(typeof pricing.priceCents, 'function');
  assert.equal(pricing.priceCents({ priceCents: 1200, quantity: 2 }), 2400);
});

test('账单渲染结果正确', () => {
  if (!app) return;
  assert.equal(app.renderBill([{ name: '键盘', priceCents: 39900, quantity: 1 }]), '键盘: 39900 分');
  assert.equal(app.renderBill([{ name: '鼠标', priceCents: 5000, quantity: 3 }]), '鼠标: 15000 分');
});

test('数量为 0 时算 0 分', () => {
  const pricing = require('../src/pricing.js');
  assert.equal(pricing.priceCents({ priceCents: 5000, quantity: 0 }), 0);
});
