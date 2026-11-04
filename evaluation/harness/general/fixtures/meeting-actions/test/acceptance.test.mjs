import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile, lstat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
test('交付内容、原始资料和报告均可核对', async () => {
  assert.deepEqual(JSON.parse(await readFile('result.json', 'utf8')), {"actions": [{"task": "接口文档", "owner": "林", "due": "2026-11-06"}, {"task": "测试方案", "owner": "周", "due": null}], "undecided": ["重写服务"]});
  assert.equal(createHash('sha256').update(await readFile('input.json')).digest('hex'), '48bd90bcb6f1c7faca550ac3dad2de550bdb7aaf0f26daa768284fc0d1d6e1e9');
  assert.equal((await lstat('report.md')).isSymbolicLink(), false);
  const report = await readFile('report.md', 'utf8');
  assert.ok(report.length > 40 && /input.json/.test(report), '报告必须说明来源与处理口径');
});
