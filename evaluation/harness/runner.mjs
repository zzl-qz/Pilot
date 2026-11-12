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
import { createHash } from "node:crypto";
import { platform, arch } from "node:os";
import { appendFile, cp, lstat, mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from "node:fs/promises";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { pathToFileURL } from "node:url";
import { aggregate, compare, gate, summarize, validateBaseline } from "./metrics.mjs";
import { FAULT_MODES } from "./support/fault-proxy.mjs";

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
    dryRun: false, resume: false,
    baseline: "",
    strict: false,
    verifyFixtures: false,
    repeat: 1, taskIds: [], environment: "",
    promptDir: "pilot-core/src/main/resources/prompts", runtimeSources: [],
    faultProxyUrl: "", faultProfiles: null, faultEvery: 3, faultStallMs: 70_000,
    faultMaxRequests: 0, faultSeed: 0
  };
  const valued = new Set(['--base', '--out', '--tasks', '--model', '--prompt-version', '--tool-version',
    '--fixtures', '--token', '--baseline', '--timeout-minutes', '--repeat', '--task', '--environment', '--prompt-dir', '--runtime-source',
    '--fault-proxy', '--fault-profile', '--fault-every', '--fault-stall-ms', '--fault-max-requests', '--seed']);
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
      case "--repeat": args.repeat = Number(value); index++; break;
      case "--task": args.taskIds.push(...value.split(",").map(v => v.trim()).filter(Boolean)); index++; break;
      case "--environment": args.environment = value; index++; break;
      case "--prompt-dir": args.promptDir = value; index++; break;
      case "--runtime-source": args.runtimeSources.push(value); index++; break;
      case "--fault-proxy": args.faultProxyUrl = value; index++; break;
      case "--fault-profile": args.faultProfiles = value.split(",").map(v => v.trim()).filter(Boolean); index++; break;
      case "--fault-every": args.faultEvery = Number(value); index++; break;
      case "--fault-stall-ms": args.faultStallMs = Number(value); index++; break;
      case "--fault-max-requests": args.faultMaxRequests = Number(value); index++; break;
      case "--seed": args.faultSeed = Number(value); index++; break;
      case "--dry-run": args.dryRun = true; break;
      case "--resume": args.resume = true; break;
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
  if (!Number.isInteger(args.repeat) || args.repeat < 1 || args.repeat > 100) throw new Error("--repeat 必须是 1 到 100 的整数");
  if (args.resume && args.dryRun) throw new Error("--resume 只用于真实评估记录");
  if (!args.runtimeSources.length) args.runtimeSources = ["pilot-core/src/main/java", "pilot-server/src/main/java"];
  if (args.strict && (!args.baseline || args.dryRun)) throw new Error("--strict 需要真实运行和 --baseline，不能使用 dry-run");
  if (args.faultProxyUrl) {
    if (args.dryRun) throw new Error("--fault-proxy 只用于真实评估，不能与 --dry-run 同用");
    if (!Array.isArray(args.faultProfiles) || !args.faultProfiles.length)
      throw new Error("--fault-proxy 需要 --fault-profile（如 mid-eof,stall,http-503,reset）");
    for (const mode of args.faultProfiles) if (!FAULT_MODES.has(mode)) throw new Error(`未知故障模式：${mode}`);
    args.faultProfile = args.faultProfiles.join(",");
    if (!Number.isInteger(args.faultEvery) || args.faultEvery < 1 || args.faultEvery > 1000)
      throw new Error("--fault-every 必须是 1 到 1000 的整数");
    if (!Number.isFinite(args.faultStallMs) || args.faultStallMs < 1) throw new Error("--fault-stall-ms 必须是正毫秒数");
    if (!Number.isInteger(args.faultMaxRequests) || args.faultMaxRequests < 0)
      throw new Error("--fault-max-requests 必须是非负整数");
    if (!Number.isSafeInteger(args.faultSeed)) throw new Error("--seed 必须是整数");
  } else if (args.faultProfiles) {
    throw new Error("--fault-profile 需要配合 --fault-proxy 使用");
  }
  return args;
}

