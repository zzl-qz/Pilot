import type { ThreadRow, TurnRow, PlanRow, RunUsage } from "../types/thread.js";
import type { RequestReport } from "../types/runtime.js";
import type { ApiResults } from "../types/api.js";
import { cacheRatio, formatCache, totalCost } from "../lib/request-usage.js";
import { modifierKey, duplicateResult } from "../lib/interaction.js";
import { buildThreadDisplay, type CompactionGroupRow, type DisplayThreadRow } from "../lib/thread-display.js";
import { lazy, memo, Suspense, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { IconButton } from "@astryxdesign/core/IconButton";
import { useStickToBottom } from "use-stick-to-bottom";
import { useStore } from "../store.js";
import { api } from "../api.js";
import { historyPrefetchDistance } from "../lib/history.js";
import { MarkdownBody } from "./MarkdownBody.jsx";
import { writeClipboard } from "../lib/clipboard.js";
import { formatClock, formatCount, formatTime } from "../thread-utils.js";
import { Icon } from "./Icon.jsx";
import { ErrorBoundary } from "./ErrorBoundary.jsx";
import { safeLocalStorage } from "../lib/safe-storage.js";
import { UserMessage } from "./thread/UserMessageRow.js";
import { TurnProcess } from "./thread/ProcessDetails.js";
import { ResultCard, SandboxCard, ArbitrationCard } from "./thread/OutcomeCards.js";
import { ProcessChevron, isSummaryClick, useSecondTick, elapsedSeconds } from "./thread/process-helpers.js";
import "./conversation.css";

const RequestList = lazy(() => import("./RequestUsage.jsx").then(m => ({ default: m.RequestList })));
const ThreadNavigator = lazy(() => import("./ThreadNavigator.jsx"));

/* 回到底部的弹簧参数：库的默认值在长距离上要 1s 以上，这里压到 ~200ms 收敛。 */
const BACK_TO_LATEST = { stiffness: 0.2, damping: 0.9, mass: 1 };
function latestAnimation() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : BACK_TO_LATEST;
}

/**
 * 消息流：真实 DOM + use-stick-to-bottom（MIT）+ 浏览器原生 scroll anchoring。
 *
 * 位置正确性全部由两个成熟机制负责，组件里不再有任何 scrollTop 计算：
 * 1. use-stick-to-bottom：只有「用户本来就贴底」时才跟随新内容；滚轮上滑立刻解锁，
 *    流式输出永远抢不走视口，回底交给一个气泡按钮。
 * 2. scroll anchoring：前插历史、视口上方某行长高、图片加载完成……所有布局变化
 *    都由浏览器把视口锚点钉在原地。它生效的前提是 .thread 保持 overflow-anchor: auto，
 *    且消息行不能带 transform（这就是入场动画用 backwards 而不是 both 的原因）。
 */
