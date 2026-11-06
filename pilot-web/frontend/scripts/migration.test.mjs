import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm, readFile, readdir, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

test('全部 72 个应用模块保持 TS/TSX 严格检查且没有 any 或检查豁免', async () => {
  const root = fileURLToPath(new URL('..', import.meta.url));
  const manifest = JSON.parse(await readFile(join(root, 'migration.json'), 'utf8'));
  const files = manifest.batches.flatMap(batch => batch.files);
  assert.equal(files.length, 72); assert.equal(new Set(files).size, 72);
  for (const file of files) {
    await access(join(root, 'src', file.replace(/\.jsx?$/, extension => extension === '.jsx' ? '.tsx' : '.ts')));
    await assert.rejects(access(join(root, 'src', file)), { code: 'ENOENT' });
  }
  const config = JSON.parse(await readFile(join(root, 'tsconfig.json'), 'utf8'));
  assert.equal(config.compilerOptions.strict, true); assert.notEqual(config.compilerOptions.allowJs, true);
  const visit = async directory => {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const file = join(directory, entry.name);
      if (entry.isDirectory()) { await visit(file); continue; }
      assert.ok(!/\.jsx?$/.test(entry.name), `应用模块不能回退为 JS：${file}`);
      if (!/\.tsx?$/.test(entry.name)) continue;
      const source = await readFile(file, 'utf8');
      assert.doesNotMatch(source, /@ts-(?:ignore|nocheck)/, file);
      const parsed = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
      const check = node => { assert.notEqual(node.kind, ts.SyntaxKind.AnyKeyword, `不能用 any 绕过应用类型：${file}`); ts.forEachChild(node, check); };
      check(parsed);
    }
  };
  await visit(join(root, 'src'));
});

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
