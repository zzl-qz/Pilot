import type { ToolRow as ToolView, Thinking, AgentView } from "../../types/thread.js";
import type React from "react";
import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { Spinner } from "@astryxdesign/core/Spinner";
import { StatusDot } from "@astryxdesign/core/StatusDot";
import { useStore } from "../../store.js";
import { api } from "../../api.js";
import { writeClipboard } from "../../lib/clipboard.js";
import { Icon } from "../Icon.jsx";
import { MarkdownBody } from "../MarkdownBody.jsx";
import {
  TOOL_STATUS_TITLES,
  computeToolSpanSeconds,
  formatLatency,
  formatProcessDuration,
  parseAsk,
  parseToolDisplay,
  questionChoice,
  summarizeActiveTool,
  toolLabel,
  type ToolIconKind
} from "../../lib/tool-meta.js";
import { agentMeta, agentTitle, manifestFacts, splitSubagentResult, summarizeAgents, agentStatusLabel, agentStatusDotVariant } from "../../lib/agents.js";
import { formatClock } from "../../thread-utils.js";

export function ProcessChevron() {
  return (
    <svg className="process-chevron-svg" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="m6 3.5 4.5 4.5L6 12.5" />
    </svg>
  );
}

export function ProcessKindIcon({ kind }: { kind: ToolIconKind }) {
  let content: React.ReactNode;
  switch (kind) {
    case 'java':
      content = (
        <>
          <path d="M4.2 7.2h6.2v2.7a2.8 2.8 0 0 1-2.8 2.8H7a2.8 2.8 0 0 1-2.8-2.8V7.2Z" />
          <path d="M10.4 8h1.1a1.3 1.3 0 0 1 0 2.6h-1.1" />
          <path d="M6.2 4.8c0-.7.7-1 .7-1.7M8.5 4.8c0-.7.7-1 .7-1.7" />
          <path d="M3.6 13.8h7.6" />
        </>
      );
      break;
    case 'markdown':
      content = (
        <>
          <path d="M2.4 11.4V4.8l2.5 3.1 2.5-3.1v6.6" />
          <path d="M11.6 4.8v6.2m-2-2 2 2 2-2" />
        </>
      );
      break;
    case 'typescript':
    case 'javascript':
    case 'python':
    case 'code':
      content = <path d="m5.4 5.2-2.9 2.8 2.9 2.8m5.2-5.6 2.9 2.8-2.9 2.8M9 3.6 7 12.4" />;
      break;
    case 'style':
      content = <path d="M6 3 4.6 13M11.4 3 10 13M3 6.2h10M2.6 9.8h10" />;
      break;
    case 'json':
      content = <path d="M5.6 3.2c-1 0-1.4.5-1.4 1.4v1.8c0 .8-.4 1.3-1.2 1.6.8.3 1.2.8 1.2 1.6v1.8c0 .9.4 1.4 1.4 1.4M10.4 3.2c1 0 1.4.5 1.4 1.4v1.8c0 .8.4 1.3 1.2 1.6-.8.3-1.2.8-1.2 1.6v1.8c0 .9-.4 1.4-1.4 1.4" />;
      break;
    case 'markup':
      content = <path d="m5.5 4.8-3 3.2 3 3.2m5-6.4 3 3.2-3 3.2" />;
      break;
    case 'shell':
    case 'terminal':
      content = <path d="m3.5 5 3.2 3-3.2 3M8.2 11h4.3" />;
      break;
    case 'search':
      content = (
        <>
          <circle cx="7.2" cy="7.2" r="4.2" />
          <path d="m10.5 10.5 2.7 2.7" />
        </>
      );
      break;
    case 'folder':
      content = <path d="M2.4 4.4h3.7l1.5 1.5h6v6.5H2.4Z" />;
      break;
    case 'browser':
      content = (
        <>
          <circle cx="8" cy="8" r="5.4" />
          <path d="M2.8 8h10.4M8 2.6a8 8 0 0 1 0 10.8M8 2.6a8 8 0 0 0 0 10.8" />
        </>
      );
      break;
    case 'plan':
      content = <path d="m3 5 1.3 1.3L6.6 4M8.4 5.2H13M3 9.2l1.3 1.3L6.6 8.2M8.4 9.4H13M8.4 12.6H13" />;
      break;
    case 'agent':
      content = (
        <>
          <circle cx="8" cy="3.8" r="1.6" />
          <circle cx="4" cy="11.8" r="1.6" />
          <circle cx="12" cy="11.8" r="1.6" />
          <path d="M7.2 5.2 4.8 10.4M8.8 5.2l2.4 5.2" />
        </>
      );
      break;
    case 'file':
    default:
      content = <path d="M4.2 2.4h5l3.2 3.2v8H4.2Z M9.2 2.4v3.2h3.2" />;
      break;
  }
  return (
    <svg className={`process-kind-icon is-${kind}`} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {content}
    </svg>
  );
}

type ScrollFade = 'none' | 'top' | 'bottom' | 'both';

