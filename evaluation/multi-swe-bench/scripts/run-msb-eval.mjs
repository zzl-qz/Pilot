#!/usr/bin/env node
/**
 * Pilot x Multi-SWE-bench (Java / TypeScript) 规范化评测运行器
 *
 * 特性：
 *  - 支持单组评测与严格交错双组 A/B 对照（--arm name:jar[:profile] --interleave）
 *  - 每题独立销毁型 Docker 容器 + 反作弊洗白（删 /home/*.patch/*.sh + 单提交 baseline git 仓库）
 *  - 容器内挂载只读 Linux x64 JRE 25 跑 Pilot，同时用 .mavenrc / gradle.properties 锁定项目原生 JDK
 *  - 实时逐题落盘 JSONL、支持 --resume 断点续跑、自动导出官方 Multi-SWE-bench 补丁格式
 *  - 自动调用 trace-audit.mjs 提取缓存命中、未命中 token、编辑失配与门禁拦截信号
 *  - 严禁将任何 API Key 写入日志、JSONL 或汇总报告
 *
 * 示例：
 *  # 交错双组对照（极简基线 vs 完整版 Pilot）
 *  caffeinate -s -i node scripts/run-msb-eval.mjs \
 *    --batch ab-v1 \
 *    --dataset datasets/flash-java-ts-40.jsonl \
 *    --arm minimal:jars/pilot-head.jar:minimal \
 *    --arm full:jars/pilot-head.jar:full \
 *    --interleave --parallel 2 --timeout 30
 *
 *  # 纵向版本对照（旧版 JAR vs 新版 JAR）
 *  caffeinate -s -i node scripts/run-msb-eval.mjs \
 *    --batch ver-v1 \
 *    --dataset datasets/flash-java-ts-40.jsonl \
 *    --arm baseline:jars/pilot-old.jar:full \
 *    --arm candidate:jars/pilot-head.jar:full \
 *    --interleave --parallel 2 --timeout 30
 */
import { createHash, randomUUID } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, appendFileSync } from 'node:fs';
import { createServer } from 'node:net';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { DatabaseSync } from 'node:sqlite';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const JRE_DIR = path.join(ROOT, 'pilot-jre25-linux-x64');
const ENTRYPOINT = path.join(HERE, 'container-pilot-entrypoint.sh');
const PILOT_REPO = path.resolve(ROOT, '..', '..');
const TRACE_AUDIT = process.env.PILOT_TRACE_AUDIT || path.join(PILOT_REPO, 'evaluation', 'dogfood', 'tools', 'trace-audit.mjs');
const TERMINAL = new Set(['COMPLETED', 'INTERRUPTED', 'FAILED', 'CANCELLED', 'PAUSED', 'ARCHIVED']);

const ARM_PROFILES = {
  full: [],
  minimal: [
    '--pilot.agent.completion-checks=false',
    '--pilot.agent.lint-gate-enabled=false',
    '--pilot.agent.preflight-localization-enabled=false',
    '--pilot.agent.repository-brief=off',
  ],
};

function parseArgs(argv) {
  const out = { arms: [], tasks: null, interleave: false, resume: false, dryRun: false };
  for (let i = 0; i < argv.length; i++) {
    const t = argv[i];
    if (t === '--arm') out.arms.push(argv[++i]);
    else if (t === '--interleave') out.interleave = true;
    else if (t === '--resume') out.resume = true;
    else if (t === '--dry-run') out.dryRun = true;
    else if (t.startsWith('--')) {
      const k = t.slice(2);
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) out[k] = true;
      else { out[k] = next; i++; }
    }
  }
  return out;
}

