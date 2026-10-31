#!/usr/bin/env node
/**
 * 真实模型评估的执行器。
 *
 * 用法（需要本机已启动 Pilot，并使用真实模型端点）：
 *   node evaluation/harness/runner.mjs \
 *     --base http://127.0.0.1:8787 \
 *     --out evaluation/harness/runs/$(date +%Y%m%d-%H%M).jsonl \
 *     --model gpt-5-codex --prompt-version P12 --tool-version T7 \
 *     --fixtures evaluation/harness/fixtures
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
import { cp, mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { aggregate, compare, summarize } from "./metrics.mjs";

const TERMINAL = new Set(["COMPLETED", "INTERRUPTED", "FAILED", "ARCHIVED"]);

function parseArgs(argv) {
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
    baseline: ""
  };
  for (let index = 0; index < argv.length; index++) {
    const flag = argv[index];
    const value = argv[index + 1];
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
      case "--help":
        console.log(helpText());
        process.exit(0);
        break;
      default:
        if (flag.startsWith("--")) throw new Error(`未知参数: ${flag}`);
    }
  }
  return args;
}

function helpText() {
  return [
    "runner.mjs --base <url> --out <jsonl> [--model M] [--prompt-version P] [--tool-version T]",
    "           [--tasks tasks.json] [--fixtures dir] [--token T] [--timeout-minutes N]",
    "           [--baseline 上次的 jsonl] [--dry-run]"
  ].join("\n");
}

function headers(token) {
  const base = { "Content-Type": "application/json" };
  return token ? { ...base, Authorization: `Bearer ${token}` } : base;
}

async function api(args, path, init = {}) {
  const response = await fetch(`${args.base}${path}`, {
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

function runCommand(command, cwd, timeoutMs) {
  return new Promise((resolvePromise) => {
    const started = Date.now();
    const child = spawn(command, {
      cwd,
      shell: true,
      env: { ...process.env, CI: "true", PILOT_EVAL: "1" },
      stdio: ["ignore", "pipe", "pipe"]
    });
    let output = "";
    const timer = setTimeout(() => child.kill("SIGKILL"), timeoutMs);
    child.stdout.on("data", (chunk) => { output += chunk.toString(); });
    child.stderr.on("data", (chunk) => { output += chunk.toString(); });
    child.on("error", (error) => {
      clearTimeout(timer);
      resolvePromise({ exitCode: -1, output: String(error), seconds: 0 });
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      resolvePromise({ exitCode: code ?? -1, output, seconds: (Date.now() - started) / 1000 });
    });
  });
}

async function acceptanceResult(task, workspace) {
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

async function waitForTerminal(args, sessionId) {
  const deadline = Date.now() + args.timeoutMinutes * 60 * 1000;
  let last = null;
  while (Date.now() < deadline) {
    last = await api(args, `/api/sessions/${sessionId}/diagnostics`);
    if (TERMINAL.has(last.sessionStatus)) return last;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 3000));
  }
  return last;
}

async function prepareWorkspace(args, task) {
  if (!args.fixtures) return null;
  const source = resolve(args.fixtures, task.fixture ?? task.id);
  try {
    await stat(source);
  } catch {
    return null;
  }
  const target = resolve(`.pilot-eval/${Date.now()}/${task.id}`);
  await cp(source, target, { recursive: true });
  return target;
}

async function runTask(args, task) {
  const workspace = await prepareWorkspace(args, task);
  const started = Date.now();
  const session = await api(args, "/api/sessions", {
    method: "POST",
    body: JSON.stringify({ title: `eval:${task.id}`, workspace })
  });
  // 接口返回 { sessionId, ... }；保留 id 兼容字段以便旧版本也能跑
  const sessionId = session.sessionId ?? session.id;
  await api(args, `/api/sessions/${sessionId}/messages`, {
    method: "POST",
    body: JSON.stringify({ content: task.prompt })
  });
  const diagnostics = await waitForTerminal(args, sessionId);
  const acceptance = await acceptanceResult(task, workspace);
  const durationSeconds = (Date.now() - started) / 1000;
  const failedCommands = (diagnostics?.commands ?? []).filter((item) => (item.exitCode ?? 0) !== 0);

  return {
    taskId: task.id,
    title: task.title,
    sessionId,
    model: args.model,
    promptVersion: args.promptVersion,
    toolVersion: args.toolVersion,
    success: acceptance.ok,
    acceptanceRan: acceptance.ran,
    durationSeconds,
    rework: failedCommands.length,
    takeover: diagnostics?.sessionStatus === "INTERRUPTED",
    costUsd: 0,
    costKnown: false,
    inputTokens: diagnostics?.inputTokens ?? 0,
    outputTokens: diagnostics?.outputTokens ?? 0,
    validationCovered: diagnostics?.validation?.covered ?? false,
    acceptanceDetail: acceptance.detail,
    storageHealth: diagnostics?.storageHealth ?? "",
    notes: ""
  };
}

function dryRunRecords(tasks, args) {
  return tasks.map((task, index) => ({
    taskId: task.id,
    title: task.title,
    sessionId: `dry-${index}`,
    model: args.model,
    promptVersion: args.promptVersion,
    toolVersion: args.toolVersion,
    success: index % 2 === 0,
    acceptanceRan: true,
    durationSeconds: 60 + index * 15,
    rework: index % 3,
    takeover: index === 4,
    costUsd: 0.02 * (index + 1),
    costKnown: true,
    inputTokens: 1200 * (index + 1),
    outputTokens: 400 * (index + 1),
    validationCovered: true,
    acceptanceDetail: "dry-run（未执行真实命令）",
    notes: "dry-run 记录不能用于能力结论"
  }));
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const tasksFile = JSON.parse(await readFile(args.tasks, "utf8"));
  const tasks = tasksFile.tasks ?? [];
  if (tasks.length === 0) throw new Error("任务集为空");

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

  const baseline = args.baseline
    ? (await readFile(args.baseline, "utf8")).split("\n").filter(Boolean).map((line) => JSON.parse(line))
    : null;
  const compared = baseline ? compare(baseline, records) : records;

  await mkdir(dirname(args.out), { recursive: true });
  await writeFile(args.out, compared.map((record) => JSON.stringify(record)).join("\n") + "\n", "utf8");
  const summary = { generatedAt: new Date().toISOString(), dryRun: args.dryRun, groups: aggregate(compared), overall: summarize(compared) };
  await writeFile(args.out.replace(/\.jsonl$/, ".summary.json"), JSON.stringify(summary, null, 2), "utf8");

  console.log("\n结果：");
  for (const group of summary.groups) {
    console.log(`  ${group.key}  成功率 ${(group.successRate * 100).toFixed(1)}%  ` +
      `回归 ${(group.regressionRate * 100).toFixed(1)}%  平均耗时 ${group.avgDurationSeconds.toFixed(0)}s  ` +
      `返工 ${group.avgRework.toFixed(1)}  接管 ${(group.takeoverRate * 100).toFixed(1)}%`);
  }
  if (args.dryRun) console.log("  ⚠ dry-run：这些数字只用于验证流程，不能当作模型能力结论。");
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