export function Thread() {
  const rows = useStore((state) => state.thread.rows);
  const history = useStore((state) => state.thread.history);
  const eventCount = useStore((state) => state.thread.windowEvents.length);
  const bottomSignal = useStore((state) => state.ui.bottomSignal);
  const requestEarlierHistory = useStore((state) => state.requestEarlierHistory);
  const requestLaterHistory = useStore((state) => state.requestLaterHistory);

  /*
   * initial / resize 都用 'instant'：首屏直接落在最新消息；流式输出时底部内容持续长高，
   * 每帧瞬时贴底。用弹簧动画追赶会稳定落后一两百像素，还会让「回到底部」按钮反复闪烁。
   */
  const { scrollRef, contentRef, scrollToBottom, isAtBottom, stopScroll } = useStickToBottom({
    initial: 'instant',
    resize: 'instant'
  });

  /*
   * DSH / Codex 式全自动零抖动历史加载：
   * 1. 元素级锚点锁定（而非脆弱的 scrollHeight 差值）：前插前锁定视口内首条可见消息行 [data-row-key]
   *    相对容器顶部的偏移，临时关闭 overflow-anchor 避免浏览器双倍补偿，在 useLayoutEffect 绘制前同步精确回正
   *    el.scrollTop += (newOffset - prevOffset)。即使底部 Agent 正在实时流式吐字、或跨页合并保留首行 key、
   *    或顶到 scrollTop === 0，均能保持 0.0px 视口漂移。
   * 2. 顶部固定高度哨兵 + IntersectionObserver (rootMargin: 760px)：在到达顶部前就自动预取并无缝插入，
   *    不再因 100ms 防抖在连续触控板惯性滚动中被不断打断，也不因单页全是不可见事件而停滞逼迫手动点击。
   */
  const topSentinelRef = useRef<HTMLDivElement>(null);
  const lastScrollTop = useRef(0);
  const lastScrollHeight = useRef(0);
  const lastViewportRef = useRef({ width: 0, height: 0 });
  const earlierPending = useRef(false);
  const pointerHeld = useRef(false);
  const navigationUntil = useRef(0);
  const continueFrameRef = useRef<number | undefined>(undefined);
  const idlePrefetchRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const navigationResumeRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const prependAnchorRef = useRef<{
    active: boolean;
    key: string | null;
    offsetTop: number;
    scrollHeight: number;
    scrollTop: number;
    firstKey: string | null;
    firstSeq: number | null;
    count: number;
  }>({
    active: false, key: null, offsetTop: 0, scrollHeight: 0, scrollTop: 0, firstKey: null, firstSeq: null, count: 0
  });

  const firstWindowSeq = useStore((state) => state.thread.windowEvents[0]?.sequence ?? null);

  const capturePrependAnchor = useCallback((el: HTMLElement) => {
    const containerTop = el.getBoundingClientRect().top;
    let anchorKey: string | null = null;
    let anchorOffsetTop = 0;
    const nodes = el.querySelectorAll<HTMLElement>('[data-row-key]');
    for (const node of nodes) {
      const rect = node.getBoundingClientRect();
      if (rect.bottom > containerTop + 1) {
        anchorKey = node.dataset.rowKey || null;
        anchorOffsetTop = rect.top - containerTop;
        break;
      }
    }
    const state = useStore.getState();
    prependAnchorRef.current = {
      active: true,
      key: anchorKey,
      offsetTop: anchorOffsetTop,
      scrollHeight: el.scrollHeight,
      scrollTop: el.scrollTop,
      firstKey: state.thread.rows[0]?.key ?? null,
      firstSeq: state.thread.windowEvents[0]?.sequence ?? null,
      count: state.thread.rows.length
    };
    el.style.overflowAnchor = 'none';
  }, []);

  const isAtBottomRef = useRef(isAtBottom);
  isAtBottomRef.current = isAtBottom;
  const hasEvents = eventCount > 0;

  const detachStickToBottom = useCallback(() => {
    isAtBottomRef.current = false;
    stopScroll();
  }, [stopScroll]);

  const triggerEarlierAuto = useCallback(() => {
    const el = scrollRef.current;
    const state = useStore.getState();
    if (!el || pointerHeld.current) return;
    const navRemaining = navigationUntil.current - performance.now();
    if (navRemaining > 0) {
      // 导航静音窗结束后再自动复检一次，避免跳转落点本身位于顶部预加载区或紧接着上翻时被永久吞掉。
      clearTimeout(navigationResumeRef.current);
      navigationResumeRef.current = setTimeout(() => {
        navigationResumeRef.current = undefined;
        triggerEarlierAuto();
      }, Math.ceil(navRemaining) + 16);
      return;
    }
    if (earlierPending.current || !state.thread.history.hasMore || state.thread.history.loading || state.thread.history.error) return;
    const scrollable = el.scrollHeight - el.clientHeight;
    const toBottom = scrollable - el.scrollTop;
    // 长内容贴底，或首屏刚挂载尚未由 useStickToBottom 贴底（scrollTop === 0 && lastScrollTop === 0 && isAtBottom）时，
    // 绝不打断贴底；用户一旦上离底部（toBottom >= 8），即使 React 的 isAtBottom 晚一帧更新也立即接管自动加载。
    if (scrollable >= 8 && (toBottom < 8 || (isAtBottomRef.current && el.scrollTop === 0 && lastScrollTop.current === 0))) return;
    const triggerZone = Math.max(760, historyPrefetchDistance(el.clientHeight) * 0.6);
    if (scrollable >= 8 && el.scrollTop > triggerZone) return;
    earlierPending.current = true;
    if (scrollable >= 8) detachStickToBottom();
    capturePrependAnchor(el);
    void requestEarlierHistory().finally(() => {
      if (earlierPending.current) {
        setTimeout(() => {
          earlierPending.current = false;
          if (scrollRef.current) scrollRef.current.style.overflowAnchor = '';
        }, 0);
      }
    });
  }, [capturePrependAnchor, detachStickToBottom, requestEarlierHistory, scrollRef]);

  useLayoutEffect(() => {
    const el = scrollRef.current;
    const prev = prependAnchorRef.current;
    const currentFirstKey = rows[0]?.key ?? null;
    const seqMovedEarlier = prev.firstSeq != null && firstWindowSeq != null && firstWindowSeq < prev.firstSeq;
    const keyPrepended = prev.count > 0 && currentFirstKey !== prev.firstKey && rows.length >= prev.count;
    const prepended = prev.active && (seqMovedEarlier || keyPrepended);

    if (el && prepended) {
      const wasAtBottom = prev.scrollHeight - el.clientHeight - prev.scrollTop < 8;
      if (!wasAtBottom) {
        const containerTop = el.getBoundingClientRect().top;
        let compensated = false;
        if (prev.key) {
          const escaped = typeof CSS !== 'undefined' && typeof CSS.escape === 'function'
            ? CSS.escape(prev.key) : prev.key.replace(/"/g, '\\"');
          const target = el.querySelector<HTMLElement>(`[data-row-key="${escaped}"]`);
          if (target) {
            const newOffsetTop = target.getBoundingClientRect().top - containerTop;
            const drift = newOffsetTop - prev.offsetTop;
            if (Math.abs(drift) > 0.5) {
              el.scrollTop += drift;
              lastScrollTop.current = el.scrollTop;
              lastScrollHeight.current = el.scrollHeight;
            }
            compensated = true;
          }
        }
        if (!compensated && prev.scrollHeight > 0) {
          const deltaHeight = el.scrollHeight - prev.scrollHeight;
          if (deltaHeight > 0) {
            el.scrollTop = prev.scrollTop + deltaHeight;
            lastScrollTop.current = el.scrollTop;
            lastScrollHeight.current = el.scrollHeight;
          }
        }
      }
      el.style.overflowAnchor = '';
      earlierPending.current = false;
      prependAnchorRef.current = {
        active: false, key: null, offsetTop: 0, scrollHeight: el.scrollHeight, scrollTop: el.scrollTop,
        firstKey: currentFirstKey, firstSeq: firstWindowSeq, count: rows.length
      };
      // 若前插后仍位于顶部自动加载区（例如 Home 直达顶部或刚跨过全是工具/心跳事件的稀疏页），下一帧自动衔接下一页。
      const latestHistory = useStore.getState().thread.history;
      const scrollable = el.scrollHeight - el.clientHeight;
      if (latestHistory.hasMore && !latestHistory.loading && !latestHistory.error && !pointerHeld.current
          && (scrollable < 8 || el.scrollTop <= Math.max(760, historyPrefetchDistance(el.clientHeight) * 0.6))) {
        if (continueFrameRef.current != null) cancelAnimationFrame(continueFrameRef.current);
        continueFrameRef.current = requestAnimationFrame(() => {
          continueFrameRef.current = undefined;
          triggerEarlierAuto();
        });
      }
    } else if (!prev.active) {
      prependAnchorRef.current = {
        active: false, key: null, offsetTop: 0, scrollHeight: 0, scrollTop: 0,
        firstKey: currentFirstKey, firstSeq: firstWindowSeq, count: rows.length
      };
    }
  }, [rows, eventCount, firstWindowSeq, scrollRef, triggerEarlierAuto]);

  const beforeNavigation = useCallback(() => {
    if (continueFrameRef.current != null) cancelAnimationFrame(continueFrameRef.current);
    continueFrameRef.current = undefined;
    clearTimeout(navigationResumeRef.current);
    navigationResumeRef.current = undefined;
    navigationUntil.current = performance.now() + 250;
  }, []);

  useEffect(() => () => {
    if (continueFrameRef.current != null) cancelAnimationFrame(continueFrameRef.current);
    clearTimeout(idlePrefetchRef.current);
    clearTimeout(navigationResumeRef.current);
  }, []);

  /* 进入带历史的会话后静默空闲预取前一页进内存，首次上滑 0ms 无感直插；依赖 hasEvents/firstWindowSeq，避免流式吐字频繁清零定时器。 */
  useEffect(() => {
    clearTimeout(idlePrefetchRef.current);
    if (!history.hasMore || history.loading || history.error || !hasEvents) return;
    idlePrefetchRef.current = setTimeout(() => {
      const state = useStore.getState();
      if (state.thread.history.hasMore && !state.thread.history.loading && !state.thread.history.error) {
        void state.prefetchEarlier();
      }
    }, 180);
    return () => clearTimeout(idlePrefetchRef.current);
  }, [history.hasMore, history.loading, history.error, hasEvents, firstWindowSeq]);

  const scheduleEarlierFrame = useCallback(() => {
    if (continueFrameRef.current != null) return;
    continueFrameRef.current = requestAnimationFrame(() => {
      continueFrameRef.current = undefined;
      triggerEarlierAuto();
    });
  }, [triggerEarlierAuto]);

  /* 当用户刚脱离贴底且已位于顶部预加载区时兜底触发一次，避免 IntersectionObserver 只在边界穿越瞬间触发一次而漏掉。 */
  useEffect(() => {
    const el = scrollRef.current;
    if (isAtBottom || !el || !history.hasMore || history.loading || history.error) return;
    const triggerZone = Math.max(760, historyPrefetchDistance(el.clientHeight) * 0.6);
    if (el.scrollTop <= triggerZone) {
      scheduleEarlierFrame();
    }
  }, [isAtBottom, history.hasMore, history.loading, history.error, scrollRef, scheduleEarlierFrame]);

  /* 顶部哨兵 IntersectionObserver：距顶 760px 即在下一帧自动接管加载，不阻塞滚动合成线程，也不因连续滚动反复清零防抖。 */
  useEffect(() => {
    const rootEl = scrollRef.current;
    const sentinelEl = topSentinelRef.current;
    if (!rootEl || !sentinelEl || !history.hasMore || typeof IntersectionObserver !== 'function') return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          scheduleEarlierFrame();
        }
      },
      { root: rootEl, rootMargin: '760px 0px 0px 0px', threshold: 0 }
    );
    observer.observe(sentinelEl);
    return () => observer.disconnect();
  }, [history.hasMore, scrollRef, scheduleEarlierFrame]);

  const handleScroll = useCallback((event: React.UIEvent<HTMLDivElement>) => {
    const el = event.currentTarget;
    if (event.target !== el) return;
    const threshold = historyPrefetchDistance(el.clientHeight);
    const toTop = el.scrollTop;
    const height = el.scrollHeight;
    const vw = el.clientWidth;
    const vh = el.clientHeight;
    const toBottom = Math.max(0, height - toTop - vh);
    const prevTop = lastScrollTop.current;
    const prevHeight = lastScrollHeight.current;
    const sameViewport = lastViewportRef.current.width === vw && lastViewportRef.current.height === vh;
    const goingUp = prevTop > 0 && toTop < prevTop - 2;
    const goingDown = toTop > prevTop;
    lastScrollTop.current = toTop;
    lastScrollHeight.current = height;
    lastViewportRef.current = { width: vw, height: vh };
    // 流式输出期间 ResizeObserver 会令 use-stick-to-bottom 吞掉部分上滑滚动事件；
    // 仅在视口尺寸未变且内容未收缩（排除窗口/侧栏缩放导致的重排）时视作用户主动上滑并立刻解绑贴底。
    if (sameViewport && goingUp && height >= prevHeight - 2 && toBottom > 24 && isAtBottomRef.current) {
      detachStickToBottom();
    }
    if (earlierPending.current) capturePrependAnchor(el);
    if (performance.now() < navigationUntil.current) return;
    if (toTop <= threshold && toBottom >= 8 && history.hasMore && !history.loading && !history.error) {
      void useStore.getState().prefetchEarlier();
      if (toTop <= Math.max(760, threshold * 0.6)) {
        scheduleEarlierFrame();
      }
    }
    if (goingDown && toBottom <= threshold) {
      void requestLaterHistory();
    }
  }, [capturePrependAnchor, detachStickToBottom, history.error, history.hasMore, history.loading, requestLaterHistory, scheduleEarlierFrame]);

  const handlePointerDown = useCallback(() => {
    pointerHeld.current = true;
    if (continueFrameRef.current != null) cancelAnimationFrame(continueFrameRef.current);
    continueFrameRef.current = undefined;
  }, []);
  const handlePointerRelease = useCallback(() => {
    pointerHeld.current = false;
    const el = scrollRef.current;
    if (el && el.scrollTop <= Math.max(760, historyPrefetchDistance(el.clientHeight) * 0.6)) {
      scheduleEarlierFrame();
    }
  }, [scrollRef, scheduleEarlierFrame]);

  const handleWheel = useCallback((event: React.WheelEvent<HTMLDivElement>) => {
    if (event.deltaY < 0 && event.currentTarget.scrollTop <= 12) scheduleEarlierFrame();
  }, [scheduleEarlierFrame]);

  const loadEarlierManually = useCallback(() => {
    stopScroll();
    const el = scrollRef.current;
    if (el) capturePrependAnchor(el);
    earlierPending.current = true;
    void requestEarlierHistory({ force: true }).finally(() => {
      if (earlierPending.current) {
        setTimeout(() => {
          earlierPending.current = false;
          if (scrollRef.current) scrollRef.current.style.overflowAnchor = '';
        }, 0);
      }
    });
  }, [capturePrependAnchor, requestEarlierHistory, scrollRef, stopScroll]);

  useEffect(() => {
    window.addEventListener('pointerup', handlePointerRelease);
    window.addEventListener('pointercancel', handlePointerRelease);
    return () => {
      window.removeEventListener('pointerup', handlePointerRelease);
      window.removeEventListener('pointercancel', handlePointerRelease);
    };
  }, [handlePointerRelease]);

  /* 内容不足一屏时不会产生滚动事件，主动补更早的一页，否则历史永远加载不出来。 */
  useEffect(() => {
    const el = scrollRef.current;
    if (!el || !history.hasMore || history.loading || history.error) {
      return;
    }
    if (el.scrollHeight - el.clientHeight < 8) {
      const timer = setTimeout(() => triggerEarlierAuto(), 0);
      return () => clearTimeout(timer);
    }
  }, [rows.length, eventCount, history.hasMore, history.loading, history.error, scrollRef, triggerEarlierAuto]);

  /* 发送消息 / 切换会话后主动贴底：此刻用户要看的是刚发出的内容。 */
  const lastSignal = useRef(bottomSignal);
  useEffect(() => {
    if (bottomSignal === lastSignal.current) {
      return;
    }
    lastSignal.current = bottomSignal;
    scrollToBottom({ animation: latestAnimation() });
  }, [bottomSignal, scrollToBottom]);

  const display = useMemo(() => buildThreadDisplay(rows), [rows]);
  const sessionLoading = useStore((state) => state.sessionLoading);
  const running = useStore((state) => state.running);
  const returnToLatest = useStore((state) => state.returnToLatest);
  const hasPendingApprovals = useStore((state) => Object.keys(state.pendingApprovals || {}).length > 0);
  const hasPendingQuestions = useStore((state) => Object.keys(state.pendingQuestions || {}).length > 0);
  const bottomHint = hasPendingApprovals ? '等待批准' : hasPendingQuestions ? '等待选择' : running ? '有新输出' : '';
  const lastResultRow = display.rows.findLast((row) => row.type === 'result');
  const lastInteractionIndex = display.rows.findLastIndex((row) => row.type === 'turn' || row.type === 'user');

  if (sessionLoading && !rows.length && !eventCount) {
    return <ThreadLoadingSkeleton />;
  }

  if (!rows.length && !eventCount && !history.hasMore) {
    return <EmptyState />;
  }

  return (
    <div className="thread-scroll-region conversation-thread">
      <div className="thread" ref={scrollRef} role="region" aria-label="消息记录" tabIndex={0}
        onPointerDownCapture={handlePointerDown} onScroll={handleScroll} onWheel={handleWheel}>
        <div className="thread-column" ref={contentRef}>
          {history.hasMore && (
            <div ref={topSentinelRef} className="history-sentinel" role="status" aria-live="polite">
              {history.error ? (
                <RetryRow label="加载更早失败，点击重试" onClick={loadEarlierManually} />
              ) : history.loading ? (
                <span className="history-sentinel-status">正在加载更早消息…</span>
              ) : null}
            </div>
          )}
          {display.rows.map((row, index) => (
            <RowView
              key={row.key}
              row={row}
              duplicate={row.type === 'result' && duplicateResult(display.rows as ThreadRow[], index)}
              hasPrecedingReply={index > 0 && display.rows[index - 1]?.type === 'turn' && Boolean((display.rows[index - 1] as TurnRow).reply?.text)}
              isLast={index >= lastInteractionIndex}
              isLastResult={Boolean(lastResultRow && row.key === lastResultRow.key)}
            />
          ))}
          {history.hasNewer && history.forwardError && (
            <RetryRow label="加载更新失败，点击重试" onClick={() => requestLaterHistory({ force: true })} />
          )}
        </div>
      </div>
      <ThreadBottomButton
        visible={!isAtBottom}
        hasNewer={history.hasNewer}
        hint={bottomHint}
        onJump={() => {
          if (history.hasNewer) void returnToLatest();
          else scrollToBottom({ animation: latestAnimation() });
        }}
        onReturnLatest={() => { void returnToLatest(); }}
      />
      {display.pinnedPlan && (
        <div className="thread-pinned-plan">
          <PlanCard row={display.pinnedPlan} />
        </div>
      )}
      {rows.length > 0 && (
        <ErrorBoundary name="对话导航"><Suspense fallback={null}>
          <ThreadNavigator rows={rows} scrollRef={scrollRef} contentRef={contentRef} stopFollowing={stopScroll} beforeJump={beforeNavigation} />
        </Suspense></ErrorBoundary>
      )}
    </div>
  );
}

