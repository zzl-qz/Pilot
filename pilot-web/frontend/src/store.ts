import { createRunStateActions } from './store/run-state.js';
import { createRunControlActions } from './store/run-controls.js';
import { createSessionStreams } from './store/streams.js';
import { unrefTimer } from './store/context.js';
import { createSessionActions, emptyMirror, resetFilePreview } from './store/sessions.js';
import { createHistoryController } from './store/history.js';
import { createModelSettingsActions } from './store/model-settings.js';
import { createPanelActions } from './store/panels.js';
import { createSideChatActions } from './store/side-chats.js';
import { createInputActions } from './store/input.js';
import { requireData } from './store/shared.js';
import type { StoreState, UiState, AlertPreferences } from './types/store.js';
import type * as S from './types/server.js';
import type { EventEnvelope } from './types/events.js';
import type { PendingQuestion } from './types/thread.js';
import type { GlobalPendingApproval, GlobalPendingQuestion, PendingInteractionsSnapshot, RunAttentionPayload } from './types/api.js';
import { errorInfo } from './lib/errors.js';
import { resolveTheme } from './lib/theme.js';
import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { api, apiFetch } from './api.js';
import { initialThread } from './thread-reducer.js';
import { classifyApprovalRisk, faviconHref, isWindowBackgrounded, tabTitleWithCount } from './lib/attention.js';
import { readAlertPreferences, writeAlertPreferences } from './lib/sound.js';
import { buildApprovalFloatingAlert, buildQuestionFloatingAlert, buildRunFloatingAlert, dispatchAttentionAlert, resetAlertDedup } from './lib/alert-dispatcher.js';
import { parseAsk } from './lib/tool-meta.js';
import { mergeCommands, toSkillCommands } from './lib/skills.js';
import { QUICK_COMMANDS } from './lib/commands.js';
import { readSideChats, readEmbeddedSession } from './lib/side-chat.js';
import { createWorkbenchRefresher, saveWorkbenchView, takeWorkbenchView } from './lib/workbench-sync.js';

/**
 * 全局状态：zustand + immer。
 *
 * <p>字段与旧版 state 对象一一对应；动作函数替代旧版的直接 DOM 操纵。
 * 会话、历史、事件流等各自拥有异步请求和清理逻辑，这里负责组合。
 */

let stopStreams: () => void = () => {};
let noticeTimer: ReturnType<typeof setTimeout> | undefined;
let noticeCallback: (() => unknown) | null = null;
let skillLoadSequence = 0;
let memoryDraftCountSequence = 0;
let interactionsInitialized = false;
let cachedUrgentCount = 0;
/** 桌面通知开关（与旧版同名键）。 */
const NOTIFY_KEY = 'pilot.notify';

export const initialUi = (): UiState => ({
  notice: null,
  noticeTimer: null,
  refreshing: false,
  syncError: '',
  lastSyncedAt: 0,
  filesRevision: 0,
  themeMode: localStorage.getItem('pilot.theme') || 'light',
  accent: localStorage.getItem('pilot.accent') || 'sky',
  sidebarOpen: typeof window.matchMedia === 'function' ? !window.matchMedia('(max-width: 960px)').matches : true,
  inspectorOpen: false,
  inspectorTab: 'changes',
  executionMode: 'act',
  workbenchFileId: null,
  shortcutsOpen: false,
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
  reviewRunId: null,
  previewPath: '',
  /** 浏览器状态：{ enabled, capable, available, reason, paused }；没有它就是还没查过。 */
  browserStatus: null,
  usageDays: 7
});

