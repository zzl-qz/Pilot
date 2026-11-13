import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { cp, mkdtemp, mkdir, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { assessChangeScope, resumePlan, createRunContext, dryRunRecords, expandRuns, faultConfigPatch, faultControl, faultScenarioAt, hashSources, parseArgs, prepareWorkspace, recordMetadata, runCommand, runTask, selectTasks, serverIdentity, traceMetrics, waitForTerminal } from './runner.mjs';
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
      let result = await handle(req.url, body, req.headers);
      // Older test fixtures describe session state; materialize the active-run endpoint explicitly.
      if (req.url.endsWith('/runs/active') && !Object.hasOwn(result ?? {}, 'runId')) {
        result = { runId: result?.sessionStatus === 'RUNNING' ? result.activeRunId ?? null : null };
      }
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
  assert.deepEqual(await traceMetrics({ base: mock.base }, 's'), { traceObserved: true, toolCallCount: 2, modelRetryCount: 1, budgetStopObserved: false, changeRecordObserved: false, changedFiles: [] });
  const missing = await server(t, () => ({}));
  assert.deepEqual(await traceMetrics({ base: missing.base }, 's'), { traceObserved: false, toolCallCount: null, modelRetryCount: null, budgetStopObserved: null, changeRecordObserved: false, changedFiles: null });
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
  const paths = [];
  t.mock.method(globalThis, 'fetch', async url => {
    now = 10_999; paths.push(String(url));
    return new Response(JSON.stringify(String(url).endsWith('/runs/active') ? { runId: null } : { sessionStatus: 'COMPLETED' }), { headers: { 'content-type': 'application/json' } });
  });
  const result = await waitForTerminal({ base: 'http://localhost', timeoutMinutes: 1 / 60 }, 's');
  assert.equal(paths.length, 2);
  assert.equal(result.sessionStatus, 'COMPLETED');
});

test('宿主长时间停调度后拒绝结果，并保留独立中断标记', async t => {
  let now = 10_000;
  t.mock.method(Date, 'now', () => now);
  const mock = await server(t, () => { now += 90_000; return { sessionStatus: 'COMPLETED' }; });
  await assert.rejects(waitForTerminal({ base: mock.base, timeoutMinutes: 15 }, 's'), error => {
    assert.equal(error.evaluationInterrupted, true);
    assert.match(error.message, /未调度/);
    return true;
  });
});

test('执行异常保留会话身份，取消未确认时也不能丢失关联', async t => {
  const { args, task } = await fixture(t);
  const mock = await server(t, path => {
    if (path === '/api/sessions') return { sessionId: 'own' };
    if (path.endsWith('/cancel')) throw new Error('connection lost');
    return { sessionStatus: 'RUNNING', activeRunId: 'own-run' };
  });
  await assert.rejects(runTask({ ...args, base: mock.base, timeoutMinutes: 0.001 }, task), error => {
    assert.equal(error.sessionId, 'own');
    assert.equal(error.runId, 'own-run');
    assert.match(error.message, /取消状态未确认/);
    return true;
  });
});

test('一次基础设施异常即停止批量派发，写入失败身份和中断摘要', async t => {
  const { root, args, task } = await fixture(t);
  const mock = await server(t, path => {
    if (path === '/api/settings') return { model: 'fixture-model' };
    if (path === '/api/sessions') return { sessionId: 'own' };
    return { sessionStatus: 'RUNNING', activeRunId: 'own-run' };
  });
  const tasks = join(root, 'tasks.json'), out = join(root, 'results.jsonl');
  await writeFile(tasks, JSON.stringify({ tasks: [{ ...task, fixture: 'bug' }, { ...task, id: 'next', fixture: 'bug' }] }));
  await assert.rejects(promisify(execFile)(process.execPath, ['evaluation/harness/runner.mjs', '--base', mock.base,
    '--tasks', tasks, '--fixtures', args.fixtures, '--out', out, '--timeout-minutes', '0.001']), { code: 1 });
  const rows = (await readFile(out, 'utf8')).trim().split('\n').map(JSON.parse);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].sessionId, 'own');
  assert.equal(rows[0].runId, 'own-run');
  assert.equal(rows[0].cancellationRequested, true);
  assert.equal(rows[0].evaluationError, true);
  assert.equal(mock.requests.filter(request => request.path === '/api/sessions').length, 1);
  assert.equal(JSON.parse(await readFile(out.replace('.jsonl', '.summary.json'), 'utf8')).stoppedEarly, true);
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

