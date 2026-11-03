import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { dryRunRecords, parseArgs, prepareWorkspace, runCommand, runTask, waitForTerminal } from './runner.mjs';
import { cacheHitRate, gate } from './metrics.mjs';

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'pilot-eval-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(join(root, 'fixtures', 'bug', 'test'), { recursive: true });
  await writeFile(join(root, 'fixtures', 'bug', 'src.cjs'), 'module.exports = 1;');
  await writeFile(join(root, 'fixtures', 'bug', 'test', 'value.test.cjs'),
    "require('node:assert/strict').equal(require('../src.cjs'), 2);");
  return { root, args: { fixtures: join(root, 'fixtures'), workRoot: join(root, 'runs'), timeoutMinutes: 1, pollMs: 5 },
    task: { id: 'bug', prompt: 'Fix the implementation', acceptance: ['node --test'] } };
}

async function server(t, handle) {
  const requests = [];
  const http = createServer(async (req, res) => {
    let raw = '';
    for await (const chunk of req) raw += chunk;
    const body = raw ? JSON.parse(raw) : null;
    requests.push({ path: req.url, method: req.method, body });
    try {
      const result = await handle(req.url, body);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(result ?? {}));
    } catch (error) {
      res.writeHead(500);
      res.end(error.message);
    }
  });
  await new Promise(resolve => http.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { http.close(resolve); http.closeAllConnections(); }));
  return { base: `http://127.0.0.1:${http.address().port}`, requests };
}

test('缺失 fixture 在请求服务前失败，不能使用默认工作区', async t => {
  const { args, task } = await fixture(t);
  const mock = await server(t, () => ({}));
  await assert.rejects(runTask({ ...args, base: mock.base }, { ...task, id: 'missing' }), /fixture 不存在/);
  assert.equal(mock.requests.length, 0);
});

test('同一任务并发准备使用不同工作区', async t => {
  const { args, task } = await fixture(t);
  const [a, b] = await Promise.all([prepareWorkspace(args, task), prepareWorkspace(args, task)]);
  assert.notEqual(a, b);
});

test('引导发给当前 run，而不是排队 messages；取消和暂停是终态', async t => {
  let polls = 0;
  const mock = await server(t, path => path.endsWith('/diagnostics')
    ? { sessionStatus: ++polls === 1 ? 'RUNNING' : 'CANCELLED', activeRunId: 'run-1' } : {});
  const result = await waitForTerminal({ base: mock.base, timeoutMinutes: 1, pollMs: 1 }, 's', { afterSeconds: 0, content: '改用 B' });
  assert.equal(result.sessionStatus, 'CANCELLED');
  assert.deepEqual(mock.requests.find(r => r.path.endsWith('/steer'))?.body, { content: '改用 B', runId: 'run-1' });
  assert.equal(mock.requests.some(r => r.path.endsWith('/messages')), false);
  const paused = await server(t, () => ({ sessionStatus: 'PAUSED' }));
  assert.equal((await waitForTerminal({ base: paused.base, timeoutMinutes: 1 }, 's')).sessionStatus, 'PAUSED');
});

test('超时取消本次运行，不执行验收', async t => {
  const { args, task } = await fixture(t);
  let workspace;
  const mock = await server(t, (path, body) => {
    if (path === '/api/sessions') { workspace = body.workspace; return { sessionId: 'own' }; }
    return { sessionStatus: 'RUNNING', activeRunId: 'own-run' };
  });
  await assert.rejects(runTask({ ...args, base: mock.base, timeoutMinutes: 0.001 },
    { ...task, acceptance: ['node -e "require(\'node:fs\').writeFileSync(\'accepted\', \'yes\')"'] }), /任务超时/);
  assert.equal(mock.requests.filter(r => r.path === '/api/runs/own-run/cancel').length, 1);
  await assert.rejects(readFile(join(workspace, 'accepted')), { code: 'ENOENT' });
});

