import type { Draft } from 'immer';
import type { StoreState, UiState, MirrorState, MirrorFrame, CommandTask } from './types/store.js';
import type { EventEnvelope } from './types/events.js';
import type { ApiResults, InputMessage } from './types/api.js';
import type { SideChatTab } from './types/content.js';
import type { PlanRow, ResultRow } from './types/thread.js';
import type * as S from './types/server.js';
import { errorInfo } from './lib/errors.js';
type StoreGet = () => StoreState;
type StoreSet = (change: (draft: Draft<StoreState>) => void) => void;
/**
 * 全局状态：zustand + immer。
 *
 * <p>字段与旧版 state 对象一一对应；动作函数替代旧版的直接 DOM 操纵。
 * SSE 的连接与重连退避也在这里（旧版 connect/scheduleReconnect 的移植）。
 */
import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { api, apiJson, apiFetch } from './api.js';
import {
  applyEvent, initialThread, appendUserRow, prependHistoryPage, appendHistoryPage, isUserMessageType
} from './thread-reducer.js';
import { EVENT_TYPES } from './event-types.js';
import { canLoadEarlier, isValidEarlierHistoryPage } from './lib/history.js';
import { loadInputHistory, rememberInput } from './lib/input-history.js';
import { readDraft, writeDraft, dropDraft } from './lib/drafts.js';
import { faviconHref, shouldNotify, tabTitle } from './lib/attention.js';
import { mergeCommands, resolveSkillCommand, toSkillCommands } from './lib/skills.js';
import { normalizePermission, permissionLabel } from './lib/permission.js';
import { QUICK_COMMANDS, filterQuickCommands, expandQuickCommand } from './lib/commands.js';
import { groupSessionsByProject, samePath, sortSessions } from './lib/projects.js';
import { mergeRunReports, sortChangeFiles } from './lib/changes.js';
import { isCommandActive, commandStatusLabel, mergeCommandLog } from './lib/command-tasks.js';
import { nextMirrorMode } from './lib/browser-mirror.js';
import { classifyModelError, describeModelSaved } from './lib/model-settings.js';
import { sameUserInput } from './thread-utils.js';
import {
  readSideChats, writeSideChats, readEmbeddedSession, sideChatsFor, sideChatTitle
} from './lib/side-chat.js';
import { normalizeQueue } from './lib/queue.js';
import { createWorkbenchRefresher, saveWorkbenchView, takeWorkbenchView } from './lib/workbench-sync.js';

let queueLoadSequence = 0;
let activationSequence = 0;
let projectLoadSequence = 0;
let sessionLoadSequence = 0;
let settingsLoadSequence = 0;
/** 侧边对话正在创建：打开面板的同时又点「新开」时共用一个请求，别建出两条。 */
const sideChatPending = new Map<string, Promise<SideChatTab | null>>();
const sideChatClosing = new Map<string, Promise<boolean>>();
const RECONNECT_BASE_MS = 800;
const RECONNECT_MAX_MS = 15000;

/**
 * 内置浏览器面板的初始镜像状态。
 *
 * <p>每次会话切换都造一份新的：地址栏、前进/后退可用性都跟着上一轮页面走，
 * 复用同一个对象会让新会话的地址栏还挂着上一个会话的地址。
 */
function emptyMirror(): MirrorState {
  return {
    mode: 'idle', frame: null, url: '', embed: true, frameOptions: '', latency: 0, paused: false,
    // 实际生效的 headless（含无桌面降级）：后台模式没有窗口可置前，提示文案也跟着换
    headless: true,
    frameW: 1280, frameH: 800, canGoBack: false, canGoForward: false
  };
}
/** 桌面通知开关（与旧版同名键）。 */
const NOTIFY_KEY = 'pilot.notify';

let eventSource: EventSource | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
let mirrorSource: EventSource | null = null;
/** 截图帧直接画到面板上，不进 store。JPEG 进 React 状态会把整页拖卡。 */
let paintMirrorFrame: ((frame: MirrorFrame) => void) | null = null;
let lastMirrorPayload: MirrorFrame | null = null;

export function setMirrorPainter(painter: typeof paintMirrorFrame) {
  paintMirrorFrame = painter;
  if (painter && lastMirrorPayload) {
    painter(lastMirrorPayload);
  }
}
let commandTimer: ReturnType<typeof setTimeout> | undefined;
let runClockTimer: ReturnType<typeof setTimeout> | undefined;
let usageRefreshTimer: ReturnType<typeof setTimeout> | undefined;
let noticeTimer: ReturnType<typeof setTimeout> | undefined;

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
let earlierPage: { sessionId: string; before: number; events: EventEnvelope[]; hasMore: boolean } | null = null;      // { sessionId, before, events, hasMore }
let earlierInflight: { sessionId: string; before: number; promise: Promise<boolean> } | null = null;  // { sessionId, before, promise }
let earlierRequest: Promise<boolean> | null = null;   // 同一次边界触发的并发去重

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
const EPHEMERAL_EVENTS = new Set(['assistant.preview', 'assistant.reasoning']);
let eventQueue: EventEnvelope[] = [];
let flushHandle: number | undefined;
let flushRunner: (() => void) | null = null;

function scheduleFlush() {
  if (flushHandle != null) {
    return;
  }
  const run = () => {
    flushHandle = undefined;
    flushRunner?.();
  };
  flushHandle = typeof requestAnimationFrame === 'function'
    ? requestAnimationFrame(run)
    : setTimeout(run, 16);
}

function discardQueuedEvents() {
  eventQueue = [];
  if (flushHandle != null) {
    if (typeof cancelAnimationFrame === 'function') {
      cancelAnimationFrame(flushHandle);
    } else {
      clearTimeout(flushHandle);
    }
    flushHandle = undefined;
  }
}

/**
 * 清空当前会话的全部界面状态：切换会话与「开新对话」共用同一套重置。
 * 放在模块级是为了让 startNewSession / activate 不会各写一份、漏字段。
 */
function resetFilePreview(draft: StoreState) {
  draft._previewRequest = (draft._previewRequest || 0) + 1;
  draft._previewData = null;
  draft._previewLoading = false;
  draft.ui.previewPath = '';
}

function applySettings(draft: StoreState, data: ApiResults['settings'] | null) {
  draft.settingsData = data;
  if (data?.permissionMode) draft.permissionMode = normalizePermission(data.permissionMode);
  draft.modelOptions = Array.isArray(data?.modelOptions) ? data.modelOptions : [];
}

function resetSessionState(draft: StoreState) {
  resetFilePreview(draft);
  draft.sessionEpoch = (draft.sessionEpoch || 0) + 1;
  draft._editTarget = null;
  draft.steering = false;
  draft.queueBusy = false;
  draft.queueRevision = null;
  draft.pausing = false;
  draft.sessionId = null;
  draft.thread = initialThread();
  draft.timeline = [];
  draft.pendingInputs = [];
  draft.queue = [];
  draft.queueEntries = [];
  draft.pendingApprovals = {};
  draft.pendingArbitrations = {};
  draft.agents = {};
  draft.running = false;
  draft.cancelling = false;
  draft.runStartedAt = 0;
  draft.runId = null;
  draft.lastRunId = null;
  draft.runUsage = null;
  draft.requestReports = [];
  draft.latestContextReport = null;
  draft.lastOutcome = '';
  draft.queueHeld = false;
  draft.liveOutputTokens = 0;
  draft.contextStatus = null;
  draft.task = null;
  draft.taskDirty = false;
  draft.planItems = [];
  draft.ui.bottomSignal = 0;
  draft.ui.attentionKind = 'idle';
  draft.ui.browserDot = false;
  draft.ui.mirror = emptyMirror();
  draft.ui.agentsDockOpen = false;
  draft.ui.queueEdit = null;
  if (typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 960px)').matches) draft.ui.sidebarOpen = false;
  draft.ui.draftFiles = [];
  draft.ui.checkpointDialog = null;
  draft.ui.changeView = null;
  draft.changeReport = null;
  draft.changesState = '';
  draft.runChanges = null;
  draft.commandTasks = [];
  draft.commandDetail = null;
  draft.commandLog = null;
  draft.commandLogTaskId = null;
}

export const initialUi = (): UiState => ({
  notice: null,
  noticeTimer: null,
  refreshing: false,
  syncError: '',
  lastSyncedAt: 0,
  filesRevision: 0,
  themeMode: localStorage.getItem('pilot.theme') || 'dark',
  accent: localStorage.getItem('pilot.accent') || 'sky',
  sidebarOpen: typeof window.matchMedia === 'function' ? !window.matchMedia('(max-width: 960px)').matches : true,
  inspectorOpen: false,
  inspectorTab: 'changes',
  studioDocumentId: null,
  reviewExpanded: false,
  filters: 'all',
  settingsSection: 'appearance',
  settingsOpen: false,
  paletteOpen: false,
  picker: null,
  projectEditor: null,
  skillsInstall: null,
  checkpointDialog: null,
  /** 通用二次确认：{ title, message, confirmText, danger, onConfirm }。 */
  confirm: null,
  agentsDockOpen: false,
  queueCollapsed: false,
  queueEdit: null,
  draftFiles: [],
  connection: { status: 'ok', text: '已连接' },
  /** 自增信号：Thread 监听到变化就把视口拉到底（发送消息 / 切换会话）。 */
  bottomSignal: 0,
  attentionKind: 'idle',
  browserDot: false,
  mirror: emptyMirror(),
  changeView: null,
  previewPath: '',
  /** 浏览器状态：{ enabled, capable, available, reason, paused }；没有它就是还没查过。 */
  browserStatus: null,
  usageDays: 7
});

