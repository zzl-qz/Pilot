'use strict';

/**
 * 执行 fn，失败时按指数退避重试。
 * @param {object} options baseDelayMs 首次退避毫秒，maxAttempts 最多尝试次数，sleep 可注入的等待函数
 */
async function withRetry(fn, options) {
  const { maxAttempts = 3, sleep = () => Promise.resolve() } = options ?? {};
  let lastError = null;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      return await fn();
    } catch (error) {
      lastError = error;
      await sleep(0);
    }
  }
  throw lastError;
}

module.exports = { withRetry };
