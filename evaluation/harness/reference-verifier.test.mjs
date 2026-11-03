import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, readdir, rm, symlink } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { verifyReference, verifyReferences } from './reference-verifier.mjs';

async function setup(t, { initial = 'module.exports = 1;', solution = 'module.exports = 2;' } = {}) {
  const root = await mkdtemp(join(tmpdir(), 'pilot 参考解-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const fixturesRoot = root, referencesRoot = join(root, 'references');
  const fixture = join(root, 'fixtures', 'example'), reference = join(referencesRoot, 'example');
  await mkdir(join(fixture, 'test'), { recursive: true });
  await mkdir(join(fixture, 'src'), { recursive: true });
  await mkdir(join(reference, 'src'), { recursive: true });
  await writeFile(join(fixture, 'src', 'value.js'), initial);
  await writeFile(join(reference, 'src', 'value.js'), solution);
  const testSource = "const { test } = require('node:test'); const assert = require('node:assert/strict'); test('值满足要求', () => assert.equal(require('../src/value.js'), 2));\n";
  await writeFile(join(fixture, 'test', 'value.test.js'), testSource);
  const task = { id: 'example', fixture: 'fixtures/example', acceptance: ['node --test'] };
  const tasksPath = join(root, 'tasks.json');
  await writeFile(tasksPath, JSON.stringify({ tasks: [task] }));
  return { root, fixture, reference, task, testSource, tasksPath, options: { fixturesRoot, referencesRoot, timeoutMs: 5000 } };
}

test('同一份验收初始失败、参考解通过；中文空格路径可执行且源夹具不变', async t => {
  const s = await setup(t);
  const result = await verifyReference(s.task, s.options);
  assert.equal(result.ok, true);
  assert.equal(result.baseline.failed, 1);
  assert.equal(result.reference.failed, 0);
  assert.equal(await readFile(join(s.fixture, 'src', 'value.js'), 'utf8'), 'module.exports = 1;');
  assert.equal(await readFile(join(s.fixture, 'test', 'value.test.js'), 'utf8'), s.testSource);
  assert.deepEqual((await readdir(s.fixture)).sort(), ['src', 'test']);
});

test('错误参考解不能通过，即使基线确实失败', async t => {
  const s = await setup(t, { solution: 'module.exports = 3;' });
  const result = await verifyReference(s.task, s.options);
  assert.equal(result.ok, false);
  assert.equal(result.discriminates, true);
  assert.equal(result.solvable, false);
});

test('初始已经通过的任务没有分辨力', async t => {
  const s = await setup(t, { initial: 'module.exports = 2;' });
  const result = await verifyReference(s.task, s.options);
  assert.equal(result.ok, false);
  assert.equal(result.discriminates, false);
  assert.equal(result.solvable, true);
});

test('参考解不能写入或删除验收测试', async t => {
  const s = await setup(t);
  await mkdir(join(s.reference, 'test'));
  await writeFile(join(s.reference, 'test', 'value.test.js'), '');
  assert.match((await verifyReference(s.task, s.options)).error, /路径必须位于src/);
  await rm(join(s.reference, 'test'), { recursive: true });
  await writeFile(join(s.reference, 'reference.json'), JSON.stringify({ remove: ['test/value.test.js'] }));
  assert.match((await verifyReference(s.task, s.options)).error, /路径必须位于src/);
});

test('拒绝 POSIX/Windows 越界、符号根路径和恶意任务 id', async t => {
  const s = await setup(t);
  for (const fixture of ['../outside', '/outside', 'C:\\outside', 'fixtures/../../outside', 'fixtures/./example']) {
    assert.equal((await verifyReference({ ...s.task, fixture }, s.options)).ok, false, fixture);
  }
  for (const id of ['../example', '/example', 'C:\\example', '', undefined]) {
    assert.match((await verifyReference({ ...s.task, id }, s.options)).error, /任务 id 非法/);
  }
  assert.match((await verifyReference(null, s.options)).error, /任务 id 非法/);
});

test('删除清单只能删除 src 内路径，支持移动文件', async t => {
  const s = await setup(t);
  await writeFile(join(s.fixture, 'src', 'old.js'), '// 待移动');
  await writeFile(join(s.fixture, 'test', 'move.test.js'), "const { test } = require('node:test'); const assert = require('node:assert/strict'); const fs = require('node:fs'); const path = require('node:path'); test('旧路径已移走', () => assert.equal(fs.existsSync(path.join(__dirname, '../src/old.js')), false));");
  for (const name of ['src/../../outside', 'src\\..\\outside', '/src/old.js', 'src']) {
    await writeFile(join(s.reference, 'reference.json'), JSON.stringify({ remove: [name] }));
    assert.equal((await verifyReference(s.task, s.options)).ok, false, name);
  }
  await writeFile(join(s.reference, 'reference.json'), JSON.stringify({ remove: ['src/old.js'] }));
  assert.equal((await verifyReference(s.task, s.options)).ok, true);
  assert.equal(await readFile(join(s.fixture, 'src', 'old.js'), 'utf8'), '// 待移动');
});

test('缺少参考解、空验收或 shell 命令必须报配置错误', async t => {
  const s = await setup(t);
  assert.match((await verifyReference({ ...s.task, acceptance: [] }, s.options)).error, /仅支持/);
  assert.match((await verifyReference({ ...s.task, acceptance: ['node --test; exit 0'] }, s.options)).error, /仅支持/);
  await rm(s.reference, { recursive: true });
  const missing = await verifyReference(s.task, s.options);
  assert.equal(missing.ok, false);
  assert.ok(missing.error);
  assert.equal(missing.baseline, undefined);
});

test('不跟随参考解中的符号链接', async t => {
  const s = await setup(t);
  await rm(join(s.reference, 'src', 'value.js'));
  try { await symlink(join(s.fixture, 'src', 'value.js'), join(s.reference, 'src', 'value.js')); }
  catch (error) {
    if (process.platform === 'win32' && ['EPERM', 'EACCES'].includes(error.code)) return t.skip('Windows 当前账户无符号链接权限');
    throw error;
  }
  assert.match((await verifyReference(s.task, s.options)).error, /符号链接/);
});

test('超时不能冒充可分辨的失败或成功参考解', async t => {
  const s = await setup(t, { initial: 'while (true) {}', solution: 'while (true) {}' });
  const result = await verifyReference(s.task, { ...s.options, timeoutMs: 150 });
  assert.equal(result.ok, false);
  assert.equal(result.baseline.timedOut, true);
  assert.equal(result.reference.timedOut, true);
  assert.equal(result.discriminates, false);
  assert.equal(result.solvable, false);
});

test('任务筛选、argv 验收和任务集配置校验', async t => {
  const s = await setup(t);
  await writeFile(s.tasksPath, JSON.stringify({ tasks: [{ ...s.task, acceptance: [['node', '--test']] }] }));
  assert.equal((await verifyReferences({ tasksPath: s.tasksPath, taskId: 'example' }))[0].ok, true);
  await assert.rejects(verifyReferences({ tasksPath: s.tasksPath, taskId: 'missing' }), /未找到任务/);
  await writeFile(s.tasksPath, JSON.stringify({ tasks: [s.task, s.task] }));
  await assert.rejects(verifyReferences({ tasksPath: s.tasksPath }), /任务 id 重复/);
  await writeFile(s.tasksPath, JSON.stringify({ tasks: [] }));
  await assert.rejects(verifyReferences({ tasksPath: s.tasksPath }), /不能为空/);
});
