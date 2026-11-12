import type { StoreContext, StoreSlice } from './context.js';
import type { StoreState, MirrorState } from '../types/store.js';
import { api } from '../api.js';
import { initialThread } from '../thread-reducer.js';
import { errorInfo } from '../lib/errors.js';
import { loadInputHistory } from '../lib/input-history.js';
import { readDraft, writeDraft, dropDraft } from '../lib/drafts.js';
import { normalizePermission } from '../lib/permission.js';
import { sortSessions } from '../lib/projects.js';
import { sideChatsFor, writeSideChats } from '../lib/side-chat.js';

interface SessionConnections {
  closeStreams: () => void;
  connectMirror: () => void;
  connectCommandTasks: () => void;
}

/** 会话切换时重置浏览器状态，默认按无窗口模式提供替代操作。 */
export function emptyMirror(): MirrorState {
  return { mode: 'idle', url: '', headless: true };
}
/**
 * 清空当前会话的全部界面状态：切换会话与「开新对话」共用同一套重置。
 * 放在模块级是为了让 startNewSession / activate 不会各写一份、漏字段。
 */
export function resetFilePreview(draft: StoreState) {
  draft._previewRequest = (draft._previewRequest || 0) + 1;
  draft._previewData = null;
  draft._previewLoading = false;
  draft.ui.previewPath = '';
}

function resetSessionState(draft: StoreState) {
  draft.historyRevision = null;
  draft._reconnectAttempt = 0;
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
  draft.taskBusy = false;
  draft.taskDraftRevision = 0;
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
  draft.ui.reviewRunId = null;
  draft.changeReport = null;
  draft.changesState = '';
  draft.runChanges = null;
  draft.commandTasks = [];
  draft.commandDetail = null;
  draft.commandLog = null;
  draft.commandLogTaskId = null;
}

/** 会话生命周期拥有列表请求和创建请求；连接由调用方提供，避免反向依赖全局 store。 */
export function createSessionActions({ set, get }: StoreContext, { closeStreams, connectMirror, connectCommandTasks }: SessionConnections): StoreSlice<'loadProjects' | 'loadSessions' | 'startNewSession' | 'ensureSession' | 'activate' | 'renameSession' | 'updateSessionPreferences' | 'archiveSession' | 'deleteSession'> {
  let activationSequence = 0;
  let sessionCreation: { epoch: number; promise: Promise<string> } | null = null;
  let projectLoadSequence = 0;
  let sessionLoadSequence = 0;
  return {
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
      if (sessionCreation?.epoch === sessionEpoch) return sessionCreation.promise;
      const promise = (async () => {
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
        connectMirror();
        connectCommandTasks();
        get().loadPlan();
        get().loadQueue();
        get().loadTask();
        get().refreshContext();
        get().refreshActiveRun();
        return id;
      })();
      sessionCreation = { epoch: sessionEpoch, promise };
      try { return await promise; }
      finally { if (sessionCreation?.promise === promise) sessionCreation = null; }
    },

    async activate(sessionId) {
      const activation = ++activationSequence;
      const current = () => activation === activationSequence && get().sessionId === sessionId;
      closeStreams();
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
      if (!await get().loadHistory()) return false;
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
      connectMirror();
      connectCommandTasks();
      get().refreshActiveRun();
      return true;
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
          writeSideChats(get().sideChats);
          orphans.forEach((item) => { api.deleteSession(item.id).catch(() => {}); });
        }
        get().showNotice('已删除对话');
        const list = await get().loadSessions();
        if (get().sessionId !== id) {
          return true;
        }
        const next = list.find(session => session.id !== id && !session.archived && !session.isBlank)
          || list.find(session => session.id !== id && !session.archived);
        if (next) {
          await get().activate(next.id);
        } else {
          // 删的是最后一个对话：回到「新任务」，别继续对着不存在的会话轮询
          get().startNewSession();
        }
        return true;
      } catch (error) {
        get().showNotice('删除失败：' + errorInfo(error).message);
        return false;
      }
    },
  };
}
