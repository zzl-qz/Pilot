#!/usr/bin/env node
/**
 * Pilot 轨迹自动审计脚本（A–L 12 类缺陷信号提取，跨平台）。
 *
 * 数据来源：
 * - <PILOT_DATA_DIR>/pilot.db（sessions / runs / events / conversation_messages）
 * - <PILOT_DATA_DIR>/traces/<sessionId>.jsonl
 * - 可选：运行中的 http://127.0.0.1:<port>/api/sessions/<sid>/diagnostics
 * - 可选：--workspace <dir> 与 --base <sha> 下的 git diff / git status
 *
 * 严禁读取或打印 settings 表中的任何密钥。
 */

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { DatabaseSync } from 'node:sqlite';

const DEFAULT_MAIN_REPO = path.resolve(
  process.env.PILOT_MAIN_REPO || path.join(os.homedir(), 'Desktop', 'pilot')
);
const DEFAULT_DATA_DIR = path.resolve(
  process.env.PILOT_DATA_DIR || path.join(DEFAULT_MAIN_REPO, '.pilot')
);
const DEFAULT_BASE_URL = process.env.PILOT_BASE_URL || 'http://127.0.0.1:9999';

const EDIT_TOOLS = new Set(['edit_file', 'create_file', 'apply_patch', 'write_file', 'delete_file']);
const DOC_ONLY_EXT = /\.(md|mdx|txt|rst)$/i;
const READ_SEARCH_TOOLS = new Set([
  'read_file',
  'search_text',
  'grep_files',
  'glob_files',
  'find_files',
  'list_files',
  'list_directory',
  'workspace_outline',
  'git_status',
  'git_diff',
  'git_log',
  'command_logs',
  'command_status',
  'inspect_file',
  'search_files',
]);

function parseArgs(argv) {
  const opts = {
    session: '',
    workspace: '',
    base: 'HEAD',
    dataDir: DEFAULT_DATA_DIR,
    baseUrl: DEFAULT_BASE_URL,
    allowedPrefixes: [],
    json: false,
    jsonOut: '',
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--session' || arg === '-s') opts.session = argv[++i] || '';
    else if (arg === '--workspace' || arg === '-w') opts.workspace = argv[++i] || '';
    else if (arg === '--base') opts.base = argv[++i] || 'HEAD';
    else if (arg === '--data-dir') opts.dataDir = path.resolve(argv[++i] || DEFAULT_DATA_DIR);
    else if (arg === '--base-url') opts.baseUrl = argv[++i] || DEFAULT_BASE_URL;
    else if (arg === '--allowed-prefix') opts.allowedPrefixes.push(argv[++i] || '');
    else if (arg === '--json') opts.json = true;
    else if (arg === '--json-out') opts.jsonOut = argv[++i] || '';
    else if (arg === '--help' || arg === '-h') {
      console.log(
        [
          '用法: node trace-audit.mjs --session <sid> [options]',
          '  --workspace <dir>         任务 worktree 路径（用于范围、越界、临时文件与 diff 检查）',
          '  --base <sha>              diff 基准（默认 HEAD）',
          '  --data-dir <dir>          PILOT_DATA_DIR（默认 ~/Desktop/pilot/.pilot）',
          '  --allowed-prefix <prefix> 允许修改的路径前缀（可多次传入）',
          '  --json                    仅输出完整 JSON',
          '  --json-out <file>         将结构化 JSON 写入文件',
        ].join('\n')
      );
      process.exit(0);
    }
  }
  if (!opts.session) throw new Error('必须提供 --session <sessionId>');
  return opts;
}

function safeJsonParse(raw, fallback = null) {
  if (!raw || typeof raw !== 'string') return fallback;
  try {
    return JSON.parse(raw);
  } catch {
    return fallback;
  }
}

function median(values) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
}

function round(num, digits = 4) {
  if (num === null || num === undefined || Number.isNaN(num)) return null;
  const factor = 10 ** digits;
  return Math.round(num * factor) / factor;
}