function resumeFixture() {
  const tasks = [{ id: 'a' }, { id: 'b' }];
  const data = { schemaVersion: 2, model: 'model', modelConfigHash: 'model-hash', promptHash: 'prompt-hash',
    runtimeHash: 'runtime-hash', environmentVersion: 'env', fixtureVersion: 'fixture', taskVersion: 'task',
    promptVersion: 'p1', toolVersion: 't1' };
  const metadata = new Map(tasks.map(t => [t.id, data]));
  return { tasks, data, metadata };
}

test('故障注入参数校验与轮换方案可由 seed/位置/attempt 复算', () => {
  assert.throws(() => parseArgs(['--fault-proxy', 'http://127.0.0.1:18086', '--fault-profile', 'boom']), /未知故障模式/);
  assert.throws(() => parseArgs(['--fault-proxy', 'http://127.0.0.1:18086']), /需要 --fault-profile/);
  assert.throws(() => parseArgs(['--fault-proxy', 'http://127.0.0.1:18086', '--fault-profile', 'stall', '--dry-run']), /dry-run/);
  assert.throws(() => parseArgs(['--fault-profile', 'stall']), /配合 --fault-proxy/);
  assert.throws(() => parseArgs(['--fault-proxy', 'http://127.0.0.1:18086', '--fault-profile', 'stall', '--fault-every', '0']), /fault-every/);
  assert.throws(() => parseArgs(['--fault-proxy', 'http://127.0.0.1:18086', '--fault-profile', 'stall', '--fault-stall-ms', '0']), /fault-stall-ms/);
  assert.throws(() => parseArgs(['--fault-proxy', 'http://127.0.0.1:18086', '--fault-profile', 'stall', '--fault-max-requests', '-1']), /fault-max-requests/);
  const args = parseArgs(['--fault-proxy', 'http://127.0.0.1:18086', '--fault-profile', 'mid-eof,stall', '--seed', '1']);
  assert.equal(args.faultProfile, 'mid-eof,stall');
  assert.equal(faultScenarioAt(args, 0, 1).scenario, 'stall');
  assert.equal(faultScenarioAt(args, 0, 2).scenario, 'mid-eof');
  assert.equal(faultScenarioAt(args, 1, 1).scenario, 'mid-eof');
  const repeat3 = parseArgs(['--fault-proxy', 'http://127.0.0.1:18086', '--fault-profile', 'mid-eof,stall,reset', '--seed', '2', '--repeat', '3']);
  // 同一任务的连续 attempt 索引 +1，轮遍不同 profile，不会三次都撞同一个故障。
  const first = faultScenarioAt(repeat3, 0, 1), second = faultScenarioAt(repeat3, 0, 2), third = faultScenarioAt(repeat3, 0, 3);
  assert.equal(second.rotationIndex - first.rotationIndex, 1);
  assert.equal(third.rotationIndex - second.rotationIndex, 1);
  assert.equal(new Set([first.scenario, second.scenario, third.scenario]).size, 3);
  // 8 任务 × 3 尝试 × 2 profile：全计划索引连续且无重复，每个 profile 恰好 12 次。
  const stats = new Map();
  const indexes = new Set();
  for (let position = 0; position < 8; position++) for (let attempt = 1; attempt <= 3; attempt++) {
    const rotation = faultScenarioAt(repeat3, position, attempt);
    indexes.add(rotation.rotationIndex);
    stats.set(rotation.scenario, (stats.get(rotation.scenario) ?? 0) + 1);
  }
  assert.equal(indexes.size, 24);
  assert.deepEqual([...stats.entries()].sort(), [['mid-eof', 8], ['reset', 8], ['stall', 8]]);
});

