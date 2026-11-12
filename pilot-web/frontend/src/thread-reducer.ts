import type { ReducerState, ThreadState, ThreadRow, TurnRow, PlanRow, UserRow, ToolRow, TimelineInput, TimelineItem, ArbitrationRow } from './types/thread.js';
import type { EventEnvelope, EventMetadata, LiveOutput } from './types/events.js';
import type { MessageAttachment } from './types/runtime.js';
/**
 * SSE 事件 → 线程行模型的纯 reducer。
 *
 * <p>语义与旧版 handleEvent/各 handler 一一对应，只是把「直接改 DOM」换成
 * 「改行模型」。每行带 start/end 序号，供历史窗口裁剪用（与旧版
 * data-history-start/end 相同的边界规则，走 lib/history.js 的纯函数）。
 *
 * <p>作用域约定：这里的 draft 是 store 的<b>根</b> draft——
 * 线程字段一律走 draft.thread（rows/cursor/windowEvents/
 * replaying/history/activityCount），界面字段走 draft.ui（attentionKind 等），
 * 其余为根级运行状态（running/runId/pendingApprovals/...）。
 */
import {
  HISTORY_WINDOW_LIMIT,
  mergeHistoryEvents,
  trimHistoryWindowAtRows
} from './lib/history.js';
import { classifyRunFailure, isEphemeral, planProgressLabel } from './lib/events.js';
import { eventTimestamp, parseTimestamp, toolLabel } from './lib/tool-meta.js';
import { preview, estimateTokens } from './thread-utils.js';
import { splitAttachmentMarkers } from './lib/attachments.js';

/** 活动流保留上限，与旧版 MAX_TIMELINE_EVENTS 一致。 */
const MAX_TIMELINE_EVENTS = 300;

export const initialThread = (): ThreadState => ({
  /** 行注册表：[{ key, type, start, end, ... }]，显示顺序 = 数组顺序（旧→新）。 */
  rows: [],
  /** 当前渲染的事件窗口（升序），翻页游标与裁剪都用它。 */
  windowEvents: [],
  cursor: 0,
  replaying: false,
  history: { hasMore: false, hasNewer: false, loading: false, forwardError: false, error: false, pageSize: 80, loadId: 0 },
  activityCount: 0
});

/** 该事件是否来自历史回放窗口的 user.message（不在 EVENT_TYPES 里）。 */
export function isUserMessageType(event: { type: string }) {
  return event.type === 'user.message';
}

/** 计划跟着当前这段用户消息走。上一问的计划卡片留在原处，不再被后来的更新改掉。 */
function planInOpenSegment(draft: ReducerState): PlanRow | null {
  const rows = draft.thread.rows;
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    const row = rows[i];
    if (row.type === 'user') {
      return null;
    }
    if (row.type === 'plan') {
      return row;
    }
  }
  return null;
}

/** 子智能体可能挂在更早的回合上，完成事件不能只看最后一轮。 */
function turnWithAgent(draft: ReducerState, id: string | null | undefined): TurnRow | null {
  if (!id) {
    return null;
  }
  const rows = draft.thread.rows;
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    const row = rows[i];
    if (row.type === 'turn' && row.agents?.[id]) {
      return row;
    }
  }
  return null;
}

function openTurn(draft: ReducerState): TurnRow | null {
  const rows = draft.thread.rows;
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    const row = rows[i];
    if (row.type === 'user') {
      return null;
    }
    if (row.type === 'turn') {
      return row;
    }
  }
  return null;
}

/** 按 runId 找回那一次运行的回合。对不上就留空，不拿「最后一轮」凑。 */
function turnForRun(draft: ReducerState, runId: string | null | undefined): TurnRow | null {
  if (!runId) {
    return null;
  }
  const rows = draft.thread.rows;
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    const row = rows[i];
    if (row.type === 'turn' && row.runIds?.includes(runId)) {
      return row;
    }
  }
  return null;
}

/** 结束、失败、取消只收自己那一轮。runId 对不上时，才落到当前这段还开着的回合。 */
function turnToClose(draft: ReducerState, runId: string | null | undefined) {
  return turnForRun(draft, runId) || openTurn(draft);
}

/** 工具结果要回到发起它的那一轮。新一轮已经开了之后，不能写到更靠后的空回合上。 */
function turnOwning(draft: ReducerState, callId: string | null | undefined): TurnRow | null {
  const rows = draft.thread.rows;
  if (callId) {
    for (let i = rows.length - 1; i >= 0; i -= 1) {
      const row = rows[i];
      if (row.type === 'turn' && row.toolIndex?.[callId]) {
        return row;
      }
    }
  }
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    const row = rows[i];
    if (row.type === 'turn') {
      return row;
    }
  }
  return null;
}

function ensureTurn(draft: ReducerState): TurnRow {
  const thread = draft.thread;
  const rows = thread.rows;
  // 从末尾往前找。中间夹了用户消息，说明上一轮已经结束，不能把新工具接回去。
  let turn: TurnRow | null = null;
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    const row = rows[i];
    if (row.type === 'user') {
      break;
    }
    if (row.type === 'turn') {
      turn = row;
      break;
    }
  }
  if (!turn) {
    turn = {
      key: `turn:${draft.runId || 'live'}:${thread.cursor}`,
      type: 'turn',
      runIds: draft.runId ? [draft.runId] : [],
      start: thread.cursor,
      end: thread.cursor,
      tools: [],
      toolIndex: {},
      thinking: null,
      reply: { text: '', preview: '', live: false, needsBreak: false, breakReason: '', committed: '' },
      stats: null,
      agents: {}
    };
    thread.rows.push(turn);
  } else if (draft.runId && !turn.runIds.includes(draft.runId)) {
    turn.runIds.push(draft.runId);
  }
  return turn;
}

