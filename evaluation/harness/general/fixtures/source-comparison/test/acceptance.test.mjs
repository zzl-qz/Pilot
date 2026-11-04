import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile, lstat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
test('交付内容、原始资料和报告均可核对', async () => {
  assert.deepEqual(JSON.parse(await readFile('result.json', 'utf8')), {"products": [{"name": "A", "offline": true, "export": ["csv", "md"], "sources": ["official-a"]}, {"name": "B", "offline": false, "export": ["csv", "pdf"], "sources": ["official-b"]}], "unknown": ["price"]});
  assert.equal(createHash('sha256').update(await readFile('input.json')).digest('hex'), '9b61eb903dcd1a1c94d71a3dae80bf405a423623cdebffd79baab1f5e4b22eca');
  assert.equal((await lstat('report.md')).isSymbolicLink(), false);
  const report = await readFile('report.md', 'utf8');
  assert.ok(report.length > 40 && /input.json/.test(report), '报告必须说明来源与处理口径');
});