test('续跑拒绝故障方案不一致的记录；未启用代理时带故障字段的记录同样拒绝', () => {
  const { tasks, data, metadata } = resumeFixture();
  const fault = { faultProfiles: ['mid-eof', 'stall'], faultSeed: 1, repeat: 2, faultProfile: 'mid-eof,stall', faultEvery: 3 };
  const a1 = { ...data, taskId: 'a', attempt: 1, success: false, evaluationError: true,
    faultScenario: 'stall', faultProfile: 'mid-eof,stall', faultEvery: 3 }; // (1+0*2+0)%2=1 → stall
  const b1 = { ...data, taskId: 'b', attempt: 1, success: true, sessionId: 's1',
    faultScenario: 'stall', faultProfile: 'mid-eof,stall', faultEvery: 3 }; // (1+1*2+0)%2=1 → stall
  assert.deepEqual(resumePlan([a1, b1], tasks, 2, metadata, fault).map(r => [r.task.id, r.attempt]), [['a', 2], ['b', 2]]);
  assert.throws(() => resumePlan([{ ...a1, faultScenario: 'mid-eof' }], tasks, 2, metadata, fault), /故障注入方案/);
  assert.throws(() => resumePlan([{ ...a1, faultEvery: 9 }], tasks, 2, metadata, fault), /故障注入方案/);
  assert.throws(() => resumePlan([a1], tasks, 2, metadata), /故障注入信息/);
});

test('故障注入：记录代理观测；未注入则场景未覆盖不算成功', async t => {
  const { args, task } = await fixture(t);
  let workspace;
  const mock = await server(t, async (path, body) => {
    if (path === '/api/sessions') { workspace = body.workspace; return { sessionId: 'own' }; }
    if (path.endsWith('/messages')) await writeFile(join(workspace, 'src.cjs'), 'module.exports = 2;');
    return { sessionStatus: 'COMPLETED', usageExact: true, inputTokens: 10 };
  });
  let injected = 0;
  const fault = await server(t, path => {
    if (path === '/fault/state') return { requests: 5, injectedRequests: injected,
      faultsByMode: injected ? { 'mid-eof': injected } : {}, stopped: false,
      usage: { observedRequests: 4, requestsWithoutUsage: 1, inputTokens: 100, outputTokens: 20 }, spendEstimate: 123.4 };
    throw new Error('fault proxy: unexpected path ' + path);
  });
  const run = () => runTask({ ...args, base: mock.base, faultProxyUrl: fault.base, faultScenario: 'mid-eof',
    faultProfile: 'mid-eof', faultEvery: 3, faultRotationIndex: 2, faultWindowStart: Date.now() - 5 }, task);
  injected = 1;
  const covered = await run();
  assert.equal(covered.success, true);
  assert.equal(covered.scenarioCovered, true);
  assert.equal(covered.faultScenario, 'mid-eof');
  assert.equal(covered.faultRequestCount, 5);
  assert.equal(covered.faultInjectedCount, 1);
  assert.deepEqual(covered.faultInjectionModes, { 'mid-eof': 1 });
  assert.equal(covered.faultProxyStopped, false);
  assert.equal(covered.faultSpendEstimateUnits, 123.4);
  assert.ok(covered.faultRunDurationSeconds >= 0.005, '记录故障窗口耗时');
  injected = 0;
  const uncovered = await run();
  assert.equal(uncovered.scenarioCovered, false);
  assert.equal(uncovered.success, false);
  assert.match(uncovered.notes, /没有任何故障实际注入/);
});

test('续跑只补缺失轮次，已记录的失败和基础设施异常不被覆盖', () => {
  const { tasks, data, metadata } = resumeFixture();
  const records = [{ ...data, taskId: 'a', attempt: 1, success: false, evaluationError: true },
    { ...data, taskId: 'b', attempt: 1, success: true, sessionId: 's1' }];
  assert.deepEqual(resumePlan(records, tasks, 2, metadata).map(r => [r.task.id, r.attempt]), [['a', 2], ['b', 2]]);
});