/**
 * 运行已经结束，却还挂在「运行中」的工具行：不可能再等到 tool.completed 了
 * （事件丢失、进程被中断、或整轮被取消）。留着转圈会让人以为卡死，统一收成中断态。
 */
function settleUnfinishedTools(turn: TurnRow | null, event: EventMetadata) {
  if (!turn?.tools) {
    return;
  }
  const finishedAt = eventTimestamp(event);
  for (const tool of turn.tools) {
    if (tool.status === 'run' || tool.status === 'wait') {
      tool.status = 'interrupted';
      tool.meta = tool.meta || '未返回结果';
      tool.finishedAt = finishedAt;
      turn.toolIndex[tool.callId] = { ...tool };
    }
  }
}

/** Immer 会分离数组与索引里的对象引用，更新工具后显式同步两个视图。 */
function syncToolRow(turn: TurnRow, tool: ToolRow) {
  for (let index = 0; index < turn.tools.length; index++) {
    if (turn.tools[index]?.callId === tool.callId) {
      turn.tools[index] = { ...turn.tools[index], ...tool };
    }
  }
  turn.toolIndex[tool.callId] = { ...tool };
}

/**
 * 这一轮已经结束。用量上的结束时间要写回这一轮自己的统计，
 * 否则界面会拿「现在」继续加秒，看起来像还在跑。
 */
function finishTurnClock(draft: ReducerState, turn: TurnRow | null, event: EventMetadata) {
  const finishedAt = eventTimestamp(event);
  if (draft.runUsage) {
    draft.runUsage.finishedAt = finishedAt;
  }
  if (!turn) {
    return;
  }
  if (turn.stats || draft.runUsage) {
    turn.stats = { ...(turn.stats || draft.runUsage), finishedAt };
  }
  if (turn.thinking?.live) {
    turn.thinking.live = false;
    turn.thinking.finishedAt = finishedAt;
  }
  for (const agent of Object.values(turn.agents || {})) {
    if (agent.status === 'running') {
      agent.status = 'stopped';
      agent.finishedAt = agent.finishedAt || finishedAt;
    }
    // Immer 在后续事件中会分离两个路径的引用，不能依赖最初赋值的对象别名同步状态。
    if (draft.agents[agent.id]) draft.agents[agent.id] = { ...agent };
  }
}

function settleRun(draft: ReducerState, event: EventMetadata) {
  const turn = turnToClose(draft, event.runId);
  draft.lastRunId = event.runId || draft.lastRunId;
  draft.running = false;
  draft.cancelling = false;
  draft.runStartedAt = 0;
  finishTurnClock(draft, turn, event);
  if (turn) {
    turn.reply.live = false;
    turn.end = event.sequence;
    settleUnfinishedTools(turn, event);
  }
  return turn;
}

function runResult(event: EventMetadata) {
  return {
    key: `result:${event.runId || event.sequence}`,
    type: 'result' as const,
    changedFiles: [],
    runIds: event.runId ? [event.runId] : [],
    start: event.sequence,
    end: event.sequence
  };
}

/** 正文或工具出来了，思考块就该收起（旧版 settleReasoning），秒数保留。 */
function settleThinking(turn: TurnRow, event: EventMetadata) {
  if (turn.thinking && turn.thinking.live) {
    turn.thinking.live = false;
    turn.thinking.finishedAt = eventTimestamp(event);
  }
}

function pushRow(draft: ReducerState, row: ThreadRow) {
  row.start = row.start ?? draft.thread.cursor;
  row.end = row.end ?? draft.thread.cursor;
  draft.thread.rows.push(row);
}

function formatBudgetCount(value: unknown) {
  const count = Number(value);
  return Number.isFinite(count) ? Math.max(0, count).toLocaleString() : '0';
}

function record(draft: ReducerState, event: EventEnvelope, entry: TimelineInput) {
  if (event.type !== 'assistant.delta' && event.type !== 'assistant.preview') {
    draft.streamCard = null;
  }
  // 活动流是 append-only 的，长任务不裁剪就会一屏几百条 <li> 陪着每帧重渲染
  while (draft.timeline.length >= MAX_TIMELINE_EVENTS) {
    draft.timeline.shift();
  }
  draft.thread.activityCount += 1;
  const item: TimelineItem = {
    key: `${event.type}:${event.sequence}:${draft.thread.activityCount}`,
    dispatchId: entry.dispatchId || null,
    kind: entry.kind,
    tone: entry.tone,
    status: entry.status,
    title: entry.title ?? null,
    detail: entry.detail ?? null,
    foot: entry.foot ?? null,
    sequence: event.sequence,
    type: event.type,
    timestamp: event.timestamp,
    actions: entry.actions || false,
    callId: entry.callId || null
  };
  if ((event.type === 'assistant.delta' || event.type === 'assistant.preview') && draft.streamCard) {
    const last = draft.timeline[draft.timeline.length - 1];
    if (last) {
      last.detail = preview((last._stream || '') + (entry.detail || ''), 160);
      last._stream = (last._stream || '') + (entry.detail || '');
      return;
    }
  }
  if (event.type === 'assistant.delta' || event.type === 'assistant.preview') {
    draft.streamCard = item.key;
    item._stream = entry.detail || '';
  }
  draft.timeline.push(item);
}

