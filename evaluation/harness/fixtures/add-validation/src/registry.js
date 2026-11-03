'use strict';

// 注册用户。当前实现直接写入，没有做任何校验。
function register(users, payload) {
  const user = { id: users.length + 1, email: payload.email, name: payload.name };
  users.push(user);
  return { ok: true, user };
}

module.exports = { register };
