import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

test('统一加载器可以在含中文空格的临时路径执行 TS 与类型导入', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'pilot 类型迁移 '));
  try {
    await writeFile(join(directory, 'types.ts'), 'export interface Value { text: string }; export const value: Value = { text: "验证成功" };');
    const entry = join(directory, '入口.ts');
    await writeFile(entry, 'import { value, type Value } from "./types.ts"; const result: Value = value; console.log(result.text);');
    const result = spawnSync(process.execPath, ['--import', new URL('./register-typescript.mjs', import.meta.url).href, entry], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), '验证成功');
  } finally { await rm(directory, { recursive: true, force: true }); }
});