/** 用户消息行（run.started 的 input / user.message 回放 / 乐观发送共用）。 */
export function appendUserRow(draft: ReducerState, text: string, sequence: number | null | undefined, { attachments = [], imagePositions = [], pendingKey = null }: { attachments?: MessageAttachment[]; imagePositions?: number[]; pendingKey?: string | null } = {}): UserRow {
  const thread = draft.thread;
  const row: UserRow = {
    key: pendingKey || (Number.isFinite(sequence) ? `message:${sequence}` : `message:p${thread.activityCount}`),
    type: 'user',
    sequence: Number.isFinite(sequence) ? sequence! : null,
    text,
    attachments,
    imagePositions,
    start: sequence ?? thread.cursor,
    end: sequence ?? thread.cursor
  };
  thread.rows.push(row);
  return row;
}

function stampLastUserRow(draft: ReducerState, sequence: number, text: string, attachments: MessageAttachment[], imagePositions: number[]) {
  const rows = draft.thread.rows;
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    const row = rows[i];
    if (row.type === 'user' && row.sequence == null && Number.isFinite(sequence)) {
        row.sequence = sequence;
        row.key = `message:${sequence}`;
        row.start = sequence;
        row.end = sequence;
        row.text = text;
        row.attachments = attachments;
        row.imagePositions = imagePositions;
        return;
    }
  }
}

function replaceReasoning(turn: TurnRow, text: string) {
  if (!turn.thinking && text) turn.thinking = { text: '', live: true, action: '' };
  if (!turn.thinking) return;
  const previous = turn.thinking.preview || '';
  if (previous && turn.thinking.text.endsWith(previous)) turn.thinking.text = turn.thinking.text.slice(0, -previous.length);
  turn.thinking.text += text;
  turn.thinking.preview = text;
}

export function restoreLiveOutput(draft: ReducerState, snapshot: LiveOutput) {
  if (snapshot.baseSequence !== draft.thread.cursor) throw new Error('临时输出与正式历史不匹配');
  if (snapshot.runId && draft.runId && snapshot.runId !== draft.runId) throw new Error('临时输出运行不匹配');
  const startedAt = parseTimestamp(snapshot.reasoningStartedAt);
  const finishedAt = parseTimestamp(snapshot.reasoningFinishedAt);
  const turn = openTurn(draft) || (snapshot.text || snapshot.reasoning || startedAt != null ? ensureTurn(draft) : null);
  if (turn) {
    turn.reply.preview = snapshot.text;
    if (snapshot.text) turn.reply.live = true;
    replaceReasoning(turn, snapshot.reasoning);
    if (startedAt != null) {
      turn.thinking ??= { text: '', live: true, action: '' };
      turn.thinking.startedAt = turn.thinking.startedAt == null ? startedAt : Math.min(turn.thinking.startedAt, startedAt);
      turn.thinking.finishedAt = finishedAt ?? null;
      turn.thinking.live = finishedAt == null;
      turn.thinking.action = '';
    } else if (turn.thinking && snapshot.text) {
      // 旧服务端没有计时元数据：仍能判断正文已经开始，但不伪造思考时长。
      turn.thinking.live = false;
    }
    draft.liveOutputTokens = estimateTokens(turn.reply.text + snapshot.text);
  }
  draft.thread.previewId = snapshot.previewId;
  draft.thread.previewOffset = snapshot.previewOffset;
}