function readDbSession(dbPath, sessionId) {
  if (!fs.existsSync(dbPath)) throw new Error(`未找到数据库: ${dbPath}`);
  const db = new DatabaseSync(dbPath, { readOnly: true });
  try {
    const session = db
      .prepare('SELECT id, title, workspace, created_at, updated_at, status, sequence FROM sessions WHERE id = ?')
      .get(sessionId);
    if (!session) {
      const prefixMatches = db
        .prepare('SELECT id, title FROM sessions WHERE id LIKE ? LIMIT 5')
        .all(`${sessionId}%`);
      if (prefixMatches.length === 1) {
        return readDbSessionFromHandle(db, prefixMatches[0].id);
      }
      throw new Error(`未找到会话 ${sessionId}`);
    }
    return readDbSessionFromHandle(db, session.id);
  } finally {
    db.close();
  }
}

function readDbSessionFromHandle(db, resolvedId) {
  const session = db
    .prepare('SELECT id, title, workspace, created_at, updated_at, status, sequence FROM sessions WHERE id = ?')
    .get(resolvedId);
  const runs = db
    .prepare('SELECT id, status, started_at, finished_at FROM runs WHERE session_id = ? ORDER BY started_at ASC')
    .all(resolvedId);
  const events = db
    .prepare('SELECT sequence, run_id, type, payload, created_at FROM events WHERE session_id = ? ORDER BY sequence ASC')
    .all(resolvedId)
    .map((row) => ({
      sequence: Number(row.sequence),
      runId: row.run_id,
      type: row.type,
      payload: safeJsonParse(row.payload, {}),
      createdAt: row.created_at,
    }));
  const messages = db
    .prepare('SELECT seq, role, content, tool_calls, tool_call_id, created_at FROM conversation_messages WHERE session_id = ? ORDER BY seq ASC')
    .all(resolvedId);
  return { session, runs, events, messages };
}

function readTraceJsonl(dataDir, sessionId) {
  const tracePath = path.join(dataDir, 'traces', `${sessionId}.jsonl`);
  if (!fs.existsSync(tracePath)) return { path: tracePath, exists: false, entries: [] };
  const lines = fs.readFileSync(tracePath, 'utf8').split(/\r?\n/).filter(Boolean);
  const entries = [];
  for (const line of lines) {
    const parsed = safeJsonParse(line);
    if (parsed) entries.push(parsed);
  }
  return { path: tracePath, exists: true, entries };
}

async function fetchDiagnostics(baseUrl, sessionId) {
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 3000);
    try {
      const resp = await fetch(`${baseUrl.replace(/\/$/, '')}/api/sessions/${encodeURIComponent(sessionId)}/diagnostics`, {
        signal: controller.signal,
      });
      if (!resp.ok) return null;
      return await resp.json();
    } finally {
      clearTimeout(timer);
    }
  } catch {
    return null;
  }
}

function inspectWorkspaceGit(workspace, baseRef, allowedPrefixes) {
  if (!workspace || !fs.existsSync(workspace)) return null;
  const st = spawnSync('git', ['-C', workspace, 'status', '--porcelain=v1'], { encoding: 'utf8' });
  const diff = spawnSync('git', ['-C', workspace, 'diff', '--name-only', baseRef], { encoding: 'utf8' });
  const untracked = [];
  const modifiedWorking = [];
  for (const line of (st.stdout || '').split(/\r?\n/)) {
    if (!line.trim()) continue;
    const code = line.slice(0, 2);
    const filePath = line.slice(3).trim();
    if (code === '??') untracked.push(filePath);
    else modifiedWorking.push(filePath);
  }
  const diffFiles = (diff.stdout || '')
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean);
  const allTouched = [...new Set([...diffFiles, ...modifiedWorking, ...untracked])];
  const tempArtifacts = allTouched.filter((p) =>
    /(^|\/)(\.tmp-verify|\.tmp-electron-check|\.playwright-cli|shot-.*\.png|mvn-run\.(log|err))/.test(p)
  );
  const outOfScope =
    allowedPrefixes.length > 0
      ? allTouched.filter((p) => !allowedPrefixes.some((pref) => p.startsWith(pref)))
      : [];
  return {
    workspace: path.resolve(workspace),
    baseRef,
    diffFiles,
    modifiedWorking,
    untracked,
    allTouched,
    tempArtifacts,
    outOfScope,
  };
}

function extractPathFromArgs(args) {
  if (!args || typeof args !== 'object') return '';
  return String(args.path || args.file || args.filePath || args.target || '').trim();
}

