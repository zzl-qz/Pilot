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
import { eventTimestamp, toolLabel } from './lib/tool-meta.js';
import { preview, estimateTokens } from './thread-utils.js';

/** 活动流保留上限，与旧版 MAX_TIMELINE_EVENTS 一致。 */
const MAX_TIMELINE_EVENTS = 300;

export const initialThread = () => ({
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
export function isUserMessageType(event) {
  return event.type === 'user.message';
}

function ensureTurn(draft) {
  const thread = draft.thread;
  let turn = [...thread.rows].reverse().find((row) => row.type === 'turn');
  if (!turn) {
    turn = {
      key: `turn:${draft.runId || 'live'}`,
      type: 'turn',
      runIds: [],
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
  }
  return turn;
}

/**
 * 运行已经结束，却还挂在「运行中」的工具行：不可能再等到 tool.completed 了
 * （事件丢失、进程被中断、或整轮被取消）。留着转圈会让人以为卡死，统一收成中断态。
 */
function settleUnfinishedTools(turn, event) {
  if (!turn?.tools) {
    return;
  }
  const finishedAt = eventTimestamp(event);
  for (const tool of turn.tools) {
    if (tool.status === 'run' || tool.status === 'wait') {
      tool.status = 'interrupted';
      tool.meta = tool.meta || '未返回结果';
      tool.finishedAt = finishedAt;
    }
  }
}

/** 正文或工具出来了，思考块就该收起（旧版 settleReasoning），秒数保留。 */
function settleThinking(turn, event) {
  if (turn.thinking && turn.thinking.live) {
    turn.thinking.live = false;
    turn.thinking.finishedAt = eventTimestamp(event);
  }
}

function pushRow(draft, row) {
  row.start = row.start ?? draft.thread.cursor;
  row.end = row.end ?? draft.thread.cursor;
  draft.thread.rows.push(row);
}

function record(draft, event, entry) {
  if (event.type !== 'assistant.delta' && event.type !== 'assistant.preview') {
    draft.streamCard = null;
  }
  // 活动流是 append-only 的，长任务不裁剪就会一屏几百条 <li> 陪着每帧重渲染
  while (draft.timeline.length >= MAX_TIMELINE_EVENTS) {
    draft.timeline.shift();
  }
  draft.thread.activityCount += 1;
  const item = {
    key: `${event.type}:${event.sequence}:${draft.thread.activityCount}`,
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
export function appendUserRow(draft, text, sequence, { attachments = [], imagePositions = [], pendingKey = null } = {}) {
  const thread = draft.thread;
  const row = {
    key: pendingKey || (Number.isFinite(sequence) ? `message:${sequence}` : `message:p${thread.activityCount}`),
    type: 'user',
    sequence: Number.isFinite(sequence) ? sequence : null,
    text,
    attachments,
    imagePositions,
    start: sequence ?? thread.cursor,
    end: sequence ?? thread.cursor
  };
  thread.rows.push(row);
  return row;
}

function stampLastUserRow(draft, sequence, text) {
  const rows = draft.thread.rows;
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    const row = rows[i];
    if (row.type === 'user' && row.sequence == null) {
      const same = (row.text || '').replace(/\s+/g, ' ').trim() === String(text || '').replace(/\s+/g, ' ').trim();
      if (same && Number.isFinite(sequence)) {
        row.sequence = sequence;
        row.key = `message:${sequence}`;
        row.start = sequence;
        row.end = sequence;
        return;
      }
    }
  }
}

export function applyEvent(draft, event, { live }) {
  const thread = draft.thread;
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
      const same = (a, b) => String(a || '').replace(/\s+/g, ' ').trim() === String(b || '').replace(/\s+/g, ' ').trim();
      if (draft.pendingInputs.length && same(draft.pendingInputs[0], input)) {
        draft.pendingInputs.shift();
        stampLastUserRow(draft, event.sequence, input);
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

    case 'run.usage': {
      const payload = event.payload || {};
      if (!draft.runUsage || (event.runId && draft.runUsage.runId && event.runId !== draft.runUsage.runId)) {
        draft.runUsage = { runId: event.runId || draft.runId, startedAt: eventTimestamp(event), finishedAt: null };
      }
      Object.assign(draft.runUsage, {
        modelRequests: Number(payload.modelRequests || 0),
        inputTokens: Number(payload.inputTokens || 0),
        outputTokens: Number(payload.outputTokens || 0),
        contextTokens: Number(payload.contextTokens || 0),
        contextLimit: Math.max(1, Number(payload.contextLimit || 1)),
        exact: Boolean(payload.exact),
        reported: true
      });
      draft.liveOutputTokens = 0;
      const turn = ensureTurn(draft);
      turn.stats = { ...draft.runUsage };
      draft.contextStatus = {
        ...(draft.contextStatus || {}),
        usedTokens: draft.runUsage.contextTokens,
        limitTokens: draft.runUsage.contextLimit,
        estimated: true,
        canCompact: false
      };
      record(draft, event, { kind: 'run', tone: 'ok', status: '用量', title: null, detail: null });
      break;
    }

    case 'assistant.preview': {
      // 预览只是「模型还在吐字」，落库的 assistant.delta 才是正文：两段不能都往正文里接。
      const turn = ensureTurn(draft);
      turn.reply.preview = (turn.reply.preview || '') + (event.payload?.text || '');
      turn.reply.live = true;
      draft.liveOutputTokens = estimateTokens((turn.reply.text || '') + (turn.reply.preview || ''));
      settleThinking(turn, event);
      record(draft, event, { kind: 'run', tone: 'text', status: '增量', title: null, detail: preview(event.payload?.text || '', 160) });
      break;
    }

    case 'assistant.delta': {
      const turn = ensureTurn(draft);
      turn.reply.text += event.payload?.text || '';
      turn.reply.preview = '';
      turn.reply.live = true;
      draft.liveOutputTokens = estimateTokens(turn.reply.text);
      settleThinking(turn, event);
      record(draft, event, { kind: 'run', tone: 'text', status: '增量', title: null, detail: preview(event.payload?.text || '', 160) });
      break;
    }

    case 'assistant.reasoning': {
      const turn = ensureTurn(draft);
      const text = event.payload?.text || '';
      if (!turn.thinking) {
        turn.thinking = { text: '', live: true, action: '', startedAt: eventTimestamp(event), finishedAt: null };
      }
      turn.thinking.text += text;
      break;
    }

    case 'tool.started': {
      const turn = ensureTurn(draft);
      // 调工具前那句「我先去看…」是思考，不是结论：收进思考块。
      if (turn.reply.text) {
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
      const turn = [...draft.thread.rows].reverse().find((row) => row.type === 'turn');
      const failed = String(event.payload?.resultSummary || '').startsWith('ERROR');
      const row = turn?.toolIndex?.[event.payload?.callId];
      if (row) {
        row.status = failed ? 'error' : 'ok';
        row.meta = `${event.payload.latencyMs} ms`;
        row.body = event.payload.resultSummary || '';
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
      const turn = [...draft.thread.rows].reverse().find((row) => row.type === 'turn');
      const row = turn?.toolIndex?.[event.payload?.callId];
      if (row) {
        row.status = approved ? 'run' : 'error';
        row.meta = approved ? '已批准' : '已拒绝';
      }
      record(draft, event, {
        kind: 'approval', tone: approved ? 'ok' : 'error',
        status: approved ? '已批准' : '已拒绝', title: event.payload?.callId, detail: null
      });
      break;
    }

    case 'run.completed': {
      const turn = [...draft.thread.rows].reverse().find((row) => row.type === 'turn');
      draft.running = false;
      draft.cancelling = false;
      draft.runStartedAt = 0;
      draft.queueHeld = false;
      if (draft.runUsage) {
        draft.runUsage.finishedAt = eventTimestamp(event);
      }
      if (turn) {
        turn.reply.live = false;
        turn.end = event.sequence;
        settleUnfinishedTools(turn, event);
      }
      draft.lastOutcome = 'completed';
      pushRow(draft, {
        key: `result:${event.runId || event.sequence}`,
        type: 'result', tone: 'ok', title: '任务完成',
        detail: event.payload?.summary || null,
        changedFiles: [],
        runIds: turn?.runIds?.length ? turn.runIds : (event.runId ? [event.runId] : []),
        start: event.sequence, end: event.sequence
      });
      if (live) {
        draft.ui.attentionKind = 'idle';
        draft.runChangesFetch = event.runId;
      }
      record(draft, event, { kind: 'run', tone: 'ok', status: '完成', title: '运行结束', detail: event.payload?.summary || null });
      break;
    }

    case 'run.failed': {
      const turn = [...draft.thread.rows].reverse().find((row) => row.type === 'turn');
      draft.running = false;
      draft.cancelling = false;
      draft.runStartedAt = 0;
      // 传的是失败码：暂停/中断/阻塞都不是「失败」，界面上要区分开并且可继续
      const code = String(event.payload?.code || '');
      const failure = classifyRunFailure(code);
      const outcomeLabels = {
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
      if (turn) {
        turn.reply.live = false;
        turn.end = event.sequence;
        settleUnfinishedTools(turn, event);
      }
      pushRow(draft, {
        key: `result:${event.runId || event.sequence}`,
        type: 'result', tone, title,
        detail: event.payload?.message || event.payload?.error || null,
        changedFiles: [],
        runIds: event.runId ? [event.runId] : [],
        // NEED_INPUT 要的是新信息而不是原样重跑，不给常驻的「继续执行」
        resumable: failure.resumable && code !== 'NEED_INPUT',
        errorCode: code || null,
        start: event.sequence, end: event.sequence
      });
      if (live) {
        draft.ui.attentionKind = failure.resumable ? 'attention' : 'idle';
      }
      record(draft, event, {
        kind: 'run', tone, status, title,
        detail: event.payload?.message || event.payload?.error || null
      });
      break;
    }

    case 'run.cancelled': {
      const turn = [...draft.thread.rows].reverse().find((row) => row.type === 'turn');
      draft.running = false;
      draft.cancelling = false;
      draft.runStartedAt = 0;
      if (turn) {
        turn.reply.live = false;
        turn.end = event.sequence;
        settleUnfinishedTools(turn, event);
      }
      pushRow(draft, {
        key: `result:${event.runId || event.sequence}`,
        type: 'result', tone: 'warn', title: '运行已取消',
        detail: event.payload?.reason || null,
        changedFiles: [],
        runIds: event.runId ? [event.runId] : [],
        resumable: true,
        start: event.sequence, end: event.sequence
      });
      draft.queueHeld = true;
      if (live) {
        draft.ui.attentionKind = 'idle';
      }
      record(draft, event, { kind: 'run', tone: 'warn', status: '取消', title: '运行已取消', detail: event.payload?.reason || null });
      break;
    }

    case 'run.steered': {
      draft.queueHeld = false;
      // 旧的「继续执行」按钮作废
      for (const row of draft.thread.rows) {
        if (row.type === 'result') {
          row.continued = true;
        }
      }
      record(draft, event, { kind: 'run', tone: 'ok', status: '纠正', title: '纠正已生效', detail: event.payload?.message || null });
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
      const turn = [...draft.thread.rows].reverse().find((row) => row.type === 'turn');
      if (turn && event.runId && !turn.runIds.includes(event.runId)) {
        turn.runIds.push(event.runId);
      }
      draft.running = true;
      draft.runStartedAt = eventTimestamp(event);
      record(draft, event, { kind: 'run', tone: 'run', status: '继续', title: '继续执行', detail: null });
      break;
    }

    case 'context.compacted': {
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
      const existing = draft.thread.rows.find((row) => row.type === 'plan');
      if (existing) {
        existing.items = items;
        existing.end = event.sequence;
      } else {
        pushRow(draft, { key: 'plan', type: 'plan', items, start: event.sequence, end: event.sequence });
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
      const turn = [...draft.thread.rows].reverse().find((row) => row.type === 'turn');
      const id = event.payload?.agentId || event.payload?.id;
      const agent = turn?.agents?.[id] || draft.agents[id];
      if (agent) {
        agent.status = event.payload?.ok === false ? 'failed' : 'done';
        agent.result = event.payload?.result || agent.result || '';
        agent.finishedAt = eventTimestamp(event);
      }
      record(draft, event, { kind: 'run', tone: 'ok', status: '完成', title: `子智能体完成 · ${id}`, detail: preview(event.payload?.result || '', 160) });
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
        title: payload.title || '多智能体仲裁', detail: payload.summary || '',
        options: payload.options || [], resolved: false,
        start: event.sequence, end: event.sequence
      });
      record(draft, event, { kind: 'approval', tone: 'warn', status: '仲裁', title: payload.title || '多智能体仲裁', detail: payload.summary || null });
      break;
    }

    case 'arbitration.resolved': {
      const id = event.payload?.arbitrationId;
      delete draft.pendingArbitrations[id];
      const row = draft.thread.rows.find((item) => item.type === 'arb' && item.arbitrationId === id);
      if (row) {
        row.resolved = true;
        row.resolution = event.payload?.decision || null;
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

  // 每个事件都进窗口；事件戳边界由行自身维护（start/end 已在上面维护）。
  thread.windowEvents.push(event);
  if (!thread.replaying && thread.windowEvents.length > HISTORY_WINDOW_LIMIT + thread.history.pageSize
    && !draft.running && ['run.completed', 'run.failed', 'run.cancelled', 'user.message'].includes(event.type)) {
    trimThreadWindow(draft);
  }
}

/** 窗口裁剪：与旧版 trimHistoryDom 相同的边界规则，只丢最前面的行。 */
function trimThreadWindow(draft) {
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
  thread.windowEvents = trim.events;
  thread.history.hasMore = true;
  if (trim.removeStartCount > 0) {
    thread.rows = thread.rows.slice(trim.removeStartCount);
  }
}

/** 历史页（更早）前插：合并事件窗口 + 行注册表，位置由浏览器 scroll anchoring 保持。 */
export function prependHistoryPage(draft, events) {
  const thread = draft.thread;
  const before = thread.windowEvents.length;
  thread.windowEvents = mergeHistoryEvents(events, thread.windowEvents);
  const added = thread.windowEvents.length - before;
  // 新行插到最前面（按事件顺序重建前段）
  const replayed = [];
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
function applyEventInto(root, event, bucket) {
  const thread = root.thread;
  const isolated = {
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
    runId: root.runId,
    turnRuns: root.turnRuns,
    runUsage: root.runUsage,
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
}

/** 历史页（更新）追加：行加到尾部。 */
export function appendHistoryPage(draft, events) {
  const thread = draft.thread;
  thread.windowEvents = mergeHistoryEvents(thread.windowEvents, events);
  const bucket = [];
  for (const event of events) {
    applyEventInto(draft, event, bucket);
  }
  thread.rows = [...thread.rows, ...bucket];
}