export function applyEvent(draft: ReducerState, event: EventEnvelope, { live }: { live: boolean }) {
  const thread = draft.thread;
  if (isEphemeral(event.type) && event.previewId && event.previewOffset != null) {
    if (thread.previewId === event.previewId && event.previewOffset <= (thread.previewOffset || 0)) return;
    thread.previewId = event.previewId; thread.previewOffset = event.previewOffset;
  }
  // 流式增量只用于界面，不推进游标（否则重连后正式事件会被判成重复而丢掉）
  if (!isEphemeral(event.type)) {
    thread.cursor = Math.max(thread.cursor, Number(event.sequence) || thread.cursor);
  }

  switch (event.type) {
    case 'user.message': {
      const payload = event.payload || {};
      appendUserRow(draft, payload.input || payload.text || '', event.sequence, {
        attachments: payload.attachments || [],
        imagePositions: payload.imagePositions || []
      });
      record(draft, event, { kind: 'run', tone: 'text', status: '提问', title: null, detail: preview(payload.input || payload.text || '', 160) });
      break;
    }

    case 'run.started': {
      draft.lastOutcome = '';
      draft.queueHeld = false;
      draft.running = true;
      draft.runStartedAt = eventTimestamp(event);
      draft.requestReports = [];
      draft.cancelling = false;
      draft.runId = event.runId || draft.runId;
      draft.turnRuns = event.runId ? [event.runId] : [];
      const startedAt = eventTimestamp(event);
      draft.runUsage = {
        runId: draft.runId,
        startedAt,
        finishedAt: null,
        modelRequests: 0,
        inputTokens: 0,
        outputTokens: 0,
        contextTokens: 0,
        contextLimit: Number(draft.contextStatus?.limitTokens || 1),
        exact: false,
        reported: false
      };
      draft.liveOutputTokens = 0;
      draft.modelRetryUntil = 0;
      if (live) {
        draft.ui.notice = null;
        draft.ui.attentionKind = 'running';
      }
      const payload = event.payload || {};
      const input = payload.input || '';
      const same = (a: unknown, b: unknown) => String(a || '').replace(/\s+/g, ' ').trim() === String(b || '').replace(/\s+/g, ' ').trim();
      const parsedInput = splitAttachmentMarkers(input);
      const pendingRow = draft.thread.rows.findLast((row): row is UserRow => row.type === 'user' && row.sequence == null);
      const receivedNames = [...parsedInput.files, ...(payload.attachments || [])].map(item => item.name).filter(Boolean).sort();
      const pendingNames = (pendingRow?.attachments || []).map(item => item.name).filter(Boolean).sort();
      const sameAttachments = receivedNames.length > 0 && receivedNames.length === pendingNames.length
        && receivedNames.every((name, index) => name === pendingNames[index]);
      const inputForMatch = parsedInput.files.length ? parsedInput.body : input;
      if (draft.pendingInputs.length && (same(draft.pendingInputs[0], inputForMatch) || sameAttachments)) {
        draft.pendingInputs.shift();
        stampLastUserRow(draft, event.sequence, input, payload.attachments || [], payload.imagePositions || []);
      } else if (draft.queue.length && same(draft.queue[0], input)) {
        draft.queue.shift();
        appendUserRow(draft, input, event.sequence, {
          attachments: payload.attachments || [],
          imagePositions: payload.imagePositions || []
        });
      } else if (input) {
        appendUserRow(draft, input, event.sequence, {
          attachments: payload.attachments || [],
          imagePositions: payload.imagePositions || []
        });
      }
      ensureTurn(draft);
      record(draft, event, { kind: 'run', tone: 'run', status: '开始', title: '运行开始', detail: input || null });
      if (live) {
        draft.promoteBlank = { input };
      }
      break;
    }

    case 'model.request': {
      const report = event.payload?.report;
      if (!report?.id) break;
      const current = !draft.runId || !event.runId || event.runId === draft.runId;
      if (current || String(event.runId || '').startsWith('compact_')) {
        const reports = draft.requestReports || [];
        if (!reports.some(item => item.id === report.id)) draft.requestReports = [...reports, report].slice(-200);
        if (report.purpose === 'main' && (!draft.latestContextReport || report.startedAt >= draft.latestContextReport.startedAt!))
          draft.latestContextReport = report;
      }
      if (!String(event.runId || '').startsWith('compact_')) {
        const turn = draft.thread.rows.findLast((row): row is TurnRow => row.type === 'turn' && row.runIds.includes(event.runId!)) || (current ? ensureTurn(draft) : null);
        if (turn && !(turn.requests || []).some(item => item.id === report.id)) turn.requests = [...(turn.requests || []), report].slice(-200);
      }
      break;
    }

    case 'run.usage': {
      const payload = event.payload || {};
      if (String(event.runId || '').startsWith('compact_')) {
        draft.runUsage = { ...payload, runId: event.runId, reported: true };
        break;
      }
      if (!draft.runUsage || (event.runId && draft.runUsage.runId && event.runId !== draft.runUsage.runId)) {
        draft.runUsage = { runId: event.runId || draft.runId, startedAt: eventTimestamp(event), finishedAt: null };
      }
      Object.assign(draft.runUsage, {
        modelRequests: Number(payload.modelRequests || 0),
        inputTokens: Number(payload.inputTokens || 0),
        outputTokens: Number(payload.outputTokens || 0),
        // 命中缓存的输入 token，与 inputTokens 同口径；端点不返回时为 0
        cachedInputTokens: Number(payload.cachedInputTokens || 0),
        contextTokens: Number(payload.contextTokens || 0),
        contextLimit: Math.max(1, Number(payload.contextLimit || 1)),
        exact: Boolean(payload.exact),
        details: payload.details || null,
        reported: true
      });
      draft.liveOutputTokens = 0;
      const turn = ensureTurn(draft);
      if (!String(event.runId || '').startsWith('compact_')) turn.stats = { ...draft.runUsage };
      draft.contextStatus = {
        ...(draft.contextStatus || {}),
        usedTokens: draft.runUsage.contextTokens,
        limitTokens: draft.runUsage.contextLimit,
        percent: Math.min(100, Math.round(draft.runUsage.contextTokens! / draft.runUsage.contextLimit! * 100)),
        estimated: true,
        canCompact: false
      };
      record(draft, event, { kind: 'run', tone: 'ok', status: '用量', title: null, detail: null });
      break;
    }

    case 'assistant.preview': {
      // 请求重试或输出被判为逐行复读：上一次尝试的片段作废，既不进正文也不进活动流。
      if (event.payload?.reset) {
        const previous = openTurn(draft);
        if (previous) {
          previous.reply.preview = '';
          draft.liveOutputTokens = estimateTokens(previous.reply.text || '');
        }
        break;
      }
      // 预览只是「模型还在吐字」，落库的 assistant.delta 才是正文：两段不能都往正文里接。
      const turn = ensureTurn(draft);
      turn.reply.preview = (turn.reply.preview || '') + (event.payload?.text || '');
      turn.reply.live = true;
      draft.liveOutputTokens = estimateTokens((turn.reply.text || '') + (turn.reply.preview || ''));
      if (event.payload?.text) settleThinking(turn, event);
      record(draft, event, { kind: 'run', tone: 'text', status: '增量', title: null, detail: preview(event.payload?.text || '', 160) });
      break;
    }

    case 'assistant.delta': {
      const turn = ensureTurn(draft);
      turn.reply.text += event.payload?.text || '';
      if (turn.thinking) turn.thinking.preview = '';
      thread.previewId = ''; thread.previewOffset = 0;
      turn.reply.preview = '';
      turn.reply.live = true;
      draft.liveOutputTokens = estimateTokens(turn.reply.text);
      settleThinking(turn, event);
      record(draft, event, { kind: 'run', tone: 'text', status: '增量', title: null, detail: preview(event.payload?.text || '', 160) });
      break;
    }

    case 'assistant.reasoning': {
      const turn = openTurn(draft);
      if (event.payload?.reset) {
        if (turn) replaceReasoning(turn, '');
        break;
      }
      const activeTurn = turn || ensureTurn(draft);
      const text = event.payload?.text || '';
      if (!text) break;
      if (!activeTurn.thinking) {
        activeTurn.thinking = { text: '', live: true, action: '', startedAt: eventTimestamp(event), finishedAt: null };
      }
      activeTurn.thinking.live = true;
      activeTurn.thinking.finishedAt = null;
      activeTurn.thinking.action = '';
      activeTurn.thinking.text += text;
      activeTurn.thinking.preview = (activeTurn.thinking.preview || '') + text;
      break;
    }

    case 'tool.started': {
      const turn = ensureTurn(draft);
      // 调工具前那句还在往外吐的「我先去看…」是思考，不是结论。已经收住的回复不能再挪走。
      if (turn.reply.live && turn.reply.text) {
        if (!turn.thinking) {
          turn.thinking = { text: '', live: true, action: '', startedAt: eventTimestamp(event) };
        }
        turn.thinking.text += (turn.thinking.text ? '\n\n' : '') + turn.reply.text;
        turn.reply = { text: '', live: false, needsBreak: true, breakReason: '', committed: turn.reply.committed };
      }
      // 思考块的摘要行显示「思考中 12 秒 · 执行命令」这种当前动作
      if (turn.thinking && turn.thinking.live) {
        turn.thinking.action = toolLabel(event.payload?.name);
      }
      if (event.payload?.name === 'browser') {
        draft.ui.browserDot = true;
      }
      const owner = event.payload?.agentId ? draft.agents[event.payload.agentId] : null;
      const row = {
        callId: event.payload.callId,
        name: event.payload.name,
        args: event.payload.summary || '',
        status: 'run',
        meta: '',
        body: '',
        // 记录开始时间：慢工具（大目录 grep、npm test）要能显示「已跑 N 秒」，
        // 否则一个不动的转圈看起来就是卡死
        startedAt: eventTimestamp(event),
        agentId: event.payload.agentId || null
      };
      turn.tools.push(row);
      turn.toolIndex[row.callId] = row;
      if (owner) {
        owner.latest = `${toolLabel(event.payload.name)} ${row.args}`.trim().slice(0, 64);
      }
      record(draft, event, {
        kind: 'tool', tone: 'tool', status: '运行中',
        title: `${owner ? `@${owner.id?.slice(0, 8)} ` : ''}${event.payload.name} · ${event.payload.summary}`,
        detail: null
      });
      break;
    }

    case 'tool.completed': {
      const turn = turnOwning(draft, event.payload?.callId);
      const failed = String(event.payload?.resultSummary || '').startsWith('ERROR');
      const row = turn?.toolIndex?.[event.payload?.callId];
      if (row) {
        row.status = failed ? 'error' : 'ok';
        row.meta = `${event.payload.latencyMs} ms`;
        row.body = event.payload.resultSummary || '';
        syncToolRow(turn!, row);
      }
      record(draft, event, {
        kind: 'tool', tone: failed ? 'error' : 'ok', status: failed ? '失败' : '完成',
        title: event.payload?.callId, detail: event.payload?.resultSummary, foot: `${event.payload?.latencyMs} ms`
      });
      break;
    }

    case 'approval.required': {
      const turn = ensureTurn(draft);
      draft.ui.attentionKind = 'attention';
      const row = {
        callId: event.payload.callId,
        name: event.payload.name,
        args: event.payload.argsSummary || '',
        status: 'wait',
        meta: '',
        body: '',
        agentId: event.payload.agentId || null
      };
      turn.tools.push(row);
      turn.toolIndex[row.callId] = row;
      draft.pendingApprovals[event.payload.callId] = {
        callId: event.payload.callId,
        agent: event.payload.agentId || null,
        name: event.payload.name,
        args: event.payload.argsSummary
      };
      syncToolRow(turn, row);
      record(draft, event, {
        kind: 'approval', tone: 'warn', status: '待审批',
        title: `${event.payload.name} · ${event.payload.argsSummary}`,
        detail: '需要你批准后才能执行', actions: true, callId: event.payload.callId
      });
      break;
    }

    case 'approval.resolved': {
      const approved = event.payload?.decision === 'APPROVED';
      const pending = draft.pendingApprovals[event.payload?.callId];
      if (pending) {
        delete draft.pendingApprovals[event.payload.callId];
        if (!live || Object.keys(draft.pendingApprovals).length === 0) {
          draft.ui.attentionKind = Object.keys(draft.pendingApprovals).length ? 'attention' : 'running';
        }
      }
      const turn = turnOwning(draft, event.payload?.callId);
      const row = turn?.toolIndex?.[event.payload?.callId];
      if (row) {
        row.status = approved ? 'run' : 'error';
        row.meta = approved ? '已批准' : '已拒绝';
        syncToolRow(turn!, row);
      }
      record(draft, event, {
        kind: 'approval', tone: approved ? 'ok' : 'error',
        status: approved ? '已批准' : '已拒绝', title: event.payload?.callId, detail: null
      });
      break;
    }

    case 'run.completed': {
      const turn = settleRun(draft, event);
      draft.queueHeld = false;
      draft.lastOutcome = 'completed';
      const result = runResult(event);
      pushRow(draft, {
        ...result,
        tone: 'text', title: '执行结束',
        detail: event.payload?.summary || null,
        runIds: turn?.runIds?.length ? turn.runIds : result.runIds,
      });
      if (live) {
        draft.ui.attentionKind = 'idle';
        draft.runChangesFetch = event.runId;
      }
      record(draft, event, { kind: 'run', tone: 'text', status: '结束', title: '运行结束', detail: event.payload?.summary || null });
      break;
    }

    case 'run.failed': {
      settleRun(draft, event);
      // 传的是失败码：暂停/中断/阻塞都不是「失败」，界面上要区分开并且可继续
      const code = String(event.payload?.code || '');
      const failure = classifyRunFailure(code);
      const outcomeLabels: Record<string, string> = {
        WAITING: '等待外部事件', NEED_INPUT: '需要输入', UNVERIFIED: '结果未验证',
        NO_PROGRESS: '暂无有效进展', REVIEW_UNAVAILABLE: '核验暂不可用'
      };
      const outcomeLabel = outcomeLabels[failure.outcome] || '';
      const title = outcomeLabel ? `本轮结束 · ${outcomeLabel}`
        : failure.blocked ? '任务遇到阻塞'
        : failure.budgetLimited ? '本轮已达预算'
        : failure.paused ? `本轮已暂停 · ${code}`
        : failure.interrupted ? `运行已中断 · ${code}`
        : failure.cancelled ? '本轮已取消'
        : `运行失败 · ${code || 'ERROR'}`;
      const status = outcomeLabel || (failure.blocked ? '遇到阻塞' : failure.budgetLimited ? '已达预算'
        : failure.paused ? '可继续' : failure.interrupted ? '已中断' : failure.cancelled ? '已取消' : '失败');
      const tone = failure.resumable || failure.cancelled ? 'warn' : 'error';
      draft.lastOutcome = failure.outcome || code;
      pushRow(draft, {
        ...runResult(event),
        tone, title,
        detail: event.payload?.message || event.payload?.error || null,
        // NEED_INPUT 要的是新信息而不是原样重跑，不给常驻的「继续执行」
        resumable: failure.resumable && code !== 'NEED_INPUT',
        errorCode: code || null,
      });
      if (live) {
        draft.ui.attentionKind = failure.resumable ? 'attention' : 'idle';
        draft.runChangesFetch = event.runId;
      }
      record(draft, event, {
        kind: 'run', tone, status, title,
        detail: event.payload?.message || event.payload?.error || null
      });
      break;
    }

    case 'run.cancelled': {
      settleRun(draft, event);
      pushRow(draft, {
        ...runResult(event),
        tone: 'warn', title: '运行已取消',
        detail: event.payload?.reason || null,
        resumable: true,
      });
      draft.queueHeld = true;
      if (live) {
        draft.ui.attentionKind = 'idle';
        draft.runChangesFetch = event.runId;
      }
      record(draft, event, { kind: 'run', tone: 'warn', status: '取消', title: '运行已取消', detail: event.payload?.reason || null });
      break;
    }

    case 'run.steered': {
      const previous = openTurn(draft);
      if (previous) { settleThinking(previous, event); previous.reply.live = false; previous.reply.preview = ''; }
      appendUserRow(draft, event.payload?.input || '', event.sequence, { attachments: event.payload?.attachments || [], imagePositions: event.payload?.imagePositions || [] }).steered = true;
      draft.queueHeld = false;
      // 旧的「继续执行」按钮作废
      for (const row of draft.thread.rows) {
        if (row.type === 'result') {
          row.continued = true;
        }
      }
      record(draft, event, { kind: 'run', tone: 'ok', status: '引导', title: '引导已生效', detail: event.payload?.message || null });
      break;
    }

    case 'run.retrying': {
      draft.modelRetryUntil = Number(event.payload?.until || 0) || eventTimestamp(event) + 15000;
      record(draft, event, { kind: 'run', tone: 'warn', status: '重试', title: '模型暂时不可用，自动重试中', detail: null });
      break;
    }

    case 'run.continued': {
      for (let i = draft.thread.rows.length - 1; i >= 0; i -= 1) {
        const row = draft.thread.rows[i];
        if (row.type === 'result') {
          row.continued = true;
          break;
        }
      }
      if (event.runId) {
        draft.runId = event.runId;
      }
      const turn = ensureTurn(draft);
      if (event.runId && !turn.runIds.includes(event.runId)) {
        turn.runIds.push(event.runId);
      }
      draft.running = true;
      draft.runStartedAt = eventTimestamp(event);
      record(draft, event, { kind: 'run', tone: 'run', status: '继续', title: '继续执行', detail: null });
      break;
    }

    case 'context.compacted': {
      draft.latestContextReport = null;
      const payload = event.payload || {};
      pushRow(draft, {
        key: `compaction:${event.sequence}`,
        type: 'compaction',
        coveredMessages: payload.coveredMessages || 0,
        charactersBefore: payload.charactersBefore || 0,
        charactersAfter: payload.charactersAfter || 0,
        summary: payload.summary || '（摘要为空）',
        start: event.sequence, end: event.sequence
      });
      record(draft, event, { kind: 'run', tone: 'ok', status: '压缩', title: '上下文已压缩', detail: null });
      break;
    }

    case 'plan.updated': {
      const items = Array.isArray(event.payload?.plan) ? event.payload.plan : [];
      const existing = planInOpenSegment(draft);
      if (existing) {
        existing.items = items;
        existing.end = event.sequence;
      } else {
        pushRow(draft, { key: `plan:${event.sequence}`, type: 'plan', items, start: event.sequence, end: event.sequence });
      }
      draft.planItems = items;
      const done = items.filter((item) => (item.status || '') === 'completed' || item.status === 'done').length;
      record(draft, event, {
        kind: 'run', tone: 'ok', status: '计划',
        title: `计划更新${planProgressLabel(done, items.length)}`, detail: null
      });
      break;
    }

    case 'subagent.started': {
      const turn = ensureTurn(draft);
      const id = event.payload?.agentId || event.payload?.id;
      if (id && !turn.agents[id]) {
        turn.agents[id] = {
          id,
          name: event.payload?.name || '',
          task: event.payload?.task || '',
          status: 'running',
          startedAt: eventTimestamp(event),
          tools: [],
          result: ''
        };
        draft.agents[id] = turn.agents[id];
      }
      record(draft, event, { kind: 'run', tone: 'run', status: '启动', title: `子智能体启动 · ${event.payload?.name || event.payload?.agentId}`, detail: event.payload?.task || null });
      break;
    }

    case 'subagent.finished': {
      const id = event.payload?.agentId || event.payload?.id;
      const turn = turnWithAgent(draft, id);
      const agent = id ? turn?.agents?.[id] || draft.agents[id] : undefined;
      if (agent && id) {
        agent.status = event.payload?.ok === false ? 'failed' : 'done';
        agent.result = event.payload?.result || event.payload?.summary || agent.result || '';
        agent.finishedAt = eventTimestamp(event);
        if (draft.agents[id]) draft.agents[id] = { ...agent };
      }
      record(draft, event, { kind: 'run', tone: 'ok', status: '完成', title: `子智能体完成 · ${id}`, detail: preview(event.payload?.result || event.payload?.summary || '', 160) });
      break;
    }

    case 'multi.budget': {
      const payload = event.payload || {};
      const tokenLimit = Number(payload.maxTotalTokens || 0) > 0
        ? `${formatBudgetCount(payload.maxTotalTokens)} token` : '不限';
      const detail = [
        `墙钟上限 ${formatBudgetCount(payload.maxDispatchSeconds)} 秒 · token 上限 ${tokenLimit}`,
        `已结算 ${formatBudgetCount(payload.usedTokens)} token（估算 ${formatBudgetCount(payload.estimatedTokens)}）`,
        `在途输入预留 ${formatBudgetCount(payload.reservedTokens)} token · ${formatBudgetCount(payload.inFlightRequests)} 个请求未结算`,
        payload.stopReason ? `停止原因：${payload.stopReason}` : null,
        payload.cancellationRequested ? '已请求中断在途子任务；这不保证供应商撤销请求或停止计费。' : null,
        '本地软预算；在途输出用量可能造成超额。'
      ].filter(Boolean).join('\n');
      const existing = draft.timeline.find((item) => item.type === event.type
        && item.dispatchId === payload.dispatchId);
      const status = payload.status === 'stopped' ? '软停止'
        : payload.status === 'completed' ? '完成' : '运行中';
      const tone = payload.status === 'stopped' ? 'warn' : payload.status === 'completed' ? 'ok' : 'run';
      if (existing) {
        Object.assign(existing, { tone, status, title: '多智能体派发预算', detail, sequence: event.sequence, timestamp: event.timestamp });
      } else {
        record(draft, event, { kind: 'run', tone, status, title: '多智能体派发预算', detail, dispatchId: payload.dispatchId });
      }
      break;
    }

    case 'task.updated': {
      draft.task = { ...(draft.task || {}), ...(event.payload || {}) };
      record(draft, event, { kind: 'run', tone: 'ok', status: '契约', title: '任务契约更新', detail: null });
      break;
    }

    case 'sandbox.diff': {
      const payload = event.payload || {};
      pushRow(draft, {
        key: `sandbox:${payload.sandboxId || event.sequence}`,
        type: 'sandbox', sandboxId: payload.sandboxId, title: payload.title || '沙箱差异',
        detail: payload.summary || payload.diff || '', start: event.sequence, end: event.sequence
      });
      record(draft, event, { kind: 'run', tone: 'warn', status: '沙箱', title: payload.title || '沙箱差异', detail: null });
      break;
    }

    case 'arbitration.required': {
      const payload = event.payload || {};
      draft.pendingArbitrations[payload.arbitrationId || event.sequence] = payload;
      pushRow(draft, {
        key: `arb:${payload.arbitrationId || event.sequence}`,
        type: 'arb', arbitrationId: payload.arbitrationId,
        title: payload.title || '多智能体仲裁', detail: payload.summary || payload.report || '',
        options: payload.options || [], resolved: false,
        start: event.sequence, end: event.sequence
      });
      record(draft, event, { kind: 'approval', tone: 'warn', status: '仲裁', title: payload.title || '多智能体仲裁', detail: payload.summary || payload.report || null });
      break;
    }

    case 'arbitration.resolved': {
      const id = event.payload?.arbitrationId;
      delete draft.pendingArbitrations[id];
      const row = draft.thread.rows.find((item): item is ArbitrationRow => item.type === 'arb' && item.arbitrationId === id);
      if (row) {
        row.resolved = true;
        row.resolution = event.payload?.decision || event.payload?.choice || null;
      }
      record(draft, event, { kind: 'approval', tone: 'ok', status: '已裁决', title: id, detail: null });
      break;
    }

    case 'execution.updated':
    default: {
      // execution.updated 只触发任务刷新调度；未知事件不渲染。
      break;
    }
  }

  // 历史窗口只保留持久事件，临时增量已折入当前输出，避免长时间生成时重复保存。
  if (!isEphemeral(event.type)) thread.windowEvents.push(event);
  if (!thread.replaying && thread.windowEvents.length > HISTORY_WINDOW_LIMIT + thread.history.pageSize
    && !draft.running && ['run.completed', 'run.failed', 'run.cancelled', 'user.message'].includes(event.type)) {
    trimThreadWindow(draft);
  }
}