function sha256File(filePath) {
  return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

function imageName(row) {
  return `mswebench/${row.org.toLowerCase()}_m_${row.repo.toLowerCase()}:pr-${row.number}`;
}

function parseArmSpec(spec) {
  const parts = spec.split(':');
  if (parts.length < 2) throw new Error(`Invalid --arm format (expected name:jarPath[:profile]): ${spec}`);
  const [name, jarRaw, profile = 'full'] = parts;
  if (!ARM_PROFILES[profile]) throw new Error(`Unknown arm profile '${profile}' (supported: ${Object.keys(ARM_PROFILES).join(', ')})`);
  const jarPath = path.resolve(ROOT, jarRaw);
  if (!existsSync(jarPath)) throw new Error(`JAR not found for arm '${name}': ${jarPath}`);
  const metaPath = `${jarPath}.meta.json`;
  const jarMeta = existsSync(metaPath) ? JSON.parse(readFileSync(metaPath, 'utf8')) : {};
  return {
    name,
    jarPath,
    jarSha256: sha256File(jarPath),
    pilotGitSha: jarMeta.gitSha ?? null,
    promptSha256: jarMeta.promptSha256 ?? null,
    profile,
    extraArgs: ARM_PROFILES[profile],
  };
}

function findFreePort() {
  return new Promise((resolvePort, reject) => {
    const srv = createServer();
    srv.unref();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolvePort(port));
    });
  });
}

function buildIssuePrompt(row) {
  const issues = row.resolved_issues || [];
  const issueBlocks = issues.map((iss, idx) => {
    const title = (iss.title || '').trim();
    const body = (iss.body || '').trim();
    const heading = issues.length > 1 ? `### Issue ${idx + 1}: ${title}` : `### ${title}`;
    return `${heading}\n\n${body}`.trim();
  });
  const langHint = row.language === 'java'
    ? 'Maven/Gradle (`mvn test` / `./mvnw test` / `./gradlew test`)'
    : 'Node/TypeScript (`pnpm test` / `npm test` / `npx vitest` / `npx jest`)';

  return [
    `You are working in the repository \`${row.org}/${row.repo}\` checked out at the current workspace.`,
    'Please investigate and resolve the following issue(s):',
    '',
    ...issueBlocks,
    '',
    'Requirements & Constraints:',
    '1. Make minimal, clean, production-quality changes in the repository source code to fix the issue.',
    '2. Do NOT leave modifications to existing test files or build configs in your final working tree (external evaluation applies its own hidden test suite on top of your git diff; if you write temporary tests to reproduce the bug, revert or remove them before finishing).',
    `3. Run targeted existing tests using the project's test runner (${langHint}) to verify your fix and check for regressions before finishing.`,
    '4. Do NOT access paths outside the repository workspace, and do NOT clone or query remote git repositories.',
    '',
  ].join('\n');
}

async function api(baseUrl, token, urlPath, init = {}, attempts = 3) {
  let lastErr = null;
  for (let i = 0; i < attempts; i++) {
    try {
      const res = await fetch(`${baseUrl}${urlPath}`, {
        signal: AbortSignal.timeout(45_000),
        ...init,
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
          ...(init.headers ?? {}),
        },
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(`${init.method ?? 'GET'} ${urlPath} -> ${res.status}: ${text.slice(0, 300)}`);
      }
      const ct = res.headers.get('content-type') ?? '';
      return ct.includes('application/json') ? res.json() : res.text();
    } catch (err) {
      if (String(err.message).includes(' -> ')) throw err;
      lastErr = err;
      if (i < attempts - 1) await new Promise((r) => setTimeout(r, 2000 * (i + 1)));
    }
  }
  throw lastErr;
}

function ensureDockerImage(img) {
  const check = spawnSync('docker', ['image', 'inspect', img], { stdio: 'ignore' });
  if (check.status === 0) return;
  console.log(`[pull] ${img}`);
  const pull = spawnSync('docker', ['pull', img], { stdio: 'inherit' });
  if (pull.status !== 0) throw new Error(`Failed to pull Docker image: ${img}`);
}

function dockerExec(containerName, bashCmd, timeoutMs = 30_000) {
  return spawnSync('docker', ['exec', containerName, 'bash', '-c', bashCmd], {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    timeout: timeoutMs,
  });
}

function redactEndpoint(urlStr) {
  try {
    const u = new URL(urlStr);
    return `${u.protocol}//${u.host}${u.pathname}`;
  } catch {
    return urlStr;
  }
}