/**
 * 限高可滑动容器：默认由父级折叠，展开后限制最大高度并支持内部下拉滚动；
 * 顶部 / 底部在可滚动方向显示柔和渐隐遮罩，流式输出时仅在贴底状态跟随，用户上翻立刻放手。
 */
function ProcessScrollViewport({
  active,
  className,
  autoFollow = false,
  followKey,
  focusSelector,
  tabIndex,
  children
}: {
  active: boolean;
  className: string;
  autoFollow?: boolean;
  followKey?: unknown;
  focusSelector?: string;
  tabIndex?: number;
  children: React.ReactNode;
}) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const innerRef = useRef<HTMLDivElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const thumbRef = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const [fade, setFade] = useState<ScrollFade>('none');
  const [scrollable, setScrollable] = useState(false);

  const measureScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const maxScroll = el.scrollHeight - el.clientHeight;
    const overflow = maxScroll > 2;
    const top = overflow && el.scrollTop > 2;
    const bottom = overflow && maxScroll - el.scrollTop > 2;
    const next: ScrollFade = top && bottom ? 'both' : top ? 'top' : bottom ? 'bottom' : 'none';
    setFade((prev) => (prev === next ? prev : next));
    setScrollable((prev) => (prev === overflow ? prev : overflow));
    const thumb = thumbRef.current;
    const track = trackRef.current;
    if (thumb && track && overflow) {
      const trackHeight = Math.max(0, track.clientHeight || el.clientHeight - 12);
      const thumbHeight = Math.max(28, Math.min(trackHeight, Math.round((el.clientHeight / el.scrollHeight) * trackHeight)));
      const travel = Math.max(0, trackHeight - thumbHeight);
      const thumbTop = Math.round((el.scrollTop / maxScroll) * travel);
      thumb.style.height = `${thumbHeight}px`;
      thumb.style.transform = `translateY(${thumbTop}px)`;
    }
  }, []);

  useLayoutEffect(() => {
    if (!active) return undefined;
    if (autoFollow) following.current = true;
    const el = scrollRef.current;
    const inner = innerRef.current;
    measureScroll();
    const frame = requestAnimationFrame(measureScroll);
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(measureScroll) : null;
    if (el) observer?.observe(el);
    if (inner) observer?.observe(inner);
    return () => {
      cancelAnimationFrame(frame);
      observer?.disconnect();
    };
  }, [active, autoFollow, measureScroll]);

  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el || !active) return;
    if (autoFollow && following.current) {
      el.scrollTop = el.scrollHeight;
    }
    measureScroll();
  }, [active, autoFollow, followKey, measureScroll]);

  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el || !active || !focusSelector) return;
    const target = el.querySelector<HTMLElement>(focusSelector);
    if (!target) return;
    const view = el.getBoundingClientRect();
    const box = target.getBoundingClientRect();
    if (box.top < view.top || box.bottom > view.bottom) {
      const offset = box.top - view.top + el.scrollTop;
      el.scrollTop = Math.max(0, offset - Math.max(16, (el.clientHeight - box.height) / 2));
      measureScroll();
    }
  }, [active, focusSelector, measureScroll]);

  const handleScroll = useCallback((event: React.UIEvent<HTMLDivElement>) => {
    const el = event.currentTarget;
    following.current = el.scrollHeight - el.scrollTop - el.clientHeight < 18;
    measureScroll();
  }, [measureScroll]);

  const handleThumbPointerDown = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const el = scrollRef.current;
    const track = trackRef.current;
    const thumb = thumbRef.current;
    if (!el || !track || !thumb) return;
    const maxScroll = el.scrollHeight - el.clientHeight;
    if (maxScroll <= 2) return;
    event.preventDefault();
    event.stopPropagation();
    const startY = event.clientY;
    const startScrollTop = el.scrollTop;
    const travel = Math.max(1, track.clientHeight - thumb.offsetHeight);
    const pointerId = event.pointerId;
    thumb.setPointerCapture?.(pointerId);
    thumb.dataset.dragging = 'true';
    const onMove = (move: PointerEvent) => {
      el.scrollTop = startScrollTop + ((move.clientY - startY) / travel) * maxScroll;
    };
    const onEnd = () => {
      delete thumb.dataset.dragging;
      thumb.releasePointerCapture?.(pointerId);
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onEnd);
      window.removeEventListener('pointercancel', onEnd);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onEnd);
    window.addEventListener('pointercancel', onEnd);
  }, []);

  const handleTrackPointerDown = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const el = scrollRef.current;
    const track = trackRef.current;
    const thumb = thumbRef.current;
    if (!el || !track || !thumb || event.target === thumb) return;
    const maxScroll = el.scrollHeight - el.clientHeight;
    if (maxScroll <= 2) return;
    const rect = track.getBoundingClientRect();
    const travel = Math.max(1, rect.height - thumb.offsetHeight);
    const offset = event.clientY - rect.top - thumb.offsetHeight / 2;
    el.scrollTop = Math.max(0, Math.min(1, offset / travel)) * maxScroll;
  }, []);

  return (
    <div className="process-scroll-shell" data-fade={fade} data-scrollable={scrollable ? 'true' : 'false'}>
      <div className={className} ref={scrollRef} tabIndex={tabIndex} onScroll={handleScroll}>
        <div className="process-scroll-content" ref={innerRef}>
          {children}
        </div>
      </div>
      <div className="process-scroll-track" ref={trackRef} aria-hidden="true" onPointerDown={handleTrackPointerDown}>
        <div className="process-scroll-thumb" ref={thumbRef} onPointerDown={handleThumbPointerDown} />
      </div>
    </div>
  );
}

