'use strict';
const orders = new Map();

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

module.exports = { addOrder, getOrder, ordersOf, cancelOrder };