/** 窗口裁剪：与旧版 trimHistoryDom 相同的边界规则，只丢最前面的行。 */
function trimThreadWindow(draft: ReducerState) {
  const thread = draft.thread;
  const trim = trimHistoryWindowAtRows(
    thread.windowEvents,
    thread.rows.map((row) => ({ start: row.start, end: row.end })),
    'newer',
    HISTORY_WINDOW_LIMIT
  );
  if (!trim.evictedBefore) {
    return;
  }
  // 事件数是软上限：一轮流式回复可能就超过整个窗口。最新提问和回答必须一起保留，
  // 否则 turn.end 与结束卡片共用序号时，会删光事件游标，只剩一张无法向上翻页的结束卡。
  const latestUser = thread.rows.findLastIndex((row) => row.type === 'user');
  const protectedStart = latestUser >= 0 ? latestUser : thread.rows.findLastIndex((row) => row.type === 'turn');
  let removeCount = Math.min(trim.removeStartCount, Math.max(0, protectedStart));
  if (!removeCount) {
    return;
  }
  // 从下一条完整提问开始，避免把上一轮的孤立结束卡留在窗口头。
  if (latestUser >= 0) {
    while (removeCount < latestUser && thread.rows[removeCount].type !== 'user') removeCount += 1;
  }
  const boundary = thread.rows[removeCount].start;
  thread.windowEvents = thread.windowEvents.filter((event) => event.sequence >= boundary);
  thread.history.hasMore = true;
  thread.rows = thread.rows.slice(removeCount);
}

