const test = require('node:test');
const assert = require('node:assert/strict');

const counter = require('../src/counter.js');

test('可以创建互不影响的独立计数器', () => {
  assert.equal(typeof counter.createCounter, 'function', '应提供 createCounter 工厂，而不是全局单例');
  const left = counter.createCounter();
  const right = counter.createCounter();
  left.bump();
  left.bump();
  assert.equal(left.value(), 2);
  assert.equal(right.value(), 0, '另一个实例不受影响');
});

test('两个实例交替自增互不串味', () => {
  const left = counter.createCounter();
  const right = counter.createCounter();
  left.bump();
  right.bump();
  left.bump();
  assert.equal(left.value(), 2);
  assert.equal(right.value(), 1);
});

test('实例可以带初始值', () => {
  assert.equal(counter.createCounter(10).value(), 10);
});