function ThreadLoadingSkeleton() {
  return (
    <div className="thread-scroll-region conversation-thread">
      <div className="thread is-loading" role="status" aria-label="正在加载会话历史">
        <div className="thread-column">
          <div className="thread-skeleton-turn">
            <div className="thread-skeleton-user" />
            <div className="thread-skeleton-line is-medium" />
            <div className="thread-skeleton-line is-long" />
            <div className="thread-skeleton-line is-short" />
          </div>
          <div className="thread-skeleton-turn">
            <div className="thread-skeleton-user is-short" />
            <div className="thread-skeleton-line is-long" />
            <div className="thread-skeleton-line is-medium" />
          </div>
        </div>
      </div>
    </div>
  );
}

function ThreadBottomButton({ visible, hasNewer = false, hint, onJump, onReturnLatest }: {
  visible: boolean; hasNewer?: boolean; hint?: string; onJump: () => void; onReturnLatest?: () => void;
}) {
  const active = visible || hasNewer;
  // 始终保留固定高度停靠带，切换显隐时绝不改变上方 .thread 滚动容器高度，避免与贴底逻辑互搏。
  return (
    <div className={`thread-bottom-dock${active ? ' is-visible' : ''}`} aria-hidden={!active || undefined}>
      {hasNewer ? (
        <Button size="sm" variant="secondary" className="conversation-return-latest" label="回到最新消息"
          onClick={onReturnLatest ?? onJump} />
      ) : visible ? (
        <>
          {hint && <span className="latest-hint" aria-hidden="true">{hint}</span>}
          <IconButton className="conversation-latest" label="回到底部" onClick={onJump}
            icon={<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 3.5v12M5.5 11l4.5 4.5 4.5-4.5" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" /></svg>} />
        </>
      ) : null}
    </div>
  );
}

