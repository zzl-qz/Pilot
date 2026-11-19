import type { StoreContext, StoreSlice } from './context.js';
import { planInOpenSegment } from '../thread-reducer.js';
import { api } from '../api.js';
import { writeDraft } from '../lib/drafts.js';
import { errorInfo } from '../lib/errors.js';
import { fetchQueuedDraft, locateQueueTarget, normalizeQueue, queueEntryKey } from '../lib/queue.js';

/** 运行面板的数据同步：计划、队列、任务契约和上下文各自保留现有响应归属检查。 */
export function createRunStateActions({ set, get }: StoreContext): StoreSlice<'resolveApproval' | 'resolveApprovalWithTrust' | 'resolveArbitration' | 'loadPlan' | 'loadQueue' | 'mutateQueue' | 'setQueueHeld' | 'takeBackQueueItem' | 'steerQueueItem' | 'saveQueueItem' | 'removeQueueItem' | 'moveQueueItem' | 'loadTask' | 'saveTask' | 'refreshContext' | 'refreshActiveRun'> {
  let queueLoadSequence = 0;
  let queueHoldSequence = 0;
  let activeRunSequence = 0;
  return {
    /* ---------- 审批 / 仲裁 ---------- */
    async resolveApproval(callId, approved) {
      const { sessionId, sessionEpoch, pendingApprovals } = get();
      if (!pendingApprovals[callId] || pendingApprovals[callId].busy) return;
      const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch;
      set((draft) => { draft.pendingApprovals[callId].busy = true; });
      try {
        await api.approval(callId, approved);
      } catch (error) {
        if (!current()) return;
        get().showNotice('提交审批失败：' + errorInfo(error).message);
        set((draft) => { if (draft.pendingApprovals[callId]) draft.pendingApprovals[callId].busy = false; });
      }
    },

    /**
     * 「批准并信任」/「始终允许此工具」：先批准本次调用，再写信任。
     *
     * <p>批准失败就不写信任；批准成功但信任失败时，本次调用照常执行，只提示信任没有保存，
     * 不会出现“提示信任未生效、实际已落库”的误导。
     */
    async resolveApprovalWithTrust(callId, approved, trust) {
      const { sessionId, sessionEpoch, pendingApprovals } = get();
      const entry = pendingApprovals[callId];
      if (!sessionId || !entry || entry.busy) return;
      const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch;
      set((draft) => { draft.pendingApprovals[callId].busy = true; });
      try {
        await api.approval(callId, approved);
      } catch (error) {
        if (!current()) return;
        get().showNotice('提交审批失败：' + errorInfo(error).message);
        set((draft) => { if (draft.pendingApprovals[callId]) draft.pendingApprovals[callId].busy = false; });
        return;
      }
      if (!current()) return;
      try {
        await api.mcpTrust(sessionId, trust);
        if (current()) {
          get().showNotice('已批准本次调用，并记住该 MCP 信任');
          void get().loadCapabilities();
        }
      } catch (error) {
        if (!current()) return;
        get().showNotice('已批准本次调用，但信任没有保存：' + errorInfo(error).message);
        void get().loadCapabilities();
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
            const existing = planInOpenSegment(draft.thread.rows);
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
          draft.queueHeld = Boolean(data?.held);
          draft.queueWaitingReason = data?.waitingReason || '';
        });
      } catch (error) { if (strict) throw error; /* 队列读取失败不阻塞 */ }
    },

    /** 暂停/恢复队列：服务端持久化，切换会话或重开后仍保持。 */
    async setQueueHeld(held) {
      const { sessionId, sessionEpoch } = get();
      if (!sessionId || get().queueHolding) return false;
      const request = ++queueHoldSequence;
      set((draft) => { draft.queueHolding = true; });
      try {
        const data = await api.holdQueue(sessionId, held);
        if (get().sessionId !== sessionId || get().sessionEpoch !== sessionEpoch || request !== queueHoldSequence) return false;
        const normalized = normalizeQueue(data);
        set((draft) => {
          draft.queueEntries = normalized.rows;
          draft.queueRevision = data?.revision || null;
          draft.queueHeld = Boolean(data?.held);
          draft.queueWaitingReason = data?.waitingReason || '';
        });
        return true;
      } catch (error) {
        if (get().sessionId === sessionId && get().sessionEpoch === sessionEpoch) {
          get().showNotice('切换队列状态失败：' + errorInfo(error).message);
        }
        return false;
      } finally {
        if (get().sessionId === sessionId && get().sessionEpoch === sessionEpoch) set((draft) => { draft.queueHolding = false; });
      }
    },

    /**
     * 取回队列项：先读出正文与附件，再按最新版本移出队列，最后放回输入框。
     * 任何一步失败都保留队列与原文，不会出现「队列删了、草稿又没拿到」。
     */
    async takeBackQueueItem(index) {
      const { sessionId, sessionEpoch, queueEntries } = get();
      if (!sessionId || get().queueTakingBack) return false;
      const entry = queueEntries[index];
      if (!entry) return false;
      const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch;
      set((draft) => { draft.queueTakingBack = true; });
      try {
        let restored;
        try {
          restored = await fetchQueuedDraft(sessionId, entry);
        } catch (error) {
          if (current()) get().showNotice('取回排队消息失败：' + errorInfo(error).message + '；消息仍在队列中。');
          return false;
        }
        if (!current()) return false;
        // 队列可能已被另一窗口或运行取走：按内容指纹重新定位，不用旧下标
        await get().loadQueue();
        if (!current()) return false;
        const key = queueEntryKey(entry);
        const at = get().queueEntries.findIndex(item => queueEntryKey(item) === key);
        if (at < 0) {
          get().showNotice('这条排队消息已经开始或已被移除，未取回；请核对队列与运行历史。');
          return false;
        }
        const removed = await get().mutateQueue((id, revision) => api.deleteQueueItem(id, at, revision),
          '取回排队消息失败：');
        if (!removed || !current()) return false;
        const bridge = get().composerApi;
        if (!bridge) {
          get().showNotice('输入框暂不可用，排队消息已移除；请检查草稿或历史。');
          return false;
        }
        const existing = bridge.getText();
        const text = restored.text.trim();
        if (text) {
          bridge.setText(existing.trim() ? existing.replace(/\s+$/, '') + '\n' + text : text);
        }
        if (restored.files.length) bridge.insertAttachments?.(restored.files);
        writeDraft(localStorage, sessionId, bridge.getText());
        get().showNotice('已放回输入框' + (restored.files.length ? '（含 ' + restored.files.length + ' 个附件）' : '') + '，可继续编辑后发送。');
        return true;
      } finally {
        if (current()) set((draft) => { draft.queueTakingBack = false; });
      }
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
      return get().mutateQueue((id, revision) => api.steerQueueItem(id, index, { runId, revision }), '插到当前这轮失败：');
    },
    async saveQueueItem(target, text, requirements) {
      if (!text?.trim()) { get().showNotice('排队消息不能为空'); return false; }
      const { sessionId, sessionEpoch } = get();
      if (!sessionId) return false;
      // 先取最新队列：编辑期间队首可能被自动取走、另一端可能删除或移动
      await get().loadQueue();
      if (get().sessionId !== sessionId || get().sessionEpoch !== sessionEpoch) return false;
      const index = locateQueueTarget(get().queueEntries, target);
      if (index < 0) {
        get().showNotice('原来的排队消息已被发送、删除或修改，本次没有保存。输入仍保留，可复制后重新选择目标。');
        return false;
      }
      return get().mutateQueue((id, revision) => api.updateQueueItem(id, index, {
        content: text, revision, constraints: requirements?.constraints, acceptance: requirements?.acceptance
      }), '修改排队消息失败：');
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
      const { sessionId, sessionEpoch, thread, running, runId } = get();
      if (!sessionId) {
        return;
      }
      const request = ++activeRunSequence;
      const cursor = thread.cursor;
      try {
        const active = await api.activeRun(sessionId);
        const current = get();
        // 查询开始后的正式事件或新操作拥有更新的状态，旧快照不能复活已结束的运行。
        if (request !== activeRunSequence || current.sessionId !== sessionId || current.sessionEpoch !== sessionEpoch
            || current.thread.cursor !== cursor || current.running !== running || current.runId !== runId) return;
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
