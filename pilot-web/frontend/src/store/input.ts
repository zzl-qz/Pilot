import type { StoreContext, StoreSlice } from './context.js';
import { attachmentBatchError, requireAttachmentLimits } from '../lib/attachments.js';
import type { InputMessage } from '../types/api.js';
import { errorInfo } from '../lib/errors.js';
import { submissions, type SubmissionOperation } from '../lib/submissions.js';
import { api } from '../api.js';
import { appendUserRow } from '../thread-reducer.js';
import { rememberInput } from '../lib/input-history.js';
import { dropDraft, readDraft } from '../lib/drafts.js';
import { resolveSkillCommand, toSkillCommands } from '../lib/skills.js';
import { sameUserInput } from '../thread-utils.js';

export function createInputActions({ set, get }: StoreContext): StoreSlice<'registerComposer' | 'modelReady' | 'submitInput' | 'steer' | 'pauseRun' | 'cancelRun' | 'continueRun' | 'resendMessage' | 'composerApi' | 'regenerate'> {
  const action = async (sessionId: string, content: string, operation: SubmissionOperation) => {
    const epoch = get().sessionEpoch;
    const result = await submissions.submit(sessionId, { content }, () => get().sessionId === sessionId && get().sessionEpoch === epoch && get().modelReady(), operation);
    if (result.state !== 'accepted') get().showNotice(result.message || '提交已取消，当前内容已保留。');
    return result.state === 'accepted';
  };
  return {
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
      const ownerEpoch = get().sessionEpoch;
      let attachmentBudget = get().settingsData?.attachmentLimits;
      if (attachments?.length) {
        try {
          const settings = get().settingsData || await api.settings();
          attachmentBudget = requireAttachmentLimits(settings?.attachmentLimits);
          const error = attachmentBatchError(attachments, attachmentBudget);
          if (error) throw new Error(error);
        } catch (error) {
          get().showNotice('无法发送附件：' + errorInfo(error).message);
          return false;
        }
      }
      if (get().sessionEpoch !== ownerEpoch) return false;
      // 草稿态：第一条消息触发创建会话，标题由服务端统一生成。
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
      const requestBudget = attachmentBudget?.maxRequestBytes;
      if (requestBudget && new TextEncoder().encode(JSON.stringify(body)).length > requestBudget) {
        get().showNotice('消息正文和附件合计超过请求预算，请减少内容后重试。');
        return false;
      }
      const pendingKey = `pending:${Date.now()}:${Math.random()}`;
      let queueInstead = get().running;
      const result = await submissions.submit(sessionId, body, () => {
        if (get().sessionEpoch !== ownerEpoch || get().sessionId !== sessionId || !get().modelReady()) return false;
        queueInstead = get().running;
        const remembered = rememberInput(localStorage, sessionId, text);
        if (!queueInstead) {
          set((draft) => {
            draft.pendingInputs.push(payloadContent);
            appendUserRow(draft, payloadContent, null, { attachments, imagePositions, pendingKey });
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
        return true;
      });
      if (result.state === 'accepted') {
        // 接收结果已确定；列表刷新失败不能把已执行的任务重新标成发送失败。
        void get().loadSessions().catch(() => get().showNotice('消息已接收，会话列表刷新失败，请稍后刷新。'));
        if (queueInstead && get().sessionId === sessionId && get().sessionEpoch === ownerEpoch) {
          void get().loadQueue().catch(() => get().showNotice('消息已接收，队列刷新失败，请稍后刷新。'));
        }
        if (get().sessionId === sessionId && get().sessionEpoch === ownerEpoch && readDraft(localStorage, sessionId) === text) {
          dropDraft(localStorage, sessionId);
        }
        return true;
      }
      if (result.state === 'rejected' && get().sessionId === sessionId && get().sessionEpoch === ownerEpoch) set((draft) => {
        const index = draft.thread.rows.findIndex(row => row.key === pendingKey && (!('sequence' in row) || row.sequence == null));
        if (index >= 0) {
          draft.thread.rows.splice(index, 1);
          const pending = draft.pendingInputs.findIndex(item => sameUserInput(item, payloadContent));
          if (pending >= 0) draft.pendingInputs.splice(pending, 1);
        }
      });
      if (result.message) get().showNotice(result.state === 'rejected' ? '发送失败：' + result.message : result.message);
      return false;
    },

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
