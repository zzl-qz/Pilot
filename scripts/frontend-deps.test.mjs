import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cp, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';

const entry = new URL('../tools/ask-frontend-deps.mjs', import.meta.url);
const dependencies = { react: '^18.0.0', 'pdfjs-dist': '^6.0.0' };
const devDependencies = { typescript: '^6.0.0' };
const packages = {
  '': { dependencies, devDependencies },
  'node_modules/react': { version: '18.3.1' },
  'node_modules/pdfjs-dist': { version: '6.5.438' },
  'node_modules/typescript': { version: '6.0.3', dev: true },
  'node_modules/@rollup/rollup-win32-x64-msvc': { version: '4.0.0', os: ['win32'], cpu: ['x64'], optional: true },
};

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'Pilot 前端 deps with spaces & 100%-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const frontend = join(root, 'pilot-web', 'frontend');
  const script = join(root, 'tools', 'ask-frontend-deps.mjs');
  const npm = join(root, '模拟 npm CLI.mjs');
  const capture = join(frontend, 'npm-invocation.json');
  async function json(file, data) {
    await mkdir(dirname(file), { recursive: true });
    await writeFile(file, JSON.stringify(data));
  }
  async function installed(name, version) {
    await json(join(frontend, 'node_modules', name, 'package.json'), { name, version });
  }
  await mkdir(dirname(script), { recursive: true });
  await cp(entry, script);
  await json(join(frontend, 'package.json'), { dependencies, devDependencies });
  await json(join(frontend, 'package-lock.json'), { lockfileVersion: 3, packages });
  await writeFile(npm, `
    import { mkdirSync, writeFileSync } from 'node:fs';
    writeFileSync('npm-invocation.json', JSON.stringify(process.argv.slice(2)));
    if (process.env.PILOT_FIXTURE_INSTALL === 'fail') {
      console.error('fixture: 缓存缺少锁定包');
      process.exit(7);
    }
    if (process.env.PILOT_FIXTURE_INSTALL !== 'incomplete') {
      for (const [name, version] of [['react', '18.3.1'], ['pdfjs-dist', '6.5.438'], ['typescript', '6.0.3']]) {
        mkdirSync('node_modules/' + name, { recursive: true });
        writeFileSync('node_modules/' + name + '/package.json', JSON.stringify({name, version}));
      }
    }
  `);
  function run(mode = 'ok') {
    return spawnSync(process.execPath, [script, npm], {
      cwd: root,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout: 10_000,
      env: { ...process.env, CI: 'true', PILOT_SKIP_DEPS_ASK: '1', PILOT_OFFLINE: 'true', PILOT_FIXTURE_INSTALL: mode },
    });
  }
  return { frontend, capture, installed, json, run };
}

test('IDE 或 CI 存在旧 node_modules 但缺少 PDF.js 时按锁文件安装，第二次直接通过', async t => {
  const f = await fixture(t);
  await f.installed('react', '18.3.1');
  await f.installed('typescript', '6.0.3');
  const lock = await readFile(join(f.frontend, 'package-lock.json'), 'utf8');
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /pdfjs-dist 缺失/);
  const args = JSON.parse(await readFile(f.capture, 'utf8'));
  assert.equal(args[0], 'ci');
  assert.ok(args.includes('--include=dev'));
  assert.ok(args.includes('--offline'));
  assert.equal(await readFile(join(f.frontend, 'package-lock.json'), 'utf8'), lock);

  await rm(f.capture);
  const next = f.run('fail');
  assert.equal(next.status, 0, next.stderr);
  await assert.rejects(readFile(f.capture), { code: 'ENOENT' });
});

test('目录存在但 PDF.js 的版本不符合锁文件时重新同步', async t => {
  const f = await fixture(t);
  await f.installed('react', '18.3.1');
  await f.installed('pdfjs-dist', '6.5.437');
  await f.installed('typescript', '6.0.3');
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /当前版本为 6\.5\.437，锁定版本为 6\.5\.438/);
});

test('首次启动安装失败时报告 npm 原因，返回非零并给出重试命令', async t => {
  const f = await fixture(t);
  const result = f.run('fail');
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stderr, /缓存缺少锁定包/);
  assert.match(result.stderr, /退出码 7/);
  assert.match(result.stderr, /npm ci --include=dev/);
});

test('安装返回成功但开发依赖仍不完整时阻止构建', async t => {
  const f = await fixture(t);
  await f.installed('react', '18.3.1');
  await f.installed('pdfjs-dist', '6.5.438');
  const result = f.run('incomplete');
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stderr, /依赖仍未就绪：node_modules\/typescript/);
});

test('依赖声明修改后未更新锁文件时明确报错，不尝试安装', async t => {
  const f = await fixture(t);
  await f.json(join(f.frontend, 'package.json'), { dependencies: { ...dependencies, 'pdfjs-dist': '^7.0.0' }, devDependencies });
  const result = f.run();
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stderr, /package.json 与 package-lock.json 不一致/);
  await assert.rejects(readFile(f.capture), { code: 'ENOENT' });
});
