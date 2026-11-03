#!/usr/bin/env node
/**
 * 真实模型评估的执行器。
 *
 * 用法（需要本机已启动 Pilot，并使用真实模型端点）：
 *   node evaluation/harness/runner.mjs \
 *     --base http://127.0.0.1:8787 \
 *     --out evaluation/harness/runs/$(date +%Y%m%d-%H%M).jsonl \
 *     --model gpt-5-codex --prompt-version P12 --tool-version T7 \
 *     --fixtures evaluation/harness
 *
 * 每个任务：复制初始工作区 → 建会话 → 发提示词 → 轮询诊断直到终态 →
 * 在**工作区之外**用验收命令独立验证 → 写一条记录。
 *
 * 关键约束：
 * - 验收命令必须真的执行（acceptanceRan）。模型说「测试通过」不构成成功证据。
 * - 工作区按任务复制，避免上一个任务的产物污染下一个任务。
 * - 记录模型、提示词版本、工具版本，才能做同任务集的横向比较。
 *
 * 没有真实端点时可以 `--dry-run`：只验证聚合与产物格式，不产生能力结论。
 */
import { spawn } from "node:child_process";
import { cp, mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { aggregate, compare, gate, summarize } from "./metrics.mjs";

const TERMINAL = new Set(["COMPLETED", "INTERRUPTED", "FAILED", "CANCELLED", "PAUSED", "ARCHIVED"]);

export function parseArgs(argv) {
  const args = {
    base: "http://127.0.0.1:8787",
    out: `evaluation/harness/runs/${new Date().toISOString().replace(/[:.]/g, "-")}.jsonl`,
    tasks: "evaluation/harness/tasks.json",
    model: "unknown",
    promptVersion: "unversioned",
    toolVersion: "unversioned",
    fixtures: "",
    token: process.env.PILOT_SECURITY_TOKEN ?? "",
    timeoutMinutes: 30,
    dryRun: false,
    baseline: "",
    strict: false,
    verifyFixtures: false
  };
  const valued = new Set(['--base', '--out', '--tasks', '--model', '--prompt-version', '--tool-version',
    '--fixtures', '--token', '--baseline', '--timeout-minutes']);
  for (let index = 0; index < argv.length; index++) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (valued.has(flag) && (!value || value.startsWith('--'))) throw new Error(`${flag} 缺少参数值`);
    switch (flag) {
      case "--base": args.base = value; index++; break;
      case "--out": args.out = value; index++; break;
      case "--tasks": args.tasks = value; index++; break;
      case "--model": args.model = value; index++; break;
      case "--prompt-version": args.promptVersion = value; index++; break;
      case "--tool-version": args.toolVersion = value; index++; break;
      case "--fixtures": args.fixtures = value; index++; break;
      case "--token": args.token = value; index++; break;
      case "--baseline": args.baseline = value; index++; break;
      case "--timeout-minutes": args.timeoutMinutes = Number(value); index++; break;
      case "--dry-run": args.dryRun = true; break;
      case "--strict": args.strict = true; break;
      case "--verify-fixtures": args.verifyFixtures = true; break;
      case "--help":
        console.log(helpText());
        process.exit(0);
        break;
      default:
        if (flag.startsWith("--")) throw new Error(`未知参数: ${flag}`);
    }
  }
  if (!Number.isFinite(args.timeoutMinutes) || args.timeoutMinutes <= 0) throw new Error("超时必须为正数");
  if (args.strict && (!args.baseline || args.dryRun)) throw new Error("--strict 需要真实运行和 --baseline，不能使用 dry-run");
  return args;
}

function helpText() {
  return [
    "runner.mjs --base <url> --out <jsonl> [--model M] [--prompt-version P] [--tool-version T]",
    "           [--tasks tasks.json] [--fixtures dir] [--token T] [--timeout-minutes N]",
    "           [--baseline 上次的 jsonl] [--dry-run] [--strict]",
    "",
    "  --baseline  与上次记录比较，并给出优化门禁结论。",
    "  --strict    门禁不是 pass 就以非零退出码结束（用于 CI 拦住会造成能力退化的「优化」）。",
    "  --dry-run   只验证流程与产物格式，不产生能力结论。",
    "  --verify-fixtures  只检查每个 fixture 在「未修复」状态下验收必须失败（有分辨力）。"
  ].join("\n");
}

function headers(token) {
  const base = { "Content-Type": "application/json" };
  return token ? { ...base, Authorization: `Bearer ${token}` } : base;
}