function isErrorResult(entry, payload) {
  if (entry && entry.decision && entry.decision !== 'EXECUTED') return true;
  const text = String(
    (entry && entry.resultSummary) || (payload && (payload.resultSummary || payload.output || payload.error)) || ''
  );
  if (!text) return false;
  return /^(错误|失败|Error:|Exception|拒绝|denied|failed|exitCode=[1-9]|退出码\s*[1-9]|命令失败)/i.test(text.trim()) ||
    /\b(exitCode|exit_code)\s*[:=]\s*[1-9]\d*\b/i.test(text) ||
    /未找到匹配|找不到文件|not found|does not match|multiple matches|失败：/i.test(text.slice(0, 260));
}

function isOutsideWorkspacePath(rawPath, workspace) {
  if (!rawPath || !workspace) return false;
  if (rawPath.startsWith('../') || rawPath.includes('/../') || rawPath.startsWith('..\\')) return true;
  if (path.isAbsolute(rawPath)) {
    const resolvedWs = path.resolve(workspace);
    const resolvedTarget = path.resolve(rawPath);
    return resolvedTarget !== resolvedWs && !resolvedTarget.startsWith(resolvedWs + path.sep);
  }
  return false;
}

function analyze({ session, runs, events, messages, trace, diagnostics, gitInfo }) {
  const workspace = gitInfo?.workspace || session.workspace || '';
  const traceByCallId = new Map();
  for (const t of trace.entries) {
    if (t.callId) traceByCallId.set(t.callId, t);
  }

  // Timeline assembly
  const toolCalls = [];
  const callById = new Map();
  const modelRequests = [];
  const compactions = [];
  const planUpdates = [];
  const executionUpdates = [];
  const askUsers = [];
  let steerCount = 0;
  let continueCount = 0;
  let failedRuns = 0;
  let completedRuns = 0;
  let cancelledRuns = 0;
  let finalSummary = '';

  // Sequence continuity (L)
  const seqGaps = [];
  const seqDuplicates = [];
  let prevSeq = null;

  for (const ev of events) {
    if (prevSeq !== null) {
      if (ev.sequence === prevSeq) seqDuplicates.push(ev.sequence);
      else if (ev.sequence !== prevSeq + 1) seqGaps.push({ from: prevSeq, to: ev.sequence });
    }
    prevSeq = ev.sequence;

    const p = ev.payload || {};
    switch (ev.type) {
      case 'tool.started': {
        const callId = p.callId || `seq_${ev.sequence}`;
        const traceEntry = traceByCallId.get(callId);
        const args = safeJsonParse(traceEntry?.argsSummary, null) || p.arguments || p.args || {};
        const item = {
          index: toolCalls.length + 1,
          sequence: ev.sequence,
          callId,
          name: p.name || p.tool || traceEntry?.name || 'unknown',
          summary: p.summary || '',
          args,
          decision: traceEntry?.decision || 'EXECUTED',
          latencyMs: traceEntry?.latencyMs ?? null,
          resultSummary: traceEntry?.resultSummary || '',
          failed: false,
          modelReqIndexBefore: modelRequests.length,
        };
        toolCalls.push(item);
        callById.set(callId, item);
        break;
      }
      case 'tool.completed': {
        const callId = p.callId || '';
        const item = callById.get(callId);
        const traceEntry = traceByCallId.get(callId);
        if (item) {
          if (!item.resultSummary) item.resultSummary = String(p.resultSummary || p.output || '');
          if (item.latencyMs === null && p.latencyMs !== undefined) item.latencyMs = p.latencyMs;
          item.failed = isErrorResult(traceEntry, p);
        }
        break;
      }
      case 'model.request': {
        const rep = p.report || p;
        modelRequests.push({
          index: modelRequests.length + 1,
          sequence: ev.sequence,
          purpose: rep.purpose || 'unknown',
          model: rep.model || '',
          status: rep.status || 'unknown',
          durationMs: rep.durationMs ?? null,
          firstTokenMs: rep.firstTokenMs ?? null,
          usage: rep.usage || {},
          context: rep.context || {},
          prefix: rep.prefix || {},
        });
        break;
      }
      case 'context.compacted':
        compactions.push({
          sequence: ev.sequence,
          coveredMessages: p.coveredMessages ?? null,
          charactersBefore: p.charactersBefore ?? null,
          charactersAfter: p.charactersAfter ?? null,
        });
        break;
      case 'plan.updated':
        planUpdates.push({ sequence: ev.sequence, items: p.items || p.plan || [] });
        break;
      case 'execution.updated':
        executionUpdates.push({ sequence: ev.sequence, ...p });
        break;
      case 'question.asked':
      case 'user.question':
        askUsers.push({ sequence: ev.sequence, ...p });
        break;
      case 'run.steered':
        steerCount++;
        break;
      case 'run.continued':
        continueCount++;
        break;
      case 'run.completed':
        completedRuns++;
        if (typeof p.summary === 'string') finalSummary = p.summary;
        break;
      case 'run.failed':
        failedRuns++;
        break;
      case 'run.cancelled':
        cancelledRuns++;
        break;
      default:
        break;
    }
  }

  // Also detect ask_user from toolCalls if no separate event emitted
  for (const tc of toolCalls) {
    if (tc.name === 'ask_user' && !askUsers.some((q) => q.callId === tc.callId)) {
      askUsers.push({ sequence: tc.sequence, callId: tc.callId, args: tc.args });
    }
  }

  // A: Positioning efficiency & unchanged file re-reads
  let firstEditCallIndex = null;
  let requestsBeforeFirstEdit = null;
  let lastEditCallIndex = null;
  let lastCodeEditCallIndex = null;
  let readSearchCount = 0;
  let maxConsecutiveNonEdit = 0;
  let currentNonEditStreak = 0;

  const lastReadStateByPath = new Map(); // path -> { modifiedSinceRead: boolean, ranges: Set<string>, count: number }
  let duplicateUnchangedReads = 0;
  let duplicateExactRangeReads = 0;
  const duplicateReadDetails = [];

  // D: Edit stats
  let editTotal = 0;
  let codeEditTotal = 0;
  let editFailures = 0;
  const editCountByPath = new Map();

  // C: Out-of-workspace path attempts
  const outOfWorkspaceToolArgs = [];

  // E: Verification commands
  const commands = [];
  let postLastEditCheckPassed = false;
  let postLastEditChecks = [];

  // F: Tool errors, pagination, shell read workarounds
  const errorsByTool = {};
  let paginationReadCount = 0;
  let shellReadWorkarounds = [];

  for (const tc of toolCalls) {
    const isEdit = EDIT_TOOLS.has(tc.name);
    const isRead = READ_SEARCH_TOOLS.has(tc.name);
    const targetPath = extractPathFromArgs(tc.args);

    if (targetPath && isOutsideWorkspacePath(targetPath, workspace)) {
      outOfWorkspaceToolArgs.push({ index: tc.index, tool: tc.name, path: targetPath });
    }

    if (isRead) readSearchCount++;

    if (isEdit) {
      if (firstEditCallIndex === null) {
        firstEditCallIndex = tc.index;
        requestsBeforeFirstEdit = tc.modelReqIndexBefore;
      }
      lastEditCallIndex = tc.index;
      if (!targetPath || !DOC_ONLY_EXT.test(targetPath)) {
        lastCodeEditCallIndex = tc.index;
        codeEditTotal++;
      }
      currentNonEditStreak = 0;
      editTotal++;
      if (tc.failed) editFailures++;
      if (targetPath) {
        editCountByPath.set(targetPath, (editCountByPath.get(targetPath) || 0) + 1);
        const st = lastReadStateByPath.get(targetPath);
        if (st) st.modifiedSinceRead = true;
      }
    } else {
      currentNonEditStreak++;
      if (currentNonEditStreak > maxConsecutiveNonEdit) {
        maxConsecutiveNonEdit = currentNonEditStreak;
      }
    }

    if (tc.name === 'read_file' && targetPath) {
      const start = tc.args.start_line ?? tc.args.startLine ?? 1;
      const count = tc.args.line_count ?? tc.args.lineCount ?? 'default';
      const rangeKey = `${start}:${count}`;
      if (Number(start) > 1) paginationReadCount++;
      const prev = lastReadStateByPath.get(targetPath);
      if (prev && !prev.modifiedSinceRead) {
        if (prev.ranges.has(rangeKey)) {
          duplicateExactRangeReads++;
          duplicateUnchangedReads++;
          duplicateReadDetails.push({ index: tc.index, path: targetPath, range: rangeKey, exact: true });
        } else {
          prev.ranges.add(rangeKey);
        }
      } else {
        lastReadStateByPath.set(targetPath, {
          modifiedSinceRead: false,
          ranges: new Set([rangeKey]),
        });
      }
    }

    if (tc.name === 'command_logs') {
      paginationReadCount++;
    }

    if (tc.name === 'run_command' || tc.name === 'start_command') {
      const cmdStr = String(tc.args.command || tc.summary || '');
      const isTestOrVerify = /\b(mvnw|mvn|npm\s+test|vitest|jest|pytest|quality-gate|spotless|checkstyle|node\s+.*test)\b/i.test(cmdStr);
      const isShellRead = /(^|[;&|]\s*)(cat|head|tail|sed|awk|nl|Get-Content)\s+/i.test(cmdStr);
      if (isShellRead) shellReadWorkarounds.push({ index: tc.index, command: cmdStr.slice(0, 180) });
      commands.push({
        index: tc.index,
        tool: tc.name,
        command: cmdStr.slice(0, 260),
        failed: tc.failed,
        isTestOrVerify,
      });
    }

    if (tc.failed) {
      errorsByTool[tc.name] = (errorsByTool[tc.name] || 0) + 1;
    }
  }

  // Re-evaluate post-last-code-edit checks (doc-only edits like tasks.md do not invalidate prior code tests)
  const effectiveEditIndex = lastCodeEditCallIndex ?? lastEditCallIndex;
  if (effectiveEditIndex !== null) {
    postLastEditChecks = commands.filter((c) => c.index > effectiveEditIndex && c.isTestOrVerify);
    postLastEditCheckPassed = postLastEditChecks.some((c) => !c.failed);
  } else {
    postLastEditChecks = commands.filter((c) => c.isTestOrVerify);
    postLastEditCheckPassed = postLastEditChecks.some((c) => !c.failed);
  }

  // B & I: Scan conversation_messages for internal reminders and prune markers
  let repeatReminders = 0;
  let stuckReminders = 0;
  let investigationReminders = 0;
  let prunedToolMessages = 0;
  let truncatedToolMessages = 0;
  for (const m of messages) {
    const content = String(m.content || '');
    if (m.role === 'user' || m.role === 'system') {
      if (/连续多次.*相同|重复调用|repeat/i.test(content) && /提醒|提示|不要重复/.test(content)) {
        repeatReminders++;
      }
      if (/连续多步无新|停滞|卡住|长时间没有进入编辑|调研阶段已|只读/.test(content)) {
        investigationReminders++;
      }
      if (/stuck|无进展|空转/.test(content)) {
        stuckReminders++;
      }
    }
    if (m.role === 'tool') {
      if (content.includes('[已裁剪') || content.includes('[pruned') || content.startsWith('（已裁剪')) {
        prunedToolMessages++;
      }
      if (content.includes('已截断') || content.includes('truncated')) {
        truncatedToolMessages++;
      }
    }
  }

  // G: Plan & subagent stats
  const finalPlan = planUpdates.length ? planUpdates[planUpdates.length - 1].items : [];
  const planCompleted = finalPlan.filter((i) => String(i.status || '').toLowerCase() === 'completed').length;
  const subagentCalls = toolCalls.filter((t) => t.name === 'spawn_agent').length;
  const subagentRequests = modelRequests.filter((r) => r.purpose === 'subagent').length;

  // H: Prompt cache stability (focus on main requests + all observed)
  const mainRequests = modelRequests.filter((r) => r.purpose === 'main');
  const cacheRatesMain = [];
  const prefixChangeCounts = {};
  const nonAppendOnlyMain = [];
  let totalInputTokens = 0;
  let totalCachedInputTokens = 0;
  let totalOutputTokens = 0;
  let totalReasoningTokens = 0;
  let peakContextTokens = 0;
  let failedModelRequests = 0;

  for (const r of modelRequests) {
    const inp = Number(r.usage?.inputTokens || 0);
    const cached = Number(r.usage?.cachedInputTokens || 0);
    const out = Number(r.usage?.outputTokens || 0);
    const reasoning = Number(r.usage?.reasoningTokens || 0);
    const ctxTok = Number(r.context?.totalTokens || 0);
    totalInputTokens += inp;
    totalCachedInputTokens += cached;
    totalOutputTokens += out;
    totalReasoningTokens += reasoning;
    if (ctxTok > peakContextTokens) peakContextTokens = ctxTok;
    if (r.status !== 'completed') failedModelRequests++;

    if (r.purpose === 'main') {
      const change = r.prefix?.change || 'unknown';
      prefixChangeCounts[change] = (prefixChangeCounts[change] || 0) + 1;
      if (change !== 'append_only' && change !== 'initial') {
        nonAppendOnlyMain.push({ index: r.index, sequence: r.sequence, change });
      }
      // Exclude very first main request (cold start) from steady-state cache hit rate if more than 1 request
      if (r.usage?.cacheReadObserved && inp > 0) {
        cacheRatesMain.push({ index: r.index, rate: cached / inp, inp, cached });
      }
    }
  }
  const steadyCacheRates =
    cacheRatesMain.length > 1 ? cacheRatesMain.slice(1).map((x) => x.rate) : cacheRatesMain.map((x) => x.rate);
  const medianCacheHitRate = median(steadyCacheRates);
  const minCacheHitRate = steadyCacheRates.length ? Math.min(...steadyCacheRates) : null;
  const overallCacheHitRate = totalInputTokens > 0 ? totalCachedInputTokens / totalInputTokens : null;

  // J: Completion / claim consistency checks
  const completionMismatches = [];
  const summaryClaimsTestsPass = /(测试.*通过|全通过|全部通过|tests?\s+pass|0\s*failures?)/i.test(finalSummary);
  if (session.status === 'COMPLETED' && codeEditTotal > 0 && !postLastEditCheckPassed) {
    completionMismatches.push('会话标记 COMPLETED 且有代码修改，但最后一次代码编辑之后未观测到通过的测试/校验命令');
  }
  if (summaryClaimsTestsPass && codeEditTotal > 0 && !postLastEditCheckPassed) {
    completionMismatches.push('最终总结声称测试通过，但最后一次代码编辑之后没有通过的测试/校验命令证据');
  }
  if (gitInfo && gitInfo.tempArtifacts.length > 0) {
    completionMismatches.push(`工作区遗留临时文件: ${gitInfo.tempArtifacts.join(', ')}`);
  }

  // Alerts (A–L)
  const alerts = [];
  if (duplicateExactRangeReads >= 3) {
    alerts.push({ category: 'A', severity: 'P2', message: `同一文件未修改重复读取同范围 ${duplicateExactRangeReads} 次` });
  }
  if (requestsBeforeFirstEdit !== null && requestsBeforeFirstEdit >= 10) {
    alerts.push({ category: 'A', severity: 'P2', message: `首次编辑前模型请求数偏高 (${requestsBeforeFirstEdit})` });
  }
  if (maxConsecutiveNonEdit >= 15) {
    alerts.push({ category: 'B', severity: 'P1', message: `连续非编辑工具调用达 ${maxConsecutiveNonEdit} 次（存在调研瘫痪风险）` });
  }
  if (outOfWorkspaceToolArgs.length > 0) {
    alerts.push({ category: 'C', severity: 'P0', message: `检测到工作区外路径参数 ${outOfWorkspaceToolArgs.length} 次` });
  }
  if (gitInfo && gitInfo.tempArtifacts.length > 0) {
    alerts.push({ category: 'C', severity: 'P2', message: `工作区遗留临时产物: ${gitInfo.tempArtifacts.join(', ')}` });
  }
  if (gitInfo && gitInfo.outOfScope.length > 0) {
    alerts.push({ category: 'C', severity: 'P1', message: `存在范围外改动文件 (${gitInfo.outOfScope.length}): ${gitInfo.outOfScope.slice(0, 5).join(', ')}` });
  }
  if (editFailures >= 3) {
    alerts.push({ category: 'D', severity: 'P2', message: `编辑工具失败次数较多 (${editFailures}/${editTotal})` });
  }
  if (codeEditTotal > 0 && !postLastEditCheckPassed) {
    alerts.push({ category: 'E', severity: 'P1', message: '存在代码改动但最后代码编辑后未见合格验证通过' });
  }
  if (shellReadWorkarounds.length > 0) {
    alerts.push({ category: 'F', severity: 'P2', message: `检测到 shell 读取绕路 ${shellReadWorkarounds.length} 次` });
  }
  if (medianCacheHitRate !== null && medianCacheHitRate < 0.8) {
    alerts.push({
      category: 'H',
      severity: 'P1',
      message: `稳态缓存命中率中位数偏低 (${(medianCacheHitRate * 100).toFixed(1)}% < 80%)`,
    });
  }
  if (nonAppendOnlyMain.length > compactions.length + 1) {
    alerts.push({
      category: 'H',
      severity: 'P1',
      message: `非 append_only 前缀变化过多 (${nonAppendOnlyMain.length} 次，压缩仅 ${compactions.length} 次)`,
    });
  }
  if (completionMismatches.length > 0) {
    alerts.push({ category: 'J', severity: 'P0', message: completionMismatches.join('；') });
  }
  if (seqGaps.length > 0 || seqDuplicates.length > 0) {
    alerts.push({
      category: 'L',
      severity: 'P1',
      message: `事件序号异常: gaps=${seqGaps.length}, duplicates=${seqDuplicates.length}`,
    });
  }
  if (failedModelRequests > 0) {
    alerts.push({ category: 'L', severity: 'P2', message: `存在失败模型请求 ${failedModelRequests} 次` });
  }

  return {
    meta: {
      sessionId: session.id,
      title: session.title,
      workspace,
      status: session.status,
      createdAt: session.created_at,
      updatedAt: session.updated_at,
      runs: runs.length,
      events: events.length,
      traceFileFound: trace.exists,
      diagnosticsLive: Boolean(diagnostics),
    },
    signals: {
      A_positioning: {
        totalToolCalls: toolCalls.length,
        readSearchCalls: readSearchCount,
        readRatio: toolCalls.length ? round(readSearchCount / toolCalls.length) : 0,
        toolCallsBeforeFirstEdit: firstEditCallIndex !== null ? firstEditCallIndex - 1 : toolCalls.length,
        requestsBeforeFirstEdit,
        duplicateExactRangeReads,
        duplicateUnchangedReads,
        duplicateReadSamples: duplicateReadDetails.slice(0, 10),
      },
      B_stagnation: {
        maxConsecutiveNonEdit,
        repeatReminders,
        stuckReminders,
        investigationReminders,
        executionTransitions: executionUpdates.length,
      },
      C_scope: {
        outOfWorkspaceToolArgs,
        git: gitInfo,
      },
      D_editing: {
        editTotal,
        editFailures,
        editSuccessRate: editTotal ? round((editTotal - editFailures) / editTotal) : null,
        repeatedEditFiles: [...editCountByPath.entries()]
          .filter(([, count]) => count >= 3)
          .map(([file, count]) => ({ file, count })),
      },
      E_verification: {
        totalCommands: commands.length,
        testOrVerifyCommands: commands.filter((c) => c.isTestOrVerify).length,
        postLastEditChecks: postLastEditChecks.length,
        postLastEditCheckPassed,
      },
      F_toolErrorsAndPaging: {
        errorsByTool,
        totalToolErrors: Object.values(errorsByTool).reduce((a, b) => a + b, 0),
        paginationReadCount,
        shellReadWorkarounds: shellReadWorkarounds.slice(0, 10),
      },
      G_planAndSubagent: {
        planUpdates: planUpdates.length,
        finalPlanItems: finalPlan.length,
        finalPlanCompleted: planCompleted,
        subagentCalls,
        subagentRequests,
      },
      H_cacheStability: {
        mainRequests: mainRequests.length,
        observedCacheSamples: steadyCacheRates.length,
        medianCacheHitRate: round(medianCacheHitRate),
        minCacheHitRate: round(minCacheHitRate),
        overallCacheHitRate: round(overallCacheHitRate),
        prefixChangeCounts,
        nonAppendOnlyMainCount: nonAppendOnlyMain.length,
        nonAppendOnlyMainSamples: nonAppendOnlyMain.slice(0, 10),
      },
      I_contextAndCompaction: {
        compactions: compactions.length,
        compactionDetails: compactions,
        prunedToolMessages,
        truncatedToolMessages,
        peakContextTokens,
      },
      J_completionConsistency: {
        completedRuns,
        failedRuns,
        cancelledRuns,
        summaryClaimsTestsPass,
        mismatches: completionMismatches,
      },
      K_interaction: {
        askUserCount: askUsers.length,
        askUserSamples: askUsers.slice(0, 5),
        steerCount,
        continueCount,
      },
      L_runtimeAndCost: {
        seqGapsCount: seqGaps.length,
        seqDuplicatesCount: seqDuplicates.length,
        totalModelRequests: modelRequests.length,
        failedModelRequests,
        totalInputTokens,
        totalCachedInputTokens,
        uncachedInputTokens: Math.max(0, totalInputTokens - totalCachedInputTokens),
        totalOutputTokens,
        totalReasoningTokens,
        elapsedSeconds: diagnostics?.elapsedSeconds ?? null,
      },
    },
    alerts,
  };
}