test('续跑拒绝版本混合、重复轮次、重复会话、dry-run 和越界计划', () => {
  const { tasks, data, metadata } = resumeFixture();
  const record = { ...data, taskId: 'a', attempt: 1, success: false, sessionId: 's1' };
  for (const mutation of [{ runtimeHash: 'new' }, { fixtureVersion: 'new' }, { modelConfigHash: 'new' },
    { taskVersion: 'new' }, { promptHash: 'new' }, { dryRun: true }, { attempt: 0 }, { attempt: 3 }, { taskId: 'c' }])
    assert.throws(() => resumePlan([{ ...record, ...mutation }], tasks, 2, metadata));
  assert.throws(() => resumePlan([record, record], tasks, 2, metadata));
  assert.throws(() => resumePlan([record, { ...record, taskId: 'b' }], tasks, 2, metadata));
  assert.throws(() => parseArgs(['--resume', '--dry-run']));
});

test('真实执行入口续跑仅补空位，完整结果不重复调用且拒绝未结算和损坏尾行', async t => {
  const { root, args, task } = await fixture(t);
  let workspace, sessions = 0;
  const mock = await server(t, async (path, body) => {
    if (path === '/api/settings') return { model: 'fixture-model', permissionMode: 'AUTO' };
    if (path === '/api/sessions') { workspace = body.workspace; return { sessionId: `s-${++sessions}` }; }
    if (path.endsWith('/messages')) await writeFile(join(workspace, 'src.cjs'), 'module.exports = 2;');
    return { sessionStatus: 'COMPLETED', usageExact: true, inputTokens: 10, outputTokens: 2 };
  });
  const tasks = join(root, 'tasks.json'), out = join(root, 'results.jsonl');
  await writeFile(tasks, JSON.stringify({ tasks: [task] }));
  const cli = ['evaluation/harness/runner.mjs', '--base', mock.base, '--tasks', tasks, '--fixtures', args.fixtures,
    '--out', out, '--prompt-version', 'p1', '--tool-version', 't1'];
  const run = extra => promisify(execFile)(process.execPath, [...cli, ...extra]);
  await run(['--repeat', '1']); assert.equal(sessions, 1);
  await run(['--repeat', '2', '--resume']); assert.equal(sessions, 2);
  await run(['--repeat', '2', '--resume']); assert.equal(sessions, 2);
  const contents = await readFile(out, 'utf8');
  assert.deepEqual(contents.trim().split('\n').map(line => JSON.parse(line).attempt), [1, 2]);
  await writeFile(`${out}.inflight.json`, JSON.stringify({ taskId: 'bug', attempt: 3 }));
  await assert.rejects(run(['--repeat', '3', '--resume']), /未结算尝试/); assert.equal(sessions, 2);
  await rm(`${out}.inflight.json`);
  await writeFile(out, contents.trimEnd());
  await assert.rejects(run(['--repeat', '3', '--resume']), /末行不完整/); assert.equal(sessions, 2);
  await writeFile(out, contents);
  await writeFile(`${out}.lock`, '{}');
  await assert.rejects(run(['--repeat', '3', '--resume']), /已锁定/); assert.equal(sessions, 2);
});

for (const requests of [8, 3, null]) test(`长链路按服务真实 modelRequests 字段核验覆盖：${requests}`, async t => {
  const { args, task } = await fixture(t); let workspace;
  const mock = await server(t, async (path, body) => {
    if (path === '/api/sessions') { workspace = body.workspace; return { sessionId: 'own' }; }
    if (path.endsWith('/messages')) await writeFile(join(workspace, 'src.cjs'), 'module.exports = 2;');
    return { sessionStatus: 'COMPLETED', modelRequests: requests, modelRequestCount: 999 };
  });
  const result = await runTask({ ...args, base: mock.base }, { ...task, scenario: 'context-growth', scenarioMinModelRequests: 8 });
  assert.equal(result.acceptancePassed, true);
  assert.equal(result.scenarioCovered, requests === 8);
  assert.equal(result.success, requests === 8);
});