async function waitForHealth(baseUrl, token, deadlineMs) {
  while (Date.now() < deadlineMs) {
    try {
      const h = await api(baseUrl, token, '/api/health', {}, 1);
      if (h && (h.status === 'ok' || h.status === 'UP' || h.ok === true)) return h;
    } catch {
      // keep waiting until server boots
    }
    await new Promise((r) => setTimeout(r, 1500));
  }
  throw new Error('Pilot server inside container failed to become healthy in time');
}

function extractGateStats(pilotDataDir, sessionId) {
  // Inspect SQLite events directly for CompletionGate / LintGate catches without printing any secrets
  const dbPath = path.join(pilotDataDir, 'pilot.db');
  if (!existsSync(dbPath) || !sessionId) return null;
  const pyCode = `
import json, sqlite3, sys
con = sqlite3.connect(sys.argv[1])
rows = con.execute("SELECT type, payload FROM events WHERE session_id = ? ORDER BY sequence", (sys.argv[2],)).fetchall()
gate_blocks = 0
lint_blocks = 0
review_conts = 0
for t, p in rows:
    if t != 'run.continued':
        continue
    try:
        obj = json.loads(p or '{}')
        reason = str(obj.get('reason') or '')
    except Exception:
        reason = str(p or '')
    rl = reason.lower()
    if '自审' in reason:
        review_conts += 1
    elif '规范' in reason or 'lint' in rl:
        lint_blocks += 1
    else:
        gate_blocks += 1
print(json.dumps({"gateBlocks": gate_blocks, "lintBlocks": lint_blocks, "reviewContinuations": review_conts}))
`;
  const r = spawnSync(process.env.PILOT_MSB_PYTHON || (existsSync(path.join(ROOT, '.venv/bin/python')) ? path.join(ROOT, '.venv/bin/python') : (existsSync(path.join(ROOT, '.venv/Scripts/python.exe')) ? path.join(ROOT, '.venv/Scripts/python.exe') : 'python3')), ['-c', pyCode, dbPath, sessionId], { encoding: 'utf8' });
  if (r.status === 0 && r.stdout.trim()) {
    try { return JSON.parse(r.stdout.trim()); } catch { return null; }
  }
  return null;
}

function runTraceAudit(pilotDataDir, sessionId, outPath) {
  if (!existsSync(TRACE_AUDIT) || !sessionId) return null;
  const r = spawnSync(process.execPath, [
    TRACE_AUDIT, '--session', sessionId, '--data-dir', pilotDataDir, '--json-out', outPath, '--json',
  ], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, timeout: 30_000 });
  if (r.status === 0 && existsSync(outPath)) {
    try {
      const full = JSON.parse(readFileSync(outPath, 'utf8'));
      const s = full.signals || {};
      return {
        totalToolCalls: s.A_positioning?.totalToolCalls ?? null,
        requestsBeforeFirstEdit: s.A_positioning?.requestsBeforeFirstEdit ?? null,
        duplicateReads: s.A_positioning?.duplicateExactRangeReads ?? null,
        editTotal: s.D_editing?.editTotal ?? null,
        editFailures: s.D_editing?.editFailures ?? null,
        postLastEditCheckPassed: s.E_verification?.postLastEditCheckPassed ?? null,
        totalToolErrors: s.F_toolErrorsAndPaging?.totalToolErrors ?? null,
        shellReadWorkarounds: s.F_toolErrorsAndPaging?.shellReadWorkarounds?.length ?? null,
        medianCacheHitRate: s.H_cacheStability?.medianCacheHitRate ?? null,
        overallCacheHitRate: s.H_cacheStability?.overallCacheHitRate ?? null,
        nonAppendOnlyMainCount: s.H_cacheStability?.nonAppendOnlyMainCount ?? null,
        compactions: s.I_contextAndCompaction?.compactions ?? null,
        peakContextTokens: s.I_contextAndCompaction?.peakContextTokens ?? null,
        totalModelRequests: s.L_runtimeAndCost?.totalModelRequests ?? null,
        failedModelRequests: s.L_runtimeAndCost?.failedModelRequests ?? null,
        totalInputTokens: s.L_runtimeAndCost?.totalInputTokens ?? null,
        totalCachedInputTokens: s.L_runtimeAndCost?.totalCachedInputTokens ?? null,
        uncachedInputTokens: s.L_runtimeAndCost?.uncachedInputTokens ?? null,
        totalOutputTokens: s.L_runtimeAndCost?.totalOutputTokens ?? null,
        alertCount: (full.alerts || []).length,
        alertCategories: [...new Set((full.alerts || []).map((a) => a.category))],
      };
    } catch {
      return null;
    }
  }
  return null;
}