export const useStore = create<StoreState>()(immer((set, get) => {
  const history = createHistoryController({ set, get }, id => streams.rebuildHistory(id));
  const streams = createSessionStreams(
    { set, get },
    history.reset,
    (kind, title, applied, beforeQuestions) => applyAttention(kind, title, applied, beforeQuestions),
    (payload) => handleRunAttention(payload)
  );
  stopStreams = () => {
    interactionsInitialized = false;
    cachedUrgentCount = 0;
    resetAlertDedup();
    streams.closeAll();
  };
  return {
  /* ---------- 会话 / 项目 ---------- */
  sessionId: null,
  sessionEpoch: 0,
  historyRevision: null,
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
  notes: [],
  noteSeed: null,
  noteFocus: null,
  notesState: 'idle',
  notesError: '',
  notesSaving: '',
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
  queueWaitingReason: '',
  queueHolding: false,
  queueTakingBack: false,
  turnRuns: [],
  changedFiles: [],
  contextStatus: null,
  compactingContext: false,
  pendingInputs: [],
  queue: [],
  queueEntries: [],
  pendingApprovals: {},
  pendingQuestions: {},
  pendingArbitrations: {},
  agents: {},
  task: null,
  taskDirty: false,
  taskBusy: false,
  taskDraftRevision: 0,
  planItems: [],
  skills: [],
  /** 技能安装位置与无效项（设置页用来告诉用户「装到哪」以及哪些没通过校验）。 */
  skillsMeta: { projectRoot: '', userRoot: '', invalid: [] },
  pendingMemoryDrafts: 0,
  /** 内置 / 快捷指令：先用 QUICK_COMMANDS 兜底，loadSkills 成功后合并技能指令。 */
  quickCommands: mergeCommands(QUICK_COMMANDS, []),
  modelOptions: [],
  remoteModels: [],
  settingsData: null,
  modelSave: { busy: false, message: '', error: false },
  /** 桌面通知开关（默认关，用户主动开才打扰）。 */
  notifyEnabled: readNotifyEnabled(),
  alertPrefs: readAlertPreferences(),
  desktopFocused: null,
  globalPendingApprovals: [],
  globalPendingQuestions: [],
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
  /** 验证结论：输入框上方的结论条读它；null 表示还没加载。 */
  validation: null,
  hasRun: false,
  changeReport: null,
  changesState: '',
  runChanges: null,
  timeline: [],
  streamCard: null,
  promoteBlank: null,
  thread: initialThread(),
  ui: initialUi(),

  /* ---------- 通用 UI ---------- */
  showNotice(text, action) {
    noticeCallback = typeof action?.onAction === 'function' ? action.onAction : null;
    set((draft) => {
      draft.ui.notice = {
        text,
        at: Date.now(),
        ...(action?.label ? { actionLabel: action.label } : {})
      };
    });
    clearTimeout(noticeTimer);
    const duration = action?.durationMs ?? (action?.label ? 10_000 : 7000);
    noticeTimer = setTimeout(() => get().hideNotice(), duration);
    unrefTimer(noticeTimer);
  },
  hideNotice() {
    noticeCallback = null;
    clearTimeout(noticeTimer);
    noticeTimer = undefined;
    set((draft) => { draft.ui.notice = null; });
  },
  runNoticeAction() {
    const cb = noticeCallback;
    get().hideNotice();
    if (cb) {
      try { void cb(); } catch { /* 回调异常不阻塞界面 */ }
    }
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
  updateAlertPrefs(patch: Partial<AlertPreferences>) {
    set((draft) => {
      draft.alertPrefs = { ...draft.alertPrefs, ...patch };
    });
    const next = get().alertPrefs;
    writeAlertPreferences(next);
    if (!next.taskbarAttention) {
      void globalThis.window?.pilotDesktop?.setWindowAttention?.({ urgentCount: 0, kind: 'idle', bounce: false })?.catch?.(() => {});
    } else {
      const urgentCount = computeUrgentCount(get());
      void globalThis.window?.pilotDesktop?.setWindowAttention?.({
        urgentCount,
        kind: urgentCount > 0 ? 'attention' : get().ui.attentionKind,
        bounce: false
      })?.catch?.(() => {});
    }
  },
  setDesktopFocused(focused: boolean) {
    set((draft) => {
      draft.desktopFocused = focused;
    });
    if (focused) {
      const state = get();
      const urgentCount = computeUrgentCount(state);
      if (state.alertPrefs.taskbarAttention) {
        void globalThis.window?.pilotDesktop?.setWindowAttention?.({
          urgentCount,
          kind: urgentCount > 0 ? 'attention' : 'idle',
          bounce: false
        })?.catch?.(() => {});
      }
    }
  },
  syncPendingInteractions(snapshot?: PendingInteractionsSnapshot | null) {
    const approvals = Array.isArray(snapshot?.approvals) ? snapshot.approvals : [];
    const questions = Array.isArray(snapshot?.questions) ? snapshot.questions : [];
    const prevState = get();
    const prevApprovalIds = new Set((prevState.globalPendingApprovals || []).map((item) => item.callId));
    const prevQuestionIds = new Set((prevState.globalPendingQuestions || []).map((item) => item.callId));
    const nextApprovalIds = new Set(approvals.map((item) => item.callId));
    const nextQuestionIds = new Set(questions.map((item) => item.callId));

    const desktop = globalThis.window?.pilotDesktop;
    for (const id of prevApprovalIds) {
      if (!nextApprovalIds.has(id)) void desktop?.dismissFloatingAlert?.(`approval:${id}`)?.catch?.(() => {});
    }
    for (const id of prevQuestionIds) {
      if (!nextQuestionIds.has(id)) void desktop?.dismissFloatingAlert?.(`question:${id}`)?.catch?.(() => {});
    }

    set((draft) => {
      draft.globalPendingApprovals = approvals;
      draft.globalPendingQuestions = questions;
    });

    const after = get();
    const urgentCount = syncTabSignal(after, after.ui.attentionKind, after.sessionTitle);
    const prevCount = cachedUrgentCount;
    cachedUrgentCount = urgentCount;

    const wasInitialized = interactionsInitialized;
    interactionsInitialized = true;

    if (wasInitialized) {
      const { desktop: bridge, backgrounded, theme, onSelectSession, focusWindow } = alertEnv(after);
      for (const item of approvals) {
        if (!prevApprovalIds.has(item.callId)) {
          const risk = classifyApprovalRisk(item.toolName, item.argsSummary, item.structuredArgs);
          void dispatchAttentionAlert({
            eventKey: `approval:${item.callId}`,
            kind: 'approval',
            sessionId: item.sessionId,
            title: `${item.sessionTitle || '对话'} · 等待批准`,
            body: risk.summary || item.argsSummary || item.toolName,
            prefs: after.alertPrefs,
            backgrounded,
            notifyEnabled: after.notifyEnabled,
            theme,
            floatingAlert: buildApprovalFloatingAlert(item, theme),
            urgentCount,
            desktop: bridge,
            onSelectSession,
            focusWindow
          });
        }
      }
      for (const item of questions) {
        if (!prevQuestionIds.has(item.callId)) {
          void dispatchAttentionAlert({
            eventKey: `question:${item.callId}`,
            kind: 'question',
            sessionId: item.sessionId,
            title: `${item.sessionTitle || '对话'} · 需要选择`,
            body: item.question,
            prefs: after.alertPrefs,
            backgrounded,
            notifyEnabled: after.notifyEnabled,
            theme,
            floatingAlert: buildQuestionFloatingAlert(item, theme),
            urgentCount,
            desktop: bridge,
            onSelectSession,
            focusWindow
          });
        }
      }
    }

    if (urgentCount !== prevCount && after.alertPrefs.taskbarAttention && desktop?.setWindowAttention) {
      void desktop.setWindowAttention({
        urgentCount,
        kind: urgentCount > 0 ? 'attention' : 'idle',
        bounce: false
      })?.catch?.(() => {});
    }
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
    void get().refreshMemoryDraftCount();
    try {
      const response = await api.health();
      if (response && typeof (response as Response).ok === 'boolean' && !(response as Response).ok) {
        throw new Error(`HTTP ${(response as Response).status}`);
      }
      const health = response && typeof (response as Response).json === 'function'
        ? await (response as Response).json().catch(() => null) as { frontendStale?: boolean } | null
        : (response as { frontendStale?: boolean } | null);
      set((draft) => { draft.ui.connection = { status: 'ok', text: '已连接' }; });
      if (health?.frontendStale) {
        get().showNotice('界面资源不是最新构建：请运行 npm --prefix pilot-web/frontend run build 后刷新。');
      }
    } catch {
      set((draft) => { draft.ui.connection = { status: 'error', text: '无法连接后端' }; });
    }
    await Promise.all([get().loadProjects(), get().loadSettings()]);
    const sessions = await get().loadSessions();
    if (get().ui.syncError) {
      get().showNotice(`无法读取工作区数据：${get().ui.syncError}。请检查访问来源或点击右上角刷新重试。`);
    }
    if (!embeddedSessionId) {
      get().connectGlobalInteractions();
      const shortcut = get().alertPrefs.globalShortcut;
      if (shortcut && globalThis.window?.pilotDesktop?.setGlobalShortcut) {
        void globalThis.window.pilotDesktop.setGlobalShortcut(shortcut).then((res) => {
          if (res && !res.ok && res.error) get().showNotice(res.error);
        }).catch(() => {});
      }
      try {
        api.pendingInteractions?.().then((snap) => {
          if (snap) get().syncPendingInteractions(snap);
        }).catch(() => {});
      } catch { /* 测试替身未提供全局交互接口时跳过 */ }
    }
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

  ...createSessionActions({ set, get }, { closeStreams: streams.close,
    connectMirror: streams.connectMirror, connectCommandTasks: streams.connectCommandTasks }),

  ...history.actions,

  ...streams.actions,

  ...createInputActions({ set, get }),
  ...createRunControlActions({ set, get }),

  ...createRunStateActions({ set, get }),

  async compactContext(focus?: string) {
    const { sessionId, sessionEpoch } = get();
    const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch;
    if (!sessionId || get().compactingContext) return false;
    set((draft) => { draft.compactingContext = true; });
    try {
      await api.compactContext(sessionId, focus);
      if (current()) {
        await get().refreshContext();
        return true;
      }
      return false;
    } catch (error) {
      if (current()) get().showNotice('压缩失败：' + errorInfo(error).message);
      return false;
    } finally {
      if (current()) set((draft) => { draft.compactingContext = false; });
    }
  },

  /* ---------- 检查点 ---------- */
  async openCheckpoint(sequence) {
    const { sessionId } = get();
    if (!sessionId) return;
    set((draft) => { draft.ui.checkpointDialog = { sequence, loading: true }; });
    const pending = get().ui.checkpointDialog;
    try {
      const info = await api.checkpoints(sessionId, sequence);
      if (get().ui.checkpointDialog !== pending || get().sessionId !== sessionId) return;
      set((draft) => { draft.ui.checkpointDialog = { sequence, loading: false, info }; });
    } catch (error) {
      if (get().ui.checkpointDialog !== pending || get().sessionId !== sessionId) return;
      set((draft) => { draft.ui.checkpointDialog = null; });
      get().showNotice('读取检查点失败：' + errorInfo(error).message);
    }
  },
  async restoreCheckpoint(sequence, mode, revision) {
    const { sessionId, sessionEpoch } = get();
    const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch;
    if (!sessionId) return;
    if (!['code', 'conversation', 'both'].includes(mode)) {
      get().showNotice('请选择回退范围');
      return;
    }
    set((draft) => {
      draft.ui.checkpointDialog = { ...(draft.ui.checkpointDialog || {}), sequence, applying: true, error: '' };
    });
    const pending = get().ui.checkpointDialog;
    const ownsDialog = () => current() && get().ui.checkpointDialog === pending;
    try {
      const operationId = typeof globalThis.crypto?.getRandomValues === 'function'
        ? Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('') : undefined;
      const result = await api.restoreCheckpoint(sessionId, { sequence, mode, revision, operationId });
      if (!ownsDialog()) return;
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
      if (!await get().activate(sessionId)) return;
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
      if (!ownsDialog()) return;
      set((draft) => {
        draft.ui.checkpointDialog = { ...(draft.ui.checkpointDialog || {}), applying: false, error: errorInfo(error).message };
      });
    }
  },
  async forkCheckpoint(sequence, revision) {
    const { sessionId, sessionEpoch } = get();
    const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch;
    if (!sessionId) return;
    set((draft) => {
      draft.ui.checkpointDialog = { ...(draft.ui.checkpointDialog || {}), sequence, applying: true, error: '' };
    });
    const pending = get().ui.checkpointDialog;
    const ownsDialog = () => current() && get().ui.checkpointDialog === pending;
    try {
      const session = requireData(await api.forkCheckpoint(sessionId, { sequence, revision }));
      await get().loadSessions();
      if (!ownsDialog()) return;
      set((draft) => { draft.ui.checkpointDialog = null; });
      if (!await get().activate(session.sessionId)) return;
      if (session.input) {
        get().composerApi?.setText?.(session.input);
        get().composerApi?.focus?.();
      }
      get().showNotice('已创建对话分支，原聊天保留');
    } catch (error) {
      if (!ownsDialog()) return;
      set((draft) => {
        draft.ui.checkpointDialog = { ...(draft.ui.checkpointDialog || {}), applying: false, error: errorInfo(error).message };
      });
    }
  },

  ...createSideChatActions({ set, get }),

  /* ---------- 工作区 / worktree ---------- */
  async switchWorkspace(workspace, { throwOnError = false } = {}) {
    const { sessionId, sessionEpoch } = get();
    const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch;
    if (!sessionId) {
      set((draft) => {
        draft.workspace = workspace;
        draft.draftSession = { projectId: draft.draftSession?.projectId || null, ...draft.draftSession, workspace };
        draft.fileIndex = { items: [], sessionId: null, loading: false };
        resetFilePreview(draft);
      });
      void get().refreshMemoryDraftCount(workspace);
      return;
    }
    try {
      await api.switchWorkspace(sessionId, workspace);
      if (current()) await get().activate(sessionId);
    } catch (error) {
      if (current()) get().showNotice('切换文件夹失败：' + errorInfo(error).message);
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
    const current = () => get().sessionId === state.sessionId && get().sessionEpoch === state.sessionEpoch && get().workspace === state.workspace;
    try {
      const opened = await api.worktrees({ workspace: state.workspace, branch, projectId: state.currentProjectId });
      await get().loadSessions();
      if (!current()) return;
      if (opened?.sessionId) {
        if (!await get().activate(opened.sessionId)) return;
      }
      get().showNotice(`已从 ${opened?.branch || branch || '当前分支'} 建立 worktree，对话在这份检出里进行`);
    } catch (error) {
      if (current()) get().showNotice('建立 worktree 失败：' + errorInfo(error).message);
    }
  },

  /** 把 worktree 里的改动交回本地（旧版 handoffWorktree）。 */
  async handoffWorktree() {
    const { sessionId, sessionEpoch } = get();
    const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch;
    if (!sessionId || get().embeddedSessionId) {
      return;
    }
    try {
      const handed = await api.worktreeHandoff(sessionId);
      if (!current()) { await get().loadSessions(); return; }
      set((draft) => {
        draft.workspace = handed?.workspace || draft.workspace;
        const listed = draft.sessions.find((item) => item.id === sessionId);
        if (listed) {
          listed.workspace = draft.workspace;
        }
      });
      await get().loadSessions();
      if (current()) get().showNotice(handed?.note || 'worktree 改动已交回本地');
    } catch (error) {
      if (current()) get().showNotice('交回失败：' + errorInfo(error).message);
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

  ...createPanelActions({ set, get }),

  /** 通用二次确认：危险操作统一走这里，避免各处 window.confirm 风格不一。 */
  askConfirm(options) {
    set((draft) => { draft.ui.confirm = options; });
  },

  ...createModelSettingsActions({ set, get }),

  /**
   * 技能清单。
   *
   * <p>后端返回的是 {@code available}（外加 invalid 与两个安装根目录），早先这里读的是
   * {@code data.skills}，于是技能列表永远是空的、斜杠菜单里也一个技能都没有。
   */
  async loadSkills() {
    const { sessionId, sessionEpoch } = get();
    const sequence = ++skillLoadSequence;
    const current = () => sequence === skillLoadSequence && get().sessionId === sessionId && get().sessionEpoch === sessionEpoch;
    try {
      const data = await api.skills(sessionId || undefined);
      if (!current()) return;
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
      if (!current()) return;
      // 技能读取失败不能让斜杠菜单跟着空掉：内置指令始终可用
      set((draft) => {
        draft.quickCommands = mergeCommands(QUICK_COMMANDS, toSkillCommands(draft.skills || []));
      });
    }
  },

  async refreshMemoryDraftCount(targetWorkspace) {
    const workspace = (targetWorkspace ?? get().workspace ?? '').trim();
    const { sessionId, sessionEpoch } = get();
    const sequence = ++memoryDraftCountSequence;
    const current = () =>
      sequence === memoryDraftCountSequence
      && (targetWorkspace !== undefined || (get().sessionId === sessionId && get().sessionEpoch === sessionEpoch))
      && ((get().workspace || '').trim() === workspace);
    try {
      const data = await api.memoryDraftCount(workspace || undefined);
      if (!current()) return;
      const count = typeof data?.count === 'number' && data.count >= 0 ? data.count : 0;
      set((draft) => { draft.pendingMemoryDrafts = count; });
    } catch {
      // 计数刷新失败时不打断正常会话操作
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
  /**
   * 拉取当前会话的验证结论。
   *
   * <p>不抛错、不弹通知：这条带子只是常驻提示，网络抖动时保持上一次结论比刷一条错误更有用。
   */
  async loadValidation() {
    const { sessionId } = get();
    if (!sessionId) {
      set((draft) => { draft.validation = null; draft.hasRun = false; });
      return;
    }
    try {
      const data = await api.validation(sessionId);
      // 请求期间可能已经切了会话：晚到的结果不能写到别的会话上。
      if (get().sessionId !== sessionId) return;
      set((draft) => {
        draft.validation = data ?? null;
        draft.hasRun = draft.hasRun || Boolean(draft.runId || draft.lastRunId || draft.lastOutcome);
      });
    } catch {
      // 保留上一次结论
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


  };
}));

/* ---------- 模块级辅助 ---------- */

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
  document.documentElement.dataset.theme = resolveTheme(mode);
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

function computeUrgentCount(state: StoreState): number {
  const ids = new Set<string>();
  for (const id of Object.keys(state.pendingApprovals || {})) ids.add(`a:${id}`);
  for (const id of Object.keys(state.pendingQuestions || {})) ids.add(`q:${id}`);
  for (const id of Object.keys(state.pendingArbitrations || {})) ids.add(`r:${id}`);
  for (const item of state.globalPendingApprovals || []) ids.add(`a:${item.callId}`);
  for (const item of state.globalPendingQuestions || []) ids.add(`q:${item.callId}`);
  return ids.size;
}

function resolvedThemeFor(state: StoreState): 'light' | 'dark' {
  return resolveTheme(state.ui.themeMode) === 'dark' ? 'dark' : 'light';
}

function syncTabSignal(state: StoreState, kind: string, title: string): number {
  const urgentCount = computeUrgentCount(state);
  const effectiveKind = urgentCount > 0 ? 'attention' : kind;
  document.title = tabTitleWithCount(title || state.sessionTitle || '新任务', effectiveKind, urgentCount);
  const link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (link) {
    link.href = faviconHref(effectiveKind);
  }
  return urgentCount;
}

function alertEnv(state: StoreState) {
  const desktop = globalThis.window?.pilotDesktop;
  const backgrounded = isWindowBackgrounded(globalThis.document, state.desktopFocused);
  const theme = resolvedThemeFor(state);
  const onSelectSession = (targetSessionId: string) => {
    if (targetSessionId && useStore.getState().sessionId !== targetSessionId) {
      void useStore.getState().activate(targetSessionId);
    }
  };
  const focusWindow = () => {
    void desktop?.focusMainWindow?.()?.catch?.(() => {});
    try { globalThis.window?.focus?.(); } catch { /* 浏览器标签聚焦受策略限制 */ }
  };
  return { desktop, backgrounded, theme, onSelectSession, focusWindow };
}

function handleRunAttention(payload: RunAttentionPayload) {
  const state = useStore.getState();
  const kind = payload.kind === 'error' ? 'error' : payload.kind === 'done' ? 'done' : 'attention';
  const sid = String(payload.sessionId || '');
  const title = payload.title || (sid === state.sessionId ? state.sessionTitle : '') || '对话';
  const urgentCount = computeUrgentCount(state);
  const { desktop, backgrounded, theme, onSelectSession, focusWindow } = alertEnv(state);
  const floatingAlert = kind === 'done' || kind === 'error'
    ? buildRunFloatingAlert(kind, sid, title, payload.summary || '', theme)
    : null;
  void dispatchAttentionAlert({
    eventKey: `run:${payload.runId || sid || 'global'}:${kind}`,
    kind,
    sessionId: sid,
    title,
    body: payload.summary || ({ done: '本轮任务已完成', error: '本轮运行失败', attention: '需要你确认' }[kind]),
    prefs: state.alertPrefs,
    backgrounded,
    notifyEnabled: state.notifyEnabled,
    theme,
    floatingAlert,
    urgentCount,
    desktop,
    onSelectSession,
    focusWindow
  });
  if (sid && sid !== state.sessionId) {
    if (kind === 'done' && !backgrounded) {
      state.showNotice(`「${title}」已完成`, {
        label: '查看结果',
        onAction: () => { void useStore.getState().activate(sid); }
      });
    }
    void state.loadSessions();
  }
}

function applyAttention(
  kind: string,
  title: string,
  applied: EventEnvelope[] = [],
  beforeQuestions: Record<string, PendingQuestion> = {}
) {
  const state = useStore.getState();
  const urgentCount = syncTabSignal(state, kind, title);
  const { desktop, backgrounded, theme, onSelectSession, focusWindow } = alertEnv(state);

  let dispatchedFromEvents = false;
  for (const item of applied) {
    if (item.type === 'approval.resolved' && item.payload?.callId) {
      void desktop?.dismissFloatingAlert?.(`approval:${item.payload.callId}`)?.catch?.(() => {});
    } else if (item.type === 'tool.completed' && item.payload?.callId && beforeQuestions[item.payload.callId]) {
      void desktop?.dismissFloatingAlert?.(`question:${item.payload.callId}`)?.catch?.(() => {});
    } else if (item.type === 'approval.required' && item.payload?.callId) {
      dispatchedFromEvents = true;
      const callId = item.payload.callId;
      const entry = state.pendingApprovals[callId];
      const toolName = String(item.payload.name || entry?.name || '');
      const argsSummary = String(item.payload.argsSummary || entry?.args || '');
      const structuredArgs = (item.payload.args || entry?.structuredArgs || null) as Record<string, unknown> | null;
      const risk = classifyApprovalRisk(toolName, argsSummary, structuredArgs);
      const floating: GlobalPendingApproval = {
        callId,
        sessionId: item.sessionId || state.sessionId || '',
        sessionTitle: title || state.sessionTitle || '对话',
        toolName,
        argsSummary,
        structuredArgs,
        requestedAt: new Date(entry?.requestedAt || Date.now()).toISOString(),
        expiresAt: entry?.expiresAt ? new Date(entry.expiresAt).toISOString() : null
      };
      void dispatchAttentionAlert({
        eventKey: `approval:${callId}`,
        kind: 'approval',
        sessionId: floating.sessionId,
        title: `${floating.sessionTitle} · 等待批准`,
        body: risk.summary || argsSummary || toolName,
        prefs: state.alertPrefs,
        backgrounded,
        notifyEnabled: state.notifyEnabled,
        theme,
        floatingAlert: buildApprovalFloatingAlert(floating, theme),
        urgentCount,
        desktop,
        onSelectSession,
        focusWindow
      });
    } else if (item.type === 'tool.started' && item.payload?.name === 'ask_user' && item.payload?.callId) {
      dispatchedFromEvents = true;
      const callId = item.payload.callId;
      const entry = state.pendingQuestions?.[callId];
      const ask = parseAsk(item.payload.summary);
      if (entry || ask) {
        const floating: GlobalPendingQuestion = {
          callId,
          sessionId: item.sessionId || state.sessionId || '',
          sessionTitle: title || state.sessionTitle || '对话',
          question: entry?.question || ask?.question || '请做出选择',
          options: entry?.options || ask?.options || [],
          requestedAt: new Date(entry?.requestedAt || Date.now()).toISOString(),
          expiresAt: entry?.expiresAt ? new Date(entry.expiresAt).toISOString() : null
        };
        void dispatchAttentionAlert({
          eventKey: `question:${callId}`,
          kind: 'question',
          sessionId: floating.sessionId,
          title: `${floating.sessionTitle} · 需要选择`,
          body: floating.question,
          prefs: state.alertPrefs,
          backgrounded,
          notifyEnabled: state.notifyEnabled,
          theme,
          floatingAlert: buildQuestionFloatingAlert(floating, theme),
          urgentCount,
          desktop,
          onSelectSession,
          focusWindow
        });
      }
    } else if (item.type === 'arbitration.required') {
      dispatchedFromEvents = true;
      const arbId = String(item.payload?.arbitrationId || item.sequence || 'arb');
      void dispatchAttentionAlert({
        eventKey: `arb:${arbId}`,
        kind: 'attention',
        sessionId: item.sessionId || state.sessionId || '',
        title: `${title || state.sessionTitle || '对话'} · 等待裁决`,
        body: String(item.payload?.title || item.payload?.summary || '多代理产出需要裁决'),
        prefs: state.alertPrefs,
        backgrounded,
        notifyEnabled: state.notifyEnabled,
        theme,
        urgentCount,
        desktop,
        onSelectSession,
        focusWindow
      });
    }
  }

  if (kind === 'done' || kind === 'error') {
    const runEnd = applied.find((item) => item.type === 'run.completed' || item.type === 'run.failed' || item.type === 'run.ended');
    const latestResult = [...state.thread.rows].reverse().find((row) => row.type === 'result');
    const sid = runEnd?.sessionId || state.sessionId || '';
    const sessionName = title || state.sessionTitle || '对话';
    const summary = (latestResult?.type === 'result' ? latestResult.detail || latestResult.title : '')
      || (kind === 'done' ? '本轮任务已完成' : '本轮运行失败');
    void dispatchAttentionAlert({
      eventKey: `run:${runEnd?.runId || sid || 'active'}:${kind}`,
      kind,
      sessionId: sid,
      title: sessionName,
      body: summary,
      prefs: state.alertPrefs,
      backgrounded,
      notifyEnabled: state.notifyEnabled,
      theme,
      floatingAlert: buildRunFloatingAlert(kind, sid, sessionName, summary, theme),
      urgentCount,
      desktop,
      onSelectSession,
      focusWindow
    });
  } else if (kind === 'attention' && !dispatchedFromEvents) {
    const sid = state.sessionId || 'active';
    void dispatchAttentionAlert({
      eventKey: `attention:${sid}:${title || ''}`,
      kind: 'attention',
      sessionId: state.sessionId || '',
      title: title || '新任务',
      body: '任务需要确认',
      prefs: state.alertPrefs,
      backgrounded,
      notifyEnabled: state.notifyEnabled,
      theme,
      urgentCount,
      desktop,
      onSelectSession,
      focusWindow
    });
  } else if ((kind === 'idle' || kind === 'running') && urgentCount === 0
    && state.alertPrefs.taskbarAttention && desktop?.setWindowAttention) {
    void desktop.setWindowAttention({ urgentCount: 0, kind, bounce: false })?.catch?.(() => {});
  }
}

/** 供页面卸载和隔离测试主动关闭当前 store 的连接。 */
export function closeStreams() { stopStreams(); }