async function api(args, path, init = {}) {
  const response = await fetch(`${args.base}${path}`, {
    signal: AbortSignal.timeout(args.requestTimeoutMs ?? 30_000),
    ...init,
    headers: { ...headers(args.token), ...(init.headers ?? {}) }
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`${init.method ?? "GET"} ${path} → ${response.status}: ${text.slice(0, 300)}`);
  }
  const type = response.headers.get("content-type") ?? "";
  return type.includes("application/json") ? response.json() : response.text();
}

export function runCommand(command, cwd, timeoutMs) {
  return new Promise((resolvePromise) => {
    const started = Date.now();
    const env = { ...process.env, CI: "true", PILOT_EVAL: "1" };
    // node:test 的内部标记会令子进程跳过测试发现，制造退出码 0 的假通过。
    delete env.NODE_TEST_CONTEXT;
    const child = spawn(command, {
      cwd,
      shell: true,
      detached: process.platform !== "win32",
      env,
      stdio: ["ignore", "pipe", "pipe"]
    });
    let output = "";
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      if (process.platform === "win32") {
        const killer = spawn("taskkill", ["/pid", String(child.pid), "/T", "/F"], { stdio: "ignore" });
        killer.on("error", () => child.kill());
      } else {
        try { process.kill(-child.pid, "SIGKILL"); } catch { child.kill("SIGKILL"); }
      }
    }, timeoutMs);
    const capture = chunk => { output = (output + chunk.toString()).slice(-64 * 1024); };
    child.stdout.on("data", capture);
    child.stderr.on("data", capture);
    child.on("error", (error) => {
      clearTimeout(timer);
      resolvePromise({ exitCode: -1, output: String(error), seconds: 0 });
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      resolvePromise({ exitCode: timedOut ? -1 : code ?? -1, timedOut, output, seconds: (Date.now() - started) / 1000 });
    });
  });
}

export async function acceptanceResult(task, workspace) {
  if (!workspace) {
    return { ran: false, ok: false, detail: "没有 fixture 工作区，未执行验收" };
  }
  const details = [];
  for (const command of task.acceptance ?? []) {
    const result = await runCommand(command, workspace, 20 * 60 * 1000);
    details.push(`${command} → exit ${result.exitCode}（${result.seconds.toFixed(1)}s）`);
    if (result.exitCode !== 0) {
      return { ran: true, ok: false, detail: `${details.join("；")}\n${result.output.slice(-1500)}` };
    }
  }
  return { ran: true, ok: details.length > 0, detail: details.join("；") };
}

export async function waitForTerminal(args, sessionId, steer) {
  const started = Date.now();
  const deadline = started + args.timeoutMinutes * 60 * 1000;
  let last = null;
  let steered = false;
  while (Date.now() < deadline) {
    last = await api(args, `/api/sessions/${sessionId}/diagnostics`);
    if (TERMINAL.has(last.sessionStatus)) return last;
    // 引导必须进入当前运行；追加 messages 会排队成为下一项任务。
    if (steer && !steered && last.activeRunId && (Date.now() - started) / 1000 >= steer.afterSeconds) {
      await api(args, `/api/sessions/${sessionId}/steer`, {
        method: "POST",
        body: JSON.stringify({ content: steer.content, runId: last.activeRunId })
      });
      steered = true;
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, Math.min(args.pollMs ?? 3000, Math.max(0, deadline - Date.now()))));
  }
  throw new Error(`任务超时，未达到终态（${last?.sessionStatus ?? "未知"}），不能执行验收`);
}

export async function prepareWorkspace(args, task) {
  const source = resolve(args.fixtures || dirname(args.tasks), task.fixture ?? task.id);
  if (!(await stat(source).catch(() => null))?.isDirectory()) throw new Error(`fixture 不存在：${source}`);
  const root = resolve(args.workRoot ?? ".pilot-eval");
  await mkdir(root, { recursive: true });
  const target = await mkdtemp(join(root, "run-"));
  await cp(source, target, { recursive: true });
  return target;
}

async function snapshotTests(directory, prefix = "") {
  const files = [];
  for (const item of await readdir(directory, { withFileTypes: true })) {
    const name = join(prefix, item.name);
    if (item.isDirectory()) files.push(...await snapshotTests(join(directory, item.name), name));
    else if (item.isFile()) files.push([name, await readFile(join(directory, item.name))]);
    else throw new Error(`验收测试不允许符号链接：${name}`);
  }
  return files;
}

