#!/usr/bin/env node
/**
 * 在真实回放工作区上跑 Pilot，并做外部验收。
 *
 * 每个任务：
 *   setup  父提交的独立仓库（只有一个 baseline 提交，已剥掉评测与文档目录）
 *   run    通过 Pilot HTTP API 建会话、发任务、轮询到终态
 *   accept 还原隐藏用例后运行该模块测试（调用 replay.mjs accept）
 *
 * 用法：
 *   node evaluation/replay/run-eval.mjs --batch b1 --tasks S-01,S-02 --parallel 2
 */
import { spawnSync } from 'node:child_process';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('.', import.meta.url)).replace(/[\\/]$/, '');
const repo = resolve(HERE, '../..');
const TERMINAL = new Set(['COMPLETED', 'INTERRUPTED', 'FAILED', 'CANCELLED', 'PAUSED', 'ARCHIVED']);

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const t = argv[i];
    if (t.startsWith('--')) {
      const k = t.slice(2);
      const n = argv[i + 1];
      if (n === undefined || n.startsWith('--')) out[k] = true;
      else { out[k] = n; i++; }
    } else out._.push(t);
  }
  return out;
}

async function api(base, path, init = {}, attempts = 1) {
  let lastError = null;
  for (let i = 0; i < attempts; i++) {
    try {
      const res = await fetch(`${base}${path}`, {
        signal: AbortSignal.timeout(60_000),
        ...init,
        headers: { 'Content-Type': 'application/json', ...(init.headers ?? {}) }
      });
      if (!res.ok) throw new Error(`${init.method ?? 'GET'} ${path} -> ${res.status}: ${(await res.text()).slice(0, 300)}`);
      const type = res.headers.get('content-type') ?? '';
      return type.includes('application/json') ? res.json() : res.text();
    } catch (error) {
      // 只有网络层失败才重试；HTTP 错误直接抛出，避免掩盖真实问题
      if (error.message.includes(' -> ')) throw error;
      lastError = error;
      if (i < attempts - 1) await new Promise(r => setTimeout(r, 2000 * (i + 1)));
    }
  }
  throw lastError;
}

function git(args, encoding = 'utf8') {
  const r = spawnSync('git', ['-C', repo, ...args], { encoding, maxBuffer: 256 * 1024 * 1024 });
  if (r.status !== 0) throw new Error(`git ${args.join(' ')} failed: ${(r.stderr || '').toString().slice(-800)}`);
  return r.stdout;
}

async function exportWorktree(task, dir) {
  await mkdir(dir, { recursive: true });
  const tar = `${dir}.tar`;
  git(['archive', '--format=tar', '-o', tar, task.parent]);
  const x = spawnSync('tar', ['-xf', tar, '-C', dir], { encoding: 'utf8' });
  await rm(tar, { force: true });
  if (x.status !== 0) throw new Error(`tar failed: ${x.stderr}`);
  // 工作区不能出现评测台账、任务卡等可能描述答案的材料
  for (const name of ['evaluation', 'docs']) {
    await rm(join(dir, name), { recursive: true, force: true });
  }
  const env = {
    ...process.env,
    GIT_AUTHOR_NAME: 'pilot-eval', GIT_AUTHOR_EMAIL: 'eval@local',
    GIT_COMMITTER_NAME: 'pilot-eval', GIT_COMMITTER_EMAIL: 'eval@local'
  };
  for (const a of [['init', '-q'], ['add', '-A'], ['-c', 'user.name=pilot-eval', '-c', 'user.email=eval@local', 'commit', '-qm', 'baseline']]) {
    const r = spawnSync('git', ['-C', dir, ...a], { encoding: 'utf8', env });
    if (r.status !== 0) throw new Error(`git ${a.join(' ')} failed: ${r.stderr}`);
  }
}

function taskPrompt(task) {
  const lines = [
    `在仓库中定位并修复这个问题：${task.subject}`,
    ''
  ];
  if (task.body) {
    lines.push('需求与现象：', task.body, '');
  }
  lines.push(
    '约束：',
    `1. 只改 ${task.module} 模块的实现代码，不要修改 src/test 下的任何测试文件。`,
    '2. 不要删除、跳过或弱化测试；验收会还原原始测试后重新运行。',
    '3. 不得访问当前工作区之外的路径，不得联网，不得克隆或查询远程仓库。',
    `4. 改完请运行 ${task.testClasses.join('、')} 确认通过。`,
    ''
  );
  return lines.join('\n');
}

