import type { StoreContext, StoreSlice } from './context.js';
import type { SideChatTab } from '../types/content.js';
import { errorInfo } from '../lib/errors.js';
import { api } from '../api.js';
import { writeSideChats, sideChatsFor, sideChatTitle } from '../lib/side-chat.js';

/** 同一主会话创建与关闭侧边对话时共用在途请求。 */
const sideChatPending = new Map<string, Promise<SideChatTab | null>>();
const sideChatClosing = new Map<string, Promise<boolean>>();
export function createSideChatActions({ set, get }: StoreContext): StoreSlice<'currentProject' | 'createSideChat' | 'selectSideChat' | 'closeSideChat' | 'setSideDraft' | 'toggleSideChatPane' | 'askSideChat' | 'takeSideQuote'> {
  return {
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
        writeSideChats(get().sideChats);
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
          writeSideChats(get().sideChats);
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
      writeSideChats(get().sideChats);
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
          writeSideChats(get().sideChats);
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

  };
}