/** 历史页（更早）前插：合并事件窗口 + 行注册表，位置由浏览器 scroll anchoring 保持。 */
export function prependHistoryPage(draft: ReducerState, events: EventEnvelope[]) {
  const thread = draft.thread;
  const before = thread.windowEvents.length;
  thread.windowEvents = mergeHistoryEvents(events, thread.windowEvents);
  const added = thread.windowEvents.length - before;
  // 新行插到最前面（按事件顺序重建前段）
  const replayed: ThreadRow[] = [];
  for (const event of events) {
    applyEventInto(draft, event, replayed);
  }
  thread.rows = [...replayed, ...thread.rows];
  void added;
}

/**
 * 在隔离区里重放一页事件，产出的行收集进 bucket，不污染现有行，
 * 也不会误写根级状态（running/pendingApprovals 等）——历史重放只负责产出行。
 */
/** 翻历史时不能借用当前这次运行的 runId，否则旧回合会被盖上新运行的编号。 */
function replayRunId(bucket: ThreadRow[], event: EventEnvelope) {
  if (event.runId) {
    return event.runId;
  }
  for (let i = bucket.length - 1; i >= 0; i -= 1) {
    const row = bucket[i];
    if (row.type === 'user') {
      break;
    }
    if (row.type === 'turn' && row.runIds?.length) {
      return row.runIds[row.runIds.length - 1];
    }
  }
  return null;
}