async function runOne(ctx, job) {
  const { task, arm, attempt } = job;
  const img = imageName(task);
  const safeId = task.instance_id.replace(/[^a-zA-Z0-9._-]/g, '_');
  const shortHash = createHash('sha1').update(`${ctx.batch}:${arm.name}:${task.instance_id}:${attempt}`).digest('hex').slice(0, 10);
  const randSuffix = randomUUID().slice(0, 6);
  const armShort = arm.name.replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 10);
  const containerName = `pmsb-${armShort}-${shortHash}-${randSuffix}`;
  const pilotDataDir = path.join(ctx.batchDir, 'pilot-data', arm.name, `${safeId}-a${attempt}`);
  const auditDir = path.join(ctx.batchDir, 'audits', arm.name);
  rmSync(pilotDataDir, { recursive: true, force: true });
  mkdirSync(pilotDataDir, { recursive: true });
  mkdirSync(auditDir, { recursive: true });

  const startedAt = new Date().toISOString();
  const startedMs = Date.now();
  const record = {
    batch: ctx.batch,
    arm: arm.name,
    armProfile: arm.profile,
    pilotGitSha: arm.pilotGitSha,
    jarSha256: arm.jarSha256,
    instance_id: task.instance_id,
    org: task.org,
    repo: task.repo,
    number: task.number,
    language: task.language,
    difficulty: task.difficulty ?? null,
    attempt,
    image: img,
    model: ctx.model,
    endpoint: redactEndpoint(ctx.endpoint),
    startedAt,
    status: null,
    failureCode: null,
    sessionId: null,
    runSeconds: 0,
    patchBytes: 0,
    antiCheat: null,
    diagnostics: null,
    traceAudit: null,
    gateStats: null,
    error: null,
  };

  if (ctx.dryRun) {
    record.status = 'DRY_RUN';
    record.finishedAt = new Date().toISOString();
    return { record, patch: '' };
  }

  ensureDockerImage(img);
  const hostPort = await findFreePort();
  const evalToken = randomUUID();
  const baseUrl = `http://127.0.0.1:${hostPort}`;
  let extractedPatch = '';

  // Clean up any stale container with same name
  spawnSync('docker', ['rm', '-f', containerName], { stdio: 'ignore' });

  // Write ephemeral chmod-600 env file so API keys never appear in process argv / ps output
  const envFile = path.join(os.tmpdir(), `pilot-msb-env-${randomUUID()}.env`);
  const envLines = [
    `MSB_REPO=${task.repo}`,
    `PILOT_EVAL_TOKEN=${evalToken}`,
    `PILOT_MODEL_ENDPOINT=${ctx.endpoint}`,
    `PILOT_MODEL_MODEL=${ctx.model}`,
    `PILOT_MODEL_API_KEY=${ctx.apiKey}`,
    `PILOT_MODEL_PROMPT_CACHING=${ctx.promptCaching ? 'true' : 'false'}`,
  ];
  writeFileSync(envFile, envLines.join('\n') + '\n', { encoding: 'utf8', mode: 0o600 });
  chmodSync(envFile, 0o600);

  const dockerArgs = [
    'run', '-d', '--init',
    '--name', containerName,
    '--memory', `${ctx.containerMemGb}g`,
    '--cpus', String(ctx.containerCpus),
    '-p', `127.0.0.1:${hostPort}:9998`,
    '-v', `${JRE_DIR}:/opt/pilot-jre:ro`,
    '-v', `${arm.jarPath}:/opt/pilot/pilot-server.jar:ro`,
    '-v', `${ENTRYPOINT}:/opt/eval/entrypoint.sh:ro`,
    '-v', `${pilotDataDir}:/opt/pilot-data:rw`,
    '--env-file', envFile,
    '-e', `PILOT_EXTRA_ARGS=${arm.extraArgs.join('\n')}`,
    '--entrypoint', 'bash',
    img,
    '/opt/eval/entrypoint.sh',
  ];

  const startRes = spawnSync('docker', dockerArgs, { encoding: 'utf8' });
  rmSync(envFile, { force: true });
  if (startRes.status !== 0) {
    record.status = 'INFRA_ERROR';
    record.error = `docker run failed: ${(startRes.stderr || startRes.stdout || '').slice(0, 600)}`;
    record.finishedAt = new Date().toISOString();
    return { record, patch: '' };
  }

  try {
    await waitForHealth(baseUrl, evalToken, Date.now() + 120_000);
    const session = await api(baseUrl, evalToken, '/api/sessions', {
      method: 'POST',
      body: JSON.stringify({ title: `msb:${arm.name}:${task.instance_id}:a${attempt}`, workspace: `/home/${task.repo}` }),
    });
    record.sessionId = session.sessionId ?? session.id;

    await api(baseUrl, evalToken, `/api/sessions/${record.sessionId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ content: buildIssuePrompt(task) }),
    });

    const deadline = Date.now() + ctx.timeoutMinutes * 60 * 1000;
    let lastDiag = null;
    let timedOut = true;
    while (Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, ctx.pollMs));
      lastDiag = await api(baseUrl, evalToken, `/api/sessions/${record.sessionId}/diagnostics`);
      if (TERMINAL.has(lastDiag.sessionStatus)) {
        const active = await api(baseUrl, evalToken, `/api/sessions/${record.sessionId}/runs/active`);
        if (Object.hasOwn(active, 'runId') && active.runId === null) {
          timedOut = false;
          break;
        }
      }
    }

    if (timedOut) {
      // Politely cancel in-flight run before extracting diff
      try {
        await api(baseUrl, evalToken, `/api/sessions/${record.sessionId}/cancel`, { method: 'POST', body: '{}' }, 1);
      } catch {
        // ignore cancel failure
      }
      await new Promise((r) => setTimeout(r, 3000));
      try {
        lastDiag = await api(baseUrl, evalToken, `/api/sessions/${record.sessionId}/diagnostics`, {}, 1);
      } catch {
        // ignore
      }
      record.status = 'TIMEOUT';
    } else {
      record.status = lastDiag?.sessionStatus ?? 'UNKNOWN';
    }
    record.failureCode = lastDiag?.failureCode ?? null;
    record.diagnostics = lastDiag;

    // Extract clean git diff against single baseline commit + anti-cheat audit
    const repoPath = `/home/${task.repo}`;
    const diffRes = dockerExec(containerName, `cd "${repoPath}" && git add -N . >/dev/null 2>&1 || true; git diff HEAD`);
    extractedPatch = diffRes.status === 0 ? diffRes.stdout : '';
    const commitCountRes = dockerExec(containerName, `cd "${repoPath}" && git rev-list --count HEAD`);
    const statusRes = dockerExec(containerName, `cd "${repoPath}" && git status --porcelain`);
    const leakedArtifactsRes = dockerExec(containerName, 'ls /home/*.patch /home/*run.sh 2>/dev/null | wc -l');

    const commitCount = Number.parseInt((commitCountRes.stdout || '0').trim(), 10);
    const changedFiles = (statusRes.stdout || '').split('\n').filter(Boolean);
    const testFilesTouched = changedFiles.filter((line) => {
      const rel = line.slice(3).trim();
      return /(^|[\\/])(test|tests|__tests__|src\/test)([\\/]|$)|\.(spec|test|tests)\./i.test(rel);
    });
    record.patchBytes = Buffer.byteLength(extractedPatch, 'utf8');
    record.antiCheat = {
      commitCount,
      extraCommits: commitCount > 1,
      leakedArtifactsCount: Number.parseInt((leakedArtifactsRes.stdout || '0').trim(), 10),
      changedFilesCount: changedFiles.length,
      testFilesTouchedCount: testFilesTouched.length,
      testFilesTouched: testFilesTouched.slice(0, 20),
    };
  } catch (err) {
    record.status = 'INFRA_ERROR';
    record.error = String(err.message || err).slice(0, 800);
    // Capture tail of container logs for debugging
    const logs = spawnSync('docker', ['logs', '--tail', '40', containerName], { encoding: 'utf8' });
    writeFileSync(path.join(auditDir, `${safeId}-a${attempt}.container.log`), `${logs.stdout || ''}\n${logs.stderr || ''}`, 'utf8');
  } finally {
    spawnSync('docker', ['rm', '-f', containerName], { stdio: 'ignore' });
  }

  record.runSeconds = Math.round((Date.now() - startedMs) / 1000);
  if (record.sessionId) {
    record.traceAudit = runTraceAudit(pilotDataDir, record.sessionId, path.join(auditDir, `${safeId}-a${attempt}.audit.json`));
    record.gateStats = extractGateStats(pilotDataDir, record.sessionId);
  }
  record.finishedAt = new Date().toISOString();
  return { record, patch: extractedPatch };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.batch || !args.dataset || args.arms.length === 0) {
    console.error('Usage: run-msb-eval.mjs --batch <name> --dataset <jsonl> --arm name:jar[:profile] [--arm ...] [--interleave] [--repeat 1] [--parallel 2] [--timeout 30] [--resume] [--dry-run]');
    process.exit(1);
  }
  if (!existsSync(JRE_DIR) && !args.dryRun) {
    throw new Error(`Linux x64 JRE 25 missing at ${JRE_DIR}`);
  }

  let endpoint = args.endpoint || process.env.PILOT_MODEL_ENDPOINT || '';
  let model = args.model || process.env.PILOT_MODEL_MODEL || '';
  let apiKey = process.env.PILOT_MODEL_API_KEY || '';
  if (args['pilot-db-auth']) {
    const db = new DatabaseSync(path.resolve(args['pilot-db-auth']), { readOnly: true });
    try {
      const readSetting = (k) => db.prepare('SELECT value FROM settings WHERE key = ?').get(k)?.value ?? '';
      if (!endpoint) endpoint = readSetting('model.endpoint');
      if (!model) model = readSetting('model.name');
      if (!apiKey) apiKey = readSetting('model.api-key');
    } finally {
      db.close();
    }
  }
  if (!args.dryRun && (!endpoint || !model || !apiKey)) {
    throw new Error('Missing model config: pass --endpoint/--model/--pilot-db-auth or env PILOT_MODEL_ENDPOINT/PILOT_MODEL_MODEL/PILOT_MODEL_API_KEY');
  }

  const datasetPath = path.resolve(ROOT, args.dataset);
  const allTasks = readFileSync(datasetPath, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
  const wanted = args.tasks ? new Set(String(args.tasks).split(',').map((s) => s.trim())) : null;
  const tasks = wanted ? allTasks.filter((t) => wanted.has(t.instance_id)) : allTasks;
  if (args.limit) tasks.splice(Number(args.limit));
  if (tasks.length === 0) throw new Error('No tasks matched');

  const arms = args.arms.map(parseArmSpec);
  const repeat = Number(args.repeat ?? 1);
  const parallel = Number(args.parallel ?? 2);
  const timeoutMinutes = Number(args.timeout ?? 30);
  const pollMs = Number(args.poll ?? 5000);
  const promptCaching = String(args['prompt-caching'] ?? process.env.PILOT_MODEL_PROMPT_CACHING ?? 'false') === 'true';

  const batchDir = path.join(ROOT, 'runs', args.batch);
  const patchesDir = path.join(batchDir, 'patches');
  mkdirSync(patchesDir, { recursive: true });
  const resultsFile = path.join(batchDir, 'results.jsonl');

  const completedKeys = new Set();
  if (args.resume && existsSync(resultsFile)) {
    for (const line of readFileSync(resultsFile, 'utf8').split('\n').filter(Boolean)) {
      const r = JSON.parse(line);
      if (r.status !== 'INFRA_ERROR') {
        completedKeys.add(`${r.arm}::${r.instance_id}::${r.attempt}`);
      }
    }
    console.log(`[resume] Found ${completedKeys.size} completed records to skip.`);
  } else if (existsSync(resultsFile) && !args.resume) {
    throw new Error(`Batch '${args.batch}' already exists at ${resultsFile}. Pass --resume or choose a new --batch name.`);
  }

  // Build job queue: interleaved by task across arms to eliminate time-of-day API drift
  const jobs = [];
  for (let attempt = 1; attempt <= repeat; attempt++) {
    if (args.interleave) {
      for (const task of tasks) {
        for (const arm of arms) {
          const key = `${arm.name}::${task.instance_id}::${attempt}`;
          if (!completedKeys.has(key)) jobs.push({ task, arm, attempt });
        }
      }
    } else {
      for (const arm of arms) {
        for (const task of tasks) {
          const key = `${arm.name}::${task.instance_id}::${attempt}`;
          if (!completedKeys.has(key)) jobs.push({ task, arm, attempt });
        }
      }
    }
  }

  // Write batch provenance manifest (ZERO secrets)
  const manifest = {
    batch: args.batch,
    createdAt: new Date().toISOString(),
    datasetPath,
    datasetSha256: sha256File(datasetPath),
    taskCount: tasks.length,
    repeat,
    interleave: args.interleave,
    parallel,
    timeoutMinutes,
    model,
    endpoint: redactEndpoint(endpoint),
    promptCaching,
    host: { platform: process.platform, arch: process.arch, node: process.version, hostname: os.hostname() },
    arms: arms.map((a) => ({
      name: a.name, profile: a.profile, jarPath: a.jarPath, jarSha256: a.jarSha256,
      pilotGitSha: a.pilotGitSha, promptSha256: a.promptSha256, extraArgs: a.extraArgs,
    })),
  };
  const manifestPath = path.join(batchDir, 'manifest.json');
  if (!args.resume || !existsSync(manifestPath)) {
    writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n', 'utf8');
  }

  console.log(`Batch=${args.batch} | tasks=${tasks.length} | arms=${arms.map((a) => a.name).join(',')} | repeat=${repeat} | pending=${jobs.length} | parallel=${parallel} | interleave=${args.interleave}`);
  const ctx = {
    batch: args.batch,
    batchDir,
    patchesDir,
    endpoint,
    model,
    apiKey,
    promptCaching,
    timeoutMinutes,
    pollMs,
    dryRun: args.dryRun,
    containerMemGb: Number(args['mem-gb'] ?? 6),
    containerCpus: Number(args.cpus ?? 3),
  };

  let done = 0;
  const queue = [...jobs];
  const workers = Array.from({ length: Math.min(parallel, queue.length || 1) }, async () => {
    for (;;) {
      const job = queue.shift();
      if (!job) return;
      console.log(`[start] arm=${job.arm.name} task=${job.task.instance_id} a=${job.attempt}`);
      const { record, patch } = await runOne(ctx, job);
      appendFileSync(resultsFile, JSON.stringify(record) + '\n', 'utf8');
      if (!ctx.dryRun) {
        const patchFile = path.join(patchesDir, `${job.arm.name}-a${job.attempt}.jsonl`);
        appendFileSync(
          patchFile,
          JSON.stringify({ org: job.task.org, repo: job.task.repo, number: job.task.number, fix_patch: patch }) + '\n',
          'utf8',
        );
      }
      done += 1;
      console.log(`[done ${done}/${jobs.length}] arm=${record.arm} task=${record.instance_id} status=${record.status} patch=${record.patchBytes}B time=${record.runSeconds}s reqs=${record.traceAudit?.totalModelRequests ?? record.diagnostics?.modelRequests ?? '?'}`);
    }
  });
  await Promise.all(workers);
  console.log(`\nBatch '${args.batch}' finished. Run official scorer:\n  .venv/bin/python scripts/score-and-report.py --batch ${args.batch}`);
}

main().catch((err) => {
  console.error(`[run-msb-eval] FATAL: ${err.message || err}`);
  process.exit(1);
});
