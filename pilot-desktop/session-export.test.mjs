import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { saveSessionMarkdown } from './session-export.mjs';

test('使用用户所选位置保存完整 UTF-8 文件，并返回实际路径', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'pilot-export-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const filePath = join(dir, '中文 会话.md');
  await writeFile(filePath, '旧文件');
  const save = async options => {
    assert.equal(options.defaultPath, 'pilot-测试.md');
    return { canceled: false, filePath };
  };
  const result = await saveSessionMarkdown('pilot-测试.md', '# 正文\n', save);
  assert.deepEqual(result, { ok: true, path: filePath });
  assert.equal(await readFile(filePath, 'utf8'), '# 正文\n');
  assert.deepEqual(await readdir(dir), ['中文 会话.md']);
});

test('取消、不可写位置和非法建议文件名', async () => {
  const cancel = async () => ({ canceled: true, filePath: '' });
  assert.deepEqual(await saveSessionMarkdown('pilot-test.md', 'text', cancel), { ok: true, canceled: true });
  const missing = join(tmpdir(), 'missing-' + crypto.randomUUID(), 'pilot.md');
  await assert.rejects(saveSessionMarkdown('pilot-test.md', 'text', async () => ({ filePath: missing })));
  for (const name of ['../pilot-test.md', 'dir\\pilot-test.md', 'a.exe'])
    await assert.rejects(saveSessionMarkdown(name, 'text', cancel));
});