async function runOne(ctx, task) {
  const { batchDir, baseUrl, timeoutMinutes, pollMs } = ctx;
  const ws = join(batchDir, task.id);
  const started = Date.now();
  const record = {
    id: task.id, tier: task.tier, subject: task.subject, commit: task.commit,
    module: task.module, srcAdded: task.srcAdded, mainFiles: task.mainFiles,
    classes: task.testClasses.join(','), sessionId: null, workspace: ws,
    status: null, failureCode: null, startedAt: new Date().toISOString()
  };
  try {
    if (existsSync(ws)) await rm(ws, { recursive: true, force: true });
    await exportWorktree(task, ws);
    const session = await api(baseUrl, '/api/sessions', {
      method: 'POST',
      body: JSON.stringify({ title: `replay:${task.id}`, workspace: ws })
    }, 3);
    record.sessionId = session.sessionId ?? session.id;
    await api(baseUrl, `/api/sessions/${record.sessionId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ content: taskPrompt(task) })
    }, 3);
    const deadline = Date.now() + timeoutMinutes * 60 * 1000;
    let last = null;
    while (Date.now() < deadline) {
      await new Promise(r => setTimeout(r, pollMs));
      last = await api(baseUrl, `/api/sessions/${record.sessionId}/diagnostics`, {}, 3);
      if (TERMINAL.has(last.sessionStatus)) {
        const active = await api(baseUrl, `/api/sessions/${record.sessionId}/runs/active`, {}, 3);
        if (Object.hasOwn(active, 'runId') && active.runId === null) break;
      }
    }
    record.diagnostics = last;
    record.status = last?.sessionStatus ?? 'TIMEOUT';
    record.failureCode = last?.failureCode ?? null;
  } catch (error) {
    record.status = 'ERROR';
    record.error = error.message;
  }
  record.runSeconds = Math.round((Date.now() - started) / 1000);

  const accept = spawnSync(process.execPath, [
    join(HERE, 'replay.mjs'), 'accept', '--task', task.id, '--workspace', ws, '--repo', repo
  ], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, env: process.env });
  record.acceptExit = accept.status;
  record.acceptOutput = `${accept.stdout ?? ''}${accept.stderr ?? ''}`.split('\n').slice(-12).join('\n');
  record.passed = record.status === 'COMPLETED' && accept.status === 0;
  record.falseCompletion = record.status === 'COMPLETED' && accept.status !== 0;
  record.finishedAt = new Date().toISOString();
  return record;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const batch = args.batch ?? `b-${Date.now()}`;
  const baseUrl = (args.base ?? 'http://127.0.0.1:9999').replace(/\/$/, '');
  const parallel = Number(args.parallel ?? 2);
  const timeoutMinutes = Number(args.timeout ?? 40);
  const pollMs = Number(args.poll ?? 5000);
  const doc = JSON.parse(await readFile(join(HERE, 'tasks.json'), 'utf8'));
  const wanted = args.tasks && args.tasks !== true ? String(args.tasks).split(',').map(s => s.trim()) : doc.tasks.map(t => t.id);
  const tasks = doc.tasks.filter(t => wanted.includes(t.id));
  if (tasks.length !== wanted.length) throw new Error(`unknown tasks: ${wanted.filter(id => !tasks.some(t => t.id === id)).join(',')}`);

  // 工作区放在主仓库之外：即便模型越界浏览目录，也看不到仓库的提交历史和评测材料
  const workRoot = resolve(args['work-root'] ?? 'E:\\Pilot-eval');
  const batchDir = join(workRoot, batch);
  const resultDir = join(repo, '.pilot-eval', 'replay-runs', batch);
  await mkdir(batchDir, { recursive: true });
  await mkdir(resultDir, { recursive: true });
  const jsonl = join(resultDir, 'results.jsonl');
  console.log(`批次 ${batch}: ${tasks.length} tasks, parallel ${parallel}, timeout ${timeoutMinutes} min`);
  console.log(`model: ${(await api(baseUrl, '/api/settings')).model ?? '?'}`);
  console.log(`workspaces: ${batchDir}`);

  const queue = [...tasks];
  const records = [];
  const workers = Array.from({ length: Math.min(parallel, tasks.length) }, async () => {
    for (;;) {
      const task = queue.shift();
      if (!task) return;
      console.log(`[start] ${task.id} ${task.subject}`);
      const record = await runOne({ batchDir, baseUrl, timeoutMinutes, pollMs }, task);
      records.push(record);
      await writeFile(jsonl, records.map(r => JSON.stringify(r)).join('\n') + '\n', 'utf8');
      console.log(`[done ] ${record.id} status=${record.status} accept=${record.acceptExit} passed=${record.passed} ${record.runSeconds}s`);
    }
  });
  await Promise.all(workers);

  const passed = records.filter(r => r.passed).length;
  const summary = {
    batch, generatedAt: new Date().toISOString(),
    model: (await api(baseUrl, '/api/settings')).model ?? null,
    tasks: records.length, passed,
    passAt1: records.length ? Math.round((passed / records.length) * 1000) / 10 : null,
    falseCompletions: records.filter(r => r.falseCompletion).length,
    totalRunSeconds: records.reduce((a, r) => a + (r.runSeconds ?? 0), 0),
    runs: records.map(r => ({
      id: r.id, tier: r.tier, status: r.status, passed: r.passed,
      failureCode: r.failureCode, runSeconds: r.runSeconds, sessionId: r.sessionId,
      requests: r.diagnostics?.modelRequests ?? null,
      inputTokens: r.diagnostics?.inputTokens ?? null,
      cachedInputTokens: r.diagnostics?.cachedInputTokens ?? null,
      outputTokens: r.diagnostics?.outputTokens ?? null
    }))
  };
  await writeFile(join(resultDir, 'summary.json'), JSON.stringify(summary, null, 2), 'utf8');
  console.log(`\n完成: ${passed}/${records.length} 通过 (${summary.passAt1}%), 结果: ${resultDir}`);
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