function RetryRow({ label, onClick, disabled = false }: { label: string; onClick: () => void; disabled?: boolean }) {
  return (
    <div className="history-more-wrap">
      <Button size="sm" variant="ghost" label={label} isDisabled={disabled} onClick={onClick} />
    </div>
  );
}

function EmptyState() {
  const workspace = useStore(state => state.workspace || state.draftSession?.workspace || '');
  const project = useStore(state => state.projects.find(item => item.id === state.currentProjectId));
  const settingsData = useStore(state => state.settingsData);
  // 不能在 selector 里调用 state.modelReady()：那是带 showNotice 副作用的发送前守卫，
  // 保存模型期间会触发 useSyncExternalStore 同步 setState 无限递归卡死界面。
  const modelConfigured = Boolean(
    settingsData?.model?.trim()
    && settingsData?.modelApiKeySet
    && (settingsData.modelEndpoint === undefined || settingsData.modelEndpoint.trim())
  );
  const soundEnabled = useStore(state => state.alertPrefs.soundEnabled);
  const [onboardingDismissed, setOnboardingDismissed] = useState(() => safeLocalStorage().getItem('pilot.onboarding.dismissed') === '1');
  const [scope, setScope] = useState<ApiResults['worktreeInfo'] | null>(null);
  const [scopeUnavailable, setScopeUnavailable] = useState(false);
  useEffect(() => {
    let alive = true;
    setScope(null);
    setScopeUnavailable(false);
    if (workspace) api.worktreeInfo(workspace)
      .then(info => { if (alive) setScope(info); })
      .catch(() => { if (alive) setScopeUnavailable(true); });
    return () => { alive = false; };
  }, [workspace]);
  const prompts = [
    { title: '了解项目', sub: '识别技术栈、入口与运行方式', prompt: '分析当前工作区的结构、技术栈和启动方式，先读取项目实际配置和说明。' },
    { title: '调研问题', sub: '核对来源，交付可下载的报告', prompt: '请帮我调研以下问题，优先查阅原始来源，说明时间与不确定性，整理为报告并保存到成果面板。调研问题：' },
    { title: '整理资料', sub: '提取要点、核对数据与缺失项', prompt: '请根据我提供的文件整理要点或表格，核对数字和来源，缺失数据不要猜测，并将结果保存到成果面板。具体要求：' }
  ];
  const showOnboarding = Boolean(settingsData && !onboardingDismissed && (!modelConfigured || !workspace));
  return (
    <div className="thread-scroll-region conversation-thread">
      <div className="thread is-empty">
        <div className="thread-column">
          <section className="conversation-empty" id="empty-state" aria-labelledby="conversation-empty-title">
            <h2 id="conversation-empty-title" className="empty-title">从一个任务开始</h2>
            <p className="empty-sub">描述目标或拖入资料，Pilot 会在工作区执行任务，并留下可核对的改动与验证结论。</p>
            {showOnboarding && (
              <div className="onboarding-checklist" role="region" aria-label="首次使用就绪检查">
                <div className="onboarding-head">
                  <strong>就绪检查</strong>
                  <button
                    type="button"
                    className="onboarding-dismiss"
                    onClick={() => {
                      setOnboardingDismissed(true);
                      safeLocalStorage().setItem('pilot.onboarding.dismissed', '1');
                    }}
                  >
                    暂不显示
                  </button>
                </div>
                <ul className="onboarding-steps">
                  <li className={modelConfigured ? 'is-done' : ''}>
                    <span>{modelConfigured ? '✓ 模型服务与密钥已就绪' : '1. 配置对话模型与 API Key'}</span>
                    {!modelConfigured && (
                      <Button size="sm" variant="primary" label="去配置"
                        onClick={() => useStore.getState().setUi({ settingsOpen: true, settingsSection: 'model' })} />
                    )}
                  </li>
                  <li className={workspace ? 'is-done' : ''}>
                    <span>{workspace ? '✓ 已绑定工作区目录' : '2. 选择项目代码目录'}</span>
                    {!workspace && (
                      <Button size="sm" variant="ghost" label="选择目录"
                        onClick={() => useStore.getState().setUi({ picker: { title: '选择任务工作区' } })} />
                    )}
                  </li>
                  <li className={soundEnabled ? 'is-done' : ''}>
                    <span>{soundEnabled ? '✓ 任务完成提示音与提醒已开启' : '3. 开启完成提示音与后台悬浮提醒'}</span>
                    <Button size="sm" variant="ghost" label="提醒设置"
                      onClick={() => useStore.getState().setUi({ settingsOpen: true, settingsSection: 'alerts' })} />
                  </li>
                </ul>
              </div>
            )}
            <div className="conversation-scope">
              <Icon name="folder" />
              <div>
                <strong>{project?.name || '当前工作区'}</strong>
                <span title={workspace}>{workspace || '尚未选择工作区'}</span>
                {scope?.branch && <span>{scope.worktree ? '源分支' : 'Git 分支'} · {scope.branch}{scope.worktree ? ' · 独立 worktree' : ''}</span>}
                {scopeUnavailable && <span role="status">工作区详情暂不可用</span>}
              </div>
              <Button size="sm" label="选择工作区" onClick={() => useStore.getState().setUi({ picker: { title: '选择任务工作区' } })} />
            </div>
            <ul className="conversation-prompts" aria-label="任务示例">
              {prompts.map(item => (
                <li key={item.title}>
                  <Button className="conversation-prompt" label={item.title + ' · ' + item.sub} size="lg" variant="ghost" width="100%"
                    endContent={<Icon name="chevron" />} onClick={() => {
                      // 草稿态直接填进输入框；发送首条消息时才创建会话。
                      const state = useStore.getState();
                      if (!state.sessionId && !state.draftSession) state.startNewSession();
                      state.composerApi?.appendText((state.composerApi.getText().trim() ? '\n' : '') + item.prompt);
                      state.composerApi?.focus();
                    }}>
                    <span className="conversation-prompt-copy">
                      <span className="conversation-prompt-title">{item.title}</span>
                      <span className="conversation-prompt-note">{item.sub}</span>
                    </span>
                  </Button>
                </li>
              ))}
            </ul>
            <p className="empty-keys">
              <span><kbd>{modifierKey}</kbd><kbd>N</kbd> 新任务</span>
              <span><kbd>Shift</kbd><kbd>Tab</kbd> 切模式</span>
              <span><kbd>@</kbd> 引用文件</span>
              <span><kbd>/</kbd> 快捷指令</span>
              <span><kbd>{modifierKey}</kbd><kbd>/</kbd> 全部快捷键</span>
              <span><kbd>Esc</kbd> 停止</span>
            </p>
          </section>
        </div>
      </div>
    </div>
  );
}

