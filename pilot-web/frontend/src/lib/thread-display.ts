import type { CompactionRow, PlanRow, RunUsage, ThreadRow, TurnRow } from '../types/thread.js';

export interface CompactionGroupRow {
  type: 'compaction-group';
  key: string;
  start: number;
  end: number;
  items: CompactionRow[];
}

export type DisplayThreadRow = Exclude<ThreadRow, PlanRow | CompactionRow> | CompactionGroupRow;

export interface ThreadDisplayModel {
  pinnedPlan: PlanRow | null;
  rows: DisplayThreadRow[];
}

function hasVisibleReply(turn: TurnRow): boolean {
  return Boolean((turn.reply?.text || '').trim() || (turn.reply?.preview || '').trim() || turn.reply?.live);
}

/**
 * 同一段操作可能被历史翻页拆成多段，越靠后的段里 run.started 往往不在本页，
 * 它的 startedAt 会退化成页内首个事件时间，直接沿用就会把耗时算成 0:00。
 * 合并时取最早的开始、最晚的结束，token 与费用仍用最新一段。
 */
function mergeStats(base: RunUsage | null | undefined, next: RunUsage | null | undefined): RunUsage | null {
  if (!base) return next ?? null;
  if (!next) return base;
  const start = !base.startedAt ? next.startedAt
    : !next.startedAt ? base.startedAt : Math.min(base.startedAt, next.startedAt);
  const ends = [base.finishedAt, next.finishedAt].filter((value): value is number => typeof value === 'number');
  return { ...next, startedAt: start, finishedAt: ends.length ? Math.max(...ends) : next.finishedAt ?? base.finishedAt };
}

function mergeTurns(base: TurnRow, next: TurnRow): TurnRow {
  const seenCalls = new Set((base.tools || []).map(tool => tool.callId));
  const mergedTools = [...(base.tools || [])];
  for (const tool of next.tools || []) {
    if (tool.callId && seenCalls.has(tool.callId)) continue;
    if (tool.callId) seenCalls.add(tool.callId);
    mergedTools.push(tool);
  }
  const mergedRunIds = [...(base.runIds || [])];
  for (const id of next.runIds || []) {
    if (id && !mergedRunIds.includes(id)) mergedRunIds.push(id);
  }
  const mergedRequests = [...(base.requests || [])];
  const seenReq = new Set(mergedRequests.map(req => req.id));
  for (const req of next.requests || []) {
    if (!seenReq.has(req.id)) {
      seenReq.add(req.id);
      mergedRequests.push(req);
    }
  }
  const mergedCommentaries = [...(base.commentaries || [])];
  for (const note of next.commentaries || []) {
    if (note && mergedCommentaries[mergedCommentaries.length - 1] !== note) mergedCommentaries.push(note);
  }
  return {
    ...next,
    key: base.key,
    start: Math.min(base.start, next.start),
    end: Math.max(base.end, next.end),
    runIds: mergedRunIds,
    tools: mergedTools,
    toolIndex: { ...(base.toolIndex || {}), ...(next.toolIndex || {}) },
    thinking: next.thinking || base.thinking,
    commentaries: mergedCommentaries.length ? mergedCommentaries : undefined,
    agents: { ...(base.agents || {}), ...(next.agents || {}) },
    requests: mergedRequests.length ? mergedRequests : undefined,
    stats: mergeStats(base.stats, next.stats)
  };
}

/**
 * 视图层线程整理（不改写 reducer 原始历史）：
 * 1. 提取最新一条执行计划固定展示，避免长任务中多次 update_plan 重复铺满消息流；
 * 2. 合并连续的上下文压缩记录为单条细分隔线；
 * 3. 合并同一任务下连续且无回复穿插的纯过程回合，消除重复的「N 项操作 + 用量详情」。
 */
export function buildThreadDisplay(rows: readonly ThreadRow[]): ThreadDisplayModel {
  let pinnedPlan: PlanRow | null = null;
  const latestUserIndex = rows.findLastIndex(row => row.type === 'user');
  for (let i = rows.length - 1; i > latestUserIndex; i -= 1) {
    const row = rows[i];
    if (row.type === 'plan') {
      const valid = (row.items || []).some(item => (item.step || item.text || '').trim());
      if (valid) {
        pinnedPlan = row;
        break;
      }
    }
  }

  const out: DisplayThreadRow[] = [];
  for (const row of rows) {
    if (row.type === 'plan') continue;

    if (row.type === 'compaction') {
      const prev = out[out.length - 1];
      if (prev && prev.type === 'compaction-group') {
        out[out.length - 1] = {
          ...prev,
          end: Math.max(prev.end, row.end),
          items: [...prev.items, row]
        };
      } else {
        out.push({
          type: 'compaction-group',
          key: row.key,
          start: row.start,
          end: row.end,
          items: [row]
        });
      }
      continue;
    }

    if (row.type === 'turn') {
      const prev = out[out.length - 1];
      if (prev && prev.type === 'turn' && !hasVisibleReply(prev)) {
        out[out.length - 1] = mergeTurns(prev, row);
        continue;
      }
      // 中间只夹了上下文压缩，且前一个回合没有正文回复：把连续过程回合合到一起，压缩分隔线保留在前面
      const prev2 = out.length >= 2 ? out[out.length - 2] : null;
      if (prev && prev.type === 'compaction-group' && prev2 && prev2.type === 'turn' && !hasVisibleReply(prev2)) {
        out.splice(out.length - 2, 1);
        out.push(mergeTurns(prev2, row));
        continue;
      }
    }

    out.push(row);
  }

  // 二次收敛：连续无回复回合向前合并后，原本被夹在中间的多条压缩记录会相邻，统一再合并为单条分隔线。
  const compact: DisplayThreadRow[] = [];
  for (const row of out) {
    const prev = compact[compact.length - 1];
    if (row.type === 'compaction-group' && prev && prev.type === 'compaction-group') {
      compact[compact.length - 1] = {
        ...prev,
        end: Math.max(prev.end, row.end),
        items: [...prev.items, ...row.items]
      };
      continue;
    }
    compact.push(row);
  }

  // 三次收敛：如果一个无回复过程回合后紧跟压缩记录（且其后只有结果卡或已到末尾），
  // 避免同一操作组上下被两条「较早上下文已自动整理」夹击，把尾部压缩并入前一条压缩记录。
  for (let i = compact.length - 1; i >= 1; i -= 1) {
    const row = compact[i];
    if (row.type !== 'compaction-group') continue;
    const hasLaterWork = compact.slice(i + 1).some(item => item.type === 'user' || item.type === 'turn');
    if (hasLaterWork) continue;
    const prev = compact[i - 1];
    if (prev && prev.type === 'turn' && !hasVisibleReply(prev)) {
      const beforeTurn = i >= 2 ? compact[i - 2] : null;
      if (beforeTurn && beforeTurn.type === 'compaction-group') {
        compact[i - 2] = {
          ...beforeTurn,
          end: Math.max(beforeTurn.end, row.end),
          items: [...beforeTurn.items, ...row.items]
        };
        compact.splice(i, 1);
      }
    }
  }

  return { pinnedPlan, rows: compact };
}