const EXPLORE_TOOL_NAMES = new Set(['read_file', 'search_text', 'glob_files', 'list_dir', 'code_symbol']);

type ToolSegment =
  | { kind: 'item'; tool: ToolView }
  | { kind: 'cluster'; key: string; tools: ToolView[] };

function isFoldableExploreTool(tool: ToolView, sessionLive: boolean): boolean {
  if (!EXPLORE_TOOL_NAMES.has(tool.name)) return false;
  const shown = tool.status === 'run' && !sessionLive ? 'interrupted' : tool.status;
  return shown === 'ok';
}

function partitionToolSegments(tools: ToolView[], sessionLive: boolean): ToolSegment[] {
  const segments: ToolSegment[] = [];
  let buffer: ToolView[] = [];
  const flush = () => {
    if (buffer.length >= 3) {
      segments.push({ kind: 'cluster', key: `cluster:${buffer[0].callId}`, tools: buffer });
    } else {
      for (const item of buffer) segments.push({ kind: 'item', tool: item });
    }
    buffer = [];
  };
  for (const tool of tools) {
    if (isFoldableExploreTool(tool, sessionLive)) buffer.push(tool);
    else {
      flush();
      segments.push({ kind: 'item', tool });
    }
  }
  flush();
  return segments;
}

const ExploreCluster = memo(function ExploreCluster({ tools }: { tools: ToolView[] }) {
  const [expanded, setExpanded] = useState(false);
  const spanSeconds = computeToolSpanSeconds(tools, false);
  const durationLabel = spanSeconds != null && spanSeconds >= 1 ? formatProcessDuration(spanSeconds) : '';
  return (
    <div className={`tool-explore-cluster${expanded ? ' is-open' : ''}`}>
      <button
        type="button"
        className="tool-explore-toggle"
        aria-expanded={expanded}
        onClick={() => setExpanded(prev => !prev)}
      >
        <span className="tool-explore-badge">探索 {tools.length} 项</span>
        <span className="tool-explore-summary">{toolCounts(tools)}</span>
        {durationLabel && <span className="tool-explore-meta">{durationLabel}</span>}
        <span className="tool-row-chevron" aria-hidden="true"><ProcessChevron /></span>
      </button>
      {expanded && (
        <div className="tool-explore-items">
          {tools.map(tool => <ToolRow key={tool.callId} tool={tool} />)}
        </div>
      )}
    </div>
  );
});

const INITIAL_TOOL_SEGMENTS = 40;
const TOOL_SEGMENT_STEP = 80;
/**
 * 受控 <details> 的展开开关只看 summary 上的点击：Electron 内嵌 Chromium 与独立 Chromium 的 toggle 派发时机不一致，
 * 靠 onToggle 回写会出现“点了不开”；默认 toggle 也会和状态回写互相抵消，所以交给点处理阻止默认行为。
 */
export function isSummaryClick(event: React.MouseEvent<HTMLDetailsElement>) {
  if (!(event.target as HTMLElement).closest('summary')) return false;
  event.preventDefault();
  return true;
}

/** 只有失败和等待批准会在展开组内自动露出详情；「已中断」只是收尾常态，不替用户撑开行。 */
const AUTO_REVEAL_STATUS = new Set(['error', 'wait']);

/** 只给真正的增删行着色，文件头的 +++ / --- 保持原样。 */
function diffLineKind(line: string) {
  if (line.startsWith('+') && !line.startsWith('+++')) return 'added';
  if (line.startsWith('-') && !line.startsWith('---')) return 'removed';
  return '';
}

/**
 * 操作组单独 memo：流式输出时只有 reply 在变，tools 数组引用不变，这一整块（可能几十行）
 * 直接跳过比对。默认收起为单行摘要，展开后在固定最大高度内滚动，不把对话拉成无限高度。
 */