const RowView = memo(function RowView({ row, isLast, duplicate, hasPrecedingReply = false, isLastResult = false }: {
  row: DisplayThreadRow; isLast: boolean; duplicate: boolean; hasPrecedingReply?: boolean; isLastResult?: boolean;
}) {
  switch (row.type) {
    case 'user': return <UserMessage row={row} />;
    case 'turn': return <Turn row={row} isLast={isLast} />;
    case 'compaction-group': return <CompactionGroupCard row={row} />;
    case 'result': return <ErrorBoundary name="运行结果"><ResultCard row={row} duplicate={duplicate} hasPrecedingReply={hasPrecedingReply} isLastResult={isLastResult} /></ErrorBoundary>;
    case 'sandbox': return <ErrorBoundary name="沙箱结果"><SandboxCard row={row} /></ErrorBoundary>;
    case 'arb': return <ErrorBoundary name="审批结果"><ArbitrationCard row={row} /></ErrorBoundary>;
    default: return null;
  }
});

const Turn = memo(function Turn({ row, isLast }: { row: TurnRow; isLast: boolean }) {
  // 正文 = 已落库的 delta + 还在路上的 preview；两者不能都拼进 text（会重复）
  const replyText = `${row.reply.text || ''}${row.reply.preview || ''}`;
  // 历史回合不订阅全局 running，避免每次启停新任务都重绘整页历史消息。
  const activeRunning = useStore((state) => isLast && state.running);
  const agentCount = Object.keys(row.agents || {}).length;
  const commentaries = row.commentaries || [];
  const hasProcess = Boolean(row.thinking) || row.tools.length > 0 || agentCount > 0;
  /*
   * 「重新生成」走 /regenerate：后端用上一条用户输入重跑，历史里不会多一条重复的用户消息。
   * 只在最后一轮、且没有任务在跑时给入口，避免对中间轮次重放出错乱的历史。
   */
  const canRegenerate = isLast && !activeRunning && !row.reply.live && Boolean(replyText);
  // Codex 式元信息行：作者留在头部，复制/重新生成与时间一起放到正文下面。
  const replyAt = row.stats?.finishedAt || row.stats?.startedAt;
  const replyTime = formatTime(replyAt);
  return (
    <div className={`turn-shell${row.reply.live || (isLast && activeRunning) ? ' is-live' : ''}`} data-row-key={row.key} data-run={row.runIds?.join(',') || undefined}>
      {commentaries.length > 0 && (
        <div className="turn-commentaries">
          {commentaries.map((note, idx) => (
            <div key={idx} className="turn-commentary">
              <MarkdownBody text={note} />
            </div>
          ))}
        </div>
      )}
      {hasProcess && (
        <ErrorBoundary name="运行详情">
          <TurnProcess thinking={row.thinking} tools={row.tools} agents={row.agents} />
        </ErrorBoundary>
      )}
      {(replyText || row.reply.live) && (
        <article className={`msg msg-assistant${row.reply.live ? ' is-streaming' : ''}`}>
          <header className="conversation-reply-head">
            <span className="who">Pilot</span>
            {row.reply.live && <span className="conversation-streaming" role="status">
              <span className="conversation-streaming-dot" aria-hidden="true" />正在输出
            </span>}
          </header>
          {replyText && <div className="bubble"><MarkdownBody text={replyText} streaming={row.reply.live} /></div>}
          {replyText && (
            <footer className={`conversation-reply-foot${row.reply.live ? ' is-placeholder' : ''}`} aria-hidden={row.reply.live || undefined}>
              {!row.reply.live && (
                <>
                  <CopyControl text={replyText} label="复制这条回复" />
                  {canRegenerate && <IconButton className="message-action" size="sm" variant="ghost"
                    label="重新生成" icon={<Icon name="refresh" />} onClick={() => useStore.getState().regenerate()} />}
                  {replyTime && <span className="conversation-reply-time">{replyTime}</span>}
                </>
              )}
            </footer>
          )}
        </article>
      )}
      <TurnStats stats={row.stats} reports={row.requests} agentCount={agentCount} />
    </div>
  );
});