function applyEventInto(root: ReducerState, event: EventEnvelope, bucket: ThreadRow[]) {
  const thread = root.thread;
  const isolated: ReducerState = {
    thread: {
      rows: bucket,
      cursor: 0,
      windowEvents: [],
      replaying: true,
      history: thread.history,
      activityCount: thread.activityCount
    },
    running: root.running,
    cancelling: root.cancelling,
    runId: replayRunId(bucket, event),
    turnRuns: root.turnRuns,
    runUsage: root.runUsage,
    requestReports: [...(root.requestReports || [])],
    latestContextReport: root.latestContextReport,
    liveOutputTokens: root.liveOutputTokens,
    modelRetryUntil: root.modelRetryUntil,
    lastOutcome: root.lastOutcome,
    queueHeld: root.queueHeld,
    pendingInputs: [...(root.pendingInputs || [])],
    queue: [...(root.queue || [])],
    pendingApprovals: { ...(root.pendingApprovals || {}) },
    pendingArbitrations: { ...(root.pendingArbitrations || {}) },
    agents: { ...(root.agents || {}) },
    task: root.task,
    planItems: root.planItems,
    contextStatus: root.contextStatus,
    streamCard: root.streamCard,
    timeline: [],
    promoteBlank: null,
    runChangesFetch: null,
    ui: { attentionKind: root.ui?.attentionKind, notice: null, browserDot: root.ui?.browserDot }
  };
  applyEvent(isolated, event, { live: false });
  // activityCount 单调递增，回传避免 key 冲突
  thread.activityCount = isolated.thread.activityCount;
  // 预算卡片只写根级 timeline 而不产生 row；真实历史回放需要把它合并回根状态，否则翻页后卡片消失
  if (event.type === 'multi.budget') {
    const timeline = root.timeline || (root.timeline = []);
    for (const item of isolated.timeline) {
      const index = timeline.findIndex(existing => existing.type === item.type
        && (existing.dispatchId || null) === (item.dispatchId || null));
      if (index >= 0) {
        timeline[index] = item;
      } else {
        timeline.push(item);
      }
    }
  }
}

/** 历史页（更新）追加：行加到尾部。 */
export function appendHistoryPage(draft: ReducerState, events: EventEnvelope[]) {
  const thread = draft.thread;
  thread.windowEvents = mergeHistoryEvents(thread.windowEvents, events);
  const bucket: ThreadRow[] = [];
  for (const event of events) {
    applyEventInto(draft, event, bucket);
  }
  thread.rows = [...thread.rows, ...bucket];
}
