import type { StoreContext, StoreSlice } from './context.js';
import type { StoreState } from '../types/store.js';
import type { ApiResults } from '../types/api.js';
import { errorInfo } from '../lib/errors.js';
import { api } from '../api.js';
import { normalizePermission, permissionLabel } from '../lib/permission.js';
import { describeModelSaved } from '../lib/model-settings.js';

let settingsLoadSequence = 0;
function applySettings(draft: StoreState, data: ApiResults['settings'] | null) {
  draft.settingsData = data;
  if (data?.permissionMode) draft.permissionMode = normalizePermission(data.permissionMode);
  draft.modelOptions = Array.isArray(data?.modelOptions) ? data.modelOptions : [];
}

export function createModelSettingsActions({ set, get }: StoreContext): StoreSlice<'loadSettings' | 'savePermissionMode' | 'clearModelKey' | 'resetModelFeedback' | 'saveModel' | 'probeModels'> {
  return {
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
  };
}
