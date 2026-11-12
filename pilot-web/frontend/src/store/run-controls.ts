import type { StoreContext, StoreSlice } from './context.js';
import { api } from '../api.js';
import { errorInfo } from '../lib/errors.js';
import { submissions, type SubmissionOperation } from '../lib/submissions.js';

/** 运行入口统一沿用提交回执；控制请求只更新发起时的会话与运行。 */
export function createRunControlActions({ set, get }: StoreContext): StoreSlice<'steer' | 'pauseRun' | 'cancelRun' | 'continueRun' | 'resendMessage' | 'regenerate'> {
  const action = async (sessionId: string, content: string, operation: SubmissionOperation) => {
    const epoch = get().sessionEpoch;
    const result = await submissions.submit(sessionId, { content }, () => get().sessionId === sessionId && get().sessionEpoch === epoch && get().modelReady(), operation);
    if (result.state !== 'accepted') get().showNotice(result.message || '提交已取消，当前内容已保留。');
    return result.state === 'accepted';
  };
  return {
    async steer(text, attachments) {
      if (attachments?.length) {
        get().showNotice('请将带附件的消息加入队列，再点击该消息的‘引导’。');
        return false;
      }
      const { sessionId, runId, sessionEpoch } = get();
      if (!sessionId) return false;
      set(draft => { draft.steering = true; });
      try {
        return await action(sessionId, text, async requestId => {
          const response = await api.steer(sessionId, { content: text, runId }, requestId);
          return response ? { sessionId, runId: runId || '' } : null;
        });
      } finally {
        if (get().sessionId === sessionId && get().sessionEpoch === sessionEpoch) set(draft => { draft.steering = false; });
      }
    },

    async pauseRun() {
      const { runId, sessionId, sessionEpoch } = get();
      if (!runId) {
        return;
      }
      const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch && get().runId === runId;
      if (get().pausing) return;
      set((draft) => { draft.pausing = true; });
      try {
        await api.pauseRun(runId);
      } catch (error) {
        if (current()) get().showNotice('暂停失败：' + errorInfo(error).message);
      } finally {
        if (current()) set((draft) => { draft.pausing = false; });
      }
    },

    async cancelRun() {
      const { runId, sessionId, sessionEpoch } = get();
      if (!runId) {
        return;
      }
      const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch && get().runId === runId;
      if (get().cancelling) return;
      set((draft) => { draft.cancelling = true; });
      try {
        await api.cancelRun(runId);
      } catch (error) {
        if (current()) get().showNotice('取消失败：' + errorInfo(error).message);
      } finally {
        if (current()) set((draft) => { draft.cancelling = false; });
      }
    },

    async continueRun() {
      if (!get().modelReady()) return false;
      const { sessionId } = get();
      if (!sessionId) return false;
      if (!await action(sessionId, '继续运行当前任务', requestId => api.continueRun(sessionId, requestId))) return false;
    },

    async resendMessage(sequence, text, attachments) {
      if (!get().modelReady()) return false;
      if (attachments?.length) {
        get().showNotice('编辑历史消息暂不支持新增附件。附件已保留，可取消编辑后作为新消息发送。');
        return false;
      }
      const { sessionId } = get();
      if (!sessionId) return false;
      return action(sessionId, text, requestId => api.resend(sessionId, { sequence, content: text }, requestId));
    },

    async regenerate() {
      const { sessionId, running, sessionEpoch } = get();
      if (!sessionId || running || !get().modelReady()) return;
      if (await action(sessionId, '重新生成上一轮回复', requestId => api.regenerate(sessionId, requestId)) && get().sessionId === sessionId && get().sessionEpoch === sessionEpoch) {
        set(draft => { draft.running = true; draft.runStartedAt = Date.now(); draft.lastOutcome = ''; });
      }
    },

  };
}
