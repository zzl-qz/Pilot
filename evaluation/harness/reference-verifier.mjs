#!/usr/bin/env node
/** 验证固定任务有分辨力且可解；参考解只用于独立验收，绝不复制给模型。 */
import { spawn } from 'node:child_process';
import { lstat, readdir, readFile, mkdir, mkdtemp, writeFile, rm } from 'node:fs/promises';
import { dirname, join, resolve, posix, win32 } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { isCliEntry } from './cli-entry.mjs';

const DEFAULT_ROOT = dirname(fileURLToPath(import.meta.url));

function relativePath(value, prefix) {
  if (typeof value !== 'string' || !value || value.includes('\0') || value.includes('\\')
      || posix.isAbsolute(value) || win32.isAbsolute(value)
      || value.split('/').some(part => !part || part === '.' || part === '..')
      || (prefix && !value.startsWith(prefix + '/'))) {
    throw new Error(`路径必须位于${prefix ?? '指定根目录'}内：${value}`);
  }
  return value;
}

/** 快照只接纳普通文件/目录；复制时不跟随符号链接，避免读写夹具之外的路径。 */
async function snapshot(root, relative = '') {
  const full = relative ? join(root, relative) : root;
  const info = await lstat(full);
  if (info.isSymbolicLink()) throw new Error(`不允许符号链接：${full}`);
  if (info.isFile()) return [[relative, await readFile(full)]];
  if (!info.isDirectory()) throw new Error(`不是普通文件或目录：${full}`);
  const files = [];
  for (const name of (await readdir(full)).sort()) files.push(...await snapshot(root, relative ? `${relative}/${name}` : name));
  return files;
}

// 逐级检查，阻止根目录内某个祖先目录本身是符号链接。
async function childDirectory(root, path) {
  relativePath(path);
  let current = resolve(root);
  if (!(await lstat(current)).isDirectory()) throw new Error(`根目录不是普通目录：${current}`);
  for (const segment of path.split('/')) {
    current = join(current, segment);
    const info = await lstat(current);
    if (info.isSymbolicLink() || !info.isDirectory()) throw new Error(`路径不是普通目录：${current}`);
  }
  return current;
}

async function materialize(root, files) {
  await mkdir(root, { recursive: true });
  for (const [name, content] of files) {
    relativePath(name);
    await mkdir(dirname(join(root, name)), { recursive: true });
    await writeFile(join(root, name), content);
  }
}

function checkAcceptance(task) {
  // 本任务集专用：无 shell、无第三方依赖，命令错误不能冒充初始夹具失败。
  if (!Array.isArray(task.acceptance) || task.acceptance.length !== 1
      || JSON.stringify(task.acceptance[0]) !== JSON.stringify('node --test')
      && JSON.stringify(task.acceptance[0]) !== JSON.stringify(['node', '--test'])) {
    throw new Error('参考解验收仅支持 node --test 或 ["node", "--test"]');
  }
}