export const ToolGroup = memo(function ToolGroup({ tools }: { tools: ToolView[] }) {
  const sessionLive = useStore(state => state.running);
  const running = sessionLive ? tools.filter(tool => tool.status === 'run').length : 0;
  // 会话已停时，还停在 run 的行界面上会当成中断；组头统计也要跟显示一致，别再写「正在执行」。
  const attention = tools.filter((tool) => {
    const status = tool.status === 'run' && !sessionLive ? 'interrupted' : tool.status;
    return ['error', 'wait', 'interrupted'].includes(status);
  });
  const needsAttention = attention.length > 0;
  const failed = attention.some(tool => tool.status === 'error');
  const hasActiveAsk = sessionLive && tools.some(tool => tool.status === 'run' && tool.name === 'ask_user' && Boolean(parseAsk(tool.args)));
  // 操作组一律收起，除非用户自己点开：滚动历史或新建回合时不自动撑开，避免把正在看的位置顶走。
  // 需要留意、等待回答只体现在摘要的提示上，不代替用户展开。
  const [open, setOpen] = useState(false);
  const [attentionOnly, setAttentionOnly] = useState(false);
  const [visibleLimit, setVisibleLimit] = useState(INITIAL_TOOL_SEGMENTS);
  const detailsRef = useRef<HTMLDetailsElement>(null);
  useSecondTick(running > 0);
  // 只有「正在等我回答」这一种情况替他展开一次；其余提示留在摘要上，收起状态不动。
  useEffect(() => {
    if (hasActiveAsk) setOpen(true);
  }, [hasActiveAsk]);
  const filterAttention = attentionOnly && needsAttention;
  const visibleTools = filterAttention ? attention : tools;
  const segments = useMemo(
    () => (filterAttention ? visibleTools.map(tool => ({ kind: 'item' as const, tool })) : partitionToolSegments(visibleTools, sessionLive)),
    [filterAttention, visibleTools, sessionLive],
  );
  const spanSeconds = computeToolSpanSeconds(tools, running > 0);
  const durationLabel = spanSeconds != null && spanSeconds >= 1 ? formatProcessDuration(spanSeconds) : '';
  const activePeek = running > 0 ? summarizeActiveTool(tools) : '';
  const lastTool = tools[tools.length - 1];
  const toggleAttentionFilter = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    if (!open) {
      setOpen(true);
      setAttentionOnly(true);
    } else {
      setAttentionOnly(prev => !prev);
    }
  };
  const jumpToLatest = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    const list = detailsRef.current?.querySelector<HTMLElement>('.tool-group-list');
    if (list) list.scrollTop = list.scrollHeight;
  };
  const hiddenSegments = !filterAttention && segments.length > visibleLimit ? segments.length - visibleLimit : 0;
  const renderedSegments = hiddenSegments > 0 ? segments.slice(-visibleLimit) : segments;
  // 有提问时优先把回答卡片滚进视野，其次才是需留意项。
  let focusSelector: string | undefined;
  if (hasActiveAsk) focusSelector = '.ask-card';
  else if (needsAttention) focusSelector = '.tool-row.is-attention';
  return (
    <details
      ref={detailsRef}
      className={`tool-group conversation-tools${running > 0 ? ' is-live' : ''}${hasActiveAsk ? ' is-asking' : ''}`}
      open={open}
      onToggle={event => setOpen(event.currentTarget.open)}
    >
      <summary className="tool-group-head">
        {running > 0 && !needsAttention && <Spinner size="sm" shade="subtle" aria-hidden="true" />}
        <span className="tool-group-label">{running ? running + ' 项正在执行' : tools.length + ' 项操作'}</span>
        {durationLabel && <span className="tool-group-duration">{durationLabel}</span>}
        <span className="tool-group-count">{toolCounts(tools)}</span>
        {!open && activePeek && <span className="tool-group-peek" title={activePeek}>{activePeek}</span>}
        {needsAttention && (
          <button
            type="button"
            className={`conversation-tool-attention${filterAttention ? ' is-active' : ''}`}
            aria-pressed={filterAttention}
            title={filterAttention ? '当前仅显示需留意项，点击恢复全部' : '点击仅查看需留意的操作'}
            onClick={toggleAttentionFilter}
          >
            <StatusDot variant={failed ? 'error' : 'warning'} label="需要留意" aria-hidden="true" />
            {filterAttention ? `仅看 ${attention.length} 项` : `${attention.length} 项需留意`}
          </button>
        )}
        {open && !filterAttention && tools.length >= 25 && (
          <button
            type="button"
            className="tool-group-jump"
            title="滚动到最近一条操作"
            onClick={jumpToLatest}
          >
            跳到最新
          </button>
        )}
        <span className="tool-group-chevron" aria-hidden="true"><ProcessChevron /></span>
      </summary>
      {/* 收起时不挂载内部行：<details> 只负责隐藏，React 仍会为每行跑正文解析、diff 高亮和 Markdown，
          这些默认看不见的内容只有展开时才值得付成本，所以只在展开后构建。 */}
      {open && (
        <ProcessScrollViewport
          active
          className="tool-group-list"
          autoFollow={running > 0 && !needsAttention && !hasActiveAsk}
          followKey={`${visibleTools.length}:${lastTool?.callId ?? ''}:${lastTool?.status ?? ''}:${filterAttention ? 'attn' : 'all'}`}
          focusSelector={focusSelector}
        >
          {hiddenSegments > 0 && (
            <button
              type="button"
              className="tool-group-earlier"
              onClick={() => setVisibleLimit(prev => prev + TOOL_SEGMENT_STEP)}
            >
              显示更早的 {hiddenSegments} 组记录（当前优先展示最近步骤）
            </button>
          )}
          {renderedSegments.map(seg => (
            seg.kind === 'cluster'
              ? <ExploreCluster key={seg.key} tools={seg.tools} />
              : <ToolRow key={seg.tool.callId} tool={seg.tool} />
          ))}
        </ProcessScrollViewport>
      )}
    </details>
  );
});

