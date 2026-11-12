import type { StoreContext, StoreSlice } from './context.js';
import { attachmentBatchError, requireAttachmentLimits } from '../lib/attachments.js';
import type { InputMessage } from '../types/api.js';
import { errorInfo } from '../lib/errors.js';
import { submissions } from '../lib/submissions.js';
import { api } from '../api.js';
import { appendUserRow } from '../thread-reducer.js';
import { rememberInput } from '../lib/input-history.js';
import { dropDraft, readDraft } from '../lib/drafts.js';
import { resolveSkillCommand, toSkillCommands } from '../lib/skills.js';
import { sameUserInput } from '../thread-utils.js';

export function createInputActions({ set, get }: StoreContext): StoreSlice<'registerComposer' | 'modelReady' | 'submitInput' | 'composerApi'> {
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


  };
}