export async function verifyCandidate(task, workspace, tests) {
  const candidate = await mkdtemp(join(dirname(workspace), "verify-"));
  try {
    await cp(workspace, candidate, { recursive: true });
    // 使用发给模型前保存的测试；模型删除/改写工作区测试不能制造假通过。
    await rm(join(candidate, "test"), { recursive: true, force: true });
    for (const [name, bytes] of tests) {
      await mkdir(dirname(join(candidate, "test", name)), { recursive: true });
      await writeFile(join(candidate, "test", name), bytes);
    }
    return await acceptanceResult(task, candidate);
  } finally {
    await rm(candidate, { recursive: true, force: true });
  }
}

export async function runTask(args, task) {
  const workspace = await prepareWorkspace(args, task);
  const tests = await snapshotTests(join(workspace, "test"));
  const started = Date.now();
  const session = await api(args, "/api/sessions", {
    method: "POST",
    body: JSON.stringify({ title: `eval:${task.id}`, workspace })
  });
  // 接口返回 { sessionId, ... }；保留 id 兼容字段以便旧版本也能跑
  const sessionId = session.sessionId ?? session.id;
  let diagnostics;
  try {
    await api(args, `/api/sessions/${sessionId}/messages`, {
    method: "POST",
    body: JSON.stringify({ content: task.prompt })
    });
    diagnostics = await waitForTerminal({ ...args, timeoutMinutes: Math.min(args.timeoutMinutes, task.maxMinutes ?? args.timeoutMinutes) }, sessionId, task.steer);
  } catch (error) {
    // 只清理本次执行器创建的会话；超时或网络失败后不让模型继续悄悄运行。
    try {
      const active = await api(args, `/api/sessions/${sessionId}/diagnostics`);
      if (active.activeRunId && !TERMINAL.has(active.sessionStatus)) {
        await api(args, `/api/runs/${encodeURIComponent(active.activeRunId)}/cancel`, { method: "POST" });
      }
    } catch (cancelError) {
      throw new Error(`${error.message}；取消失败：${cancelError.message}（会话 ${sessionId}）`);
    }
    throw error;
  }
  const acceptance = await verifyCandidate(task, workspace, tests);
  const durationSeconds = (Date.now() - started) / 1000;
  const failedCommands = (diagnostics?.commands ?? []).filter((item) => (item.exitCode ?? 0) !== 0);

  return {
    taskId: task.id,
    title: task.title,
    sessionId,
    model: args.model,
    promptVersion: args.promptVersion,
    toolVersion: args.toolVersion,
    success: diagnostics.sessionStatus === "COMPLETED" && acceptance.ok,
    acceptanceRan: acceptance.ran,
    durationSeconds,
    rework: failedCommands.length,
    takeover: diagnostics?.sessionStatus !== "COMPLETED",
    costUsd: 0,
    costKnown: false,
    inputTokens: diagnostics?.inputTokens ?? 0,
    outputTokens: diagnostics?.outputTokens ?? 0,
    cachedInputTokens: diagnostics?.cachedInputTokens ?? 0,
    usageExact: diagnostics?.usageExact === true,
    validationCovered: diagnostics?.validation?.covered ?? false,
    acceptanceDetail: acceptance.detail,
    storageHealth: diagnostics?.storageHealth ?? "",
    notes: ""
  };
}

export function dryRunRecords(tasks, args) {
  return tasks.map((task, index) => ({
    taskId: task.id,
    title: task.title,
    sessionId: `dry-${index}`,
    model: args.model,
    promptVersion: args.promptVersion,
    toolVersion: args.toolVersion,
    dryRun: true,
    success: false,
    acceptanceRan: false,
    durationSeconds: 60 + index * 15,
    rework: index % 3,
    takeover: index === 4,
    costUsd: 0.02 * (index + 1),
    costKnown: true,
    inputTokens: 1200 * (index + 1),
    outputTokens: 400 * (index + 1),
    cachedInputTokens: 300 * (index + 1),
    validationCovered: true,
    acceptanceDetail: "dry-run（未执行真实命令）",
    notes: "dry-run 记录不能用于能力结论"
  }));
}

/**
 * fixture 自检：每个 fixture 在「未修复」状态下跑验收必须失败。
 *
 * 初始就通过的任务没有分辨力——模型什么都不做也能拿到 PASS，
 * 于是门禁的回归判定会完全空转（所有任务基线都是成功，永远报不出退化）。
 */