/** 「计划 1 · 命令 3 · 读取 2」：按首次出现顺序，带次数（旧版 refreshToolGroup 的文案）。 */
function toolCounts(tools: ToolView[]) {
  const order: string[] = [];
  const counts = new Map<string, number>();
  for (const tool of tools) {
    const label = toolLabel(tool.name);
    if (!counts.has(label)) {
      counts.set(label, 0);
      order.push(label);
    }
    counts.set(label, (counts.get(label) || 0) + 1);
  }
  return order.map((label) => `${label} ${counts.get(label)}`).join(' · ');
}

/**
 * 单条操作。默认收起为单行摘要，点击后再展开详情；状态放组件内，流式刷新不会顶掉用户折叠状态。
 */
const ToolRow = memo(function ToolRow({ tool }: { tool: ToolView }) {
  const sequence = tool.detailSequence;
  const autoReveal = sequence == null && AUTO_REVEAL_STATUS.has(tool.status);
  const [open, setOpen] = useState(autoReveal);
  const [detail, setDetail] = useState<{ key: string; state: 'loading' | 'error' | 'ready'; body?: string }>();
  const [copied, setCopied] = useState(false);
  const [imagePreviewFailed, setImagePreviewFailed] = useState(false);
  const rowRef = useRef<HTMLDivElement>(null);
  const userToggled = useRef(false);
  const copyTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => { if (autoReveal) setOpen(true); }, [autoReveal]);
  useEffect(() => () => clearTimeout(copyTimer.current), []);
  const sessionLive = useStore(state => state.running);
  const sessionId = useStore(state => state.sessionId);
  const sessionEpoch = useStore(state => state.sessionEpoch);
  const historyRevision = useStore(state => state.historyRevision);
  const detailKey = JSON.stringify([sessionId, sessionEpoch, historyRevision, sequence, tool.callId]);
  const currentDetail = detail?.key === detailKey ? detail : undefined;
  const detailBody = (sequence == null ? tool.body : currentDetail?.body || '').trim();
  const loadDetail = async () => {
    if (sequence == null || currentDetail?.state === 'ready' || currentDetail?.state === 'loading') return;
    setDetail({ key: detailKey, state: 'loading' });
    try {
      if (!sessionId || historyRevision == null) throw new Error('缺少历史版本');
      const result = await api.event(sessionId, sequence, historyRevision);
      const current = useStore.getState();
      if (current.sessionId !== sessionId || current.sessionEpoch !== sessionEpoch || current.historyRevision !== historyRevision) return;
      const event = result?.event;
      if (result?.historyRevision !== historyRevision || event?.sequence !== sequence || event.type !== 'tool.completed'
        || event.sessionId !== sessionId || event.payload.callId !== tool.callId) throw new Error('历史版本已变化');
      setDetail(previous => previous?.key === detailKey
        ? { key: detailKey, state: 'ready', body: event.payload.resultSummary || '' } : previous);
    } catch {
      setDetail(previous => previous?.key === detailKey ? { key: detailKey, state: 'error' } : previous);
    }
  };
  const running = tool.status === 'run' && sessionLive;
  const shownStatus = tool.status === 'run' && !sessionLive ? 'interrupted' : tool.status;
  const attention = ['error', 'wait', 'interrupted'].includes(shownStatus);
  // 协议里失败正文常带 ERROR: 前缀，界面改成「失败：」。
  const body = tool.status === 'error' && /^ERROR:\s*/i.test(detailBody)
    ? detailBody.replace(/^ERROR:\s*/i, '失败：')
    : detailBody;
  useSecondTick(running);
  const seconds = running ? elapsedSeconds(tool.startedAt, tool.finishedAt) : 0;
  const meta = formatLatency(tool.meta === '未返回结果' ? '' : (tool.meta || ''));
  const display = parseToolDisplay(currentDetail?.body == null ? tool : { ...tool, body: currentDetail.body });
  const imagePreview = tool.name === 'read_file' && /^图片\s/.test(body)
    && /\.(png|jpe?g|gif|webp)$/i.test(display.filePath) && sessionId
    ? `/api/sessions/${encodeURIComponent(sessionId)}/file/image?path=${encodeURIComponent(display.filePath)}`
    : '';
  const isWebResult = tool.name === 'web_search' || tool.name === 'web_fetch';
  const hasUnifiedDiff = /^(?:diff --git |@@ |\+\+\+ |--- )/m.test(body);
  const canOpenDiff = ['edit_file', 'create_file', 'apply_patch'].includes(tool.name) && Boolean(display.filePath);
  const status = TOOL_STATUS_TITLES[shownStatus] || '状态未知';
  const hasDetail = Boolean(body) || sequence != null;
  const Header = hasDetail ? 'button' : 'div';
  const detailLabel = display.fullTarget
    ? `${display.fullTarget}${display.lineRange ? ` ${display.lineRange}` : ''}`
    : `${toolLabel(tool.name)} · ${status.split('：')[0]}`;

  /* 正文按内容换渲染：网页结果走 markdown，带 unified diff 头的逐行着色，其余保持原样纯文本。 */
  const renderBody = () => {
    if (isWebResult) return <div className="tool-web-result"><MarkdownBody text={body} /></div>;
    if (!hasUnifiedDiff) return <pre tabIndex={0}>{body}</pre>;
    return <pre className="tool-output-diff" tabIndex={0}>{body.split('\n').map((line, index) => {
      const kind = diffLineKind(line);
      return <span key={index} className={kind ? `is-${kind}` : undefined}>{line || '\u200b'}{'\n'}</span>;
    })}</pre>;
  };

  /* 点击展开靠近限高视口底部的行时，只微调内部滚动列表，确保详情立即可见而不牵动外层消息流。 */
  useLayoutEffect(() => {
    if (!open || !hasDetail || !userToggled.current) return;
    userToggled.current = false;
    const row = rowRef.current;
    const container = row?.closest<HTMLElement>('.tool-group-list');
    if (!row || !container) return;
    const view = container.getBoundingClientRect();
    const box = row.getBoundingClientRect();
    if (box.bottom > view.bottom - 12) {
      const overflow = box.bottom - (view.bottom - 16);
      const maxAdvance = Math.max(0, box.top - view.top - 12);
      container.scrollTop += Math.min(overflow, maxAdvance);
    } else if (box.top < view.top + 8) {
      container.scrollTop -= (view.top + 12) - box.top;
    }
  }, [open, hasDetail]);

  const copyDetail = async () => {
    const ok = await writeClipboard(body);
    if (!ok) {
      useStore.getState().showNotice('复制失败，请手动选择文本');
      return;
    }
    setCopied(true);
    clearTimeout(copyTimer.current);
    copyTimer.current = setTimeout(() => setCopied(false), 1400);
  };

  return (
    <div
      ref={rowRef}
      className={`tool-row${open && hasDetail ? ' is-open' : ''}${attention ? ' is-attention' : ''}${shownStatus === 'error' ? ' is-error' : ''}${running ? ' is-running' : ''}`}
    >
      <Header
        className="conversation-tool-line"
        type={hasDetail ? 'button' : undefined}
        aria-expanded={hasDetail ? open : undefined}
        onClick={hasDetail ? () => {
          userToggled.current = true;
          setOpen(!open);
          if (!open) void loadDetail();
        } : undefined}
      >
        <span className="conversation-tool-name">{toolLabel(tool.name)}</span>
        <ProcessKindIcon kind={display.kind} />
        <span className="tool-args" title={display.fullTarget || undefined}>{display.primary}</span>
        {display.lineRange && <span className="tool-line-range">{display.lineRange}</span>}
        {display.diff && (
          <span className="tool-diff" aria-label={`新增 ${display.diff.added} 行，删除 ${display.diff.removed} 行`}>
            <span className="tool-diff-add">+{display.diff.added}</span>
            <span className="tool-diff-del">-{display.diff.removed}</span>
          </span>
        )}
        {display.exitCode != null && (
          <span className="tool-exit-code">· 退出码 {display.exitCode}</span>
        )}
        {(running || attention) && (
          <span className={'conversation-tool-state is-' + shownStatus} title={status}>
            {running ? <Spinner size="sm" shade="subtle" aria-hidden="true" /> : <Icon name="info" />}
            {running && seconds >= 3 ? formatClock(seconds) : status.split('：')[0]}
          </span>
        )}
        {hasDetail && (
          <span className="tool-row-chevron" aria-hidden="true"><ProcessChevron /></span>
        )}
        {meta && !running && <span className="tool-meta" title={meta}>{meta}</span>}
      </Header>
      {open && hasDetail && (
        <div className="tool-body">
          {currentDetail?.state === 'loading' && <p role="status">正在加载操作详情…</p>}
          {sequence != null && (!currentDetail || currentDetail.state === 'error') && (
            <p role="alert">{currentDetail ? '详情加载失败。' : '详情尚未加载。'}<button type="button" className="tool-body-action" onClick={() => void loadDetail()}>{currentDetail ? '重试' : '加载'}</button></p>
          )}
          {currentDetail?.state === 'ready' && !body && <p>没有操作详情。</p>}
          {body && <>
          <div className="tool-body-bar">
            <span className="tool-body-target" title={detailLabel}>{detailLabel}</span>
            <div className="tool-body-actions">
              {display.filePath && (
                <button
                  type="button"
                  className="tool-body-action"
                  onClick={() => useStore.getState().openPreview(display.filePath, display.startLine)}
                >
                  预览文件
                </button>
              )}
              {canOpenDiff && (
                <button type="button" className="tool-body-action" onClick={() => {
                  const state = useStore.getState();
                  if (tool.runId) state.setUi({ inspectorOpen: true, inspectorTab: 'changes', reviewRunId: tool.runId, changeView: display.filePath });
                  else state.openPreview(display.filePath);
                }}>
                  查看差异
                </button>
              )}
              {tool.name === 'run_command' && tool.status === 'error' && (
                <button type="button" className="tool-body-action" onClick={() => useStore.getState().setUi({ inspectorOpen: true, inspectorTab: 'commands' })}>
                  查看命令
                </button>
              )}
              <button
                type="button"
                className={`tool-body-action${copied ? ' is-done' : ''}`}
                onClick={copyDetail}
              >
                {copied ? '已复制' : '复制'}
              </button>
            </div>
          </div>
          {imagePreview && !imagePreviewFailed && <img className="tool-image-preview" src={imagePreview}
            alt={`图片预览：${display.primary}`} loading="lazy" onError={() => setImagePreviewFailed(true)} />}
          {imagePreviewFailed && <p className="tool-output-note" role="status">图片预览加载失败，文件可能已移动或无法读取。</p>}
          {renderBody()}
          </>}
        </div>
      )}
      {running && <AskChoices tool={tool} />}
    </div>
  );
});

