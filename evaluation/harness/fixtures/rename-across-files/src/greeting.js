'use strict';

const { getUserName, isActive } = require('./userService.js');

function greet(user) {
  if (!isActive(user)) return `你好，${getUserName(user)}（未激活）`;
  return `欢迎回来，${getUserName(user)}`;
}

module.exports = { greet };
