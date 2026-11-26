import assert from 'node:assert/strict';
import { test } from 'node:test';
import { localCapabilitySummary } from '../src/lib/local-capability-summary.ts';

test('本机资源展示将可用项优先排列并按名称稳定排序', () => {
  const result = localCapabilitySummary([
    { name: 'zeta', available: false },
    { name: 'beta', available: true },
    { name: 'alpha', available: true },
    { name: 'gamma', available: false }
  ]);
  assert.deepEqual(result.items.map(item => item.name), ['alpha', 'beta', 'gamma', 'zeta']);
  assert.equal(result.available, 2);
});

test('尚未加载本机资源时给出空列表与零可用数', () => {
  assert.deepEqual(localCapabilitySummary(undefined), { items: [], available: 0 });
});