function AskChoices({ tool }: { tool: ToolView }) {
  const ask = tool.name === 'ask_user' ? parseAsk(tool.args) : null;
  const [custom, setCustom] = useState('');
  const [sent, setSent] = useState('');
  if (!ask) return null;
  const send = (option: string) => {
    const choice = questionChoice(option, custom);
    if (!choice || sent) return;
    const sessionId = useStore.getState().sessionId;
    if (!sessionId) return;
    setSent(choice);
    void api.answerQuestion(sessionId, tool.callId, choice);
  };
  // 选项竖排整行可点：ask_user 的选项常是整句，横排胶囊会把文字挤成省略号，看不出选的是什么。
  return (
    <section className="ask-card" aria-label="需要你的选择">
      <header className="ask-card-head">
        <span className="ask-card-badge"><Icon name="info" /> 需要你的选择</span>
        {sent && <span className="ask-card-sent">已回答</span>}
      </header>
      <p className="ask-card-question">{ask.question}</p>
      <ul className="ask-card-options">
        {ask.options.map((option, index) => (
          <li key={option}>
            <button
              type="button"
              className={`ask-option${sent === option ? ' is-picked' : ''}`}
              disabled={Boolean(sent)}
              onClick={() => send(option)}
            >
              <span className="ask-option-index" aria-hidden="true">{index + 1}</span>
              <span className="ask-option-text">{option}</span>
            </button>
          </li>
        ))}
      </ul>
      <form className="ask-form" onSubmit={event => { event.preventDefault(); send(''); }}>
        <input
          className="ask-custom"
          value={custom}
          placeholder="都不是，自己填写"
          aria-label="自己填写"
          disabled={Boolean(sent)}
          onChange={event => setCustom(event.target.value)}
        />
        <button type="submit" className="ask-submit" disabled={!custom.trim() || Boolean(sent)}>提交</button>
      </form>
      {sent && <p className="ask-card-answer">已提交：{sent}</p>}
    </section>
  );
}

