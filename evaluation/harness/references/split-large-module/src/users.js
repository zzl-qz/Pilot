'use strict';
const users = new Map();

function addUser(user) {
  users.set(user.id, user);
  return user;
}

function getUser(id) {
  return users.get(id) ?? null;
}

function listUsers() {
  return [...users.values()];
}

function deactivateUser(id) {
  const user = users.get(id);
  if (!user) return false;
  user.active = false;
  return true;
}

module.exports = { addUser, getUser, listUsers, deactivateUser };
