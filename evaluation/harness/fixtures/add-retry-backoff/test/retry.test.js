const test = require('node:test');
const assert = require('node:assert/strict');

const { withRetry } = require('../src/retry.js');

test('前两次失败、第三次成功时最终成功', async () => {
  let calls = 0;
  const value = await withRetry(
    async () => {
      calls += 1;
      if (calls < 3) throw new Error('暂时不可用');
      return 'ok';
    },
    { maxAttempts: 3, baseDelayMs: 10, sleep: () => Promise.resolve() }
  );
  assert.equal(value, 'ok');
  assert.equal(calls, 3);
});

test('退避间隔按指数增长，不是固定值', async () => {
  const delays = [];
  let calls = 0;
  await withRetry(
    async () => {
      calls += 1;
      if (calls <= 3) throw new Error('暂时不可用');
      return 'ok';
    },
    { maxAttempts: 4, baseDelayMs: 10, sleep: (ms) => { delays.push(ms); return Promise.resolve(); } }
  );
  assert.deepEqual(delays, [10, 20, 40], `退避应为 10/20/40，实际 ${delays.join('/')}`);
});

test('超过最大次数后抛出最后一次错误', async () => {
  let calls = 0;
  await assert.rejects(
    () => withRetry(
      async () => { calls += 1; throw new Error('一直失败'); },
      { maxAttempts: 3, baseDelayMs: 1, sleep: () => Promise.resolve() }
    ),
    /一直失败/
  );
  assert.equal(calls, 3, 'maxAttempts 次尝试后必须停下');
});

test('第一次就成功时不等待', async () => {
  let slept = 0;
  await withRetry(async () => 'ok', { maxAttempts: 3, baseDelayMs: 10, sleep: () => { slept += 1; return Promise.resolve(); } });
  assert.equal(slept, 0);
});
