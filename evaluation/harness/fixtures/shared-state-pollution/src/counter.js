'use strict';

// 模块级共享状态：一个测试改了它，下一个测试就受影响。
let count = 0;

function bump() {
  count += 1;
  return count;
}

function value() {
  return count;
}

function reset() {
  count = 0;
}

module.exports = { bump, value, reset };
