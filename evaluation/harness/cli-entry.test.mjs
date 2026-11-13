import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, realpath, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';

const exec = promisify(execFile);
const here = dirname(fileURLToPath(import.meta.url));

for (const script of ['runner.mjs', 'reference-verifier.mjs', 'budget-plan.mjs']) {
  test(`${script} 可被带普通参数的内联脚本导入，不误执行 CLI`, async () => {
    const url = pathToFileURL(join(here, script)).href;
    const result = await exec(process.execPath,
      ['--input-type=module', '--eval', `await import(${JSON.stringify(url)}); console.log('imported');`, 'not-a-script-file'],
      { timeout: 15000 });
    assert.equal(result.stdout.trim(), 'imported');
    assert.equal(result.stderr, '');
  });

  test(`${script} 经目录别名启动仍执行 CLI，不能空跑返回成功`, async t => {
    const root = await realpath(await mkdtemp(join(tmpdir(), 'pilot cli alias-')));
    t.after(() => rm(root, { recursive: true, force: true }));
    const alias = join(root, 'harness alias');
    try {
      await symlink(here, alias, process.platform === 'win32' ? 'junction' : 'dir');
    } catch (error) {
      if (process.platform === 'win32' && ['EPERM', 'EACCES'].includes(error.code)) {
        t.skip('当前 Windows 账户不能创建目录联接');
        return;
      }
      throw error;
    }
    for (const flags of [[], ['--preserve-symlinks-main']]) {
      await assert.rejects(exec(process.execPath,
        [...flags, join(alias, script), '--invalid-evaluation-option'], { timeout: 15000 }),
      error => Number.isInteger(error.code) && error.code !== 0 && error.stderr.length > 0,
      '非法参数必须执行校验并失败，不能因路径别名未进入 main 而退出 0');
    }
  });
}
