import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cp, mkdir, mkdtemp, readFile, rm, utimes, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';

test('启动只复用和本地源码一致的完整前端产物', async t => {
  const root = await mkdtemp(join(tmpdir(), 'Pilot 前端构建 '));
  t.after(() => rm(root, { recursive: true, force: true }));
  const frontend = join(root, 'pilot-web', 'frontend');
  const output = join(frontend, '../src/main/resources/static');
  const source = join(frontend, 'src', 'app.ts');
  const bundle = join(output, 'assets', 'app.js');
  const build = join(frontend, 'scripts', 'build.mjs');
  async function write(file, value) {
    await mkdir(dirname(file), { recursive: true });
    await writeFile(file, value);
  }
  await write(source, 'v1');
  await write(join(root, 'tools/ask-frontend-deps.mjs'), '');
  await write(join(frontend, 'scripts/typecheck.mjs'), '');
  await cp(new URL('../pilot-web/frontend/scripts/build.mjs', import.meta.url), build);
  await write(join(frontend, 'node_modules/vite/bin/vite.js'), `
    const fs = require('node:fs');
    if (process.env.FIXTURE_VITE_FAIL === '1') process.exit(7);
    const output = '../src/main/resources/static';
    fs.mkdirSync(output + '/assets', { recursive: true });
    fs.writeFileSync(output + '/index.html', '<script src="/assets/app.js"></script>');
    fs.writeFileSync(output + '/assets/app.js', fs.readFileSync('src/app.ts'));
  `);
  const run = (fail = false) => spawnSync(process.execPath, [build, '--if-needed'], {
    encoding: 'utf8', timeout: 10_000, env: { ...process.env, FIXTURE_VITE_FAIL: fail ? '1' : '0' }
  });
  const fixedTime = new Date('2020-01-01T00:00:00Z');
  await utimes(source, fixedTime, fixedTime);
  const first = run();
  assert.equal(first.status, 0, first.stderr);
  assert.match(run(true).stdout, /跳过构建/);
  await write(source, 'v2');
  await utimes(source, fixedTime, fixedTime);
  assert.equal(run(true).status, 7);
  assert.equal(await readFile(bundle, 'utf8'), 'v1');
  assert.equal(run().status, 0);
  assert.equal(await readFile(bundle, 'utf8'), 'v2');
  await rm(bundle);
  assert.equal(run().status, 0);
  assert.equal(await readFile(bundle, 'utf8'), 'v2');
});
