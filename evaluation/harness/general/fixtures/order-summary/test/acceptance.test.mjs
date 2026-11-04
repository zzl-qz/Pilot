import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile, lstat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
test('交付内容、原始资料和报告均可核对', async () => {
  assert.deepEqual(JSON.parse(await readFile('result.json', 'utf8')), {"currency": "CNY", "paidCents": 3799, "refundCents": 500, "netCents": 3299, "excludedIds": ["c"], "missingIds": ["e"]});
  assert.equal(createHash('sha256').update(await readFile('input.json')).digest('hex'), '6b5d1e7ed9c8d1462c67de15e463c1ad611093f41f9678131b261383a3c39556');
  assert.equal((await lstat('report.md')).isSymbolicLink(), false);
  const report = await readFile('report.md', 'utf8');
  assert.ok(report.length > 40 && /input.json/.test(report), '报告必须说明来源与处理口径');
});
