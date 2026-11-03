import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { cp, mkdtemp, mkdir, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createRunContext, dryRunRecords, expandRuns, hashSources, parseArgs, prepareWorkspace, recordMetadata, runCommand, runTask, selectTasks, serverIdentity, traceMetrics, waitForTerminal } from './runner.mjs';
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
  assert.equal(result.reportedComplete, true);
  assert.equal(result.acceptancePassed, repair);
  assert.equal(result.costUsd, null);
  assert.equal(result.takeover, null);
  assert.equal(result.rework, null);
  assert.equal(result.failedCommandCount, null);
  assert.equal(result.resolvedModel, null);
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

test('重复次数和任务筛选有界，重复轮次交错任务且未知任务提前报错', () => {
  const args = parseArgs(['--repeat', '3', '--task', 'a,b', '--task', 'a']);
  const selected = selectTasks([{ id: 'a' }, { id: 'b' }, { id: 'c' }], args.taskIds);
  assert.deepEqual(expandRuns(selected, args.repeat).map(r => `${r.task.id}:${r.attempt}`), ['a:1', 'b:1', 'a:2', 'b:2', 'a:3', 'b:3']);
  for (const value of ['0', '-1', '101', 'NaN', '1.5']) assert.throws(() => parseArgs(['--repeat', value]), /repeat/);
  assert.throws(() => selectTasks([{ id: 'a' }], ['absent']), /未知任务/);
  assert.throws(() => selectTasks([{ id: 'a' }, { id: 'a' }]), /重复/);
});

test('fixture不能逃出根目录，源码hash跨目录稳定且能识别内容变化', async t => {
  const { root, args, task } = await fixture(t);
  await assert.rejects(prepareWorkspace(args, { ...task, fixture: '../escape' }), /越出/);
  const before = await hashSources([join(args.fixtures, 'bug')]);
  const duplicate = join(root, 'copied');
  await cp(join(args.fixtures, 'bug'), duplicate, { recursive: true });
  assert.equal(await hashSources([duplicate]), before);
  await writeFile(join(duplicate, 'src.cjs'), 'module.exports = 3;');
  assert.notEqual(await hashSources([duplicate]), before);
});

test('模型参数只核对当前服务，不修改全局设置，指纹不泄漏凭据', async t => {
  const { args, task } = await fixture(t);
  const mock = await server(t, () => ({ model: 'deepseek-chat', modelEndpoint: 'https://example.invalid?credential=secret',
    permissionMode: 'AUTO', modelApiKeyMasked: 'secret-masked' }));
  const options = { ...args, base: mock.base, model: 'deepseek-chat', promptDir: args.fixtures,
    runtimeSources: [args.fixtures], environment: 'test' };
  const context = await createRunContext(options);
  assert.equal(context.model, 'deepseek-chat'); assert.match(context.modelConfigHash, /^[0-9a-f]{64}$/);
  assert.equal(JSON.stringify(context).includes('secret'), false);
  const metadata = await recordMetadata({ ...options, runContext: context }, task);
  assert.match(metadata.fixtureVersion, /^[0-9a-f]{64}$/); assert.match(metadata.taskVersion, /^[0-9a-f]{64}$/);
  const changed = await recordMetadata({ ...options, runContext: context }, { ...task, prompt: 'different' });
  assert.notEqual(changed.taskVersion, metadata.taskVersion);
  await assert.rejects(serverIdentity({ ...options, model: 'other' }), /模型不匹配/);
  assert.ok(mock.requests.every(r => r.method === 'GET'));
});

test('结构化事件分页计数，不把命令失败说成返工；缺历史是未知', async t => {
  const mock = await server(t, path => path.includes('after=0') ? { hasMore: true, events: [
    { sequence: 1, type: 'tool.started' }, { sequence: 1, type: 'tool.started' }, { sequence: 2, type: 'run.retrying' }
  ] } : { hasMore: false, events: [{ sequence: 3, type: 'tool.started' }] });
  assert.deepEqual(await traceMetrics({ base: mock.base }, 's'), { traceObserved: true, toolCallCount: 2, modelRetryCount: 1 });
  const missing = await server(t, () => ({}));
  assert.deepEqual(await traceMetrics({ base: missing.base }, 's'), { traceObserved: false, toolCallCount: null, modelRetryCount: null });
});

