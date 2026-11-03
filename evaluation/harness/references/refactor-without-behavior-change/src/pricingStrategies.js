'use strict';
const strategies = new Map([
  ['vip', order => Math.round(order.base * 0.8)],
  ['member', order => Math.round(order.base * 0.9)],
  ['employee', order => Math.round(order.base * 0.5)]
]);
const fullPrice = order => Math.round(order.base);
function forType(type) { return strategies.get(type) ?? fullPrice; }
function registerStrategy(type, strategy) {
  if (typeof strategy !== 'function') throw new TypeError('策略必须是函数');
  strategies.set(type, strategy);
}
module.exports = { forType, registerStrategy };