/**
 * 复制按钮：图标点一下变对勾、1.4 秒后复原（旧版 copyControl 的手感）。
 * 剪贴板写入走 lib/clipboard 的双通道，内嵌 webview 里也能复制上。
 */
const CopyControl = memo(function CopyControl({ text, label }: { text: string; label: string }) {
  const [done, setDone] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  async function copy() {
    const ok = await writeClipboard(text);
    if (!ok) {
      useStore.getState().showNotice('复制失败，请手动选择文本');
      return;
    }
    setDone(true);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setDone(false), 1400);
  }
  return <IconButton className={'copy-control message-action' + (done ? ' is-done' : '')}
    label={done ? '已复制回复' : label} size="sm" variant="ghost" icon={<Icon name={done ? 'check' : 'copy'} />} onClick={copy} />;
});

const TurnStats = memo(function TurnStats({ stats, reports, agentCount }: { stats: RunUsage | null; reports?: RequestReport[]; agentCount: number }) {
  const statsRunId = stats?.runId || null;
  const live = useStore(state => Boolean(state.running && state.runId && (!statsRunId || statsRunId === state.runId)));
  const runStartedAt = useStore(state => (live ? state.runStartedAt : 0));
  const [open, setOpen] = useState(false);
  useSecondTick(live);
  if (!stats) {
    return null;
  }
  const clockStart = live && runStartedAt
    ? Math.min(stats.startedAt || runStartedAt, runStartedAt)
    : stats.startedAt;
  const elapsed = !clockStart ? null : live
    ? elapsedSeconds(clockStart, null)
    : stats.finishedAt ? elapsedSeconds(clockStart, stats.finishedAt) : null;
  const elapsedLabel = elapsed == null ? '' : formatClock(elapsed);
  // 没有模型实测用量时是估算值，加 ≈（旧版 formatTokenCount 的 exact 语义）
  const mark = stats.exact === false ? '≈' : '';
  const limit = Number(stats.contextLimit) || 0;
  const used = Number(stats.contextTokens) || 0;
  const ratio = limit > 0 ? used / limit : 0;
  const contextTone = ratio >= 0.92 ? ' is-critical' : ratio >= 0.8 ? ' is-warn' : '';
  // 估算路径没有缓存观测：显示「—」，不能把「没观测」读成「没命中」
  const cacheLabel = formatCache(stats.details);
  // 有观测就把命中率提到折叠条上，不展开也能看到；没观测就不占位。
  const cacheSeen = cacheRatio(stats.details) != null;
  const priced = stats.details?.pricedRequests ?? 0;
  const requests = stats.details?.requests ?? stats.modelRequests ?? 0;
  let priceNote: string;
  if (!stats.details) {
    priceNote = '这是较早的记录，没有缓存和单价。';
  } else if (priced === 0) {
    priceNote = '端点没有返回单价，费用先空着。token 和缓存仍然有效。';
  } else {
    const incomplete = priced < requests ? '，合计不完整' : '';
    priceNote = `含压缩与子代理。可计价 ${priced}/${requests} 次${incomplete}。`;
  }
  return (
    <details className="turn-stats-detail" onToggle={event => setOpen(event.currentTarget.open)}>
      <summary>
        <span>{elapsedLabel ? `${elapsedLabel} · ` : ''}用量详情</span>
        {cacheSeen && <span className="turn-cache" title={`本轮累计命中缓存 ${formatCount(stats.cachedInputTokens)} / 总输入 ${formatCount(stats.inputTokens)}`}>缓存命中 {cacheLabel}</span>}
        <span className="process-chevron" aria-hidden="true"><ProcessChevron /></span>
      </summary>
      <div className="turn-stats">
      {elapsedLabel && <span className="turn-stat"><span>耗时</span><b>{elapsedLabel}</b></span>}
      <span className="turn-stat"><span>模型请求</span><b>{stats.modelRequests == null ? '—' : stats.modelRequests + ' 次'}</b></span>
      {agentCount > 0 && <span className="turn-stat"><span>子智能体</span><b>{agentCount} 个</b></span>}
      <span className="turn-stat" title={String(stats.inputTokens ?? '')}><span>输入</span><b>{mark}{formatCount(stats.inputTokens)}</b></span>
      <span className="turn-stat" title={String(stats.outputTokens ?? '')}><span>输出</span><b>{mark}{formatCount(stats.outputTokens)}</b></span>
      <span className="turn-stat" title={`命中缓存 ${formatCount(stats.cachedInputTokens)} / 总输入 ${formatCount(stats.inputTokens)}`}>
        <span>缓存命中</span><b>{cacheLabel}</b>
      </span>
      <span className={`turn-stat is-context${contextTone}`} title={`当前上下文 ${used} / 有效输入预算 ${limit}（已扣除输出预留与安全余量）`}>
        <span>上下文</span><b>{formatCount(stats.contextTokens)}</b>
      </span>
      <span className="turn-stat"><span>费用估算</span><b>{priced > 0 ? totalCost(stats.details) : '未返回'}</b></span>
      <span className="turn-stat-note">{priceNote}</span>
    </div>{open && (reports?.length || 0) > 0 && <ErrorBoundary name="请求用量"><Suspense fallback={null}><RequestList reports={reports} /></Suspense></ErrorBoundary>}</details>
  );
});