test('argv命令能使用含空格工作区，不依赖系统shell quoting', async t => {
  const { root } = await fixture(t);
  const spaced = join(root, '含空格 workspace'); await mkdir(spaced);
  const result = await runCommand([process.execPath, '-e', 'process.stdout.write(process.cwd())'], spaced, 5000);
  assert.equal(result.exitCode, 0); assert.equal(await realpath(result.output), await realpath(spaced));
});

test('dry-run筛选与重复真实写出独立记录，已有输出拒绝覆盖', async t => {
  const { root } = await fixture(t);
  const tasksPath = join(root, 'tasks.json'), out = join(root, 'trials.jsonl');
  await writeFile(tasksPath, JSON.stringify({ tasks: [{ id: 'a' }, { id: 'b' }] }));
  const argv = ['evaluation/harness/runner.mjs', '--dry-run', '--tasks', tasksPath, '--out', out, '--repeat', '3', '--task', 'b'];
  await promisify(execFile)(process.execPath, argv);
  const records = (await readFile(out, 'utf8')).trim().split('\n').map(JSON.parse);
  assert.deepEqual(records.map(r => r.attempt), [1, 2, 3]);
  assert.ok(records.every(r => r.taskId === 'b' && r.costKnown === false && r.costUsd === null));
  await assert.rejects(promisify(execFile)(process.execPath, argv), /EEXIST/);
  assert.equal((await readFile(out, 'utf8')).trim().split('\n').length, 3);
});

test('任务先结束不能冒充中途引导已覆盖，不另起任务补发', async t => {
  const { args, task } = await fixture(t); let workspace;
  const mock = await server(t, async (path, body) => {
    if (path === '/api/sessions') { workspace = body.workspace; return { sessionId: 'own' }; }
    if (path.endsWith('/messages')) await writeFile(join(workspace, 'src.cjs'), 'module.exports = 2;');
    return { sessionStatus: 'COMPLETED', activeRunId: null };
  });
  const result = await runTask({ ...args, base: mock.base }, { ...task, steer: { afterSeconds: 1, content: '追加要求' } });
  assert.equal(result.acceptancePassed, true); assert.equal(result.success, false);
  assert.equal(result.steerDelivered, false); assert.equal(result.scenarioCovered, false);
  assert.equal(mock.requests.filter(r => r.path.endsWith('/messages')).length, 1);
  assert.equal(mock.requests.some(r => r.path.endsWith('/steer')), false);
});

test('引导记录投递到的run与投递时步数', async t => {
  let polls = 0;
  const mock = await server(t, path => path.endsWith('/diagnostics')
    ? { sessionStatus: ++polls === 1 ? 'RUNNING' : 'COMPLETED', activeRunId: 'run-1', step: 2 } : {});
  const result = await waitForTerminal({ base: mock.base, timeoutMinutes: 1, pollMs: 1 }, 's', { afterSeconds: 0, content: '新约束' });
  assert.equal(result.steerDelivered, true); assert.equal(result.steerRunId, 'run-1'); assert.equal(result.steerAtStep, 2);
  assert.ok(result.steerDeliveredAtSeconds >= 0);
});


test('诊断慢响应受任务剩余期限约束，不能等默认30秒后接收成功', async t => {
  let responded = false;
  const mock = await server(t, async () => {
    await new Promise(resolve => setTimeout(resolve, 200));
    responded = true;
    return { sessionStatus: 'COMPLETED' };
  });
  await assert.rejects(waitForTerminal({ base: mock.base, timeoutMinutes: 0.001 }, 's'), /任务超时/);
  assert.equal(responded, false, '应在慢响应到达前中断，而不是收到后才拒绝');
});

