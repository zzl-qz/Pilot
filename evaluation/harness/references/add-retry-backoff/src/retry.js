'use strict';
async function withRetry(fn, options = {}) {
  const { maxAttempts = 3, baseDelayMs = 10, sleep = ms => new Promise(resolve => setTimeout(resolve, ms)) } = options;
  if (!Number.isInteger(maxAttempts) || maxAttempts < 1) throw new RangeError('maxAttempts 必须为正整数');
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    try { return await fn(); }
    catch (error) {
      if (attempt + 1 === maxAttempts) throw error;
      await sleep(baseDelayMs * 2 ** attempt);
    }
  }
}
module.exports = { withRetry };