async function verifyFixtures(args, tasks) {
  let broken = 0;
  for (const task of tasks) {
    const workspace = await prepareWorkspace(args, task).catch(() => null);
    if (!workspace) {
      console.log(`✗ ${task.id}：fixture 不存在（${task.fixture ?? task.id}）`);
      broken += 1;
      continue;
    }
    const result = await acceptanceResult(task, workspace);
    if (!result.ran) {
      console.log(`✗ ${task.id}：验收命令没有执行`);
      broken += 1;
    } else if (result.ok) {
      console.log(`✗ ${task.id}：未修复就通过了，没有分辨力`);
      broken += 1;
    } else {
      console.log(`✓ ${task.id}：未修复时验收失败`);
    }
    await rm(workspace, { recursive: true, force: true });
  }
  console.log(`\n${tasks.length - broken}/${tasks.length} 个 fixture 有分辨力`);
  if (broken > 0) process.exit(1);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const tasksFile = JSON.parse(await readFile(args.tasks, "utf8"));
  const tasks = tasksFile.tasks ?? [];
  if (tasks.length === 0) throw new Error("任务集为空");

  if (args.verifyFixtures) {
    await verifyFixtures(args, tasks);
    return;
  }

  // 在启动付费任务之前确认基线可读，避免跑完才发现无法比较。
  const baseline = args.baseline
    ? (await readFile(args.baseline, "utf8")).split("\n").filter(Boolean).map((line) => JSON.parse(line))
    : null;
  if (baseline && baseline.length === 0) throw new Error("基线为空");

  let records;
  if (args.dryRun) {
    records = dryRunRecords(tasks, args);
  } else {
    records = [];
    for (const task of tasks) {
      process.stdout.write(`▶ ${task.id} ... `);
      try {
        const record = await runTask(args, task);
        records.push(record);
        console.log(record.success ? "PASS" : "FAIL");
      } catch (error) {
        records.push({
          taskId: task.id, model: args.model, promptVersion: args.promptVersion,
          toolVersion: args.toolVersion, success: false, acceptanceRan: false,
          durationSeconds: 0, rework: 0, takeover: true, costUsd: 0, costKnown: false,
          inputTokens: 0, outputTokens: 0, notes: String(error)
        });
        console.log(`ERROR ${error.message}`);
      }
    }
  }

  const compared = baseline ? compare(baseline, records) : records;
  const decision = baseline ? gate(baseline, compared) : null;

  await mkdir(dirname(args.out), { recursive: true });
  await writeFile(args.out, compared.map((record) => JSON.stringify(record)).join("\n") + "\n", "utf8");
  const summary = {
    generatedAt: new Date().toISOString(), dryRun: args.dryRun,
    groups: aggregate(compared), overall: summarize(compared), gate: decision
  };
  await writeFile(args.out.endsWith(".jsonl") ? args.out.replace(/\.jsonl$/, ".summary.json") : `${args.out}.summary.json`, JSON.stringify(summary, null, 2), "utf8");

  console.log("\n结果：");
  for (const group of summary.groups) {
    console.log(`  ${group.key}  成功率 ${(group.successRate * 100).toFixed(1)}%  ` +
      `回归 ${(group.regressionRate * 100).toFixed(1)}%  平均耗时 ${group.avgDurationSeconds.toFixed(0)}s  ` +
      `返工 ${group.avgRework.toFixed(1)}  接管 ${(group.takeoverRate * 100).toFixed(1)}%`);
  }
  if (args.dryRun) console.log("  ⚠ dry-run：这些数字只用于验证流程，不能当作模型能力结论。");

  if (decision) {
    const m = decision.metrics;
    console.log(`\n门禁：${decision.verdict.toUpperCase()}`);
    console.log(`  可比任务 ${m.pairedTasks} 个（下限 ${m.minTasks}）  ` +
      `通过率 ${(m.baselineSuccessRate * 100).toFixed(1)}% → ${(m.currentSuccessRate * 100).toFixed(1)}%  ` +
      `成本 $${m.baselineCostUsd.toFixed(3)} → $${m.currentCostUsd.toFixed(3)}  ` +
      `缓存命中 ${(m.baselineCacheHitRate * 100).toFixed(0)}% → ${(m.currentCacheHitRate * 100).toFixed(0)}%`);
    for (const reason of decision.reasons) {
      console.log(`  - ${reason}`);
    }
    if (decision.verdict === "pass") {
      console.log("  可以合入：能力没有下降，成本也没有上升。");
    }
    if (args.strict && decision.verdict !== "pass") {
      console.error("  --strict：门禁未通过，按失败退出。");
      process.exit(1);
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main().catch((error) => {
  console.error(error);
  process.exit(1);
});