/** 思考块：默认收起为单行摘要，点击后再在限高可滑动区域查看详情；实时思考展开时自动贴底跟随。 */
export const ThinkingBlock = memo(function ThinkingBlock({ thinking }: { thinking: Thinking }) {
  const [open, setOpen] = useState(false);
  const live = Boolean(thinking.live);
  useSecondTick(live);
  const seconds = elapsedSeconds(thinking.startedAt, thinking.finishedAt);
  const duration = thinking.startedAt == null || (!live && thinking.finishedAt == null) ? '' : ` ${seconds}s`;
  const action = thinking.action ? ` · ${thinking.action}` : '';
  return (
    <details
      className={`think-block${live ? ' is-live' : ''}`}
      open={open}
      onToggle={event => setOpen(event.currentTarget.open)}
    >
      <summary>
        <span className="think-summary-text">{live ? `思考中${duration}${action}` : `已思考${duration}`}</span>
        <span className="process-chevron" aria-hidden="true"><ProcessChevron /></span>
      </summary>
      {/* 思考正文同样只在展开后挂载，收起的历史回合不留正文节点。 */}
      {open && (
        <ProcessScrollViewport
          active
          className="think-body"
          autoFollow={live}
          followKey={thinking.text.length}
          tabIndex={0}
        >
          {thinking.text}
        </ProcessScrollViewport>
      )}
    </details>
  );
});