/* ---------- 卡片 ---------- */

function planStatusTone(status?: string) {
  if (status === 'completed' || status === 'done') return 'completed';
  if (status === 'in_progress' || status === 'doing' || status === 'running') return 'in_progress';
  if (status === 'blocked' || status === 'failed') return 'blocked';
  if (status === 'skipped') return 'skipped';
  return 'pending';
}

const PlanCard = memo(function PlanCard({ row }: { row: PlanRow }) {
  const running = useStore((state) => state.running);
  const items = (row.items || []).filter(item => (item.step || item.text || '').trim());
  if (!items.length) return null;
  const done = items.filter((item) => item.status === 'completed' || item.status === 'done').length;
  const finished = items.length > 0 && done === items.length;
  const pct = Math.round((done / items.length) * 100);
  const activeItem = !finished
    ? (items.find(item => planStatusTone(item.status) === 'in_progress')
      || items.find(item => planStatusTone(item.status) === 'blocked')
      || items.find(item => planStatusTone(item.status) === 'pending'))
    : null;
  const activeStep = activeItem ? (activeItem.step || activeItem.text || '').trim() : '';
  // 已完成、已结束或长计划（>4 步）默认收成单行进度胶囊，避免霸占半屏；点开仍可看全部步骤。
  const defaultOpen = running && !finished && items.length <= 4;
  // 用户没亲自点过就跟随默认值（进行中自动展开、结束自动收起）；步骤列表只在展开时挂载。
  const [userOpen, setUserOpen] = useState<boolean | null>(null);
  const open = userOpen ?? defaultOpen;
  return (
    <aside className={`plan-card${finished ? ' is-complete' : ''}`}>
      <details open={open} onClick={event => { if (isSummaryClick(event)) setUserOpen(!open); }}>
      <summary className="plan-head">
        <span className="plan-title">执行计划</span>
        <span className="plan-mini-bar" aria-hidden="true"><i style={{ width: `${pct}%` }} /></span>
        <span className="plan-progress">{items.length ? `${done}/${items.length}` : ''}</span>
        {activeStep && <span className="plan-current" title={activeStep}>{activeStep}</span>}
        <span className="process-chevron" aria-hidden="true"><ProcessChevron /></span>
      </summary>
      {/* 收起时不留步骤节点：计划条数多时这里就是上滑成本的主项之一。 */}
      {open && (<>
      {!finished && <div className="plan-bar" aria-hidden="true"><i style={{ width: `${pct}%` }} /></div>}
      <ol className="plan-list">
        {items.map((item, index) => {
          const tone = planStatusTone(item.status);
          return (
          <li key={index} className={`plan-item is-${tone}`}>
            <span className="plan-index">{index + 1}</span>
            <span className="plan-text" title={item.note || undefined}>{item.step || item.text || ''}</span>
            <span className={`plan-status is-${tone}`}>{planStatusLabel(item.status)}</span>
            {item.note && (
              <span className="plan-note">{item.note}</span>
            )}
          </li>
          );
        })}
      </ol>
      </>)}
      </details>
    </aside>
  );
});

