import { createSessionStreams } from './store/streams.js';
import { unrefTimer } from './store/context.js';
import { createSessionActions, emptyMirror, resetFilePreview } from './store/sessions.js';
import { createHistoryController } from './store/history.js';
import { createModelSettingsActions } from './store/model-settings.js';
import { createPanelActions } from './store/panels.js';
import { createSideChatActions } from './store/side-chats.js';
import { createInputActions } from './store/input.js';
import { requireData } from './store/shared.js';
import type { StoreState, UiState } from './types/store.js';
import type { PlanRow } from './types/thread.js';
import type * as S from './types/server.js';
import { errorInfo } from './lib/errors.js';
import { resolveTheme } from './lib/theme.js';
import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { api, apiFetch } from './api.js';
import { initialThread } from './thread-reducer.js';
import { faviconHref, shouldNotify, tabTitle } from './lib/attention.js';
import { mergeCommands, toSkillCommands } from './lib/skills.js';
import { QUICK_COMMANDS } from './lib/commands.js';
import { readSideChats, readEmbeddedSession } from './lib/side-chat.js';
import { normalizeQueue } from './lib/queue.js';
import { createWorkbenchRefresher, saveWorkbenchView, takeWorkbenchView } from './lib/workbench-sync.js';

/**
 * 全局状态：zustand + immer。
 *
 * <p>字段与旧版 state 对象一一对应；动作函数替代旧版的直接 DOM 操纵。
 * 会话、历史、事件流等各自拥有异步请求和清理逻辑，这里负责组合。
 */

let stopStreams: () => void = () => {};
let noticeTimer: ReturnType<typeof setTimeout> | undefined;
let queueLoadSequence = 0;
let skillLoadSequence = 0;
/** 桌面通知开关（与旧版同名键）。 */
const NOTIFY_KEY = 'pilot.notify';

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
  workbenchFileId: null,
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
  const streams = createSessionStreams({ set, get }, history.reset, applyAttention);
  stopStreams = streams.close;
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
  taskDraftRevision: 0,
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

  ...createSessionActions({ set, get }, { closeStreams: streams.close,
    connectMirror: streams.connectMirror, connectCommandTasks: streams.connectCommandTasks }),

  ...history.actions,

  ...streams.actions,

  ...createInputActions({ set, get }),

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
    const { sessionId, sessionEpoch, taskDraftRevision, taskBusy } = get();
    if (!sessionId || taskBusy) return;
    const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch;
    set((draft) => { draft.taskBusy = true; });
    try {
      const task = await api.saveTask(sessionId, { constraints, acceptance });
      if (!current()) return;
      set((draft) => {
        // 保存期间用户仍可继续输入，旧请求不能覆盖新草稿或清掉未保存标记。
        if (draft.taskDraftRevision === taskDraftRevision) {
          draft.task = task;
          draft.taskDirty = false;
        }
      });
      get().showNotice(get().taskDirty ? '已保存提交时的任务契约，后续修改尚未保存' : '任务契约已保存');
    } catch (error) {
      if (current()) get().showNotice('保存失败：' + errorInfo(error).message);
    } finally {
      if (current()) set((draft) => { draft.taskBusy = false; });
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
      const operationId = typeof globalThis.crypto?.getRandomValues === 'function'
        ? Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('') : undefined;
      const result = await api.restoreCheckpoint(sessionId, { sequence, mode, revision, operationId });
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

  ...createSideChatActions({ set, get }),

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

/** 供页面卸载和隔离测试主动关闭当前 store 的连接。 */
export function closeStreams() { stopStreams(); }
