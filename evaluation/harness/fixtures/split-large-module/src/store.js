'use strict';

// 一个文件里塞了用户和订单两套逻辑，改哪边都要碰同一个文件。
const users = new Map();
const orders = new Map();

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

function addOrder(order) {
  orders.set(order.id, order);
  return order;
}

function getOrder(id) {
  return orders.get(id) ?? null;
}

function ordersOf(userId) {
  return [...orders.values()].filter((order) => order.userId === userId);
}

function cancelOrder(id) {
  const order = orders.get(id);
  if (!order) return false;
  order.status = 'cancelled';
  return true;
}

module.exports = {
  addUser, getUser, listUsers, deactivateUser,
  addOrder, getOrder, ordersOf, cancelOrder
};
