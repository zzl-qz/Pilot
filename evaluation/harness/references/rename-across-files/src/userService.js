'use strict';

// 取名函数被三个模块引用，现在要统一改名。
function displayNameOf(user) {
  if (!user) return '未知用户';
  return user.nickname ? `${user.nickname}（${user.name}）` : user.name;
}

function isActive(user) {
  return Boolean(user && user.active);
}

module.exports = { displayNameOf, isActive };