/** 每秒重渲染一次，给计时类文案（思考中 Ns、运行耗时）用。 */
export function useSecondTick(active: boolean) {
  const [, bump] = useState(0);
  useEffect(() => {
    if (!active) {
      return undefined;
    }
    const timer = setInterval(() => bump((value) => value + 1), 1000);
    return () => clearInterval(timer);
  }, [active]);
}

export function elapsedSeconds(startedAt?: number, finishedAt?: number | null) {
  if (startedAt == null || !Number.isFinite(startedAt)) {
    return 0;
  }
  return Math.max(0, Math.floor(((finishedAt ?? Date.now()) - startedAt) / 1000));
}

export const AgentGroup = memo(function AgentGroup({ agents }: { agents: Record<string, AgentView> }) {
  const sessionLive = useStore(state => state.running);
  const list = Object.values(agents || {});
  const [open, setOpen] = useState(false);
  if (!list.length) return null;
  const effective = sessionLive
    ? list
    : list.map(agent => (agent.status === 'running' ? { ...agent, status: 'stopped' as const } : agent));
  const summary = summarizeAgents(effective);
  return (
    <details
      className={`agent-group${summary.running > 0 ? ' is-live' : ''}`}
      open={open}
      onToggle={event => setOpen(event.currentTarget.open)}
    >
      <summary className="agent-group-head">
        {summary.running > 0 && <Spinner size="sm" shade="subtle" aria-hidden="true" />}
        {!summary.running && summary.failed > 0 && <StatusDot variant="error" label="有子智能体失败" aria-hidden="true" />}
        <span className="agent-group-label">{summary.text}</span>
        <span className="tool-group-chevron" aria-hidden="true"><ProcessChevron /></span>
      </summary>
      {open && (
        <ProcessScrollViewport active className="agent-group-list" followKey={list.length}>
          {list.map(agent => <AgentCard key={agent.id} agent={agent} />)}
        </ProcessScrollViewport>
      )}
    </details>
  );
});

const AgentCard = memo(function AgentCard({ agent }: { agent: AgentView }) {
  const sessionLive = useStore(state => state.running);
  const live = agent.status === 'running' && sessionLive;
  useSecondTick(live);
  // 回传正文末尾的 <subagent_result> 是给父模型合并用的清单，不该原样糊在界面上
  const split = splitSubagentResult(agent.result);
  const facts = manifestFacts(split);
  const tools = (agent.tools || []).length;
  const ms = agent.startedAt ? ((live ? Date.now() : (agent.finishedAt || Date.now())) - agent.startedAt) : null;
  const status = live ? agent.status : (agent.status === 'running' ? 'stopped' : agent.status);
  const [open, setOpen] = useState(false);
  return (
    <details className={`tool-group agent-card is-${status || 'stopped'}`} open={open}
      onClick={event => { if (isSummaryClick(event)) setOpen(!open); }}>
      <summary className="tool-group-head agent-head">
        <StatusDot variant={agentStatusDotVariant(status)}
          label={agentStatusLabel(status)} aria-hidden="true" />
        <span className="tool-group-label agent-name" title={agent.task}>
          {agent.name || agentTitle(agent.task) || agent.id}
        </span>
        <span className="agent-state">{agentStatusLabel(status)}</span>
        <span className="tool-group-count">{agentMeta({ tools, ms, status })}</span>
        <span className="tool-group-chevron" aria-hidden="true"><ProcessChevron /></span>
      </summary>
      {open && <div className="agent-body">
        <p className="agent-brief">{agent.task}</p>
        {tools > 0 && (
          <div className="tool-group-list agent-tools">
            {(agent.tools || []).map((tool, index) => {
              const toolStatus = tool.status === 'run' && !sessionLive ? 'interrupted' : tool.status;
              const display = parseToolDisplay(tool);
              return (
                <div key={index} className="tool-row">
                  <div className="conversation-agent-tool">
                    <span className="conversation-tool-name">{toolLabel(tool.name)}</span>
                    <ProcessKindIcon kind={display.kind} />
                    <span className="tool-args" title={display.fullTarget || undefined}>{display.primary}</span>
                    {display.diff && (
                      <span className="tool-diff">
                        <span className="tool-diff-add">+{display.diff.added}</span>
                        <span className="tool-diff-del">-{display.diff.removed}</span>
                      </span>
                    )}
                    <span className={'conversation-tool-state is-' + toolStatus}>{(TOOL_STATUS_TITLES[toolStatus] || '状态未知').split('：')[0]}</span>
                  </div>
                </div>
              );
            })}
          </div>
        )}
        {split.text && (
          <>
            <span className="agent-result-label">结论</span>
            <div className="agent-result"><pre>{split.text}</pre></div>
          </>
        )}
        {facts.length > 0 && <p className="agent-result-facts">{facts.join(' · ')}</p>}
      </div>}
    </details>
  );
});