for (const repair of [false, true]) test(`验收使用原始测试，${repair ? '修复实现能通过' : '删改断言不能假通过'}`, async t => {
  const { args, task } = await fixture(t);
  let workspace;
  const mock = await server(t, async (path, body) => {
    if (path === '/api/sessions') { workspace = body.workspace; return { sessionId: 'own' }; }
    if (path.endsWith('/messages')) {
      await writeFile(join(workspace, 'test', 'value.test.cjs'), 'process.exit(0);');
      if (repair) await writeFile(join(workspace, 'src.cjs'), 'module.exports = 2;');
    }
    return { sessionStatus: 'COMPLETED', usageExact: true, inputTokens: 10 };
  });
  const result = await runTask({ ...args, base: mock.base }, task);
  assert.equal(result.success, repair);
  assert.equal(result.acceptanceRan, true);
  assert.equal(result.usageExact, true);
  assert.match(await readFile(join(workspace, 'test', 'value.test.cjs'), 'utf8'), /process.exit/);
});

test('命令超时终止整个进程树，输出保留有界', async t => {
  const { root } = await fixture(t);
  const started = Date.now();
  const timeout = await runCommand('node -e "setInterval(() => {}, 1000)"', root, 100);
  assert.equal(timeout.timedOut, true);
  assert.notEqual(timeout.exitCode, 0);
  assert.ok(Date.now() - started < 3000);
  const verbose = await runCommand('node -e "process.stdout.write(\'x\'.repeat(100000))"', root, 5000);
  assert.equal(verbose.exitCode, 0);
  assert.ok(verbose.output.length <= 64 * 1024);
});

test('strict 不能没有基线或使用 dry-run，dry-run 不能通过能力门禁', () => {
  assert.throws(() => parseArgs(['--strict']), /baseline/);
  assert.throws(() => parseArgs(['--strict', '--baseline', 'old.jsonl', '--dry-run']), /dry-run/);
  assert.throws(() => parseArgs(['--timeout-minutes', 'NaN']), /超时/);
  assert.throws(() => parseArgs(['--out', '--dry-run']), /缺少参数值/);
  const records = dryRunRecords(Array.from({ length: 20 }, (_, i) => ({ id: String(i) })), {});
  assert.ok(records.every(r => !r.success && !r.acceptanceRan && r.dryRun));
  assert.equal(gate(records, records).verdict, 'reject');
});

test('未知用量不会稀释真实缓存命中率，异常缓存数不超过 100%', () => {
  assert.equal(cacheHitRate([{ inputTokens: 100, cachedInputTokens: 50, usageExact: true, cacheReadObserved: true },
    { inputTokens: 900, cachedInputTokens: 0, usageExact: false }]), 0.5);
  assert.equal(cacheHitRate([{ inputTokens: 100, cachedInputTokens: 200, cacheReadObserved: true }]), 1);
  assert.equal(cacheHitRate([{ inputTokens: 100, cachedInputTokens: 0, usageExact: true }]), null);
  assert.equal(cacheHitRate([{ inputTokens: 1000, cachedInputTokens: 50, usageDetails: {observedCacheRequests: 1, observedCacheInputTokens: 100, cacheReadTokens: 50} }]), 0.5);
});

test('非 jsonl 输出路径不被 summary 覆盖', async t => {
  const { root } = await fixture(t);
  const tasks = join(root, 'tasks.json');
  const out = join(root, 'records.data');
  await writeFile(tasks, JSON.stringify({ tasks: [{ id: 'test' }] }));
  await promisify(execFile)(process.execPath, ['evaluation/harness/runner.mjs', '--dry-run', '--tasks', tasks, '--out', out]);
  assert.equal(JSON.parse(await readFile(out, 'utf8')).taskId, 'test');
  assert.equal(JSON.parse(await readFile(`${out}.summary.json`, 'utf8')).dryRun, true);
});