export function runNodeTests(workspace, timeoutMs = 30_000) {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new Error('timeoutMs 必须为正数');
  return new Promise(resolveResult => {
    const env = { ...process.env, CI: 'true', PILOT_EVAL: '1' };
    delete env.NODE_TEST_CONTEXT;
    delete env.NODE_OPTIONS;
    const child = spawn(process.execPath, ['--test', '--test-reporter=tap'], {
      cwd: workspace, env, shell: false, detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe']
    });
    let output = '', timedOut = false;
    const capture = chunk => { output = (output + chunk.toString()).slice(-65536); };
    child.stdout.on('data', capture); child.stderr.on('data', capture);
    const timer = setTimeout(() => {
      timedOut = true;
      if (process.platform === 'win32') {
        const killer = spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore', shell: false });
        killer.on('error', () => child.kill());
      } else {
        try { process.kill(-child.pid, 'SIGKILL'); } catch { child.kill('SIGKILL'); }
      }
    }, timeoutMs);
    child.on('error', error => { clearTimeout(timer); resolveResult({ ran: false, exitCode: -1, timedOut, error: error.message }); });
    child.on('close', code => {
      clearTimeout(timer);
      const tests = Number(output.match(/^# tests (\d+)$/m)?.[1] ?? 0);
      const failed = Number(output.match(/^# fail (\d+)$/m)?.[1] ?? 0);
      resolveResult({ ran: true, exitCode: code ?? -1, timedOut, tests, failed, output });
    });
  });
}

export async function verifyReference(task, { fixturesRoot = DEFAULT_ROOT, referencesRoot = join(DEFAULT_ROOT, 'references'), timeoutMs = 30_000 } = {}) {
  let temporary;
  try {
    if (typeof task?.id !== 'string' || !/^[a-zA-Z0-9_-]+$/.test(task.id)) throw new Error(`任务 id 非法：${task?.id}`);
    checkAcceptance(task);
    const fixture = await childDirectory(fixturesRoot, task.fixture);
    const reference = await childDirectory(referencesRoot, task.id);
    const original = await snapshot(fixture);
    const solution = await snapshot(reference);
    const tests = original.filter(([name]) => name.startsWith('test/'));
    if (!tests.length) throw new Error('fixture 缺少独立 test/ 验收');
    if (!solution.some(([name]) => name.startsWith('src/'))) throw new Error('参考解缺少 src/ 文件');
    let removals = [];
    for (const [name, contents] of solution) {
      if (name === 'reference.json') {
        const metadata = JSON.parse(contents.toString());
        if (Object.keys(metadata).some(key => key !== 'remove') || !Array.isArray(metadata.remove)) throw new Error('reference.json 只允许 remove 数组');
        removals = metadata.remove.map(name => relativePath(name, 'src'));
      } else relativePath(name, 'src');
    }
    temporary = await mkdtemp(join(tmpdir(), 'pilot-reference-'));
    const initialDir = join(temporary, 'initial'), candidateDir = join(temporary, 'candidate');
    await materialize(initialDir, original);
    await materialize(candidateDir, original);
    for (const name of removals) await rm(join(candidateDir, name), { recursive: true, force: true });
    await materialize(candidateDir, solution.filter(([name]) => name.startsWith('src/')));
    // 以源夹具快照恢复验收；任何参考解都不能提供或覆盖 test/。
    await rm(join(candidateDir, 'test'), { recursive: true, force: true });
    await materialize(candidateDir, tests);
    const baseline = await runNodeTests(initialDir, timeoutMs);
    const referenceResult = await runNodeTests(candidateDir, timeoutMs);
    const discriminates = baseline.ran && !baseline.timedOut && baseline.exitCode > 0 && baseline.tests > 0 && baseline.failed > 0;
    const solvable = referenceResult.ran && !referenceResult.timedOut && referenceResult.exitCode === 0 && referenceResult.tests > 0 && referenceResult.failed === 0;
    return { taskId: task.id, ok: discriminates && solvable, discriminates, solvable, baseline, reference: referenceResult };
  } catch (error) {
    return { taskId: task?.id, ok: false, error: error.message };
  } finally {
    if (temporary) await rm(temporary, { recursive: true, force: true });
  }
}

export async function verifyReferences({ tasksPath = join(DEFAULT_ROOT, 'tasks.json'), fixturesRoot = dirname(tasksPath), referencesRoot = join(dirname(tasksPath), 'references'), taskId, timeoutMs } = {}) {
  const dataset = JSON.parse(await readFile(tasksPath, 'utf8'));
  if (!Array.isArray(dataset.tasks) || !dataset.tasks.length) throw new Error('任务集不能为空');
  if (new Set(dataset.tasks.map(task => task.id)).size !== dataset.tasks.length) throw new Error('任务 id 重复');
  const tasks = taskId ? dataset.tasks.filter(task => task.id === taskId) : dataset.tasks;
  if (!tasks.length) throw new Error(`未找到任务：${taskId}`);
  const results = [];
  for (const task of tasks) results.push(await verifyReference(task, { fixturesRoot, referencesRoot, timeoutMs }));
  return results;
}

async function main(argv) {
  const options = {};
  const keys = { '--tasks': 'tasksPath', '--references': 'referencesRoot', '--fixtures': 'fixturesRoot', '--task': 'taskId' };
  for (let i = 0; i < argv.length; i += 2) {
    if (!keys[argv[i]] || !argv[i + 1] || argv[i + 1].startsWith('--')) throw new Error(`参数无效：${argv[i]}`);
    options[keys[argv[i]]] = argv[i + 1];
  }
  const results = await verifyReferences(options);
  for (const result of results) {
    console.log(`${result.ok ? 'PASS' : 'FAIL'} ${result.taskId}: ${result.error ?? `初始 ${result.baseline.failed}/${result.baseline.tests} 失败；参考解 ${result.reference.tests - result.reference.failed}/${result.reference.tests} 通过`}`);
    if (!result.ok && !result.error) console.log((result.reference?.output ?? result.baseline?.output ?? '').slice(-2500));
  }
  console.log(`${results.filter(result => result.ok).length}/${results.length} 个任务同时满足初始失败与参考解成功（非模型能力评测）`);
  if (results.some(result => !result.ok)) process.exitCode = 1;
}

if (isCliEntry(import.meta.url)) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
