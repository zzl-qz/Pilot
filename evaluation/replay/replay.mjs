#!/usr/bin/env node
/**
 * 历史提交回放评测：把一个真实提交的父版本作为工作区，把该提交改过的测试当作隐藏用例。
 *
 * 子命令：
 *   list                            列出任务集
 *   verify --task <id>|--all        检查 A（父提交+测试必须失败）与 B（父提交+完整改动必须通过），不花 token
 *   setup  --task <id> --out <dir>  导出父提交为一个干净 git 仓库，供 Pilot 在其中完成任务
 *   accept --task <id> --workspace <dir>  还原隐藏用例并验收
 *
 * 设计要点见 evaluation/replay/README.md。
 */
import { spawnSync } from 'node:child_process';
import { cp, mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { existsSync, readdirSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const HERE = fileURLToPath(new URL('.', import.meta.url)).replace(/[\\/]$/, '');
const TASKS_FILE = join(HERE, 'tasks.json');
const WIN = process.platform === 'win32';

function git(repo, args, opts = {}) {
  const result = spawnSync('git', ['-C', repo, ...args], {
    encoding: opts.encoding ?? 'utf8',
    maxBuffer: 256 * 1024 * 1024
  });
  if (result.status !== 0) {
    const detail = (result.stderr || result.stdout || '').toString().slice(-2000);
    throw new Error(`git ${args.join(' ')} 失败：${detail}`);
  }
  return result.stdout;
}

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (token.startsWith('--')) {
      const key = token.slice(2);
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) out[key] = true;
      else { out[key] = next; i++; }
    } else out._.push(token);
  }
  return out;
}

async function loadTasks() {
  const doc = JSON.parse(await readFile(TASKS_FILE, 'utf8'));
  return doc;
}

function findTask(doc, id) {
  const task = doc.tasks.find(t => t.id === id);
  if (!task) throw new Error(`没有这个任务：${id}（共 ${doc.tasks.length} 个）`);
  return task;
}