for (const code of [null, 'MODEL_ERROR', 'BUDGET_LIMITED']) test(`剩余台账不能代替实际预算停止事件：${code}`, async t => {
  const { args, task } = await fixture(t); let workspace;
  const mock = await server(t, async (path, body) => {
    if (path === '/api/sessions') { workspace = body.workspace; return { sessionId: 'own' }; }
    if (path.endsWith('/messages')) {
      await writeFile(join(workspace, 'src.cjs'), 'module.exports = 2;');
      await mkdir(join(workspace, 'src'));
      await writeFile(join(workspace, 'src', 'progress.json'), JSON.stringify({ done: ['01'], remaining: ['02'] }));
    }
    if (path.includes('/history')) return { hasMore: false, events: code ? [{ sequence: 1, type: 'run.failed', payload: { code } }] : [] };
    return { sessionStatus: code ? 'FAILED' : 'COMPLETED' };
  });
  const result = await runTask({ ...args, base: mock.base }, { ...task, scenario: 'budget-wind' });
  assert.equal(result.acceptancePassed, true);
  assert.equal(result.scenarioCovered, code === 'BUDGET_LIMITED');
  assert.equal(result.success, false, '部分完成与预算保护通过不能冒充整个任务完成');
});

test('场景覆盖门槛变化必须改变任务指纹，拒绝与旧口径混合', async t => {
  const { args, task } = await fixture(t);
  const original = await recordMetadata(args, task);
  const growth = await recordMetadata(args, { ...task, scenario: 'context-growth', scenarioMinModelRequests: 8 });
  const changed = await recordMetadata(args, { ...task, scenario: 'context-growth', scenarioMinModelRequests: 9 });
  assert.notEqual(original.taskVersion, growth.taskVersion);
  assert.notEqual(growth.taskVersion, changed.taskVersion);
  const scope = await recordMetadata(args, { ...task, allowedChangeRoots: ['src', 'test'] });
  assert.notEqual(original.taskVersion, scope.taskVersion);
});

test('过程变更轨迹分页合并，删除临时文件不能抹掉越界记录', async t => {
  const mock = await server(t, path => path.includes('after=0') ? { hasMore: true, events: [
    { sequence: 1, type: 'run.completed', payload: { changedFiles: ['src/csv.js', '.tmp_check.js'] } }
  ] } : { hasMore: false, events: [
    { sequence: 2, type: 'run.completed', payload: { changedFiles: ['src/csv.js'] } }
  ] });
  const trace = await traceMetrics({ base: mock.base }, 's');
  assert.deepEqual(trace.changedFiles, ['.tmp_check.js', 'src/csv.js']);
  assert.equal(trace.changeRecordObserved, true);
  const scope = assessChangeScope({ allowedChangeRoots: ['src', 'test'] }, trace);
  assert.equal(scope.changeScopePassed, false);
  assert.deepEqual(scope.changeScopeViolations, ['.tmp_check.js']);
});

test('变更范围按路径边界判断，支持 Windows 分隔符并拒绝穿越和绝对路径', () => {
  const task = { allowedChangeRoots: ['src', 'test'] };
  const trace = paths => ({ traceObserved: true, changeRecordObserved: true, changedFiles: paths });
  assert.equal(assessChangeScope(task, trace(['src/a.js', 'test\\a.test.js'])).changeScopePassed, true);
  const invalid = ['src-other/a.js', 'src/../../outside', '/src/a.js', 'C:\\src\\a.js'];
  assert.deepEqual(assessChangeScope(task, trace(invalid)).changeScopeViolations, invalid);
  for (const observed of [false, true]) assert.equal(assessChangeScope(task,
    { traceObserved: observed, changeRecordObserved: false, changedFiles: [] }).changeScopePassed, false);
  for (const roots of [[], ['../outside'], ['.'], ['/src'], ['src/../test'], 'src']) {
    assert.throws(() => assessChangeScope({ allowedChangeRoots: roots }, trace([])), /allowedChangeRoots/);
  }
});

test('最终产物验收通过也不能覆盖已发生的路径约束违规', async t => {
  const { args, task } = await fixture(t); let workspace;
  const mock = await server(t, async (path, body) => {
    if (path === '/api/sessions') { workspace = body.workspace; return { sessionId: 'own' }; }
    if (path.endsWith('/messages')) {
      assert.match(body.content, /全过程文件变更范围/);
      assert.match(body.content, /src.cjs/);
      await writeFile(join(workspace, 'src.cjs'), 'module.exports = 2;');
    }
    if (path.includes('/history')) return { hasMore: false, events: [
      { sequence: 1, type: 'run.completed', payload: { changedFiles: ['src.cjs', '.tmp_check.js'] } }
    ] };
    return { sessionStatus: 'COMPLETED' };
  });
  const result = await runTask({ ...args, base: mock.base }, { ...task, allowedChangeRoots: ['src.cjs'] });
  assert.equal(result.acceptancePassed, true);
  assert.equal(result.changeScopePassed, false);
  assert.equal(result.success, false);
});


