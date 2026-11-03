'use strict';

/**
 * 判断会话是否已过期。现在直接读全局时间，测试只能在「刚好」的时刻通过，
 * 于是变成偶发失败。
 */
function isExpired(session, now) {
  return now - session.createdAt > session.ttlMs;
}

function remainingMs(session, now) {
  return session.ttlMs - (now - session.createdAt);
}

module.exports = { isExpired, remainingMs };