function acceptanceCommand(task) {
  const tests = task.testClasses.join(',');
  // pilot-server 依赖 pilot-core，用 -am 从源码一起构建，避免本地仓库里的旧构件干扰。
  // -am 同时会跑 pilot-core 的 test 阶段，而 -Dtest 在该模块可能匹配不到用例，
  // 必须用 surefire.failIfNoSpecifiedTests=false 让没有匹配的模块跳过而不是失败。
  const also = task.module === 'pilot-core' ? [] : ['-am'];
  const args = ['-B', '-ntp', '-pl', task.module, ...also, `-Dtest=${tests}`,
    '-DfailIfNoTests=false', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'];
  return WIN
    ? { line: `mvnw.cmd ${args.join(' ')}`, display: `mvnw.cmd ${args.join(' ')}` }
    : { line: `sh mvnw ${args.join(' ')}`, display: `sh mvnw ${args.join(' ')}` };
}

function runAcceptance(cwd, task) {
  const cmd = acceptanceCommand(task);
  // tmpdir() 在 Windows 上可能返回 8.3 短路径（ZHUANG~1），Maven 在短路径下解析 -pl 会选不中模块。
  const workdir = realpathSync.native(cwd);
  const started = Date.now();
  if (process.env.REPLAY_DEBUG) {
    console.error(`[debug] cwd=${workdir}`);
    console.error(`[debug] line=${cmd.line}`);
    console.error(`[debug] pom=${existsSync(join(workdir, 'pom.xml'))} entries=${readdirSync(workdir).slice(0, 8).join(',')}`);
  }
  // shell: true 让 Windows 正确执行 .cmd，并避免 Node 二次加引号破坏 -Dtest=...
  const result = spawnSync(cmd.line, { cwd: workdir, shell: true, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  const output = `${result.stdout ?? ''}\n${result.stderr ?? ''}`;
  const summary = [...output.matchAll(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/g)].pop();
  const compileError = /COMPILATION ERROR/.test(output);
  return {
    ok: result.status === 0,
    exit: result.status,
    seconds: Math.round((Date.now() - started) / 100) / 10,
    compileError,
    tests: summary ? Number(summary[1]) : 0,
    failures: summary ? Number(summary[2]) : 0,
    errors: summary ? Number(summary[3]) : 0,
    tail: output.split('\n').slice(-25).join('\n')
  };
}

/** 从提交里取出该任务用例文件的当前内容，作为"隐藏用例"的来源 */
function hiddenTests(repo, task) {
  return task.testPaths.map(path => ({ path, content: git(repo, ['show', `${task.commit}:${path}`], { encoding: 'buffer' }) }));
}

async function cmdList(doc) {
  console.log(`任务集：${doc.tasks.length} 个（${doc.source?.repo ?? ''}）`);
  for (const task of doc.tasks) {
    const v = task.verified ?? {};
    console.log(
      `${task.id.padEnd(5)} ${task.tier}  ${String(task.srcAdded).padStart(3)}行 ${String(task.mainFiles)}文件  ` +
      `${task.module.padEnd(12)} A=${v.A ?? '?'} B=${v.B ?? '?'}  ${task.subject}`
    );
  }
}

async function cmdVerify(doc, args, repo) {
  const targets = args.all ? doc.tasks : [findTask(doc, args.task)];
  const results = [];
  for (const task of targets) {
    const parent = task.parent;
    // A：父提交 + 只放隐藏用例
    const dirA = await mkdtemp(join(tmpdir(), `replay-a-${task.id}-`));
    // B：父提交 + 参考解（提交的全部改动）
    const dirB = await mkdtemp(join(tmpdir(), `replay-b-${task.id}-`));
    try {
      for (const [dir, label] of [[dirA, 'A'], [dirB, 'B']]) {
        exportTree(repo, parent, dir);
        if (label === 'B') {
          // 参考解：把该提交改动的全部文件写进父版本
          const changed = git(repo, ['show', '--name-only', '--format=', task.commit]).split('\n').filter(Boolean);
          for (const path of changed) {
            const content = git(repo, ['show', `${task.commit}:${path}`], { encoding: 'buffer' });
            await mkdir(dirname(join(dir, path)), { recursive: true });
            await writeFile(join(dir, path), content);
          }
        }
      }
      for (const { path, content } of hiddenTests(repo, task)) {
        await mkdir(dirname(join(dirA, path)), { recursive: true });
        await writeFile(join(dirA, path), content);
      }
      const a = runAcceptance(dirA, task);
      const b = runAcceptance(dirB, task);
      const verdict = !a.ok && b.ok ? 'VALID' : 'REJECT';
      console.log(`${task.id} A=${a.ok ? '通了(不合格)' : '失败(合格)'} B=${b.ok ? '通过' : '失败(不合格)'} => ${verdict}`);
      if (verdict === 'REJECT') console.log(`  A:${a.exit} ${a.tests}/${a.failures}  B:${b.exit} ${b.tests}/${b.failures}\n${a.tail.slice(-600)}\n${b.tail.slice(-600)}`);
      results.push({ id: task.id, verdict, A: a.ok, B: b.ok });
    } finally {
      await rm(dirA, { recursive: true, force: true });
      await rm(dirB, { recursive: true, force: true });
    }
  }
  const valid = results.filter(r => r.verdict === 'VALID').length;
  console.log(`\n汇总：${valid}/${results.length} 合格`);
  if (valid !== results.length) process.exitCode = 1;
}

function exportTree(repo, sha, dir) {
  const archive = join(dir, '..', `replay-${process.pid}-${Date.now()}.tar`);
  git(repo, ['archive', '--format=tar', '-o', archive, sha]);
  const extract = spawnSync('tar', ['-xf', archive, '-C', dir], { encoding: 'utf8' });
  rm(archive, { force: true }).catch(() => {});
  if (extract.status !== 0) throw new Error(`解包失败：${extract.stderr}`);
}

async function cmdSetup(doc, args, repo) {
  const task = findTask(doc, args.task);
  const out = resolve(args.out ?? join(process.cwd(), `replay-${task.id}`));
  if (existsSync(out)) throw new Error(`目标目录已存在，先删除：${out}`);
  await mkdir(out, { recursive: true });
  exportTree(repo, task.parent, out);
  // 建成独立仓库：模型能正常用 git 工具，但看不到未来的提交
  const id = { GIT_AUTHOR_NAME: 'pilot-eval', GIT_AUTHOR_EMAIL: 'eval@local', GIT_COMMITTER_NAME: 'pilot-eval', GIT_COMMITTER_EMAIL: 'eval@local' };
  for (const args2 of [['init', '-q'], ['add', '-A'], ['-c', 'user.name=pilot-eval', '-c', 'user.email=eval@local', 'commit', '-qm', 'baseline']]) {
    const r = spawnSync('git', ['-C', out, ...args2], { encoding: 'utf8', env: { ...process.env, ...id } });
    if (r.status !== 0) throw new Error(`git ${args2.join(' ')} 失败：${r.stderr}`);
  }
  const promptPath = `${out}.task.md`;
  await writeFile(promptPath, taskPrompt(task), 'utf8');
  console.log(`工作区：${out}`);
  console.log(`任务说明：${promptPath}`);
  console.log(`\n--- 任务 ---\n${taskPrompt(task)}`);
}

function taskPrompt(task) {
  return [
    `在仓库中定位并修复这个问题：${task.subject}`,
    '',
    `约束：`,
    `1. 只改 ${task.module} 模块，不要修改 src/test 下的任何测试文件。`,
    `2. 不要删除或跳过测试；验收会还原原始测试后重新运行。`,
    `3. 改完请运行 ${task.testClasses.join('、')} 确认通过。`,
    ''
  ].join('\n');
}

async function cmdAccept(doc, args, repo) {
  const task = findTask(doc, args.task);
  const workspace = resolve(args.workspace ?? join(process.cwd(), `replay-${task.id}`));
  if (!existsSync(workspace)) throw new Error(`工作区不存在：${workspace}`);
  const candidate = await mkdtemp(join(tmpdir(), `replay-accept-${task.id}-`));
  try {
    await cp(workspace, candidate, {
      recursive: true,
      filter: source => !/(^|[\\/])(\.git|target|node_modules)([\\/]|$)/.test(source)
    });
    // 先删掉模型可能改过的测试，再写回提交里的原始版本
    for (const { path, content } of hiddenTests(repo, task)) {
      await rm(join(candidate, path), { force: true });
      await mkdir(dirname(join(candidate, path)), { recursive: true });
      await writeFile(join(candidate, path), content);
    }
    const result = runAcceptance(candidate, task);
    console.log(`任务 ${task.id}：${result.ok ? 'PASS' : 'FAIL'}（exit ${result.exit}，${result.tests} 个用例，失败 ${result.failures}，错误 ${result.errors}，${result.seconds}s）`);
    if (!result.ok) console.log(result.tail);
    if (args.keep) console.log(`保留候选目录：${candidate}`);
    process.exitCode = result.ok ? 0 : 1;
  } finally {
    if (!args.keep) await rm(candidate, { recursive: true, force: true });
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const command = args._[0];
  const repo = resolve(args.repo ?? process.cwd());
  const doc = await loadTasks();
  if (command === 'list') return cmdList(doc);
  if (command === 'verify') return cmdVerify(doc, args, repo);
  if (command === 'setup') return cmdSetup(doc, args, repo);
  if (command === 'accept') return cmdAccept(doc, args, repo);
  console.log('用法：replay.mjs <list|verify|setup|accept> [选项]');
  process.exitCode = 1;
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