function printSummary(report) {
  const { meta, signals, alerts } = report;
  const pct = (v) => (v === null || v === undefined ? 'n/a' : `${(v * 100).toFixed(1)}%`);
  console.log(`=== Trace Audit: ${meta.sessionId} ===`);
  console.log(`标题: ${meta.title} | 状态: ${meta.status} | Runs: ${meta.runs} | Events: ${meta.events}`);
  console.log(
    `[A 定位] tools=${signals.A_positioning.totalToolCalls}, readRatio=${pct(signals.A_positioning.readRatio)}, ` +
      `reqBeforeEdit=${signals.A_positioning.requestsBeforeFirstEdit ?? 'none'}, dupRead=${signals.A_positioning.duplicateExactRangeReads}`
  );
  console.log(
    `[B 空转] maxNonEditStreak=${signals.B_stagnation.maxConsecutiveNonEdit}, ` +
      `reminders(repeat=${signals.B_stagnation.repeatReminders}, inv=${signals.B_stagnation.investigationReminders}, stuck=${signals.B_stagnation.stuckReminders})`
  );
  console.log(
    `[D/E 编辑核验] edits=${signals.D_editing.editTotal} (fail=${signals.D_editing.editFailures}), ` +
      `postEditCheckPassed=${signals.E_verification.postLastEditCheckPassed} (postChecks=${signals.E_verification.postLastEditChecks})`
  );
  console.log(
    `[F 工具] toolErrors=${signals.F_toolErrorsAndPaging.totalToolErrors}, paging=${signals.F_toolErrorsAndPaging.paginationReadCount}, ` +
      `shellRead=${signals.F_toolErrorsAndPaging.shellReadWorkarounds.length}`
  );
  console.log(
    `[H 缓存] mainReqs=${signals.H_cacheStability.mainRequests}, medianHit=${pct(signals.H_cacheStability.medianCacheHitRate)}, ` +
      `overallHit=${pct(signals.H_cacheStability.overallCacheHitRate)}, nonAppendOnly=${signals.H_cacheStability.nonAppendOnlyMainCount}`
  );
  console.log(
    `[I 压缩] compactions=${signals.I_contextAndCompaction.compactions}, prunedMsgs=${signals.I_contextAndCompaction.prunedToolMessages}, ` +
      `peakCtx=${signals.I_contextAndCompaction.peakContextTokens}`
  );
  console.log(
    `[K/L 交互成本] ask=${signals.K_interaction.askUserCount}, steer=${signals.K_interaction.steerCount}, ` +
      `cont=${signals.K_interaction.continueCount}, reqs=${signals.L_runtimeAndCost.totalModelRequests}, ` +
      `in=${signals.L_runtimeAndCost.totalInputTokens} (uncached=${signals.L_runtimeAndCost.uncachedInputTokens}), out=${signals.L_runtimeAndCost.totalOutputTokens}`
  );
  if (alerts.length === 0) {
    console.log('告警: 无');
  } else {
    console.log(`告警 (${alerts.length}):`);
    for (const a of alerts) {
      console.log(`  - [${a.severity}][${a.category}] ${a.message}`);
    }
  }
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const dbPath = path.join(opts.dataDir, 'pilot.db');
  const { session, runs, events, messages } = readDbSession(dbPath, opts.session);
  const trace = readTraceJsonl(opts.dataDir, session.id);
  const diagnostics = await fetchDiagnostics(opts.baseUrl, session.id);
  const gitInfo = opts.workspace ? inspectWorkspaceGit(opts.workspace, opts.base, opts.allowedPrefixes) : null;
  const report = analyze({ session, runs, events, messages, trace, diagnostics, gitInfo });

  if (opts.jsonOut) {
    fs.mkdirSync(path.dirname(path.resolve(opts.jsonOut)), { recursive: true });
    fs.writeFileSync(opts.jsonOut, JSON.stringify(report, null, 2) + '\n', 'utf8');
  }
  if (opts.json) {
    console.log(JSON.stringify(report, null, 2));
  } else {
    printSummary(report);
  }
}

main().catch((err) => {
  console.error(`[trace-audit] 错误: ${err.message || err}`);
  process.exit(1);
});
