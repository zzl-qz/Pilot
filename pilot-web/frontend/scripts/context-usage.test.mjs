import test from 'node:test';
import assert from 'node:assert/strict';
const { usageBudgetTitle, usageLevel, usagePercent } = await import('../src/lib/context-usage.ts');

test('上下文百分比使用有效输入预算，80% 警告，92% 高温', () => {
  const status = { usedTokens: 10400, limitTokens: 104000, percent: 10 };
  assert.equal(usagePercent(status), 10);
  assert.equal(usageLevel(79), 'normal');
  assert.equal(usageLevel(80), 'warn');
  assert.equal(usageLevel(92), 'danger');
  assert.match(usageBudgetTitle(10400, 104000), /有效输入预算/);
  assert.match(usageBudgetTitle(10400, 104000), /已扣除输出预留与安全余量/);
});
