const test = require('node:test');
const assert = require('node:assert/strict');

const { isExpired, remainingMs } = require('../src/session.js');

test('过期判断接受显式时间，不再偷偷读全局时钟', () => {
  assert.ok(isExpired.length >= 2, 'isExpired 应接受 now 参数');
  assert.equal(isExpired({ createdAt: 0, ttlMs: 1000 }, 1500), true);
  assert.equal(isExpired({ createdAt: 0, ttlMs: 1000 }, 500), false);
});

test('刚好等于 ttl 的那一刻还没过期', () => {
  assert.equal(isExpired({ createdAt: 0, ttlMs: 1000 }, 1000), false);
});

test('剩余时间同样可复现', () => {
  assert.equal(remainingMs({ createdAt: 0, ttlMs: 1000 }, 400), 600);
});

test('同一组入参连跑两次结果一致', () => {
  const session = { createdAt: 0, ttlMs: 1000 };
  assert.equal(isExpired(session, 999), isExpired(session, 999));
});