function helpText() {
  return [
    "runner.mjs --base <url> --out <jsonl> [--model M] [--prompt-version P] [--tool-version T]",
    "           [--tasks tasks.json] [--fixtures dir] [--token T] [--timeout-minutes N]",
    "           [--baseline 上次的 jsonl] [--dry-run] [--strict] [--repeat N] [--task id,id]",
    "           [--resume] [--environment label] [--prompt-dir dir] [--runtime-source dir ...]",
    "           [--fault-proxy http://127.0.0.1:18086 --fault-profile mid-eof,stall,http-503,reset]",
    "           [--fault-every N] [--fault-stall-ms N] [--fault-max-requests N] [--seed N]",
    "",
    "  --baseline  与上次记录比较；严格门禁至少 20 个任务，每项两组至少 3 次。",
    "  --resume    在相同版本与任务合同下补齐缺少的轮次；已记录失败不会被重跑替换。",
    "  --repeat    每项重复次数，轮次之间交错任务，默认 1。",
    "  --task      只运行选中任务，可逗号分隔或重复传入；子集结果不能冒充全套。",
    "  --model     核对服务已配置的模型，不修改全局模型；不传时使用服务模型。",
    "  --prompt-dir / --runtime-source  对实际提示词与运行时源码生成版本指纹。",
    "  --fault-*   网络错误场景：通过 fault-proxy 在每第 N 个请求注入故障。",
    "              每次尝试的故障模式由 seed 与任务位置/attempt 复现轮换并写入记录；",
    "              本次尝试没有任何注入则 scenarioCovered=false，不算成功。",
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

/** fault-proxy 控制接口调用（独立于 Pilot 服务的鉴权头）。 */
export async function faultControl(args, path, init = {}) {
  const response = await fetch(`${args.faultProxyUrl.replace(/\/$/, "")}${path}`, {
    ...init,
    signal: AbortSignal.timeout(10_000),
    headers: init.body ? { "content-type": "application/json" } : {}
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`故障代理 ${init.method ?? "GET"} ${path} → ${response.status}: ${text.slice(0, 300)}`);
  }
  return response.json();
}

/**
 * 每次尝试的故障场景：由 seed + 任务在选中集中的位置 + attempt 复现轮换，
 * 断点续跑时可重算出同一方案，不存在运行间随机漂移。
 */
export function faultScenarioAt(args, taskPosition, attempt) {
  const profiles = args.faultProfiles;
  const rotationIndex = args.faultSeed + taskPosition * args.repeat + (attempt - 1);
  return { scenario: profiles[((rotationIndex % profiles.length) + profiles.length) % profiles.length], rotationIndex };
}

export function runCommand(command, cwd, timeoutMs) {
  return new Promise((resolvePromise) => {
    const started = Date.now();
    const env = { ...process.env, CI: "true", PILOT_EVAL: "1" };
    // node:test 的内部标记会令子进程跳过测试发现，制造退出码 0 的假通过。
    delete env.NODE_TEST_CONTEXT;
    const executable = Array.isArray(command) ? (command[0] === "node" ? process.execPath : command[0]) : command;
    const commandArgs = Array.isArray(command) ? command.slice(1) : [];
    const child = spawn(executable, commandArgs, {
      cwd,
      shell: !Array.isArray(command),
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
    const argv = command === "node --test" ? [process.execPath, "--test"] : command;
    const result = await runCommand(argv, workspace, 20 * 60 * 1000);
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
  let observedAt = started;
  const checkScheduling = () => {
    const now = Date.now();
    const gap = now - observedAt;
    observedAt = now;
    if (gap > Math.max(60_000, (args.requestTimeoutMs ?? 30_000) + (args.pollMs ?? 3000) + 10_000)) {
      throw Object.assign(new Error(`评测宿主连续 ${Math.round(gap / 1000)} 秒未调度，可能休眠或严重阻塞；停止整批并保留本次记录`),
        { evaluationInterrupted: true });
    }
  };
  let last = null;
  let steered = false, steerRunId = null, steerAtStep = null, steerDeliveredAtSeconds = null;
  const timeout = () => new Error(`任务超时，未在期限内取得终态（${last?.sessionStatus ?? "未知"}），不能执行验收`);
  async function beforeDeadline(path, init) {
    checkScheduling();
    const remaining = deadline - Date.now();
    if (remaining <= 0) throw timeout();
    const configuredTimeout = args.requestTimeoutMs ?? 30_000;
    try {
      const result = await api({ ...args, requestTimeoutMs: Math.max(1, Math.ceil(Math.min(configuredTimeout, remaining))) }, path, init);
      checkScheduling();
      // A poll sent before the deadline can arrive afterwards. Even COMPLETED must be timely.
      if (Date.now() >= deadline) throw timeout();
      return result;
    } catch (error) {
      if (error.evaluationInterrupted) throw error;
      checkScheduling();
      if (Date.now() >= deadline || (remaining <= configuredTimeout && error.name === 'TimeoutError')) throw timeout();
      throw error;
    }
  }
  while (Date.now() < deadline) {
    last = await beforeDeadline(`/api/sessions/${sessionId}/diagnostics`);
    if (TERMINAL.has(last.sessionStatus)) return { ...last, steerDelivered: steer ? steered : null, steerRunId, steerAtStep, steerDeliveredAtSeconds };
    // 引导必须进入当前运行；追加 messages 会排队成为下一项任务。
    if (steer && !steered && last.activeRunId && (Date.now() - started) / 1000 >= steer.afterSeconds) {
      await beforeDeadline(`/api/sessions/${sessionId}/steer`, {
        method: "POST",
        body: JSON.stringify({ content: steer.content, runId: last.activeRunId })
      });
      steered = true; steerRunId = last.activeRunId; steerAtStep = last.step ?? null;
      steerDeliveredAtSeconds = (Date.now() - started) / 1000;
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, Math.min(args.pollMs ?? (steer ? 250 : 3000), Math.max(0, deadline - Date.now()))));
  }
  checkScheduling();
  throw timeout();
}

const sha256 = value => createHash('sha256').update(value).digest('hex');

export function fixturePath(args, task) {
  const root = resolve(args.fixtures || dirname(args.tasks));
  const source = resolve(root, task.fixture ?? task.id);
  const inside = relative(root, source);
  if (isAbsolute(inside) || inside === '..' || inside.startsWith(`..${sep}`)) throw new Error('fixture 路径不能越出夹具目录');
  return source;
}

/** Stable relative filenames + bytes; rejects links instead of fingerprinting another workspace. */
export async function hashSources(paths) {
  const hash = createHash('sha256');
  async function visit(file, name) {
    const info = await lstat(file);
    if (info.isSymbolicLink()) throw new Error(`版本快照不允许符号链接：${name}`);
    hash.update(JSON.stringify(name));
    if (info.isDirectory()) {
      hash.update('dir');
      for (const child of (await readdir(file)).sort()) await visit(join(file, child), `${name}/${child}`);
    } else if (info.isFile()) {
      const bytes = await readFile(file); hash.update(`file:${bytes.length}:`); hash.update(bytes);
    } else throw new Error(`版本快照不支持此文件类型：${name}`);
  }
  for (let index = 0; index < paths.length; index++) await visit(resolve(paths[index]), String(index));
  return hash.digest('hex');
}

export async function serverIdentity(args) {
  const settings = await api(args, '/api/settings');
  if (typeof settings.model !== 'string' || !settings.model.trim()) throw new Error('服务未返回可核对的模型配置');
  if (args.model && args.model !== 'unknown' && args.model !== settings.model) throw new Error(`模型不匹配：请求核对 ${args.model}，服务配置 ${settings.model}`);
  // Hash endpoint/config, never write API keys, masked keys, or endpoint URLs into evaluation records.
  return { model: settings.model, modelConfigHash: sha256(JSON.stringify({
    model: settings.model, endpoint: settings.modelEndpoint ?? '', permissionMode: settings.permissionMode ?? ''
  })) };
}

export async function createRunContext(args) {
  const identity = await serverIdentity(args);
  return { ...identity, schemaVersion: 2,
    promptHash: await hashSources([args.promptDir]),
    runtimeHash: await hashSources(args.runtimeSources),
    environmentVersion: sha256(JSON.stringify({ label: args.environment, platform: platform(), arch: arch(), node: process.version })),
    environment: { label: args.environment, platform: platform(), arch: arch(), node: process.version }
  };
}

export async function recordMetadata(args, task) {
  return { ...args.runContext, fixtureVersion: await hashSources([fixturePath(args, task)]),
    taskVersion: sha256(JSON.stringify({ prompt: task.prompt, acceptance: task.acceptance, steer: task.steer,
      timeoutMinutes: Math.min(args.timeoutMinutes, task.maxMinutes ?? args.timeoutMinutes) })) };
}

/** Only count structured events; tool output prose is not a reliable error/repair label. */
export async function traceMetrics(args, sessionId) {
  let after = 0, toolCallCount = 0, modelRetryCount = 0;
  const seen = new Set();
  try {
    for (let page = 0; page < 100; page++) {
      const history = await api(args, `/api/sessions/${sessionId}/history?after=${after}&limit=500`);
      if (!Array.isArray(history.events)) throw new Error('历史事件不可用');
      for (const event of history.events) {
        if (!Number.isSafeInteger(event.sequence) || event.sequence <= after || seen.has(event.sequence)) continue;
        seen.add(event.sequence);
        if (event.type === 'tool.started') toolCallCount++;
        if (event.type === 'run.retrying') modelRetryCount++;
      }
      const cursor = Math.max(after, ...history.events.map(e => e.sequence).filter(Number.isSafeInteger));
      if (!history.hasMore) return { traceObserved: true, toolCallCount, modelRetryCount };
      if (cursor <= after) throw new Error('历史游标没有前进');
      after = cursor;
    }
  } catch { /* Optional diagnostics failure must not invent zero tool work. */ }
  return { traceObserved: false, toolCallCount: null, modelRetryCount: null };
}

export async function prepareWorkspace(args, task) {
  const source = fixturePath(args, task);
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
  const metadata = args.runContext ? await recordMetadata(args, task) : {};
  if (args.runContext) {
    const current = await serverIdentity(args);
    if (current.modelConfigHash !== args.runContext.modelConfigHash) throw new Error("模型配置已改变，请重建评测基线");
  }
  const session = await api(args, "/api/sessions", {
    method: "POST",
    body: JSON.stringify({ title: `eval:${task.id}:${args.attempt ?? 1}`, workspace })
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
    error.sessionId = sessionId;
    // 只清理本次执行器创建的会话；超时或网络失败后不让模型继续悄悄运行。
    try {
      const active = await api(args, `/api/sessions/${sessionId}/diagnostics`);
      error.runId = active.activeRunId ?? null;
      error.terminalStatus = TERMINAL.has(active.sessionStatus) ? active.sessionStatus : null;
      if (active.activeRunId && !TERMINAL.has(active.sessionStatus)) {
        await api(args, `/api/runs/${encodeURIComponent(active.activeRunId)}/cancel`, { method: "POST" });
        error.cancellationRequested = true;
        const settled = await api(args, `/api/sessions/${sessionId}/diagnostics`);
        error.terminalStatus = TERMINAL.has(settled.sessionStatus) ? settled.sessionStatus : null;
      }
    } catch (cancelError) {
      throw Object.assign(new Error(`${error.message}；取消状态未确认：${cancelError.message}（会话 ${sessionId}）`), {
        sessionId, runId: error.runId, cancellationRequested: error.cancellationRequested === true,
        evaluationInterrupted: error.evaluationInterrupted === true
      });
    }
    throw error;
  }
  const executionSeconds = (Date.now() - started) / 1000;
  const acceptance = await verifyCandidate(task, workspace, tests);
  const trace = await traceMetrics(args, sessionId);
  const after = args.runContext ? await serverIdentity(args).catch(() => null) : null;
  const durationSeconds = (Date.now() - started) / 1000;
  const failedCommands = Array.isArray(diagnostics?.commands) ? diagnostics.commands.filter(item =>
    (typeof item.exitCode === "number" && item.exitCode !== 0) || item.status === "FAILED") : null;

  // 场景覆盖：必需场景没有实际触发就不算成功（引导 / 故障注入 / 长链路 / 预算耗尽）。
  const scenarioNotes = [];
  let scenarioCovered = true;
  if (task.steer && diagnostics.steerDelivered !== true) {
    scenarioCovered = false;
    scenarioNotes.push("任务在中途引导投递前结束，引导场景未覆盖");
  }
  let faultState = null;
  if (args.faultProxyUrl) {
    faultState = await faultControl(args, "/fault/state");
    if (!(Number.isInteger(faultState.injectedRequests) && faultState.injectedRequests >= 1)) {
      scenarioCovered = false;
      scenarioNotes.push("网络错误场景未覆盖：本次尝试没有任何故障实际注入");
    }
  }
  if (task.scenario === "context-growth") {
    const minRequests = task.scenarioMinModelRequests ?? 1;
    if (diagnostics.modelRequestCount == null) {
      scenarioCovered = false;
      scenarioNotes.push("无法观测模型请求数，上下文增长场景不能确认覆盖");
    } else if (diagnostics.modelRequestCount < minRequests) {
      scenarioCovered = false;
      scenarioNotes.push(`模型请求数 ${diagnostics.modelRequestCount} 低于场景下限 ${minRequests}，没有形成长链路上下文`);
    }
  }
  if (task.scenario === "budget-wind") {
    const progress = await readBudgetWindProgress(workspace);
    if (progress == null) {
      scenarioCovered = false;
      scenarioNotes.push("未找到 src/progress.json，预算耗尽收尾场景不能确认");
    } else if (!(Array.isArray(progress.remaining) && progress.remaining.length > 0)) {
      scenarioCovered = false;
      scenarioNotes.push("40 项全部完成，本轮没有实际经历预算耗尽；应收紧步数/时间预算后复测");
    }
  }

  return {
    ...metadata,
    taskId: task.id,
    title: task.title,
    sessionId,
    attempt: args.attempt ?? 1,
    terminalStatus: diagnostics.sessionStatus,
    reportedComplete: diagnostics.sessionStatus === "COMPLETED",
    acceptancePassed: acceptance.ok,
    executionSeconds,
    configurationChanged: args.runContext ? after?.modelConfigHash !== args.runContext.modelConfigHash : false,
    ...trace,
    model: args.runContext?.model ?? args.model,
    requestedModel: args.runContext?.model ?? args.model,
    resolvedModel: null, // 当前服务记录的是请求模型，未暴露提供商解析后的别名。
    promptVersion: args.promptVersion,
    toolVersion: args.toolVersion,
    success: diagnostics.sessionStatus === "COMPLETED" && acceptance.ok && scenarioCovered,
    scenarioCovered,
    faultScenario: args.faultScenario ?? null,
    faultRotationIndex: args.faultRotationIndex ?? null,
    faultProfile: args.faultProfile ?? null,
    faultEvery: args.faultProxyUrl ? args.faultEvery : null,
    faultRequestCount: faultState?.requests ?? null,
    faultInjectedCount: faultState?.injectedRequests ?? null,
    faultInjectionModes: faultState?.faultsByMode ?? null,
    faultProxyStopped: faultState?.stopped ?? null,
    faultUsage: faultState?.usage ?? null,
    faultSpendEstimateUnits: faultState?.spendEstimate ?? null,
    faultRunDurationSeconds: args.faultWindowStart != null ? (Date.now() - args.faultWindowStart) / 1000 : null,
    steerDelivered: diagnostics.steerDelivered,
    steerRunId: diagnostics.steerRunId,
    steerAtStep: diagnostics.steerAtStep,
    steerDeliveredAtSeconds: diagnostics.steerDeliveredAtSeconds,
    acceptanceRan: acceptance.ran,
    durationSeconds,
    rework: null, // 失败命令不等于返工；没有人工标注时保持未知。
    takeover: null, // 非完成不是人工接管。脚本引导也不是人工接管。
    failedCommandCount: failedCommands?.length ?? null,
    modelRequestCount: diagnostics?.modelRequests ?? null,
    costUsd: diagnostics?.usageDetails?.costs?.USD ?? null,
    costKnown: typeof diagnostics?.usageDetails?.costs?.USD === "number"
      && diagnostics.usageDetails.requests > 0
      && diagnostics.usageDetails.pricedRequests === diagnostics.usageDetails.requests
      && Object.keys(diagnostics.usageDetails.costs || {}).every(currency => currency === 'USD'),
    inputTokens: diagnostics?.inputTokens ?? null,
    outputTokens: diagnostics?.outputTokens ?? null,
    cachedInputTokens: diagnostics?.cachedInputTokens ?? 0,
    usageExact: diagnostics?.usageExact === true,
    usageDetails: diagnostics?.usageDetails ?? null,
    cacheReadObserved: Number(diagnostics?.usageDetails?.observedCacheRequests) > 0,
    validationCovered: diagnostics?.validation?.covered ?? false,
    acceptanceDetail: acceptance.detail,
    storageHealth: diagnostics?.storageHealth ?? "",
    notes: scenarioNotes.join("；")
  };
}

/** budget-wind 场景：读取台账判断本轮是否真正经历了预算耗尽。 */
export async function readBudgetWindProgress(workspace) {
  try {
    const progress = JSON.parse(await readFile(join(workspace, "src", "progress.json"), "utf8"));
    return Array.isArray(progress.done) ? progress : null;
  } catch {
    return null;
  }
}

export function selectTasks(tasks, ids = []) {
  if (new Set(tasks.map(t => t.id)).size !== tasks.length) throw new Error("任务 ID 重复");
  const selected = new Set(ids);
  const missing = [...selected].filter(id => !tasks.some(t => t.id === id));
  if (missing.length) throw new Error(`未知任务：${missing.join('、')}`);
  return selected.size ? tasks.filter(t => selected.has(t.id)) : tasks;
}

export function expandRuns(tasks, repeat = 1) {
  return Array.from({ length: repeat }, (_, i) => tasks.map(task => ({ task, attempt: i + 1 }))).flat();
}

export function dryRunRecords(tasks, args) {
  return expandRuns(tasks, args.repeat ?? 1).map(({ task, attempt }, index) => ({
    taskId: task.id, title: task.title, sessionId: `dry-${index}`, attempt,
    model: args.model, promptVersion: args.promptVersion, toolVersion: args.toolVersion,
    dryRun: true, success: false, acceptanceRan: false, acceptancePassed: false,
    durationSeconds: null, rework: null, takeover: null, costUsd: null, costKnown: false,
    inputTokens: null, outputTokens: null, cachedInputTokens: null, cacheReadObserved: false,
    acceptanceDetail: "dry-run（未执行真实命令）", notes: "dry-run 记录不能用于能力结论"
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

/** 续跑只能补空位：失败也是有效观测，不能挑结果、重复付费或混合版本。 */
export function resumePlan(records, tasks, repeat, metadata, fault) {
  const selected = new Map(tasks.map(task => [task.id, task]));
  const positions = new Map(tasks.map((task, index) => [task.id, index]));
  const seen = new Set(), sessions = new Set();
  const keys = ['schemaVersion', 'model', 'modelConfigHash', 'promptHash', 'runtimeHash',
    'environmentVersion', 'fixtureVersion', 'taskVersion', 'promptVersion', 'toolVersion'];
  for (const record of records) {
    const expected = metadata.get(record.taskId);
    if (!selected.has(record.taskId) || !Number.isInteger(record.attempt) || record.attempt < 1 || record.attempt > repeat)
      throw new Error('续跑记录包含计划外的任务或轮次');
    if (record.dryRun || !expected || keys.some(key => expected[key] == null || record[key] !== expected[key]))
      throw new Error(`${record.taskId} 的版本、模型或任务合同已变化，不能续跑旧评估`);
    if (fault) {
      // 故障注入的轮换方案可由 seed/位置/attempt 复算；不一致即条件已变。
      const expectedScenario = faultScenarioAt(fault, positions.get(record.taskId), record.attempt).scenario;
      if (record.faultScenario !== expectedScenario || record.faultProfile !== fault.faultProfile
          || record.faultEvery !== fault.faultEvery)
        throw new Error(`${record.taskId} 第 ${record.attempt} 次的故障注入方案与当前计划不一致，不能续跑`);
    } else if (record.faultScenario != null) {
      throw new Error(`${record.taskId} 的记录包含故障注入信息，而当前未启用故障代理，条件不一致`);
    }
    const key = `${record.taskId}:${record.attempt}`;
    if (seen.has(key) || (record.sessionId && sessions.has(record.sessionId))) throw new Error('续跑记录有重复尝试或会话');
    seen.add(key); if (record.sessionId) sessions.add(record.sessionId);
  }
  return expandRuns(tasks, repeat).filter(({ task, attempt }) => !seen.has(`${task.id}:${attempt}`));
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const tasksFile = JSON.parse(await readFile(args.tasks, "utf8"));
  const tasks = selectTasks(tasksFile.tasks ?? [], args.taskIds);
  if (!tasks.length) throw new Error("任务集为空");
  if (args.verifyFixtures) { await verifyFixtures(args, tasks); return; }
  const baseline = args.baseline
    ? (await readFile(args.baseline, "utf8")).split("\n").filter(Boolean).map(line => JSON.parse(line)) : null;
  if (baseline && !baseline.length) throw new Error("基线为空");
  if (args.strict) {
    const check = validateBaseline(baseline);
    if (check.verdict !== 'pass') throw new Error(`基线尚不满足严格对照条件：${check.reasons.join('；')}`);
    if (args.repeat < 3) throw new Error('--strict 需要 --repeat 至少为 3');
    const selected = new Set(tasks.map(t => t.id));
    if (baseline.some(r => !selected.has(r.taskId))) throw new Error('选中任务缺少基线任务，不能启动不完整的严格对照');
  }
  const metadata = new Map();
  if (!args.dryRun) {
    args.runContext = await createRunContext(args);
    args.model = args.runContext.model;
    // Validate all fixture versions before the first paid request.
    for (const task of tasks) {
      const currentMetadata = { ...await recordMetadata(args, task), promptVersion: args.promptVersion, toolVersion: args.toolVersion };
      metadata.set(task.id, currentMetadata);
      if (args.strict && baseline.some(r => r.taskId === task.id &&
          ['fixtureVersion', 'taskVersion', 'environmentVersion', 'modelConfigHash'].some(key => r[key] !== currentMetadata[key]))) {
        throw new Error(`${task.id} 的模型/环境/任务条件与基线不一致`);
      }
    }
  }
  await mkdir(dirname(args.out), { recursive: true });
  // A lock prevents two runners from paying for the same missing slot. Crash locks require manual reconciliation.
  const lock = `${args.out}.lock`, inflight = `${args.out}.inflight.json`;
  await writeFile(lock, JSON.stringify({ pid: process.pid, startedAt: new Date().toISOString() }), { flag: 'wx' })
    .catch(() => { throw new Error(`评估输出已锁定：${lock}；先确认原进程结束及未结算请求，再移除锁`); });
  try {
    let records = [], planned = expandRuns(tasks, args.repeat), stoppedEarly = false;
    if (args.resume) {
      const contents = await readFile(args.out, 'utf8');
      if (contents && !contents.endsWith('\n')) throw new Error('评估文件末行不完整，请先保留原件并核对，不会自动丢弃');
      records = contents.split('\n').filter(Boolean).map(line => JSON.parse(line));
      planned = resumePlan(records, tasks, args.repeat, metadata, args.faultProxyUrl ? args : null);
    } else await writeFile(args.out, '', { encoding: 'utf8', flag: 'wx' });
    try {
      await readFile(inflight, 'utf8');
      throw new Error(`存在未结算尝试：${inflight}；核对服务终态、费用并补齐结果前禁止重复调用`);
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
    const persist = async record => { await appendFile(args.out, JSON.stringify(record) + '\n', 'utf8'); records.push(record); };
    if (args.dryRun) {
      for (const record of dryRunRecords(tasks, args)) await persist(record);
    } else {
      for (const { task, attempt } of planned) {
        await writeFile(inflight, JSON.stringify({ taskId: task.id, attempt, ...metadata.get(task.id), startedAt: new Date().toISOString() }), { flag: 'wx' });
        process.stdout.write(`▶ ${task.id} [${attempt}/${args.repeat}] ... `);
        const started = Date.now();
        // 网络错误组：每次尝试前重置计数并下发本次轮换到的故障方案。
        const rotation = args.faultProxyUrl ? faultScenarioAt(args, tasks.findIndex(item => item.id === task.id), attempt) : null;
        if (rotation) {
          await faultControl(args, '/fault/reset', { method: 'POST' });
          await faultControl(args, '/fault/config', { method: 'POST',
            body: JSON.stringify({ mode: rotation.scenario, every: args.faultEvery, stallMs: args.faultStallMs, maxRequests: args.faultMaxRequests }) });
        }
        const faultWindowStart = rotation ? Date.now() : null;
        let record;
        try {
          record = await runTask({ ...args, attempt, faultScenario: rotation?.scenario ?? null,
            faultRotationIndex: rotation?.rotationIndex ?? null, faultWindowStart }, task);
          console.log(record.success ? 'PASS' : 'FAIL');
        } catch (error) {
          record = { ...await recordMetadata(args, task).catch(() => args.runContext),
            taskId: task.id, attempt, model: args.model, requestedModel: args.model, resolvedModel: null,
            promptVersion: args.promptVersion, toolVersion: args.toolVersion, success: false, acceptanceRan: false,
            sessionId: error.sessionId ?? null, runId: error.runId ?? null,
            terminalStatus: error.terminalStatus ?? null, reportedComplete: null, evaluationError: true,
            evaluationInterrupted: error.evaluationInterrupted === true,
            cancellationRequested: error.cancellationRequested === true,
            scenarioCovered: false,
            faultScenario: rotation?.scenario ?? null, faultRotationIndex: rotation?.rotationIndex ?? null,
            faultProfile: args.faultProxyUrl ? args.faultProfile : null, faultEvery: args.faultProxyUrl ? args.faultEvery : null,
            faultRequestCount: null, faultInjectedCount: null, faultInjectionModes: null, faultProxyStopped: null,
            faultUsage: null, faultSpendEstimateUnits: null,
            faultRunDurationSeconds: faultWindowStart != null ? (Date.now() - faultWindowStart) / 1000 : null,
            durationSeconds: (Date.now() - started) / 1000, rework: null, takeover: null,
            costUsd: null, costKnown: false, inputTokens: null, outputTokens: null, notes: String(error) };
          if (args.faultProxyUrl) {
            try {
              const state = await faultControl(args, '/fault/state');
              Object.assign(record, { faultRequestCount: state.requests ?? null,
                faultInjectedCount: state.injectedRequests ?? null, faultInjectionModes: state.faultsByMode ?? null,
                faultProxyStopped: state.stopped === true, faultUsage: state.usage ?? null,
                faultSpendEstimateUnits: state.spendEstimate ?? null });
            } catch (faultError) {
              record.notes += `；故障代理状态读取失败：${faultError.message}`;
            }
          }
          console.log(`ERROR ${error.message}`);
        }
        // A disk failure must leave the in-flight marker, not append a second result for the same trial.
        await persist(record);
        await rm(inflight);
        if (record.evaluationError) {
          stoppedEarly = true;
          process.exitCode = 1;
          console.error('评测中断：已保留失败记录；核对宿主、服务和在途状态后再使用 --resume 补齐空位。');
          break;
        }
      }
    }
    const compared = baseline ? compare(baseline, records) : records;
    const decision = baseline ? gate(baseline, compared) : null;
    const summary = { generatedAt: new Date().toISOString(), dryRun: args.dryRun, stoppedEarly,
      selectedTasks: tasks.map(t => t.id), repeat: args.repeat,
      groups: aggregate(compared), overall: summarize(compared), gate: decision };
    const summaryPath = args.out.endsWith('.jsonl') ? args.out.replace(/\.jsonl$/, '.summary.json') : `${args.out}.summary.json`;
    await writeFile(summaryPath, JSON.stringify(summary, null, 2), { encoding: 'utf8', flag: args.resume ? 'w' : 'wx' });
    const display = (value, digits = 1) => value == null ? '未知' : value.toFixed(digits);
    const percent = value => value == null ? '未知' : `${(value * 100).toFixed(1)}%`;
    console.log('\n结果：');
    for (const group of summary.groups) {
      console.log(`  ${group.key}  ${group.uniqueTasks} 个任务 / ${group.tasks} 次运行  成功率 ${percent(group.successRate)}  ` +
        `正常收尾但外部验收失败率 ${percent(group.falseCompletionRate)}  p50/p95 ${display(group.p50DurationSeconds)}/${display(group.p95DurationSeconds)}s  ` +
        `每成功任务成本 ${display(group.costPerSuccessfulTaskUsd, 4)}`);
    }
    if (args.dryRun) console.log('  dry-run：未运行模型或验收，不产生能力和成本结论。');
    if (decision) {
      const m = decision.metrics;
      console.log(`\n门禁：${decision.verdict.toUpperCase()}（${decision.comparisonScope === 'prompt' ? '相同运行时的提示对照' : '提示与运行时组合变化'}）`);
      console.log(`  可比任务 ${m.pairedTasks} 个（下限 ${m.minTasks}）  通过率 ${percent(m.baselineSuccessRate)} → ${percent(m.currentSuccessRate)}  ` +
        `成本 ${display(m.baselineCostUsd, 3)} → ${display(m.currentCostUsd, 3)}  缓存命中 ${percent(m.baselineCacheHitRate)} → ${percent(m.currentCacheHitRate)}`);
      for (const reason of decision.reasons) console.log(`  - ${reason}`);
      if (decision.verdict === 'pass') console.log('  本轮可比样本未观察到退化；不代表所有任务或后续运行均有保证。');
      if (args.strict && decision.verdict !== 'pass') process.exitCode = 1;
    }
  } finally { await rm(lock, { force: true }); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main().catch((error) => {
  console.error(error);
  process.exit(1);
});
