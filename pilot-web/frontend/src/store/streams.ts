import type { Draft } from 'immer';
import type { StoreContext, StoreSlice } from './context.js';
import type { CommandTask } from '../types/store.js';
import type { EventEnvelope } from '../types/events.js';
import type { ResultRow } from '../types/thread.js';
import { api, apiJson } from '../api.js';
import { applyEvent, restoreLiveOutput } from '../thread-reducer.js';
import { EVENT_TYPES } from '../event-types.js';
import { errorInfo } from '../lib/errors.js';
import { isEphemeral, isRunEnd } from '../lib/events.js';
import { browserStateUpdate } from '../lib/browser-mirror.js';
import { sortChangeFiles } from '../lib/changes.js';
import { classifyModelError } from '../lib/model-settings.js';
import { unrefTimer } from './context.js';
import { requireData } from './shared.js';

const RECONNECT_BASE_MS = 800;
const RECONNECT_MAX_MS = 15000;
const CONNECTION_STABLE_MS = 10000;

/** 统一拥有连接、帧批处理、重连退避和面板轮询，关闭时一起清理。 */
export function createSessionStreams({ get, set }: StoreContext, resetHistory: () => void,
  applyAttention: (kind: string, title: string) => void) {
  let eventSource: EventSource | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  let stableConnectionTimer: ReturnType<typeof setTimeout> | undefined;
  let streamPaused = false;
  let streamFailures = 0;
  let streamGeneration = 0;
  let mirrorSource: EventSource | null = null;
  let commandTimer: ReturnType<typeof setTimeout> | undefined;

  /*
   * SSE 事件按帧合并。
   *
   * 每个 token 都是一条独立的 SSE 消息，也就是一个独立的宏任务——React 的自动批处理只在
   * 同一任务内生效，逐条 set 的结果是「一个 token 一次完整提交」：整条消息列表重渲染、
   * 当前消息的 markdown 重解析、行高变化、贴底跟随重排，叠起来就是肉眼看到的抽动。
   * 旧实现（app.js）在 paintMarkdown 外面套了 requestAnimationFrame，一帧最多画一次，
   * 这里把同一层保护补回到 store 入口：一帧内到达的事件合并成一次 set。
   *
   * assistant.preview / assistant.reasoning 是纯界面增量，不推进游标（否则正式事件会被当成重复丢掉）。
   */
  let eventQueue: Array<{ event: EventEnvelope; sessionId: string; sessionEpoch: number }> = [];
  let flushHandle: number | undefined;
  let flushTimer: ReturnType<typeof setTimeout> | undefined;
  let flushGeneration = 0;
  let queuedBytes = 0;
  const MAX_QUEUED_EVENTS = 2048;
  const MAX_QUEUED_BYTES = 8 * 1024 * 1024;
  const wireEncoder = new TextEncoder();

  function cancelFlush() {
    flushGeneration++;
    if (flushHandle != null && typeof cancelAnimationFrame === 'function') cancelAnimationFrame(flushHandle);
    clearTimeout(flushTimer);
    flushHandle = undefined;
    flushTimer = undefined;
  }

  function scheduleFlush() {
    if (flushTimer != null) {
      return;
    }
    const generation = ++flushGeneration;
    const run = () => {
      if (generation !== flushGeneration) return;
      cancelFlush();
      get().flushEvents();
    };
    // 后台标签可能暂停 rAF，必须同时安排定时兜底，而非仅在 API 不存在时使用。
    flushTimer = setTimeout(run, 50);
    if (typeof requestAnimationFrame === 'function') flushHandle = requestAnimationFrame(run);
  }

  function discardQueuedEvents() {
    eventQueue = [];
    queuedBytes = 0;
    cancelFlush();
  }

  function disconnectSse() {
    eventSource?.close();
    eventSource = null;
    clearTimeout(stableConnectionTimer);
    stableConnectionTimer = undefined;
  }

  /** 确认游标不动，丢弃未确认缓存并重新获取；永久坏事件最多自动重试三次。 */
  function recoverEventStream(sessionId: string, reason: string) {
    if (get().sessionId !== sessionId || streamPaused) return;
    streamPaused = true;
    streamGeneration++;
    disconnectSse();
    discardQueuedEvents();
    clearTimeout(reconnectTimer);
    reconnectTimer = undefined;
    const retry = ++streamFailures <= 3;
    set(draft => {
      draft.ui.connection = { status: 'error', text: retry ? '正在恢复事件' : '事件恢复失败，请刷新' };
    });
    if (retry) get().scheduleReconnect(sessionId);
    else get().showNotice(reason + '，已停止自动重试。请刷新页面；若仍失败，请检查服务端事件数据。');
  }

  /** 历史编辑后重建显示；不重置输入组件或附件草稿。 */
  async function rebuildStreamHistory(sessionId: string) {
    streamGeneration++;
    const epoch = get().sessionEpoch;
    streamPaused = true;
    disconnectSse();
    clearTimeout(reconnectTimer); reconnectTimer = undefined;
    discardQueuedEvents();
    const loaded = await get().loadHistory();
    const state = get();
    if (state.sessionId !== sessionId || state.sessionEpoch !== epoch) return;
    if (loaded) {
      state.connectSse(sessionId, state.thread.cursor);
      state.loadPlan(); state.loadTask(); state.refreshContext();
    } else {
      streamPaused = false;
      recoverEventStream(sessionId, '历史版本更新后无法重新加载');
    }
  }

  function closeStreams() {
    streamGeneration++;
    resetHistory();
    disconnectSse();
    streamPaused = false;
    streamFailures = 0;
    discardQueuedEvents();
    if (mirrorSource) {
      mirrorSource.close();
      mirrorSource = null;
    }
    clearInterval(commandTimer);
    commandTimer = undefined;
    clearTimeout(reconnectTimer);
    reconnectTimer = undefined;
  }

  function connectMirror() {
    const { sessionId, sessionEpoch } = get();
    if (!sessionId) return;
    mirrorSource?.close();
    const source = new EventSource(`/api/sessions/${encodeURIComponent(sessionId)}/browser/state-stream`);
    mirrorSource = source;
    const current = () => mirrorSource === source && get().sessionId === sessionId && get().sessionEpoch === sessionEpoch;
    for (const kind of ['nav', 'state', 'status', 'closed']) {
      source.addEventListener(kind, (message) => {
        if (!current()) return;
        let payload: unknown;
        try { payload = kind === 'closed' ? null : JSON.parse(message.data); }
        catch { return; }
        const update = browserStateUpdate(kind, payload);
        set(draft => { Object.assign(draft.ui.mirror, update); });
      });
    }
    source.onerror = () => {
      source.close();
      if (mirrorSource === source) {
        mirrorSource = null;
      }
    };
  }

  function connectCommandTasks() {
    const { sessionId, sessionEpoch } = get();
    clearInterval(commandTimer);
    commandTimer = undefined;
    if (!sessionId) {
      return;
    }
    // 后端只提供 REST 命令任务接口（无 SSE 流），这里用轻量轮询保持面板实时。
    const poll = async () => {
      if (get().sessionId !== sessionId) {
        return;
      }
      try {
        const data = await apiJson<{ tasks: CommandTask[] }>(`/api/sessions/${encodeURIComponent(sessionId)}/commands`);
        if (get().sessionId !== sessionId || get().sessionEpoch !== sessionEpoch) return;
        const tasks = Array.isArray(data?.tasks) ? data.tasks : [];
        set((draft) => {
          draft.commandTasks = tasks;
          const detailId = draft.commandDetail?.taskId || draft.commandDetail?.id;
          const found = detailId ? tasks.find((task) => (task.taskId || task.id) === detailId) : null;
          if (found) {
            draft.commandDetail = { ...draft.commandDetail, ...found };
          }
        });
        // 有选中的任务就顺手续读一截日志（游标前进多少由 mergeCommandLog 负责）
        const detailId = get().commandDetail?.taskId || get().commandDetail?.id;
        const current = detailId ? tasks.find((task) => (task.taskId || task.id) === detailId) : null;
        if (current) {
          get().loadCommandLog(current);
        }
      } catch { /* 命令面板不可用不阻塞 */ }
    };
    poll();
    commandTimer = setInterval(poll, 5000);
    // Node（单元测试）里定时器会吊住事件循环，让测试文件永远不退出；浏览器里没有 unref
    unrefTimer(commandTimer);
  }

  const actions: StoreSlice<'connectSse' | 'scheduleReconnect' | 'handleEvent' | 'flushEvents' | 'settleEvent' | 'ensureMirror'> = {
    /* ---------- SSE ---------- */
    connectSse(sessionId, cursor) {
      if (get().sessionId !== sessionId) return;
      const generation = ++streamGeneration;
      disconnectSse();
      clearTimeout(reconnectTimer);
      reconnectTimer = undefined;
      streamPaused = false;
      const revision = get().historyRevision;
      const source = new EventSource(`/api/sessions/${encodeURIComponent(sessionId)}/events?after=${cursor}${revision == null ? '' : `&revision=${revision}`}&protocol=2`);
      eventSource = source;
      const current = () => eventSource === source && get().sessionId === sessionId;
      const handle = (message: MessageEvent<string>) => {
        if (!current()) return;
        try {
          const event = JSON.parse(message.data) as EventEnvelope;
          if (!event || typeof event !== 'object' || event.sessionId !== sessionId) throw new Error('事件会话无效');
          get().handleEvent(event);
        } catch (error) {
          console.warn('[sse] 事件解析失败', error);
          recoverEventStream(sessionId, '事件数据无法解析');
        }
      };
      for (const type of EVENT_TYPES) {
        source.addEventListener(type, handle);
      }
      source.addEventListener('user.message', handle);
      source.addEventListener('stream.error', (message: MessageEvent<string>) => {
        if (!current()) return;
        disconnectSse();
        streamPaused = true;
        discardQueuedEvents();
        let reason = '事件恢复失败，请刷新后重试';
        try { reason = JSON.parse(message.data).payload?.message || reason; } catch { /* 使用明确的默认提示 */ }
        set(draft => { draft.ui.connection = { status: 'error', text: reason }; });
        get().showNotice(reason);
      });
      source.addEventListener('stream.preview', (message: MessageEvent<string>) => {
        if (!current()) return;
        try {
          const control = JSON.parse(message.data), snapshot = control.payload;
          if (control.sessionId !== sessionId || !snapshot || snapshot.historyRevision !== get().historyRevision
              || !Number.isSafeInteger(snapshot.baseSequence) || !Number.isSafeInteger(snapshot.previewOffset)
              || snapshot.previewOffset < 0 || typeof snapshot.previewId !== 'string' || typeof snapshot.runId !== 'string'
              || typeof snapshot.text !== 'string' || typeof snapshot.reasoning !== 'string' || typeof snapshot.complete !== 'boolean') {
            throw new Error('临时输出状态无效');
          }
          get().flushEvents();
          if (streamPaused) return;
          set(draft => { restoreLiveOutput(draft, snapshot); });
          if (!snapshot.complete) get().showNotice('当前临时输出未完整保留，完整内容将在正式结果到达后更新。');
        } catch { recoverEventStream(sessionId, '临时输出恢复失败'); }
      });
      source.addEventListener('stream.event', async (message: MessageEvent<string>) => {
        if (!current()) return;
        const epoch = get().sessionEpoch;
        try {
          const control = JSON.parse(message.data);
          const sequence = control.payload?.sequence, revision = control.payload?.historyRevision;
          if (control.sessionId !== sessionId || !Number.isSafeInteger(sequence) || sequence < 1 || !Number.isSafeInteger(revision)) throw new Error('事件引用无效');
          get().flushEvents();
          if (streamPaused) return;
          disconnectSse();
          streamPaused = true;
          const result = requireData(await apiJson<{ event: EventEnvelope; historyRevision: number }>(
            `/api/sessions/${encodeURIComponent(sessionId)}/events/${sequence}?revision=${revision}`));
          if (get().sessionId !== sessionId || get().sessionEpoch !== epoch || generation !== streamGeneration) return;
          if (result.historyRevision !== revision || result.event.sequence !== sequence || result.event.sessionId !== sessionId) throw new Error('事件引用与响应不匹配');
          streamPaused = false;
          get().handleEvent(result.event); get().flushEvents();
          if (!streamPaused) get().connectSse(sessionId, get().thread.cursor);
        } catch (error) {
          if (get().sessionId !== sessionId || get().sessionEpoch !== epoch || generation !== streamGeneration) return;
          streamPaused = false;
          recoverEventStream(sessionId, '长消息读取失败：' + errorInfo(error).message);
        }
      });
      for (const kind of ['stream.ready', 'stream.reset']) {
        source.addEventListener(kind, (message: MessageEvent<string>) => {
          if (!current()) return;
          try {
            const control = JSON.parse(message.data);
            const revision = control.payload?.historyRevision;
            if (control.sessionId !== sessionId || !Number.isSafeInteger(revision) || revision < 0) throw new Error('历史版本无效');
            if (kind === 'stream.reset' || get().historyRevision != null && get().historyRevision !== revision) {
              void rebuildStreamHistory(sessionId);
            } else set(draft => { draft.historyRevision = revision; });
          } catch { recoverEventStream(sessionId, '历史版本信息无效'); }
        });
      }
      source.onopen = () => {
        if (!current()) return;
        set((draft) => {
          draft.ui.connection = { status: 'ok', text: '已连接' };
        });
        clearTimeout(stableConnectionTimer);
        stableConnectionTimer = setTimeout(() => {
          stableConnectionTimer = undefined;
          if (current()) {
            set(draft => { draft._reconnectAttempt = 0; });
          }
        }, CONNECTION_STABLE_MS);
      };
      source.onerror = () => {
        if (!current()) return;
        disconnectSse();
        set((draft) => { draft.ui.connection = { status: 'error', text: '重连中' }; });
        get().scheduleReconnect(sessionId);
      };
    },

    scheduleReconnect(sessionId) {
      if (reconnectTimer != null || get().sessionId !== sessionId) {
        return;
      }
      const attempt = (get()._reconnectAttempt || 0) + 1;
      set((draft) => { draft._reconnectAttempt = attempt; });
      const base = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** Math.min(attempt - 1, 5));
      const delay = base + Math.round(base * 0.2 * Math.random());
      reconnectTimer = setTimeout(async () => {
        reconnectTimer = undefined;
        if (get().sessionId !== sessionId) {
          return;
        }
        // 续订：带着已渲染的游标，避免从 0 重新回放
        get().connectSse(sessionId, get().thread.cursor);
        await get().refreshActiveRun();
      }, delay);
    },

    /** SSE 事件统一入口：先入队，帧末合并提交（见 eventQueue 注释）。 */
    handleEvent(event) {
      const { sessionId, sessionEpoch } = get();
      if (!sessionId || streamPaused || event?.sessionId !== sessionId) {
        return;
      }
      if (!Number.isSafeInteger(event.sequence) || event.sequence < 0 || typeof event.type !== 'string'
          || !event.payload || typeof event.payload !== 'object' || Array.isArray(event.payload)) {
        recoverEventStream(sessionId, '事件格式无效');
        return;
      }
      const thread = get().thread;
      if (!isEphemeral(event.type) && event.sequence <= thread.cursor) {
        // 重连/重放重复推送：同一序号不再应用一次
        return;
      }
      const bytes = wireEncoder.encode(JSON.stringify(event)).byteLength;
      if (eventQueue.length >= MAX_QUEUED_EVENTS || queuedBytes + bytes > MAX_QUEUED_BYTES) {
        // 优先应用已有的有效批次，后台节流也不能无限堆积。
        get().flushEvents();
      }
      if (streamPaused) return;
      if (bytes > MAX_QUEUED_BYTES) {
        recoverEventStream(sessionId, '单条事件超过可处理大小');
        return;
      }
      eventQueue.push({ event, sessionId, sessionEpoch });
      queuedBytes += bytes;
      scheduleFlush();
    },

    /** 把这一帧攒下的事件一次性折进状态，再统一触发联动刷新。 */
    flushEvents() {
      cancelFlush();
      if (!eventQueue.length) {
        return;
      }
      const batch = eventQueue;
      eventQueue = [];
      queuedBytes = 0;
      const applied: EventEnvelope[] = [];
      const beforeKind = get().ui.attentionKind;
      const beforeCursor = get().thread.cursor;
      try { set((draft) => {
        for (const queued of batch) {
          if (draft.sessionId !== queued.sessionId || draft.sessionEpoch !== queued.sessionEpoch) continue;
          const item = queued.event;
          if (!isEphemeral(item.type) && item.sequence <= draft.thread.cursor) continue;
          applyEvent(draft, item, { live: true });
          applied.push(item);
        }
        draft.lastEventAt = Date.now();
      }); } catch (error) {
        console.warn('[sse] 事件应用失败，保留确认游标并重新回放', error);
        const sessionId = get().sessionId;
        if (sessionId) recoverEventStream(sessionId, '事件无法应用');
        return;
      }
      const after = get();
      if (after.thread.cursor > beforeCursor) streamFailures = 0;
      if (after.ui.attentionKind !== beforeKind) {
        applyAttention(after.ui.attentionKind, after.sessionTitle);
      }
      for (const item of applied) {
        try { get().settleEvent(item, after); }
        catch (error) { console.warn('[sse] 已应用事件的附属刷新失败', error); }
      }
    },

    /** 单条事件落地后的联动（标题转正、任务契约、上下文、变更报告）。 */
    settleEvent(event, after) {
      const runEnded = isRunEnd(event.type);
      const runChanged = event.type === 'run.started' || runEnded;
      const sessionChanged = runChanged || event.type === 'run.steered';
      if (event.type === 'tool.completed' && after.thread.rows.some(row => row.type === 'turn'
          && row.toolIndex[event.payload.callId]?.name === 'install_skill')) {
        void get().loadSkills();
      }
      if (sessionChanged) {
        set(draft => { draft.promoteBlank = null; });
        get().loadSessions();
      }
      // 任务契约面板刷新
      if (sessionChanged || event.type === 'run.continued' || event.type === 'tool.completed' || event.type === 'execution.updated') {
        get().loadTask();
      }
      if (runChanged) {
        get().loadQueue();
      }
      // 一轮结束后拉取本次 run 的变更
      if (runEnded && after.runChangesFetch && get().runChangesFetch === after.runChangesFetch && event.runId === after.runChangesFetch) {
        const runId = after.runChangesFetch;
        const sessionEpoch = after.sessionEpoch;
        set((draft) => { draft.runChangesFetch = null; });
        api.runChanges(runId).then((report) => {
          if (!report || get().sessionEpoch !== sessionEpoch) return;
          const files = sortChangeFiles(report.files || []);
          set((draft) => {
            const row = draft.thread.rows.find((item): item is Draft<ResultRow> => item.type === 'result' && item.key === `result:${runId}`);
            if (row) {
              row.changedFiles = files;
              row.changedSummary = report.summary || null;
            }
            if ((draft.lastRunId || draft.runId) === runId) draft.changedFiles = files.slice();
          });
          // 审阅面板开着就别让它停在旧状态：这一轮改过的文件要立刻出现在列表里
          if (get().ui.inspectorOpen && ['changes', 'preview'].includes(get().ui.inspectorTab)) {
            get().loadChanges();
          }
        }).catch(() => {});
      }
      // 模型调用失败：把原始报错翻译成可行动的提示（401 去填 Key、404 查端点…）
      if (event.type === 'run.failed' && String(event.payload?.code || '') === 'MODEL_ERROR') {
        const modelError = classifyModelError(event.payload?.message || '');
        if (modelError?.hint) {
          get().showNotice(modelError.hint);
        }
      }
      // 上下文状态跟手刷新
      if (runChanged || event.type === 'context.compacted') {
        get().refreshContext();
      }
    },

    /**
     * 确保镜像流接着。
     *
     * <p>首次订阅时浏览器运行时可能还没握手完（端点回 204），EventSource 会立刻报错并关闭；
     * 之后再没人重连，面板就永远收不到帧。打开浏览器页签、发起导航时都补一次。
     */
    ensureMirror() {
      const { sessionId, ui } = get();
      if (!sessionId || !ui.mirror) {
        return;
      }
      if (mirrorSource && mirrorSource.readyState !== 2) {
        return;
      }
      connectMirror();
    },

  };
  return { actions, close: closeStreams, connectMirror, connectCommandTasks, rebuildHistory: rebuildStreamHistory };
}