for (const offset of [0, 1]) test(`响应在deadline${offset ? '之后' : '恰好'}返回COMPLETED仍拒绝`, async t => {
  let now = 10_000;
  t.mock.method(Date, 'now', () => now);
  const mock = await server(t, () => { now = 11_000 + offset; return { sessionStatus: 'COMPLETED' }; });
  await assert.rejects(waitForTerminal({ base: mock.base, timeoutMinutes: 1 / 60 }, 's'), /任务超时/);
});

test('deadline前1毫秒取得正常终态可以返回，不误伤临界成功', async t => {
  let now = 10_000;
  t.mock.method(Date, 'now', () => now);
  const mock = await server(t, () => { now = 10_999; return { sessionStatus: 'COMPLETED' }; });
  const result = await waitForTerminal({ base: mock.base, timeoutMinutes: 1 / 60 }, 's');
  assert.equal(result.sessionStatus, 'COMPLETED');
});

test('引导请求同样受deadline约束，过期确认不能算已覆盖场景', async t => {
  let now = 10_000;
  t.mock.method(Date, 'now', () => now);
  const mock = await server(t, path => {
    if (path.endsWith('/steer')) { now = 11_000; return {}; }
    return { sessionStatus: 'RUNNING', activeRunId: 'r' };
  });
  await assert.rejects(waitForTerminal({ base: mock.base, timeoutMinutes: 1 / 60 }, 's',
    { afterSeconds: 0, content: '新要求' }), /任务超时/);
  assert.equal(mock.requests.filter(r => r.path.endsWith('/diagnostics')).length, 1);
  assert.equal(mock.requests.filter(r => r.path.endsWith('/steer')).length, 1);
});


test('strict使用独立基线预检，合法基线可进入服务核对而重复身份提前拒绝', async t => {
  const { root } = await fixture(t);
  const baselinePath = join(root, 'baseline.jsonl'), tasksPath = join(root, 'tasks.json');
  const tasks = Array.from({ length: 20 }, (_, index) => ({ id: `t${index}` }));
  const records = tasks.flatMap(task => Array.from({ length: 3 }, (_, index) => ({
    schemaVersion: 2, taskId: task.id, sessionId: `${task.id}-session-${index}`, attempt: index + 1,
    model: 'configured', promptVersion: 'P0', toolVersion: 'T0', promptHash: 'prompt', runtimeHash: 'runtime',
    fixtureVersion: 'fixture', taskVersion: 'task', environmentVersion: 'environment', modelConfigHash: 'model',
    terminalStatus: 'COMPLETED', reportedComplete: true, success: true, acceptanceRan: true,
    acceptancePassed: true, scenarioCovered: true, costKnown: true, costUsd: 0.1, inputTokens: 100
  })));
  await writeFile(tasksPath, JSON.stringify({ tasks }));
  await writeFile(baselinePath, records.map(r => JSON.stringify(r)).join('\n') + '\n');
  const mock = await server(t, () => ({ model: 'configured' }));
  const argv = ['evaluation/harness/runner.mjs', '--strict', '--repeat', '3', '--baseline', baselinePath,
    '--tasks', tasksPath, '--base', mock.base, '--model', 'intentionally-different', '--out', join(root, 'out.jsonl')];
  await assert.rejects(promisify(execFile)(process.execPath, argv), /模型不匹配/);
  assert.equal(mock.requests.length, 1, '合法基线预检后才读取服务配置，未调用模型');
  const count = mock.requests.length;
  await writeFile(baselinePath, records.map(r => JSON.stringify({ ...r, sessionId: 'duplicated' })).join('\n') + '\n');
  await assert.rejects(promisify(execFile)(process.execPath, argv), /基线尚不满足严格对照条件/);
  assert.equal(mock.requests.length, count, '重复身份应在请求服务前拒绝');
});
