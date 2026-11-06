import { spawn } from 'node:child_process';
import { readdir, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, join } from 'node:path';

const root = fileURLToPath(new URL('..', import.meta.url));
const repo = resolve(root, '../..');
const args = process.argv.slice(2);
const unitOnly = args.includes('--unit-only');
const all = args.includes('--all');
const selected = args.filter(arg => !arg.startsWith('--'));
const output = resolve(root, '.tmp-check', 'migration', new Date().toISOString().replace(/[:.]/g, '-'));
await mkdir(output, { recursive: true });
const report = { platform: process.platform, node: process.version, startedAt: new Date().toISOString(), checks: [], success: false };
async function run(name, argv, cwd = root) {
  const started = Date.now();
  let log = '';
  console.log(`开始：${name}`);
  const code = await new Promise((accept, reject) => {
    const child = spawn(process.execPath, argv, { cwd, env: { ...process.env, PILOT_SKIP_DEPS_ASK: '1' }, stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', chunk => { log += chunk; });
    child.stderr.on('data', chunk => { log += chunk; });
    child.once('error', reject);
    child.once('exit', (status, signal) => accept(signal ? 1 : status ?? 1));
  });
  await writeFile(join(output, name + '.log'), log);
  report.checks.push({ name, code, seconds: (Date.now() - started) / 1000 });
  await writeFile(join(output, 'report.json'), JSON.stringify(report, null, 2));
  if (code !== 0) { console.error(log.slice(-16000)); throw new Error(`${name} 未通过，报告：${output}`); }
  console.log(`通过：${name}（${((Date.now() - started) / 1000).toFixed(1)} 秒）`);
}
try {
  const loader = new URL('./register-typescript.mjs', import.meta.url).href;
  const webTests = (await readdir(resolve(root, '../src/test/js'))).filter(name => name.endsWith('.test.js')).sort().map(name => resolve(root, '../src/test/js', name));
  const componentTests = (await readdir(join(root, 'scripts'))).filter(name => name.endsWith('.test.mjs')).sort().map(name => join(root, 'scripts', name));
  if (!webTests.length || !componentTests.length) throw new Error('单元测试清单为空');
  await run('frontend-units', ['--import', loader, '--test', ...webTests, ...componentTests]);
  if (!unitOnly) {
    await run('typecheck-and-build', ['scripts/check.mjs']);
    const desktopTests = (await readdir(resolve(repo, 'pilot-desktop'))).filter(name => name.endsWith('.test.mjs')).sort().map(name => resolve(repo, 'pilot-desktop', name));
    await run('desktop-units', ['--test', ...desktopTests]);
    const smoke = all ? (await readdir(join(root, 'scripts'))).filter(name => name.endsWith('-smoke.mjs') && !name.includes('-package-')).sort()
      : [...new Set(['navigation-smoke.mjs', 'composer-smoke.mjs', ...selected.map(name => name.endsWith('.mjs') ? name : name + '-smoke.mjs')])];
    for (const script of smoke) {
      if (!/^[a-z0-9-]+\.mjs$/.test(script)) throw new Error('无效的测试脚本名');
      await run(script.replace('.mjs', ''), [join('scripts', script), ...(['p1-smoke.mjs', 'results-smoke.mjs', 'schedule-smoke.mjs'].includes(script) ? ['--production'] : [])]);
    }
  }
  report.success = true;
} finally {
  report.finishedAt = new Date().toISOString();
  await writeFile(join(output, 'report.json'), JSON.stringify(report, null, 2));
  console.log('验证报告：' + output);
}