function planStatusLabel(status: string) {
  const labels: Record<string, string> = {
    completed: '已完成', done: '已完成',
    in_progress: '进行中', doing: '进行中', running: '进行中',
    blocked: '已阻塞', skipped: '已跳过',
    failed: '受阻', pending: '未开始'
  };
  return labels[status] || '未开始';
}

function CompactionGroupCard({ row }: { row: CompactionGroupRow }) {
  const count = row.items.length;
  const latest = row.items[count - 1];
  const totalMessages = row.items.reduce((sum, item) => sum + (item.coveredMessages || 0), 0);
  const [open, setOpen] = useState(false);
  return (
    <details className="ctx-card is-divider" data-row-key={row.key} open={open} onClick={event => { if (isSummaryClick(event)) setOpen(!open); }}>
      <summary className="ctx-head">
        <span className="ctx-line" aria-hidden="true" />
        <span className="ctx-pill">
          {count > 1 ? `较早上下文已自动整理（${count} 次）` : '较早上下文已自动整理'}
          <span className="process-chevron" aria-hidden="true"><ProcessChevron /></span>
        </span>
        <span className="ctx-line" aria-hidden="true" />
      </summary>
      {open && <div className="ctx-panel">
        <p className="ctx-note">
          共整理 {totalMessages} 条较早消息{count > 1 ? `（最近一次：${latest.charactersBefore} → ${latest.charactersAfter} 字符）` : `（${latest.charactersBefore} → ${latest.charactersAfter} 字符）`}。
        </p>
        <pre className="ctx-body">{latest.summary}</pre>
      </div>}
    </details>
  );
}

export { formatTime };