export const useStore = create<StoreState>()(immer((set, get) => {
  flushRunner = () => get().flushEvents();
  return {
  /* ---------- 会话 / 项目 ---------- */
  sessionId: null,
  sessionEpoch: 0,
  ready: false,
  refreshWorkbench: createWorkbenchRefresher({ get, set, api }),
  async reloadWorkbench() {
    const state = get();
    const prepared = state.composerApi?.prepareReload?.() || { ok: true };
    const reason = !prepared.ok ? prepared.reason : state.taskDirty || state.taskBusy
      ? '请先保存正在编辑的任务内容，再重新载入界面。'
      : state.ui.settingsOpen || state.ui.projectEditor ? '请先保存或关闭设置、项目编辑窗口，再重新载入界面。' : '';
    if (reason) {
      if (document.querySelector('.modal')) state.askConfirm({ title: '暂时不能重新载入', message: reason + '刷新数据仍可保留当前内容。', confirmText: '返回编辑' });
      else state.showNotice(reason + '可先使用“刷新工作台数据”。');
      return false;
    }
    let saved = false;
    try { saved = saveWorkbenchView(sessionStorage, state); } catch { /* 浏览器禁止访问存储 */ }
    if (!saved) {
      state.showNotice('无法保存当前工作区状态，已保留页面。请先使用“刷新工作台数据”。');
      return false;
    }
    try {
      if (window.pilotDesktop?.reloadWorkbench) {
        const result = await window.pilotDesktop.reloadWorkbench();
        if (!result?.ok) throw new Error(result?.error || '重新载入失败，请重试');
      } else window.location.reload();
    } catch (error) { state.showNotice('重新载入失败：' + errorInfo(error).message); return false; }
    return true;
  },
  /** 草稿态的新对话：{ projectId, workspace }，还没有对应会话记录。 */
  draftSession: null,
  /** 非空表示自己是 Side chat 内嵌页：只显示这一个会话，也不许再开嵌套面板。 */
  embeddedSessionId: readEmbeddedSession(),
  sessionTitle: '',
  /** 本会话发过的输入（↑/↓ 翻），index = -1 表示停在草稿上。 */
  inputHistory: [],
  inputHistoryIndex: -1,
  inputHistoryDraft: '',
  workspace: '',
  sessions: [],
  projects: [],
  currentProjectId: null,
  /** 侧边对话标签页：{ items: [{ id, title, workspace, projectId }], activeId }。 */
  sideChats: readSideChats(),
  /** 选中文本要贴进侧边对话时暂存一句，面板取走后清空。 */
  sideQuote: null,
  runId: null,
  lastRunId: null,
  running: false,
  cancelling: false,
  pausing: false,
  steering: false,
  queueBusy: false,
  queueRevision: null,
  runStartedAt: 0,
  lastEventAt: 0,
  modelRetryUntil: 0,
  runUsage: null,
  requestReports: [],
  latestContextReport: null,
  liveOutputTokens: 0,
  lastOutcome: '',
  queueHeld: false,
  turnRuns: [],
  changedFiles: [],
  contextStatus: null,
  compactingContext: false,
  pendingInputs: [],
  queue: [],
  queueEntries: [],
  pendingApprovals: {},
  pendingArbitrations: {},
  agents: {},
  task: null,
  taskDirty: false,
  taskBusy: false,
  planItems: [],
  skills: [],
  /** 技能安装位置与无效项（设置页用来告诉用户「装到哪」以及哪些没通过校验）。 */
  skillsMeta: { projectRoot: '', userRoot: '', invalid: [] },
  /** 内置 / 快捷指令：先用 QUICK_COMMANDS 兜底，loadSkills 成功后合并技能指令。 */
  quickCommands: mergeCommands(QUICK_COMMANDS, []),
  modelOptions: [],
  remoteModels: [],
  settingsData: null,
  modelSave: { busy: false, message: '', error: false },
  /** 桌面通知开关（默认关，用户主动开才打扰）。 */
  notifyEnabled: readNotifyEnabled(),
  permissionMode: 'default',
  usageData: null,
  fileIndex: { items: [], sessionId: null, loading: false },
  commandTasks: [],
  commandDetail: null,
  /** 命令日志是 {text, offset, nextOffset, ...} 的增量片段，不是数组。 */
  commandLog: null,
  commandLogTaskId: null,
  /** 能力清单（工具 / MCP / 模型 / 权限档位），「能力」页签直接读它。 */
  capabilities: null,
  capabilitiesState: '',
  changeReport: null,
  changesState: '',
  runChanges: null,
  timeline: [],
  streamCard: null,
  promoteBlank: null,
  thread: initialThread(),
  ui: initialUi(),

  /* ---------- 通用 UI ---------- */
  showNotice(text) {
    set((draft) => {
      draft.ui.notice = { text, at: Date.now() };
    });
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => get().hideNotice(), 7000);
    unrefTimer(noticeTimer);
  },
  hideNotice() {
    set((draft) => { draft.ui.notice = null; });
  },
  setTheme(mode) {
    localStorage.setItem('pilot.theme', mode);
    set((draft) => { draft.ui.themeMode = mode; });
    applyTheme(mode, get().ui.accent);
  },
  setAccent(accent) {
    localStorage.setItem('pilot.accent', accent);
    set((draft) => { draft.ui.accent = accent; });
    applyTheme(get().ui.themeMode, accent);
  },
  /** 桌面通知开关：打开时顺手申请一次浏览器权限（旧版 toggleNotifications 同语义）。 */
  async toggleNotify() {
    const next = !get().notifyEnabled;
    try {
      if (next) {
        if (typeof Notification === 'undefined') throw new Error('此环境不支持系统通知，请使用收件箱或配置 Webhook。');
        const permission = Notification.permission === 'granted' ? 'granted' : await Notification.requestPermission();
        if (permission !== 'granted') throw new Error('通知权限未获授权，请在浏览器或系统设置中允许。');
      }
      const current = requireData(await api.deliverySettings());
      await api.saveDeliverySettings({ ...current, desktop: next });
      set(draft => { draft.notifyEnabled = next; });
      try { localStorage.setItem(NOTIFY_KEY, next ? '1' : '0'); } catch { /* 当前设备仍生效 */ }
      get().showNotice(next ? '已开启可靠桌面通知，可在收件箱查看投递状态。' : '已关闭桌面通知');
    } catch (e) { get().showNotice(errorInfo(e).message || '操作失败'); }
  },
  setUi(patch) {
    set((draft) => { Object.assign(draft.ui, typeof patch === 'function' ? patch(draft.ui) : patch); });
  },
  setFilters(value) {
    set((draft) => { draft.ui.filters = value; });
  },

  /* ---------- 启动 ---------- */
  async init(embeddedSessionId = null) {
    let restore = null;
    try { if (!embeddedSessionId) restore = takeWorkbenchView(sessionStorage); } catch { /* 隐私模式下继续正常启动 */ }
    applyTheme(get().ui.themeMode, get().ui.accent);
    get().loadSkills();
    try {
      await api.health();
      set((draft) => { draft.ui.connection = { status: 'ok', text: '已连接' }; });
    } catch {
      set((draft) => { draft.ui.connection = { status: 'error', text: '无法连接后端' }; });
    }
    await Promise.all([get().loadProjects(), get().loadSettings()]);
    const sessions = await get().loadSessions();
    // Side chat 内嵌页：只认 URL 里指定的那个会话，不按「第一个历史对话」恢复
    if (embeddedSessionId) {
      await get().activate(embeddedSessionId);
      set(state => { state.ready = true; });
      return;
    }
    if (restore?.sessionId) {
      await get().activate(restore.sessionId);
      set(state => { state.ready = true; });
      return;
    }
    if (restore?.draftSession) {
      get().startNewSession({ projectId: restore.draftSession.projectId, workspace: restore.workspace });
      set(state => { state.ready = true; });
      return;
    }
    // 空白对话是旧版「先建后聊」留下的空壳，启动时不该停在它上面
    const first = (sessions || []).find((session) => !session.isBlank && !session.archived)
      || (sessions || []).find((session) => !session.isBlank)
      || null;
    if (first) {
      await get().activate(first.id);
    } else {
      get().startNewSession();
    }
    set(state => { state.ready = true; });
  },

  async loadProjects({ strict = false } = {}) {
    const request = ++projectLoadSequence;
    try {
      const projects = await api.projects();
      if (!Array.isArray(projects)) throw new Error('项目数据格式无效');
      if (request === projectLoadSequence) set((draft) => { draft.projects = projects; });
    } catch (error) {
      if (strict) throw error;
      console.warn('[projects]', error);
    }
  },

  async loadSessions({ strict = false } = {}) {
    const request = ++sessionLoadSequence;
    try {
      // 后端 SessionResponse 的字段是 sessionId，而界面（侧栏、启动恢复等）统一按 id 读取，
      // 这里归一化补上 id 别名，避免到处取到 undefined 后把 "undefined" 当会话 ID 发给后端。
      //
      // 侧边对话由后端过滤，不进主列表；旧版本建的侧边会话没有父会话标记，
      // 这里按本地标签再兜一道，免得升级后侧栏里多出一条「未分组」的重复项。
      const sideIds = new Set((get().sideChats?.items || []).map((item) => item.id));
      const raw = await api.sessions();
      if (!Array.isArray(raw)) throw new Error('任务数据格式无效');
      const sessions = sortSessions(raw
        .map((session) => ({ ...session, id: session.sessionId || session.id }))
        .filter((session) => !sideIds.has(session.id)));
      if (request === sessionLoadSequence) set((draft) => {
        draft.sessions = sessions;
        const current = sessions.find(session => session.id === draft.sessionId);
        if (current) draft.sessionTitle = current.title;
      });
      return sessions;
    } catch (error) {
      if (strict) throw error;
      console.warn('[sessions]', error);
      return [];
    }
  },

  /**
   * 开一个新对话：只切界面状态，**不落库**。
   *
   * <p>理由：先建后聊会在侧栏留下一条「新对话」空壳（刷新、切走都还在，未分组还会越积越多），
   * 和 Codex 那种「发第一条消息才产生会话」的手感不一样。现在真正创建放到 {@link ensureSession}，
   * 由第一条消息触发；在此之前它只是一个草稿态（draftSession）。
   */
  startNewSession(options = {}) {
    const current = get();
    const projectId = options.projectId !== undefined ? options.projectId : (current.currentProjectId || current.draftSession?.projectId || null);
    const workspace = options.workspace !== undefined ? options.workspace : (current.workspace || current.draftSession?.workspace || null);
    closeStreams();
    discardQueuedEvents();
    set((draft) => {
      resetSessionState(draft);
      draft.draftSession = { projectId: projectId || null, workspace: workspace || null };
      draft.workspace = workspace || '';
      draft.currentProjectId = projectId;
      draft.sessionTitle = '';
      draft.ui.reviewExpanded = false;
      // 新任务还没有会话，也就没有可归属的侧边对话；面板留着空态等第一条消息
      draft.sideChats.activeId = null;
    });
    return null;
  },

  /**
   * 确保当前有真实会话：草稿态下按需创建，创建后立刻接上事件流与各面板连接。
   *
   * @returns {Promise<string|null>} 会话 ID；拿不到时返回 null（调用方负责提示）
   */
  async ensureSession() {
    const { sessionId, draftSession, sessionEpoch } = get();
    if (sessionId) {
      return sessionId;
    }
    if (!draftSession) {
      return null;
    }
    const body: Parameters<typeof api.createSession>[0] = { replaceBlank: true };
    if (draftSession.projectId) {
      body.projectId = draftSession.projectId;
    }
    if (draftSession.workspace) {
      body.workspace = draftSession.workspace;
    }
    // 首条消息由服务端统一命名，Web、桌面及 API 调用保持一致。
    const created = await api.createSession(body);
    const id = created?.sessionId || created?.id;
    if (get().sessionEpoch !== sessionEpoch) throw new Error('会话已切换，原草稿已保留，请回到原会话重试');
    if (!id) {
      throw new Error('后端没有返回会话 ID');
    }
    set((draft) => {
      draft.sessionId = id;
      draft.draftSession = null;
      draft.workspace = created.workspace || draft.workspace;
      draft.sessionTitle = created.title || draft.sessionTitle;
    });
    writeDraft(localStorage, id, readDraft(localStorage, null));
    dropDraft(localStorage, null);
    get().loadSessions();
    get().connectSse(id, 0);
    connectMirror(get, set);
    connectCommandTasks(get, set);
    get().loadPlan();
    get().loadQueue();
    get().loadTask();
    get().refreshContext();
    get().refreshActiveRun();
    return id;
  },

  async activate(sessionId) {
    const activation = ++activationSequence;
    const current = () => activation === activationSequence && get().sessionId === sessionId;
    closeStreams();
    discardQueuedEvents();
    set((draft) => {
      resetSessionState(draft);
      draft.sessionId = sessionId;
      draft.draftSession = null;
    });
    let detail = null;
    try {
      detail = await api.session(sessionId);
    } catch (error) {
      if (current()) get().showNotice('读取会话失败：' + errorInfo(error).message);
      return false;
    }
    if (!current()) return false;
    if (detail?.unread) await get().updateSessionPreferences(sessionId, {unread:false});
    if (!current()) return false;
    set((draft) => {
      draft.workspace = detail?.workspace || '';
      draft.sessionTitle = detail?.title || '';
      draft.currentProjectId = detail?.projectId || null;
      if (detail && 'permissionMode' in detail && typeof detail.permissionMode === 'string') {
        draft.permissionMode = normalizePermission(detail.permissionMode);
      }
      // 侧边对话跟着主任务走：切到别的任务后，只选中属于它的那一条
      draft.sideChats.activeId = sideChatsFor(draft.sideChats, {
        sessionId, workspace: draft.workspace
      }).activeId;
    });
    await get().loadHistory();
    if (!current()) return false;
    // 历史窗口就绪后再按当前游标续订，避免事件落在重建窗口之前被覆盖
    get().connectSse(sessionId, get().thread.cursor);
    await Promise.all([
      get().loadPlan(), get().loadQueue(), get().loadTask(), get().refreshContext(),
      // 技能按工作区解析（项目级技能随仓库走），换会话后 / 菜单必须跟着换
      get().loadSkills()
    ]);
    if (!current()) return false;
    const draftText = readDraft(localStorage, sessionId);
    if (draftText) {
      get().composerApi?.setText(draftText);
    }
    set((draft) => {
      draft.inputHistory = loadInputHistory(localStorage, sessionId);
      draft.inputHistoryIndex = -1;
      draft.inputHistoryDraft = '';
    });
    connectMirror(get, set);
    connectCommandTasks(get, set);
    get().refreshActiveRun();
    return true;
  },

  /* ---------- 历史 ---------- */
  async loadHistory() {
    const { sessionId, thread } = get();
    if (!sessionId) {
      return;
    }
    // 换会话 / 重建窗口：旧的预取页对不上新窗口的游标，直接丢弃
    earlierPage = null;
    earlierInflight = null;
    earlierRequest = null;
    try {
      const data = requireData(await api.history(sessionId, { limit: thread.history.pageSize }));
      if (get().sessionId !== sessionId) {
        return;
      }
      set((draft) => {
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
    } catch (error) {
      get().showNotice('读取历史失败：' + errorInfo(error).message);
    }
  },

  /**
   * 预取更早一页到内存。不插 DOM、不动 loading，滚动过程中调用也不会有副作用；
   * 同一游标只发一次请求，连续进入预取区不会重复打后端。
   */
  prefetchEarlier() {
    const { sessionId, thread } = get();
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
        if (get().sessionId !== sessionId) {
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
      if (get().sessionId !== sessionId) {
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
    const { thread } = get();
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
    earlierRequest = (async () => {
      if (earlierInflight) {
        const ready = await earlierInflight.promise;
        if (ready && get().applyEarlierPage()) {
          return true;
        }
      }
      return get().loadEarlier();
    })();
    try {
      return await earlierRequest;
    } finally {
      earlierRequest = null;
    }
  },

  async loadLater() {
    const { sessionId, thread } = get();
    if (!sessionId || !thread.windowEvents.length) {
      return false;
    }
    const after = thread.windowEvents[thread.windowEvents.length - 1].sequence;
    if (!Number.isFinite(after)) {
      return false;
    }
    try {
      const data = requireData(await api.history(sessionId, { limit: thread.history.pageSize, after }));
      if (get().sessionId !== sessionId) {
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

  /* ---------- SSE ---------- */
  connectSse(sessionId, cursor) {
    if (eventSource) {
      eventSource.close();
      eventSource = null;
    }
    clearTimeout(reconnectTimer);
    reconnectTimer = undefined;
    const source = new EventSource(`/api/sessions/${encodeURIComponent(sessionId)}/events?after=${cursor}`);
    eventSource = source;
    const handle = (message: MessageEvent<string>) => {
      if (eventSource !== source || get().sessionId !== sessionId) return;
      try {
        get().handleEvent(JSON.parse(message.data) as EventEnvelope);
      } catch (error) {
        console.warn('[sse] 事件解析失败', error);
      }
    };
    for (const type of EVENT_TYPES) {
      source.addEventListener(type, handle);
    }
    source.addEventListener('user.message', handle);
    source.onopen = () => {
      if (eventSource !== source || get().sessionId !== sessionId) return;
      set((draft) => {
        draft.ui.connection = { status: 'ok', text: '已连接' };
        draft._reconnectAttempt = 0;
      });
    };
    source.onerror = () => {
      if (eventSource !== source || get().sessionId !== sessionId) return;
      source.close();
      if (eventSource === source) {
        eventSource = null;
      }
      set((draft) => { draft.ui.connection = { status: 'error', text: '重连中' }; });
      get().scheduleReconnect(sessionId);
    };
  },

  scheduleReconnect(sessionId) {
    if (reconnectTimer || get().sessionId !== sessionId) {
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
      try {
        const active = await api.activeRun(sessionId);
        if (active?.runId && !get().running) {
          set((draft) => {
            draft.running = true;
            draft.runId = active.runId;
          });
        }
      } catch { /* 下次事件会对齐 */ }
    }, delay);
  },

  /** SSE 事件统一入口：先入队，帧末合并提交（见 eventQueue 注释）。 */
  handleEvent(event) {
    const sessionId = get().sessionId;
    if (!sessionId || event.sessionId && event.sessionId !== sessionId) {
      return;
    }
    const thread = get().thread;
    if (!EPHEMERAL_EVENTS.has(event.type)
        && typeof event.sequence === 'number' && event.sequence <= thread.cursor) {
      // 重连/重放重复推送：同一序号不再应用一次
      return;
    }
    eventQueue.push(event);
    scheduleFlush();
  },

  /** 把这一帧攒下的事件一次性折进状态，再统一触发联动刷新。 */
  flushEvents() {
    if (!eventQueue.length) {
      return;
    }
    const batch = eventQueue;
    eventQueue = [];
    const applied: EventEnvelope[] = [];
    const beforeKind = get().ui.attentionKind;
    set((draft) => {
      for (const item of batch) {
        if (!EPHEMERAL_EVENTS.has(item.type) && typeof item.sequence === 'number' && item.sequence <= draft.thread.cursor) continue;
        applyEvent(draft, item, { live: true });
        applied.push(item);
      }
      draft.lastEventAt = Date.now();
    });
    const after = get();
    if (after.ui.attentionKind !== beforeKind) {
      applyAttention(after.ui.attentionKind, after.sessionTitle);
    }
    for (const item of applied) {
      get().settleEvent(item, after);
    }
  },

  /** 单条事件落地后的联动（标题转正、任务契约、上下文、变更报告）。 */
  settleEvent(event, after) {
    const sessionId = get().sessionId;
    if (['run.started', 'run.steered', 'run.completed', 'run.failed', 'run.cancelled'].includes(event.type)) {
      set(draft => { draft.promoteBlank = null; });
      get().loadSessions();
    }
    // 任务契约面板刷新
    if (['run.started', 'run.steered', 'run.continued', 'run.completed', 'run.failed', 'run.cancelled', 'tool.completed', 'execution.updated'].includes(event.type)) {
      get().loadTask();
    }
    if (['run.started', 'run.completed', 'run.failed', 'run.cancelled'].includes(event.type)) {
      get().loadQueue();
    }
    // 一轮结束后拉取本次 run 的变更
    if (after.runChangesFetch) {
      const runId = after.runChangesFetch;
      set((draft) => { draft.runChangesFetch = null; });
      api.runChanges(runId).then((report) => {
        if (!report) return;
        set((draft) => {
          const row = [...draft.thread.rows].reverse().find((item): item is Draft<ResultRow> => item.type === 'result' && item.runIds.includes(runId));
          if (row) {
            row.changedFiles = sortChangeFiles(report.files || []);
            row.changedSummary = report.summary || null;
          }
          draft.changedFiles = sortChangeFiles(report.files || []);
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
    if (['run.started', 'run.completed', 'run.failed', 'run.cancelled', 'context.compacted'].includes(event.type)) {
      get().refreshContext();
    }
  },

  /* ---------- 输入 / 运行控制 ---------- */
  registerComposer(apiBridge) {
    // state 被 immer 冻结，必须走 set 更新，不能直接赋值
    set((draft) => { draft.composerApi = apiBridge; });
  },
  composerApi: null,

  modelReady() {
    if (!get().modelSave.busy) return true;
    get().showNotice('模型配置保存中，请稍候再发送；当前草稿已保留。');
    return false;
  },

  async submitInput(text, attachments, imagePositions) {
    if (!text?.trim() && !attachments?.length) {
      return false;
    }
    if (!get().modelReady()) return false;
    // 草稿态：第一条消息触发创建会话，标题由服务端统一生成。
    const ownerEpoch = get().sessionEpoch;
    let sessionId = get().sessionId;
    if (!sessionId) {
      try {
        sessionId = await get().ensureSession();
      } catch (error) {
        get().showNotice('创建对话失败：' + errorInfo(error).message);
        return false;
      }
      if (!sessionId || get().sessionEpoch !== ownerEpoch) {
        return false;
      }
    }
    // 创建会话是异步的，期间可能开始切换模型；发送前再次确认配置已保存。
    if (!get().modelReady()) return false;
    // /技能名 参数 → 服务端期望 content=参数 + skill=技能名（旧版 resolveSkillCommand 同语义）
    const skillCall = resolveSkillCommand(text, toSkillCommands(get().skills || []));
    const payloadContent = skillCall
      ? (skillCall.args || '（用户未提供额外要求，按技能指引执行）')
      : text;
    const body: InputMessage = { content: payloadContent, attachments: attachments || [], imagePositions: imagePositions || [] };
    if (skillCall) {
      body.skill = skillCall.skill;
    }
    const remembered = rememberInput(localStorage, sessionId, text);
    const queueInstead = get().running;
    if (!queueInstead) {
      set((draft) => {
        draft.pendingInputs.push(payloadContent);
        appendUserRow(draft, payloadContent, null, { attachments, imagePositions, pendingKey: `pending:${Date.now()}` });
        draft.ui.bottomSignal += 1;
        if (remembered) {
          draft.inputHistory = remembered;
          draft.inputHistoryIndex = -1;
          draft.inputHistoryDraft = '';
        }
      });
    } else if (remembered) {
      set((draft) => {
        draft.inputHistory = remembered;
        draft.inputHistoryIndex = -1;
        draft.inputHistoryDraft = '';
      });
    }
    try {
      await api.sendMessage(sessionId, body);
      get().loadSessions();
      if (queueInstead && get().sessionId === sessionId) {
        await get().loadQueue();
      }
      dropDraft(localStorage, sessionId);
      return true;
    } catch (error) {
      if (get().sessionId === sessionId) set((draft) => {
        draft.pendingInputs = draft.pendingInputs.filter((item) => !sameUserInput(item, payloadContent));
        const rows = draft.thread.rows;
        const index = rows.map((row) => row.text).lastIndexOf(payloadContent);
        if (index >= 0 && (!('sequence' in rows[index]) || rows[index].sequence == null)) {
          rows.splice(index, 1);
        }
      });
      get().showNotice('发送失败：' + errorInfo(error).message);
      return false;
    }
  },

  async steer(text, attachments) {
    if (attachments?.length) {
      get().showNotice('请将带附件的消息加入队列，再点击该消息的‘引导’。');
      return false;
    }
    const { sessionId, runId } = get();
    if (!sessionId) return false;
    set((draft) => { draft.steering = true; });
    try {
      await api.steer(sessionId, { content: text, runId });
      set((draft) => { draft.steering = false; });
      return true;
    } catch (error) {
      set((draft) => { draft.steering = false; });
      get().showNotice('引导失败：' + errorInfo(error).message);
      return false;
    }
  },

  async pauseRun() {
    const { runId } = get();
    if (!runId) {
      return;
    }
    set((draft) => { draft.pausing = true; });
    try {
      await api.pauseRun(runId);
    } catch (error) {
      get().showNotice('暂停失败：' + errorInfo(error).message);
    } finally {
      set((draft) => { draft.pausing = false; });
    }
  },

  async cancelRun() {
    const { runId } = get();
    if (!runId) {
      return;
    }
    set((draft) => { draft.cancelling = true; });
    try {
      await api.cancelRun(runId);
    } catch (error) {
      get().showNotice('取消失败：' + errorInfo(error).message);
    } finally {
      set((draft) => { draft.cancelling = false; });
    }
  },

  async continueRun() {
    if (!get().modelReady()) return false;
    const { sessionId } = get();
    if (!sessionId) return false;
    try {
      await api.continueRun(sessionId);
    } catch (error) {
      get().showNotice('继续失败：' + errorInfo(error).message);
    }
  },

  async resendMessage(sequence, text, attachments) {
    if (!get().modelReady()) return false;
    if (attachments?.length) {
      get().showNotice('编辑历史消息暂不支持新增附件。附件已保留，可取消编辑后作为新消息发送。');
      return false;
    }
    const { sessionId } = get();
    if (!sessionId) return false;
    try {
      await api.resend(sessionId, { sequence, content: text });
      return true;
    } catch (error) {
      get().showNotice('重新发送失败：' + errorInfo(error).message);
      return false;
    }
  },

  /* ---------- 审批 / 仲裁 ---------- */
  async resolveApproval(callId, approved) {
    set((draft) => { draft.pendingApprovals[callId].busy = true; });
    try {
      await api.approval(callId, approved);
    } catch (error) {
      get().showNotice('提交审批失败：' + errorInfo(error).message);
      set((draft) => { if (draft.pendingApprovals[callId]) draft.pendingApprovals[callId].busy = false; });
    }
  },

  async resolveArbitration(arbitrationId, decision) {
    const { sessionId } = get();
    if (!sessionId) return;
    try {
      await api.resolveArbitration(sessionId, arbitrationId, { choice: decision });
    } catch (error) {
      get().showNotice('提交裁决失败：' + errorInfo(error).message);
    }
  },

  /* ---------- 计划 / 队列 / 契约 / 上下文 ---------- */
  async loadPlan({ strict = false } = {}) {
    const { sessionId, sessionEpoch } = get();
    if (!sessionId) {
      return;
    }
    try {
      const data = await api.plan(sessionId);
      if (get().sessionId !== sessionId || get().sessionEpoch !== sessionEpoch) return;
      if (Array.isArray(data?.plan) && data.plan.length) {
        set((draft) => {
          let existing: PlanRow | null = null;
          for (let i = draft.thread.rows.length - 1; i >= 0; i -= 1) {
            const row = draft.thread.rows[i];
            if (row.type === 'user') {
              break;
            }
            if (row.type === 'plan') {
              existing = row;
              break;
            }
          }
          if (existing) {
            existing.items = data.plan;
          } else {
            draft.thread.rows.push({
              key: `plan:live:${draft.thread.cursor}`,
              type: 'plan',
              items: data.plan,
              start: draft.thread.cursor,
              end: draft.thread.cursor
            });
          }
          draft.planItems = data.plan;
        });
      }
    } catch (error) { if (strict) throw error; /* 计划恢复失败不影响对话 */ }
  },

  async loadQueue({ strict = false } = {}) {
    const { sessionId, sessionEpoch } = get();
    if (!sessionId) {
      return;
    }
    const request = ++queueLoadSequence;
    try {
      const data = await api.queue(sessionId);
      if (get().sessionId !== sessionId || get().sessionEpoch !== sessionEpoch || request !== queueLoadSequence) return;
      const normalized = normalizeQueue(data);
      set((draft) => {
        draft.queue = normalized.texts;
        draft.queueEntries = normalized.rows;
        draft.queueRevision = data?.revision || null;
      });
    } catch (error) { if (strict) throw error; /* 队列读取失败不阻塞 */ }
  },

  async mutateQueue(operation, failure) {
    const { sessionId, sessionEpoch, queueRevision, queueBusy } = get();
    if (!sessionId || queueBusy) return false;
    set(draft => { draft.queueBusy = true; });
    const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch;
    try {
      await operation(sessionId, queueRevision);
      if (current()) await get().loadQueue();
      return current();
    } catch (error) {
      if (current()) {
        get().showNotice(failure + errorInfo(error).message);
        await get().loadQueue();
      }
      return false;
    } finally {
      if (current()) set(draft => { draft.queueBusy = false; });
    }
  },

  async steerQueueItem(index) {
    const { runId, running } = get();
    if (!running || !runId) return false;
    return get().mutateQueue((id, revision) => api.steerQueueItem(id, index, { runId, revision }), '引导未发送：');
  },
  async saveQueueItem(index, text) {
    if (!text?.trim()) { get().showNotice('排队消息不能为空'); return false; }
    return get().mutateQueue((id, revision) => api.updateQueueItem(id, index, { content: text, revision }), '修改排队消息失败：');
  },
  async removeQueueItem(index) {
    return get().mutateQueue((id, revision) => api.deleteQueueItem(id, index, revision), '删除排队消息失败：');
  },
  async moveQueueItem(from, to) {
    return get().mutateQueue((id, revision) => api.moveQueueItem(id, from, to, revision), '调整顺序失败：');
  },

  async loadTask({ strict = false } = {}) {
    const { sessionId, sessionEpoch } = get();
    if (!sessionId) {
      return;
    }
    try {
      const task = await api.task(sessionId);
      if (get().sessionId !== sessionId || get().sessionEpoch !== sessionEpoch) return;
      if (get().taskDirty || get().taskBusy) return;
      set((draft) => {
        if (JSON.stringify(draft.task) !== JSON.stringify(task)) {
          draft.task = task;
          draft.taskDirty = false;
        }
      });
    } catch (error) { if (strict) throw error; /* 契约读取失败不阻塞 */ }
  },

  async saveTask(constraints, acceptance) {
    const { sessionId } = get();
    if (!sessionId) return;
    set((draft) => { draft.taskBusy = true; });
    try {
      const task = await api.saveTask(sessionId, { constraints, acceptance });
      set((draft) => {
        draft.task = task;
        draft.taskDirty = false;
        draft.taskBusy = false;
      });
      get().showNotice('任务契约已保存');
    } catch (error) {
      set((draft) => { draft.taskBusy = false; });
      get().showNotice('保存失败：' + errorInfo(error).message);
    }
  },

  async refreshContext({ strict = false } = {}) {
    const { sessionId, sessionEpoch } = get();
    if (!sessionId) {
      return;
    }
    try {
      const contextStatus = await api.context(sessionId);
      if (get().sessionId !== sessionId || get().sessionEpoch !== sessionEpoch) return;
      set((draft) => { draft.contextStatus = contextStatus; });
    } catch (error) { if (strict) throw error; /* 上下文读取失败不阻塞 */ }
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
    connectMirror(get, set);
  },

  async compactContext() {
    const { sessionId } = get();
    if (!sessionId) return;
    set((draft) => { draft.compactingContext = true; });
    try {
      await api.compactContext(sessionId);
      await get().refreshContext();
    } catch (error) {
      get().showNotice('压缩失败：' + errorInfo(error).message);
    } finally {
      set((draft) => { draft.compactingContext = false; });
    }
  },

  /* ---------- 检查点 ---------- */
  async openCheckpoint(sequence) {
    const { sessionId } = get();
    if (!sessionId) return;
    set((draft) => { draft.ui.checkpointDialog = { sequence, loading: true }; });
    try {
      const info = await api.checkpoints(sessionId, sequence);
      set((draft) => { draft.ui.checkpointDialog = { sequence, loading: false, info }; });
    } catch (error) {
      set((draft) => { draft.ui.checkpointDialog = null; });
      get().showNotice('读取检查点失败：' + errorInfo(error).message);
    }
  },
  async restoreCheckpoint(sequence, mode, revision) {
    const { sessionId } = get();
    if (!sessionId) return;
    if (!['code', 'conversation', 'both'].includes(mode)) {
      get().showNotice('请选择回退范围');
      return;
    }
    set((draft) => {
      draft.ui.checkpointDialog = { ...(draft.ui.checkpointDialog || {}), sequence, applying: true, error: '' };
    });
    try {
      const result = await api.restoreCheckpoint(sessionId, { sequence, mode, revision });
      if (!result?.applied) {
        const code: Partial<S.RunSnapshotServiceRollbackReport> & { unavailable?: string[] } = result?.code || {};
        const reason = [
          code.conflicts?.length ? `检测到后续修改：${code.conflicts.join('、')}` : '',
          code.failed?.length ? `写入失败：${code.failed.join('、')}` : '',
          code.unavailable?.length ? code.unavailable.join('；') : ''
        ].filter(Boolean).join('；') || '回退未完成，请刷新后重试';
        set((draft) => {
          draft.ui.checkpointDialog = { ...(draft.ui.checkpointDialog || {}), applying: false, error: reason };
        });
        return;
      }
      set((draft) => { draft.ui.checkpointDialog = null; });
      get().showNotice(mode === 'code'
        ? '代码已回退，对话保留'
        : mode === 'conversation' ? '对话已回退，原消息已回填' : '代码与对话都已回退，原消息已回填');
      await get().activate(sessionId);
      // 回退后把这条消息放回输入框，用户改一改就能重发（只撤代码时不动草稿）
      if (mode !== 'code' && result.input) {
        const composer = get().composerApi;
        if (composer) {
          // 草稿里可能已经有内容（含附件芯片），追加而不是覆盖
          if (composer.getText().trim()) {
            composer.appendText(`\n\n${result.input}`);
          } else {
            composer.setText(result.input);
          }
          composer.focus();
        }
      }
    } catch (error) {
      set((draft) => {
        draft.ui.checkpointDialog = { ...(draft.ui.checkpointDialog || {}), applying: false, error: errorInfo(error).message };
      });
    }
  },
  async forkCheckpoint(sequence, revision) {
    const { sessionId } = get();
    if (!sessionId) return;
    set((draft) => {
      draft.ui.checkpointDialog = { ...(draft.ui.checkpointDialog || {}), sequence, applying: true, error: '' };
    });
    try {
      const session = requireData(await api.forkCheckpoint(sessionId, { sequence, revision }));
      set((draft) => { draft.ui.checkpointDialog = null; });
      await get().loadSessions();
      await get().activate(session.sessionId);
      if (session.input) {
        get().composerApi?.setText?.(session.input);
        get().composerApi?.focus?.();
      }
      get().showNotice('已创建对话分支，原聊天保留');
    } catch (error) {
      set((draft) => {
        draft.ui.checkpointDialog = { ...(draft.ui.checkpointDialog || {}), applying: false, error: errorInfo(error).message };
      });
    }
  },

  /* ---------- 侧边对话（Side chat） ---------- */
  /** 当前对话所属项目（旧版 currentProject 的等价物）。 */
  currentProject() {
    const state = get();
    const listed = (state.sessions || []).find((session) => session.id === state.sessionId);
    const projectId = state.currentProjectId || listed?.projectId || null;
    return (state.projects || []).find((project) => project.id === projectId) || null;
  },

  /**
   * 开一条侧边对话：对齐 Codex 的 /side——从**当前任务**分叉，而不是凭空建一条全局会话。
   *
   * <p>后端负责继承父会话的工作区与模型记忆；前端只记住「它属于哪条主任务」。
   * 同一条主任务已经有侧边对话时直接切过去，只有显式 `force` 才再开一条。
   */
  async createSideChat({ force = false } = {}) {
    const state = get();
    const { sessionId, sessionEpoch, workspace } = state;
    if (state.embeddedSessionId) return null;
    if (!sessionId) {
      state.showNotice('先发一条消息开始任务，再开侧边对话');
      return null;
    }
    const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch && get().workspace === workspace;
    const owned = sideChatsFor(state.sideChats, { sessionId, workspace });
    const existing = owned.items.find(item => item.id === owned.activeId);
    if (existing && !force) {
      set(draft => { draft.sideChats.activeId = existing.id; draft.ui.inspectorOpen = true; draft.ui.inspectorTab = 'chat'; });
      persistSideChats();
      return existing;
    }
    const key = JSON.stringify([sessionId, sessionEpoch, workspace]);
    const pending = sideChatPending.get(key);
    if (pending) return pending;
    const request = (async () => {
      try {
        const session = await api.createSideChat(sessionId);
        const id = session?.sessionId || session?.id;
        if (!id) throw new Error('后端没有返回会话 ID');
        const item: SideChatTab = {
          id,
          parentId: session?.parentSessionId || sessionId,
          title: sideChatTitle(get().sideChats.items, sessionId),
          workspace: session?.workspace || workspace || null,
          projectId: session?.projectId || null
        };
        if (!item.id) throw new Error('后端没有返回会话 ID');
        set(draft => {
          if (!draft.sideChats.items.some(existing => existing.id === item.id)) draft.sideChats.items.push(item);
          if (current()) {
            draft.sideChats.activeId = item.id;
            draft.ui.inspectorOpen = true;
            draft.ui.inspectorTab = 'chat';
          }
        });
        persistSideChats();
        get().loadSessions();
        return current() ? item : null;
      } catch (error) {
        if (current()) get().showNotice('创建侧边对话失败：' + errorInfo(error).message);
        return null;
      } finally {
        sideChatPending.delete(key);
      }
    })();
    sideChatPending.set(key, request);
    return request;
  },

  selectSideChat(id) {
    set((draft) => {
      if (!draft.sideChats.items.some((item) => item.id === id)) {
        return;
      }
      draft.sideChats.activeId = id;
      draft.ui.inspectorOpen = true;
      draft.ui.inspectorTab = 'chat';
    });
    persistSideChats();
  },

  /**
   * 关闭侧边对话 = 丢弃（Codex 的侧边对话本来就不进历史）。
   * 后端会话连同事件与记忆一起删掉，主对话不受影响。
   */
  async closeSideChat(id) {
    const closing = sideChatClosing.get(id);
    if (closing) return closing;
    if (!get().sideChats.items.some(item => item.id === id)) return false;
    const request = (async () => {
      try {
        try { await api.deleteSession(id); } catch (error) { if (errorInfo(error).status !== 404) throw error; }
        set(draft => {
          draft.sideChats.items = draft.sideChats.items.filter(item => item.id !== id);
          draft.sideChats.activeId = sideChatsFor(draft.sideChats, { sessionId: draft.sessionId, workspace: draft.workspace }).activeId;
          if (draft.sideDrafts) delete draft.sideDrafts[id];
          if (draft.sideQuote?.id === id) draft.sideQuote = null;
        });
        persistSideChats();
        return true;
      } catch (error) {
        get().showNotice('侧边对话未能关闭，已保留，请重试：' + errorInfo(error).message);
        return false;
      } finally { sideChatClosing.delete(id); }
    })();
    sideChatClosing.set(id, request);
    return request;
  },

  setSideDraft(id, value) {
    set(draft => {
      draft.sideDrafts ||= {};
      draft.sideDrafts[id] = typeof value === 'function' ? value(draft.sideDrafts[id] || '') : value;
    });
  },

  /** Ctrl+Alt+S：已经停在对话页就收起，否则开/切到当前任务的侧边对话。 */
  toggleSideChatPane() {
    const state = get();
    if (state.embeddedSessionId) {
      return;
    }
    if (state.ui.inspectorOpen && state.ui.inspectorTab === 'chat') {
      state.setUi({ inspectorOpen: false });
      return;
    }
    state.createSideChat();
  },

  /** 把选中的文本带进当前任务的侧边对话（没有就现开一条）。 */
  async askSideChat(text) {
    if (!String(text || '').trim() || get().embeddedSessionId) {
      return;
    }
    const item = await get().createSideChat();
    if (!item) {
      return;
    }
    set((draft) => { draft.sideQuote = { id: item.id, text, nonce: Date.now() }; });
  },

  takeSideQuote(id) {
    const quote = get().sideQuote;
    if (!quote || quote.id !== id) {
      return null;
    }
    set((draft) => { draft.sideQuote = null; });
    return quote.text;
  },

  /* ---------- 工作区 / worktree ---------- */
  async switchWorkspace(workspace, { throwOnError = false } = {}) {
    const { sessionId } = get();
    if (!sessionId) {
      set((draft) => {
        draft.workspace = workspace;
        draft.draftSession = { projectId: draft.draftSession?.projectId || null, ...draft.draftSession, workspace };
        draft.fileIndex = { items: [], sessionId: null, loading: false };
        resetFilePreview(draft);
      });
      return;
    }
    try {
      await api.switchWorkspace(sessionId, workspace);
      await get().activate(sessionId);
    } catch (error) {
      get().showNotice('切换文件夹失败：' + errorInfo(error).message);
      if (throwOnError) throw error;
    }
  },

  /**
   * 在独立 worktree 里开新对话（旧版 openWorktree）。
   *
   * @param branch 从哪条分支检出；留空用当前分支。分支名由菜单里的输入框给出，
   *               不再用 window.prompt——内嵌 webview 里它会被直接吞掉
   */
  async openWorktree(branch = '') {
    const state = get();
    if (state.embeddedSessionId) {
      return;
    }
    if (!state.workspace) {
      state.showNotice('先选定一个 git 仓库目录');
      return;
    }
    try {
      const opened = await api.worktrees({ workspace: state.workspace, branch, projectId: state.currentProjectId });
      await get().loadSessions();
      if (opened?.sessionId) {
        await get().activate(opened.sessionId);
      }
      get().showNotice(`已从 ${opened?.branch || branch || '当前分支'} 建立 worktree，对话在这份检出里进行`);
    } catch (error) {
      get().showNotice('建立 worktree 失败：' + errorInfo(error).message);
    }
  },

  /** 把 worktree 里的改动交回本地（旧版 handoffWorktree）。 */
  async handoffWorktree() {
    const { sessionId } = get();
    if (!sessionId || get().embeddedSessionId) {
      return;
    }
    try {
      const handed = await api.worktreeHandoff(sessionId);
      set((draft) => {
        draft.workspace = handed?.workspace || draft.workspace;
        const listed = draft.sessions.find((item) => item.id === sessionId);
        if (listed) {
          listed.workspace = draft.workspace;
        }
      });
      await get().loadSessions();
      get().showNotice(handed?.note || 'worktree 改动已交回本地');
    } catch (error) {
      get().showNotice('交回失败：' + errorInfo(error).message);
    }
  },

  /* ---------- 多沙箱 ---------- */
  /** 单独接受 / 拒绝某个沙箱的改动；服务端拦下未校验的改动时让用户显式确认后重试。 */
  async decideSandbox(sandboxId, action, candidateHash) {
    const { sessionId } = get();
    if (!sessionId || !sandboxId || !['accept', 'reject', 'recover'].includes(action)) {
      return false;
    }
    const post = (acknowledge: boolean) => apiFetch(
      `/api/sessions/${encodeURIComponent(sessionId)}/multi/sandboxes/${encodeURIComponent(sandboxId)}/${action}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ candidateHash, ...(acknowledge ? { acknowledgeUnverified: true } : {}) })
      },
      action === 'accept' ? 60 * 60 * 1000 : 20000
    );
    try {
      let response = await post(false);
      if (get().sessionId !== sessionId) return false;
      if (action !== 'accept') {
        if (!response.ok) throw new Error(await describeError(response));
        get().showNotice(action === 'recover' ? '恢复已完成，请重新审阅变更' : `已拒绝 ${sandboxId} 的改动`);
        return true;
      }
      if (response.status === 409 || response.status === 412) {
        const reason = await describeError(response);
        if (response.status === 409 || !window.confirm(`${reason}\n\n仍要接受这批未通过校验的改动吗？`)) {
          get().showNotice(reason);
          return false;
        }
        response = await post(true);
        if (get().sessionId !== sessionId) return false;
      }
      const data = await response.json().catch(() => ({})) as Partial<S.SandboxStoreApplyReport> & { message?: string };
      if (response.ok && data?.ok !== false) {
        get().showNotice(`已接受 ${sandboxId}：写入 ${(data.written || []).length} 个文件`);
        return true;
      }
      const reason = data?.failed?.length ? data.failed.join('；') : (data?.message || `HTTP ${response.status}`);
      get().showNotice(`未能写入：${reason}`);
      return false;
    } catch (error) {
      if (get().sessionId !== sessionId) return false;
      get().showNotice('操作失败：' + errorInfo(error).message + (errorInfo(error).message?.includes('超时') ? '；服务端可能仍在处理，请刷新核对写入状态后再操作。' : ''));
      return false;
    }
  },

  /* ---------- 变更 / 预览 ---------- */
  async loadChanges({ strict = false } = {}) {
    const { sessionId, sessionEpoch, workspace } = get();
    if (!sessionId || get().changesState === 'restoring') {
      return;
    }
    const request = (get()._changesRequest || 0) + 1;
    const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch && get().workspace === workspace && get()._changesRequest === request;
    set((draft) => { draft.changesState = 'loading'; draft._changesRequest = request; });
    try {
      const report = requireData(await api.diff(sessionId));
      if (!current()) return;
      set((draft) => {
        draft.changeReport = report;
        draft.changesState = '';
        draft.ui.changeCount = (report.files || []).length;
      });
    } catch (error) {
      if (!current()) return;
      set((draft) => { draft.changesState = 'error'; });
      if (strict) throw error;
      get().showNotice('读取变更失败：' + errorInfo(error).message);
    }
  },
  /**
   * 撤销改动：已跟踪文件回退到 HEAD，未跟踪文件删除；成功后刷新变更列表。
   * 失败文件逐条回报，别把「撤销没成」显示成成功。
   */
  async restoreChanges(paths, all = false) {
    const { sessionId, sessionEpoch, workspace } = get();
    if (!sessionId || get().changesState === 'restoring') {
      return null;
    }
    const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch && get().workspace === workspace;
    set((draft) => { draft.changesState = 'restoring'; draft._changesRequest = (draft._changesRequest || 0) + 1; });
    try {
      const report = await api.restoreChanges(sessionId, { paths: paths || [], all });
      if (!current()) return report;
      const failed = Array.isArray(report?.failed) ? report.failed : [];
      const done = (report?.restored?.length || 0) + (report?.deleted?.length || 0);
      if (report?.error) {
        get().showNotice('撤销失败：' + report.error);
      } else if (failed.length) {
        get().showNotice(`已撤销 ${done} 个文件，${failed.length} 个失败：${failed[0].path}（${failed[0].reason}）`);
      } else if (done) {
        get().showNotice(`已撤销 ${done} 个文件的改动`);
      } else {
        get().showNotice('没有需要撤销的改动');
      }
      set((draft) => {
        draft.changesState = '';
        // 撤销之后原来打开的差异已经没有意义，直接收起来
        if (all || (paths || []).some((path) => samePath(path, draft.ui.changeView))) {
          draft.ui.changeView = null;
        }
      });
      // 预览里正开着的文件被撤销了：只刷新内容，别把用户从变更面板拽到预览页
      const previewTarget = get().ui.previewPath;
      if (previewTarget && (all || (paths || []).some((path) => samePath(path, previewTarget)))) {
        try {
          const fresh = await api.file(sessionId, previewTarget);
          if (!current()) return report;
          set((draft) => { draft._previewData = fresh; });
        } catch {
          if (!current()) return report;
          set((draft) => { draft._previewData = null; });
        }
      }
      await get().loadChanges();
      return report;
    } catch (error) {
      if (!current()) return null;
      set((draft) => { draft.changesState = ''; });
      get().showNotice('撤销失败：' + errorInfo(error).message);
      return null;
    }
  },
  async rollbackRun(runId) {
    try {
      await api.rollbackRun(runId);
      get().showNotice('已回滚本次运行改动');
      await get().loadChanges();
    } catch (error) {
      get().showNotice('回滚失败：' + errorInfo(error).message);
    }
  },
  async createBranch(name) {
    const { sessionId, sessionEpoch, workspace } = get();
    if (!sessionId) return { ok: false, error: '请先打开一个会话。' };
    try {
      await api.branch(sessionId, name);
      if (get().sessionId === sessionId && get().sessionEpoch === sessionEpoch && get().workspace === workspace) {
        get().showNotice(`已创建并切换分支 ${name}`);
        await get().loadChanges();
      }
      return { ok: true };
    } catch (error) {
      return { ok: false, error: '创建分支失败：' + errorInfo(error).message };
    }
  },
  async openPreview(path) {
    const { sessionId, workspace, sessionEpoch } = get();
    const target = String(path || '').trim();
    if ((!sessionId && !workspace) || !target) return;
    const request = (get()._previewRequest || 0) + 1;
    set((draft) => {
      draft._previewRequest = request;
      draft.ui.previewPath = target;
      draft.ui.inspectorTab = 'preview';
      draft.ui.inspectorOpen = true;
      draft.ui.reviewExpanded = false;
      draft._previewData = null;
      draft._previewLoading = true;
    });
    const current = () => get()._previewRequest === request && get().sessionId === sessionId
      && get().workspace === workspace && get().sessionEpoch === sessionEpoch;
    try {
      const data = sessionId ? await api.file(sessionId, target) : await api.workspaceFile(workspace, target);
      if (current()) set((draft) => { draft._previewData = data; draft._previewLoading = false; });
    } catch (error) {
      if (current()) {
        set((draft) => { draft._previewLoading = false; });
        get().showNotice('读取文件失败：' + errorInfo(error).message);
      }
    }
  },

  /* ---------- 命令任务 ---------- */
  async loadCommandTasks() {
    const { sessionId, sessionEpoch } = get();
    if (!sessionId) {
      return;
    }
    try {
      const data = await apiJson<{ tasks: CommandTask[] }>(`/api/sessions/${encodeURIComponent(sessionId)}/commands`);
      if (get().sessionId !== sessionId || get().sessionEpoch !== sessionEpoch) return;
      // 任务列表是数组，日志才是增量片段：早先这里把 mergeCommandLog 用在任务上，
      // 结果 commandTasks 变成 {text, offset...} 对象，面板里 .filter 直接 TypeError
      set((draft) => {
        draft.commandTasks = Array.isArray(data?.tasks) ? data.tasks : [];
        // 切了会话还留着上一条命令的详情，会对着已经不存在的任务轮询日志
        const detail = draft.commandDetail;
        if (detail && detail.sessionId && detail.sessionId !== sessionId) {
          draft.commandDetail = null;
          draft.commandLog = null;
          draft.commandLogTaskId = null;
        }
      });
    } catch { /* 命令面板不可用不阻塞 */ }
  },

  /** 选中某个命令任务并把它的日志拉到最新（offset 用上次的 nextOffset 续读）。 */
  async loadCommandLog(task) {
    const { sessionId, sessionEpoch } = get();
    const taskId = task?.taskId || task?.id;
    if (!sessionId || !taskId) {
      return;
    }
    const sameTask = get().commandLogTaskId === taskId;
    const offset = sameTask ? (get().commandLog?.offset || 0) : 0;
    set((draft) => {
      draft.commandDetail = { ...(task || {}) };
      if (!sameTask) {
        draft.commandLogTaskId = taskId;
        draft.commandLog = { text: '', offset: 0, trimmed: 0, truncated: false, eof: false };
      }
    });
    try {
      const chunk = await apiJson<S.CommandTaskManagerLogChunk>(`/api/sessions/${encodeURIComponent(sessionId)}/commands/${encodeURIComponent(taskId)}/logs?offset=${offset}&limit=200000`);
      if (get().sessionId !== sessionId || get().sessionEpoch !== sessionEpoch) return;
      set((draft) => {
        if (draft.commandLogTaskId !== taskId) {
          return;
        }
        draft.commandLog = mergeCommandLog(draft.commandLog, chunk);
      });
    } catch { /* 日志拉取失败不阻塞面板 */ }
  },

  /** 停止一个还在跑的命令任务：后端收到后返回最新任务状态，界面随即刷新。 */
  async stopCommand(task) {
    const { sessionId } = get();
    const taskId = task?.taskId || task?.id;
    if (!sessionId || !taskId) {
      return;
    }
    try {
      await api.stopCommand(sessionId, taskId);
      get().showNotice('已请求停止：' + (task.command || taskId));
      await get().loadCommandTasks();
    } catch (error) {
      get().showNotice('停止失败：' + errorInfo(error).message);
    }
  },

  /* ---------- 会话操作 ---------- */
  async renameSession(title, id = get().sessionId) {
    const normalized = String(title || '').trim();
    if (!normalized || [...normalized].length > 120) {
      get().showNotice('标题不能为空，且不能超过 120 个字符');
      return false;
    }
    try {
      if (!id) return false;
      const response = await api.renameSession(id, normalized);
      sessionLoadSequence++;
      set(draft => {
        const row = draft.sessions.find(session => session.id === id);
        if (row) Object.assign(row, response, {id, title: response?.title || normalized});
        if (draft.sessionId === id) draft.sessionTitle = response?.title || normalized;
      });
      return true;
    } catch (error) {
      get().showNotice('重命名失败：' + errorInfo(error).message);
      return false;
    }
  },
  async updateSessionPreferences(id, values) {
    try {
      const response = await api.sessionPreferences(id, values);
      sessionLoadSequence++;
      set(draft => {
        const row = draft.sessions.find(session => session.id === id);
        if (row) Object.assign(row, values, response, {id});
        draft.sessions = sortSessions(draft.sessions);
      });
      return true;
    } catch (error) {
      get().showNotice('会话设置保存失败：' + errorInfo(error).message);
      return false;
    }
  },
  async archiveSession(id, archived = true) {
    try {
      await api.archiveSession(id, archived);
      await get().loadSessions();
      if (archived && get().sessionId === id) {
        const next = get().sessions.find((session) => !session.archived);
        if (next) await get().activate(next.id);
        else get().startNewSession();
      }
      return true;
    } catch (error) {
      get().showNotice('归档失败：' + errorInfo(error).message);
      return false;
    }
  },
  /** 彻底删除会话（事件、检查点、计划一起清）；归档只是藏起来，列表会越积越多。 */
  async deleteSession(id) {
    const wasActive = get().sessionId === id;
    try {
      await api.deleteSession(id);
      // 先本地摘掉这一行：后端的清理是异步的，紧接着的列表可能还带着它
      set((draft) => { draft.sessions = (draft.sessions || []).filter((session) => session.id !== id); });
      // 主任务没了，挂在它下面的侧边对话也一起清掉（它们不会出现在列表里，留着就成了孤儿）
      const orphans = (get().sideChats.items || []).filter((item) => item.parentId === id);
      if (orphans.length) {
        set((draft) => {
          draft.sideChats.items = draft.sideChats.items.filter((item) => item.parentId !== id);
          draft.sideChats.activeId = sideChatsFor(draft.sideChats, {
            sessionId: draft.sessionId, workspace: draft.workspace
          }).activeId;
        });
        persistSideChats();
        orphans.forEach((item) => { api.deleteSession(item.id).catch(() => {}); });
      }
      get().showNotice('已删除对话');
      const list = await get().loadSessions();
      if (!wasActive) {
        return;
      }
      const next = (list || []).find((session) => !session.archived && !session.isBlank)
        || (list || []).find((session) => !session.archived);
      if (next) {
        await get().activate(next.id);
      } else {
        // 删的是最后一个对话：回到「新任务」，别继续对着不存在的会话轮询
        get().startNewSession();
      }
    } catch (error) {
      get().showNotice('删除失败：' + errorInfo(error).message);
    }
  },
  /** 用上一条用户输入重跑一轮，历史里不会多一条重复的用户消息。 */
  async regenerate() {
    const { sessionId, running } = get();
    if (!sessionId || running) {
      return;
    }
    try {
      await api.regenerate(sessionId);
      set((draft) => {
        draft.running = true;
        draft.runStartedAt = Date.now();
        draft.lastOutcome = '';
      });
    } catch (error) {
      get().showNotice('重新生成失败：' + errorInfo(error).message);
    }
  },
  /** 通用二次确认：危险操作统一走这里，避免各处 window.confirm 风格不一。 */
  askConfirm(options) {
    set((draft) => { draft.ui.confirm = options; });
  },

  /* ---------- 设置 ---------- */
  async loadSettings() {
    const sequence = ++settingsLoadSequence;
    try {
      const data = await api.settings();
      if (sequence !== settingsLoadSequence || get().modelSave.busy) return;
      set((draft) => {
        applySettings(draft, data);
      });
    } catch { /* 设置读取失败不阻塞 */ }
  },
  async savePermissionMode(mode) {
    const next = normalizePermission(mode);
    try {
      const data = await api.savePermissionMode(next);
      set((draft) => {
        draft.permissionMode = normalizePermission(data?.permissionMode || next);
        // 「能力」页签里的权限档位跟着变，别让面板显示旧值
        if (draft.capabilities) {
          draft.capabilities.permissionMode = draft.permissionMode;
        }
        // 配置页里当前选中的卡片也要跟着变（原来只改了 store，卡片仍高亮旧值）
        if (draft.settingsData) {
          draft.settingsData.permissionMode = draft.permissionMode;
        }
      });
      get().showNotice('权限模式已切换为「' + permissionLabel(next) + '」');
    } catch (error) {
      get().showNotice('保存权限模式失败：' + errorInfo(error).message);
    }
  },
  /** 清除已保存的 API Key（后端独立接口，和「留空表示不改」区分开）。 */
  async clearModelKey() {
    return get().saveModel({}, { clearKey: true });
  },
  resetModelFeedback() {
    if (!get().modelSave.busy) set((draft) => { draft.modelSave = { busy: false, message: '', error: false }; });
  },
  async saveModel(body, { clearKey = false } = {}) {
    // 两个入口共用写入锁：慢请求期间关闭、重开表单也不能重复提交。
    if (get().modelSave.busy) return false;
    settingsLoadSequence++;
    set((draft) => { draft.modelSave = { busy: true, message: clearKey ? '正在清除 API Key…' : '正在保存模型配置…', error: false }; });
    try {
      const result = clearKey ? await api.clearModelKey() : await api.saveModel(body);
      if (typeof result?.model !== 'string' || typeof result?.modelEndpoint !== 'string') {
        throw new Error('无法确认保存结果，请重新加载工作台核对配置');
      }
      const message = describeModelSaved(result, {
        clearKey: clearKey || Boolean(body?.clearApiKey),
        keyChanged: Boolean(String(body?.apiKey || '').trim())
      });
      // PUT / DELETE 已返回完整设置，无需等额外 GET 才显示新配置。
      settingsLoadSequence++;
      set((draft) => {
        applySettings(draft, result);
        if (draft.capabilities) draft.capabilities.model = result.model;
        draft.modelSave = { busy: false, message, error: false };
      });
      get().showNotice(message);
      return true;
    } catch (error) {
      const message = (clearKey ? '清除失败：' : '保存失败：') + (errorInfo(error).message || '无法连接服务，请检查网络后重试');
      set((draft) => { draft.modelSave = { busy: false, message, error: true }; });
      get().showNotice(message);
      return false;
    }
  },
  async probeModels(body) {
    return api.probeModels(body);
  },
  /**
   * 技能清单。
   *
   * <p>后端返回的是 {@code available}（外加 invalid 与两个安装根目录），早先这里读的是
   * {@code data.skills}，于是技能列表永远是空的、斜杠菜单里也一个技能都没有。
   */
  async loadSkills() {
    const { sessionId } = get();
    try {
      const data = await api.skills(sessionId || undefined);
      const list = Array.isArray(data?.available) ? data.available : [];
      set((draft) => {
        draft.skills = list;
        draft.skillsMeta = {
          projectRoot: data?.projectRoot || '',
          userRoot: data?.userRoot || '',
          invalid: Array.isArray(data?.invalid) ? data.invalid : []
        };
        draft.quickCommands = mergeCommands(QUICK_COMMANDS, toSkillCommands(list));
      });
    } catch {
      // 技能读取失败不能让斜杠菜单跟着空掉：内置指令始终可用
      set((draft) => {
        draft.quickCommands = mergeCommands(QUICK_COMMANDS, toSkillCommands(draft.skills || []));
      });
    }
  },
  /** 能力清单：工具、MCP 服务器、模型与权限档位，全部来自后端注册表而不是写死的文案。 */
  async loadCapabilities() {
    const { sessionId } = get();
    if (!sessionId) {
      return null;
    }
    set((draft) => { draft.capabilitiesState = 'loading'; });
    try {
      const data = await api.capabilities(sessionId);
      set((draft) => {
        draft.capabilities = data;
        draft.capabilitiesState = '';
      });
      return data;
    } catch (error) {
      set((draft) => { draft.capabilitiesState = 'error'; });
      get().showNotice('读取能力清单失败：' + errorInfo(error).message);
      return null;
    }
  },
  /** 浏览器运行时状态：只有 capable 时镜像和置前按钮才有意义。 */
  async loadBrowserStatus() {
    const { sessionId } = get();
    if (!sessionId) {
      return null;
    }
    try {
      const status = await api.browserStatus(sessionId);
      set((draft) => { draft.ui.browserStatus = status; });
      return status;
    } catch {
      // 浏览器运行时不在线时接口也会报错，界面按「不可用」展示即可
      return null;
    }
  },
  async loadUsage(days) {
    try {
      const data = await api.usage(days);
      set((draft) => { draft.usageData = data; draft.ui.usageDays = days; });
    } catch { /* 用量读取失败不阻塞 */ }
  },

  async refreshActiveRun() {
    const { sessionId, sessionEpoch } = get();
    if (!sessionId) {
      return;
    }
    try {
      const active = await api.activeRun(sessionId);
      if (get().sessionId !== sessionId || get().sessionEpoch !== sessionEpoch) return;
      if (active?.runId) {
        set((draft) => {
          draft.running = true;
          draft.runId = active.runId;
        });
      }
    } catch { /* 无活跃 run */ }
  }
  };
}));

/* ---------- 模块级辅助 ---------- */

/** Side chat 标签页落盘（旧版 persistSideChats）。 */
function persistSideChats() {
  writeSideChats(useStore.getState().sideChats);
}

/**
 * 后端错误体 → 可读文案（旧版 errorText 的顺序：message → detail → error）。
 *
 * <p>Spring 默认错误体把业务原因放在 {@code message}，{@code error} 只是状态短语
 * （如 "Precondition Failed"）；先取 error 会把「为什么被拦」整段丢掉。
 */
async function describeError(response: Response) {
  let body: Record<string, unknown> | null = null;
  try {
    body = await response.json() as Record<string, unknown> | null;
  } catch {
    // 非 JSON 错误体（网关返回的 HTML 之类）
  }
  if (body && typeof body === 'object') {
    for (const key of ['message', 'detail', 'error']) {
      const value = body[key];
      if (typeof value === 'string' && value.trim()) {
        return value.trim();
      }
    }
  }
  return `HTTP ${response.status}`;
}

function applyTheme(mode: string, accent: string) {
  const prefersDark = Boolean(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  const resolved = mode === 'system' ? (prefersDark ? 'dark' : 'light') : (mode === 'dark' ? 'dark' : 'light');
  document.documentElement.dataset.theme = resolved;
  document.documentElement.dataset.themeMode = mode;
  if (accent) {
    document.documentElement.dataset.accent = accent;
  } else {
    delete document.documentElement.dataset.accent;
  }
}

/** 桌面通知开关的本地持久化（旧版 NOTIFY_KEY 同名），默认关闭，只在你主动开后打扰你。 */
function readNotifyEnabled() {
  try {
    return localStorage.getItem(NOTIFY_KEY) === '1';
  } catch {
    return false;
  }
}

function applyAttention(kind: string, title: string) {
  // tabTitle(base, kind)：参数顺序反了标题会变成「● 需要你」这种状态词
  document.title = tabTitle(title || '新任务', kind);
  const link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (link) {
    link.href = faviconHref(kind);
  }
  // 只有页面在后台、用户开了通知、浏览器已授权、且这类信号值得打扰时才弹
  const permission = typeof Notification === 'undefined' ? 'denied' : Notification.permission;
  const enabled = useStore.getState().notifyEnabled;
  if (kind !== 'attention' || !shouldNotify({ kind, hidden: document.hidden, enabled, permission })) {
    return;
  }
  try {
    const body = ({ attention: '任务需要确认', done: '本轮已完成', error: '本轮运行失败' } as Record<string, string>)[kind];
    new Notification(`Pilot · ${title || '新任务'}`, { body: body || '' });
  } catch {
    // 通知失败不影响主流程
  }
}

function closeStreams() {
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
  if (mirrorSource) {
    mirrorSource.close();
    mirrorSource = null;
  }
  lastMirrorPayload = null;
  clearInterval(commandTimer);
  commandTimer = undefined;
  clearTimeout(reconnectTimer);
  reconnectTimer = undefined;
  clearInterval(runClockTimer);
  runClockTimer = undefined;
}

function connectMirror(get: StoreGet, set: StoreSet) {
  const { sessionId, ui } = get();
  if (!sessionId || !ui.mirror) {
    return;
  }
  if (mirrorSource) {
    mirrorSource.close();
  }
  const source = new EventSource(`/api/sessions/${encodeURIComponent(sessionId)}/browser/stream`);
  mirrorSource = source;
  source.addEventListener('frame', (message) => {
    let payload: MirrorFrame;
    try {
      payload = JSON.parse(message.data) as MirrorFrame;
    } catch {
      return;
    }
    lastMirrorPayload = payload;
    if (paintMirrorFrame) {
      paintMirrorFrame(payload);
    }
    const mirror = get().ui.mirror;
    const mode = nextMirrorMode(mirror.mode, 'frame');
    const url = payload.url || mirror.url;
    const frameW = Number(payload.w) > 0 ? Number(payload.w) : mirror.frameW;
    const frameH = Number(payload.h) > 0 ? Number(payload.h) : mirror.frameH;
    if (mirror.mode === mode && mirror.url === url && mirror.frameW === frameW && mirror.frameH === frameH) {
      return;
    }
    set((draft) => {
      draft.ui.mirror.mode = mode;
      draft.ui.mirror.url = url;
      draft.ui.mirror.frameW = frameW;
      draft.ui.mirror.frameH = frameH;
      draft.ui.mirror.frame = null;
    });
  });
  source.addEventListener('status', (message) => {
    try {
      const payload = JSON.parse(message.data) as { paused?: boolean; headless?: boolean; url?: string };
      set((draft) => {
        draft.ui.mirror.paused = Boolean(payload.paused);
        if (typeof payload.headless === 'boolean') {
          draft.ui.mirror.headless = payload.headless;
        }
        if (payload.url) {
          draft.ui.mirror.url = payload.url;
        }
      });
    } catch { /* 忽略 */ }
  });
  // 页面跳转（用户点链接 / 地址栏 / 智能体导航）：地址栏跟着变，前进后退按钮跟着亮灭
  source.addEventListener('nav', (message) => {
    try {
      const payload = JSON.parse(message.data) as { url?: string; canGoBack?: boolean; canGoForward?: boolean; embed?: boolean; frameOptions?: string };
      set((draft) => {
        if (payload.url) {
          draft.ui.mirror.url = payload.url;
        }
        draft.ui.mirror.canGoBack = Boolean(payload.canGoBack);
        draft.ui.mirror.canGoForward = Boolean(payload.canGoForward);
        if (payload.embed === false) {
          draft.ui.mirror.embed = false;
        } else if (payload.embed === true) {
          draft.ui.mirror.embed = true;
        }
        if (typeof payload.frameOptions === 'string') {
          draft.ui.mirror.frameOptions = payload.frameOptions;
        }
        if (draft.ui.mirror.mode === 'closed' && payload.url !== 'about:blank') {
          draft.ui.mirror.mode = 'live';
        }
      });
    } catch { /* 忽略 */ }
  });
  // 浏览器被回收（空闲回收 / 崩溃）：保留最后一张画面，但按钮不能再用
  source.addEventListener('closed', () => {
    set((draft) => {
      draft.ui.mirror.mode = 'closed';
      draft.ui.mirror.canGoBack = false;
      draft.ui.mirror.canGoForward = false;
    });
  });
  source.onerror = () => {
    source.close();
    if (mirrorSource === source) {
      mirrorSource = null;
    }
  };
}

function connectCommandTasks(get: StoreGet, set: StoreSet) {
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

export { closeStreams };

/** 浏览器计时器是数字，Node 测试计时器可主动释放事件循环。 */
function unrefTimer(timer: number | { unref?: () => void }) {
  if (typeof timer === 'object') timer.unref?.();
}

function requireData<T>(value: T | null): T {
  if (value === null) throw new Error('服务端返回空响应，请刷新后重试');
  return value;
}
