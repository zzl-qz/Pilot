import type { StoreContext, StoreSlice } from './context.js';
import type { PlanRow } from '../types/thread.js';
import { api } from '../api.js';
import { errorInfo } from '../lib/errors.js';
import { normalizeQueue } from '../lib/queue.js';

/** 运行面板的数据同步：计划、队列、任务契约和上下文各自保留现有响应归属检查。 */
export function createRunStateActions({ set, get }: StoreContext): StoreSlice<'resolveApproval' | 'resolveArbitration' | 'loadPlan' | 'loadQueue' | 'mutateQueue' | 'steerQueueItem' | 'saveQueueItem' | 'removeQueueItem' | 'moveQueueItem' | 'loadTask' | 'saveTask' | 'refreshContext' | 'refreshActiveRun'> {
  let queueLoadSequence = 0;
  return {
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
}
