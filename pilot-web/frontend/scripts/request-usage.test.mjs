import assert from 'node:assert/strict';
import test from 'node:test';
import { formatCache, formatCost, totalCost, requestContext, groupRequests } from '../src/lib/request-usage.js';
import { EVENT_TYPES } from '../src/event-types.js';

test('cache coverage distinguishes missing, actual zero and weighted totals', () => {
  assert.equal(formatCache({ inputTokens: 100, cachedInputTokens: 0 }), '—');
  assert.equal(formatCache({ inputTokens: 100, cachedInputTokens: 0, cacheReadObserved: true }), '0%');
  assert.equal(formatCache({ inputTokens: 100, cachedInputTokens: 70, cacheReadObserved: true }), '70%');
  assert.equal(formatCache({ observedCacheRequests: 2, observedCacheInputTokens: 1000, cacheReadTokens: 800, inputTokens: 10000 }), '80%');
  assert.equal(formatCache({ inputTokens: 0, cachedInputTokens: 0, cacheReadObserved: true }), '—');
  assert.ok(EVENT_TYPES.includes('model.request'));
});
test('money never turns unknown into free and never sums different currencies', () => {
  assert.equal(formatCost(null), '—'); assert.equal(formatCost(-1), '—'); assert.equal(formatCost(0), 'USD 0');
  assert.equal(formatCost(0.0000001), 'USD <0.000001');
  assert.equal(totalCost({ pricedRequests: 2, costs: { USD: 0.01, CNY: 0.02 } }), 'USD 0.01 · CNY 0.02');
  assert.equal(totalCost({ pricedRequests: 0, costs: {} }), '—');
});
test('composition sums real parts while preserving unknown capacity and attachment caveat', () => {
  const report = { context: { parts: [{ category: 'system', tokens: 10 }, { category: 'tools', tokens: 20 }], attachments: 2 } };
  assert.equal(requestContext({ limitTokens: 100 }, report).percent, 30);
  assert.equal(requestContext({ limitTokens: 1 }, report).percent, null);
  assert.equal(requestContext({}, report).attachments, 2);
  assert.equal(requestContext({ usedTokens: 10, limitTokens: 100 }, null).fromRequest, false);
});

test('request groups count requests, preserve failures and never infer agent counts', () => {
  const reports = Array.from({ length: 16 }, (_, i) => ({ id: `r${i}`, purpose: i < 10 ? 'main' : 'subagent', status: i === 15 ? 'failed' : 'completed' }));
  const groups = groupRequests([...reports, reports[15]]);
  assert.deepEqual(groups.map(g => [g.purpose, g.reports.length, g.failed]), [['main', 10, 0], ['subagent', 6, 1]]);
  assert.equal(groups[1].agentCount, undefined);
  assert.equal(groups[1].pricedRequests, 0);
});

test('group prices preserve zero, partial coverage and separate currencies', () => {
  const costs = [null, {}, { total: '' }, { total: -1 }, { total: Infinity }, { currency: 'USD', total: 0 }, { currency: 'USD', total: 0.02 }, { currency: 'CNY', total: 0.5 }];
  const [group] = groupRequests(costs.map((cost, i) => ({ id: `r${i}`, purpose: 'subagent', cost })));
  assert.equal(group.pricedRequests, 3);
  assert.equal(totalCost(group), 'USD 0.02 · CNY 0.5');
});

test('empty, unknown purpose and old records without ids remain safe', () => {
  assert.deepEqual(groupRequests([]), []);
  const groups = groupRequests([null, { purpose: 'future' }, { purpose: '__proto__' }, { purpose: 'main' }]);
  assert.deepEqual(groups.map(g => [g.purpose, g.reports.length]), [['main', 1], ['other', 2]]);
});
