'use strict';

const { displayNameOf, isActive } = require('./userService.js');

function greet(user) {
  if (!isActive(user)) return `你好，${displayNameOf(user)}（未激活）`;
  return `欢迎回来，${displayNameOf(user)}`;
}

module.exports = { greet };
