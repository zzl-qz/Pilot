'use strict';
function register(users, payload = {}) {
  const errors = [];
  const email = payload?.email;
  const name = payload?.name;
  if (typeof email !== 'string' || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    errors.push({ field: 'email', code: 'INVALID_EMAIL' });
  }
  if (users.some(user => user.email === email)) errors.push({ field: 'email', code: 'DUPLICATE_EMAIL' });
  if (typeof name !== 'string' || !name.trim()) errors.push({ field: 'name', code: 'INVALID_NAME' });
  if (errors.length) return { ok: false, errors };
  const user = { id: users.length + 1, email, name };
  users.push(user);
  return { ok: true, user };
}
module.exports = { register };