test('故障控制请求使用独立凭据，不误用 Pilot 服务令牌', async t => {
  const mock = await server(t, (_path, _body, headers) => {
    assert.equal(headers.authorization, 'Bearer fixture-fault-token');
    return { requests: 0 };
  });
  assert.deepEqual(await faultControl({ faultProxyUrl: mock.base, token: 'pilot-token', faultToken: 'fixture-fault-token' }, '/fault/state'), { requests: 0 });
});


test('未提供请求限额时保留代理预设上限，不默认为零解除保护', () => {
  const args = parseArgs(['--fault-proxy', 'http://localhost', '--fault-profile', 'stall']);
  assert.equal(args.faultMaxRequests, null);
  assert.equal('maxRequests' in faultConfigPatch(args, 'stall'), false);
  assert.equal(faultConfigPatch({ ...args, faultMaxRequests: 12 }, 'stall').maxRequests, 12);
});

test('故障停顿时间或请求限额变化必须改变方案指纹，凭据不进入指纹', async t => {
  const { args, task } = await fixture(t);
  const fault = { ...args, faultProxyUrl: 'http://localhost', faultProfile: 'stall', faultEvery: 3,
    faultStallMs: 100, faultMaxRequests: 12, faultSeed: 0, faultToken: 'secret-a' };
  const a = await recordMetadata(fault, task);
  assert.notEqual(a.faultPlanVersion, (await recordMetadata({ ...fault, faultStallMs: 200 }, task)).faultPlanVersion);
  assert.notEqual(a.faultPlanVersion, (await recordMetadata({ ...fault, faultMaxRequests: 13 }, task)).faultPlanVersion);
  assert.equal(a.faultPlanVersion, (await recordMetadata({ ...fault, faultToken: 'secret-b' }, task)).faultPlanVersion);
  assert.ok(!JSON.stringify(a).includes('secret'));
});


test('终态与自动续跑重叠时等待活动运行释放后才验收', async t => {
  let polls = 0;
  const mock = await server(t, path => {
    if (path.endsWith('/diagnostics')) return { sessionStatus: ++polls < 3 ? 'FAILED' : 'COMPLETED', activeRunId: polls < 2 ? 'old' : 'continued' };
    if (path.endsWith('/runs/active')) return { runId: polls < 3 ? 'continued' : null };
    return {};
  });
  const result = await waitForTerminal({ base: mock.base, timeoutMinutes: 1, pollMs: 1 }, 's');
  assert.equal(result.sessionStatus, 'COMPLETED');
  assert.equal(polls, 3);
});

test('异常清理取消实际活动续跑而不是诊断中的旧运行', async t => {
  const { args, task } = await fixture(t);
  let cancelled = false;
  const mock = await server(t, path => {
    if (path === '/api/sessions') return { sessionId: 'own' };
    if (path.endsWith('/runs/active')) return { runId: cancelled ? null : 'continued' };
    if (path === '/api/runs/continued/cancel') { cancelled = true; return {}; }
    return { sessionStatus: 'FAILED', activeRunId: 'old' };
  });
  await assert.rejects(runTask({ ...args, base: mock.base, timeoutMinutes: 0.001 }, task), /任务超时/);
  assert.equal(cancelled, true);
  assert.equal(mock.requests.some(r => r.path === '/api/runs/old/cancel'), false);
});


test('缺少活动运行字段不能把终态当成已结束', async t => {
  const mock = await server(t, path => path.endsWith('/runs/active') ? { runId: undefined } : { sessionStatus: 'COMPLETED' });
  await assert.rejects(waitForTerminal({ base: mock.base, timeoutMinutes: 1 }, 's'), /不能确认终态/);
});
