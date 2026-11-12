import type { StoreContext, StoreSlice } from './context.js';
import type { EventEnvelope } from '../types/events.js';
import { api } from '../api.js';
import { errorInfo } from '../lib/errors.js';
import { canLoadEarlier, isValidEarlierHistoryPage } from '../lib/history.js';
import { applyEvent, initialThread, prependHistoryPage, appendHistoryPage } from '../thread-reducer.js';
import { requireData } from './shared.js';

/** 历史窗口拥有预取缓存和请求代次；关闭会话时统一作废，版本变化交给事件流重建。 */
export function createHistoryController({ set, get }: StoreContext, rebuildStreamHistory: (sessionId: string) => Promise<void>) {
  let historyGeneration = 0;
  /*
   * 更早历史的「一页缓冲」。
   *
   * 只把预取距离调大不管用：用户快速上滑时两个视口一秒就滑完了，请求还在路上，
   * 贴到顶部仍旧要空等一趟网络。这里改成进入预取区就先把更早一页拉进内存，
   * 真正越过边界时只是把已有数据插进 DOM，省掉整趟往返。
   *
   * 不放进 zustand state：预取不该触发任何重渲染；插入时机由组件按「滚动是否停稳」决定，
   * 因为滚动过程中前插内容——浏览器的 scroll anchoring 在滚动时会被抑制——视口会被推走一截。
   */
  let earlierPage: { sessionId: string; before: number; events: EventEnvelope[]; hasMore: boolean } | null = null;
  let earlierInflight: { sessionId: string; before: number; promise: Promise<boolean> } | null = null;
  let earlierRequest: Promise<boolean> | null = null;   // 同一次边界触发的并发去重

  function currentHistoryPage(sessionId: string, generation: number, revision?: number) {
    const state = get();
    if (state.sessionId !== sessionId || historyGeneration !== generation) return false;
    if (state.historyRevision != null && (revision ?? 0) !== state.historyRevision) {
      void rebuildStreamHistory(sessionId);
      return false;
    }
    return true;
  }

  const actions: StoreSlice<'loadHistory' | 'prefetchEarlier' | 'applyEarlierPage' | 'loadEarlier' | 'requestEarlierHistory' | 'loadLater' | 'requestLaterHistory'> = {
    /* ---------- 历史 ---------- */
    async loadHistory() {
      const { sessionId, thread } = get();
      if (!sessionId) {
        return false;
      }
      const generation = ++historyGeneration;
      // 换会话 / 重建窗口：旧的预取页对不上新窗口的游标，直接丢弃
      earlierPage = null;
      earlierInflight = null;
      earlierRequest = null;
      try {
        const data = requireData(await api.history(sessionId, { limit: thread.history.pageSize }));
        if (get().sessionId !== sessionId || historyGeneration !== generation) {
          return false;
        }
        set((draft) => {
          draft.historyRevision = data.historyRevision ?? 0;
          draft.thread = initialThread();
          draft.running = false;
          draft.runId = null;
          draft.lastRunId = null;
          draft.pendingApprovals = {};
          draft.pendingArbitrations = {};
          draft.agents = {};
          draft.thread.replaying = true;
          draft.thread.rows = [];
          draft.thread.windowEvents = [];
          draft.timeline = [];
          draft.thread.cursor = 0;
          for (const event of data.events || []) {
            applyEvent(draft, event, { live: false });
          }
          draft.thread.replaying = false;
          draft.thread.history.hasMore = data.hasMore === true;
          draft.thread.history.hasNewer = false;
          draft.thread.history.error = false;
          draft.thread.history.forwardError = false;
          // 窗口整体重建（切会话 / 重连）后落在最新一条，而不是停在上次的滚动位置。
          draft.ui.bottomSignal += 1;
        });
        return true;
      } catch (error) {
        if (get().sessionId === sessionId && historyGeneration === generation) get().showNotice('读取历史失败：' + errorInfo(error).message);
        return false;
      }
    },

    /**
     * 预取更早一页到内存。不插 DOM、不动 loading，滚动过程中调用也不会有副作用；
     * 同一游标只发一次请求，连续进入预取区不会重复打后端。
     */
    prefetchEarlier() {
      const { sessionId, thread } = get();
      const generation = historyGeneration;
      if (!sessionId || !thread.history.hasMore || thread.history.error) {
        return Promise.resolve(false);
      }
      const before = thread.windowEvents[0]?.sequence;
      if (!Number.isFinite(before)) {
        return Promise.resolve(false);
      }
      if (earlierPage && earlierPage.sessionId === sessionId && earlierPage.before === before) {
        return Promise.resolve(true);
      }
      if (earlierInflight && earlierInflight.sessionId === sessionId && earlierInflight.before === before) {
        return earlierInflight.promise;
      }
      const promise = api.history(sessionId, { limit: thread.history.pageSize, before })
        .then((result) => {
          const data = requireData(result);
          if (!currentHistoryPage(sessionId, generation, data.historyRevision)) {
            return false;
          }
          const events = Array.isArray(data.events) ? data.events : [];
          if (!isValidEarlierHistoryPage(events, before)) {
            if (!events.length) {
              set((draft) => { draft.thread.history.hasMore = false; });
            }
            return false;
          }
          earlierPage = { sessionId, before, events, hasMore: data.hasMore === true };
          return true;
        })
        .catch(() => false)
        .finally(() => {
          if (earlierInflight && earlierInflight.promise === promise) {
            earlierInflight = null;
          }
        });
      earlierInflight = { sessionId, before, promise };
      return promise;
    },

    /** 缓冲里有对得上当前窗口的一页就直接插到最前面（同步，无网络往返）。 */
    applyEarlierPage() {
      const { sessionId, thread } = get();
      const before = thread.windowEvents[0]?.sequence;
      const page = earlierPage;
      if (!page || page.sessionId !== sessionId || page.before !== before) {
        return false;
      }
      earlierPage = null;
      set((draft) => {
        const known = new Set(draft.thread.windowEvents.map((event) => event.sequence));
        const fresh = page.events.filter((event) => !known.has(event.sequence));
        if (fresh.length) {
          prependHistoryPage(draft, fresh);
        }
        draft.thread.history.hasMore = page.hasMore;
        draft.thread.history.error = false;
      });
      // 用掉一页就顺手补下一页：连续上滑时第二屏开始也不必等网络。
      get().prefetchEarlier();
      return true;
    },

    async loadEarlier() {
      const { sessionId, thread } = get();
      const generation = historyGeneration;
      if (!sessionId || !thread.windowEvents.length) {
        return false;
      }
      const before = thread.windowEvents[0].sequence;
      if (!Number.isFinite(before)) {
        return false;
      }
      const loadId = thread.history.loadId + 1;
      set((draft) => {
        draft.thread.history.loadId = loadId;
        draft.thread.history.loading = true;
        draft.thread.history.error = false;
      });
      try {
        const data = requireData(await api.history(sessionId, { limit: thread.history.pageSize, before }));
        if (!currentHistoryPage(sessionId, generation, data.historyRevision)) {
          return false;
        }
        const older = Array.isArray(data.events) ? data.events : [];
        set((draft) => {
          draft.thread.history.loading = false;
          if (!older.length) {
            draft.thread.history.hasMore = false;
            return;
          }
          const known = new Set(draft.thread.windowEvents.map((event) => event.sequence));
          const fresh = older.filter((event) => !known.has(event.sequence));
          if (!fresh.length) {
            draft.thread.history.hasMore = data.hasMore === true;
            return;
          }
          prependHistoryPage(draft, fresh);
          draft.thread.history.hasMore = data.hasMore === true;
        });
        return true;
      } catch (error) {
        if (historyGeneration !== generation || get().sessionId !== sessionId) return false;
        set((draft) => {
          draft.thread.history.loading = false;
          draft.thread.history.error = true;
        });
        get().showNotice('加载更早的消息失败：' + errorInfo(error).message);
        return false;
      }
    },

    /**
     * 用户在边界上要更早的消息了：缓冲命中就立刻插（用户感觉不到加载），
     * 预取在路上就等它（只等剩下的网络时间，不额外加一轮），都没有才退回整趟请求。
     */
    async requestEarlierHistory({ force = false } = {}) {
      if (earlierRequest) {
        return earlierRequest;
      }
      const { sessionId, thread } = get();
      const generation = historyGeneration;
      if (!canLoadEarlier({
        hasMore: thread.history.hasMore,
        loading: thread.history.loading,
        eventCount: thread.windowEvents.length
      })) {
        return false;
      }
      if (!force && thread.history.error) {
        return false;
      }
      if (get().applyEarlierPage()) {
        return true;
      }
      const request = (async () => {
        if (earlierInflight) {
          const ready = await earlierInflight.promise;
          if (historyGeneration !== generation || get().sessionId !== sessionId) return false;
          if (ready && get().applyEarlierPage()) {
            return true;
          }
        }
        return get().loadEarlier();
      })();
      earlierRequest = request;
      try {
        return await request;
      } finally {
        if (earlierRequest === request) earlierRequest = null;
      }
    },

    async loadLater() {
      const { sessionId, thread } = get();
      const generation = historyGeneration;
      if (!sessionId || !thread.windowEvents.length) {
        return false;
      }
      const after = thread.windowEvents[thread.windowEvents.length - 1].sequence;
      if (!Number.isFinite(after)) {
        return false;
      }
      try {
        const data = requireData(await api.history(sessionId, { limit: thread.history.pageSize, after }));
        if (!currentHistoryPage(sessionId, generation, data.historyRevision)) {
          return false;
        }
        const newer = Array.isArray(data.events) ? data.events : [];
        set((draft) => {
          draft.thread.history.forwardError = false;
          if (!newer.length) {
            draft.thread.history.hasNewer = false;
            return;
          }
          appendHistoryPage(draft, newer);
          draft.thread.history.hasNewer = data.hasMore === true;
        });
        return true;
      } catch (error) {
        if (historyGeneration !== generation || get().sessionId !== sessionId) return false;
        set((draft) => { draft.thread.history.forwardError = true; });
        get().showNotice('加载更新的消息失败：' + errorInfo(error).message);
        return false;
      }
    },

    async requestLaterHistory({ force = false } = {}) {
      const { thread } = get();
      if (!canLoadEarlier({
        hasMore: thread.history.hasNewer,
        loading: thread.history.loading,
        eventCount: thread.windowEvents.length
      })) {
        return false;
      }
      if (!force && thread.history.forwardError) {
        return false;
      }
      return get().loadLater();
    },

  };
  return {
    actions,
    reset() {
      historyGeneration++;
      earlierPage = null;
      earlierInflight = null;
      earlierRequest = null;
    }
  };
}
