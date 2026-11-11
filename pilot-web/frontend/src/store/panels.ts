import type { StoreContext, StoreSlice } from './context.js';
import { requireData } from './shared.js';
import type { CommandTask } from '../types/store.js';
import type * as S from '../types/server.js';
import { errorInfo } from '../lib/errors.js';
import { api, apiJson } from '../api.js';
import { samePath } from '../lib/projects.js';
import { mergeCommandLog } from '../lib/command-tasks.js';

export function createPanelActions({ set, get }: StoreContext): StoreSlice<'loadChanges' | 'restoreChanges' | 'rollbackRun' | 'createBranch' | 'openPreview' | 'loadCommandTasks' | 'loadCommandLog' | 'stopCommand'> {
  return {
    /* ---------- 变更 / 预览 ---------- */
    async loadChanges({ strict = false } = {}) {
      const { sessionId, sessionEpoch, workspace } = get();
      if (!sessionId || get().changesState === 'restoring') {
        return;
      }
      const request = (get()._changesRequest || 0) + 1;
      const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch && get().workspace === workspace && get()._changesRequest === request;
      set((draft) => { draft.changesState = 'loading'; draft._changesRequest = request; });
      try {
        const report = requireData(await api.diff(sessionId));
        if (!current()) return;
        set((draft) => {
          draft.changeReport = report;
          draft.changesState = '';
          draft.ui.changeCount = (report.files || []).length;
        });
      } catch (error) {
        if (!current()) return;
        set((draft) => { draft.changesState = 'error'; });
        if (strict) throw error;
        get().showNotice('读取变更失败：' + errorInfo(error).message);
      }
    },
    /**
     * 撤销改动：已跟踪文件回退到 HEAD，未跟踪文件删除；成功后刷新变更列表。
     * 失败文件逐条回报，别把「撤销没成」显示成成功。
     */
    async restoreChanges(paths, all = false) {
      const { sessionId, sessionEpoch, workspace } = get();
      if (!sessionId || get().changesState === 'restoring') {
        return null;
      }
      const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch && get().workspace === workspace;
      set((draft) => { draft.changesState = 'restoring'; draft._changesRequest = (draft._changesRequest || 0) + 1; });
      try {
        const report = await api.restoreChanges(sessionId, { paths: paths || [], all });
        if (!current()) return report;
        const failed = Array.isArray(report?.failed) ? report.failed : [];
        const done = (report?.restored?.length || 0) + (report?.deleted?.length || 0);
        if (report?.error) {
          get().showNotice('撤销失败：' + report.error);
        } else if (failed.length) {
          get().showNotice(`已撤销 ${done} 个文件，${failed.length} 个失败：${failed[0].path}（${failed[0].reason}）`);
        } else if (done) {
          get().showNotice(`已撤销 ${done} 个文件的改动`);
        } else {
          get().showNotice('没有需要撤销的改动');
        }
        set((draft) => {
          draft.changesState = '';
          // 撤销之后原来打开的差异已经没有意义，直接收起来
          if (all || (paths || []).some((path) => samePath(path, draft.ui.changeView))) {
            draft.ui.changeView = null;
          }
        });
        // 预览里正开着的文件被撤销了：只刷新内容，别把用户从变更面板拽到预览页
        const previewTarget = get().ui.previewPath;
        if (previewTarget && (all || (paths || []).some((path) => samePath(path, previewTarget)))) {
          try {
            const fresh = await api.file(sessionId, previewTarget);
            if (!current()) return report;
            set((draft) => { draft._previewData = fresh; });
          } catch {
            if (!current()) return report;
            set((draft) => { draft._previewData = null; });
          }
        }
        await get().loadChanges();
        return report;
      } catch (error) {
        if (!current()) return null;
        set((draft) => { draft.changesState = ''; });
        get().showNotice('撤销失败：' + errorInfo(error).message);
        return null;
      }
    },
    async rollbackRun(runId) {
      try {
        await api.rollbackRun(runId);
        get().showNotice('已回滚本次运行改动');
        await get().loadChanges();
      } catch (error) {
        get().showNotice('回滚失败：' + errorInfo(error).message);
      }
    },
    async createBranch(name) {
      const { sessionId, sessionEpoch, workspace } = get();
      if (!sessionId) return { ok: false, error: '请先打开一个会话。' };
      try {
        await api.branch(sessionId, name);
        if (get().sessionId === sessionId && get().sessionEpoch === sessionEpoch && get().workspace === workspace) {
          get().showNotice(`已创建并切换分支 ${name}`);
          await get().loadChanges();
        }
        return { ok: true };
      } catch (error) {
        return { ok: false, error: '创建分支失败：' + errorInfo(error).message };
      }
    },
    async openPreview(path) {
      const { sessionId, workspace, sessionEpoch } = get();
      const target = String(path || '').trim();
      if ((!sessionId && !workspace) || !target) return;
      const request = (get()._previewRequest || 0) + 1;
      set((draft) => {
        draft._previewRequest = request;
        draft.ui.previewPath = target;
        draft.ui.inspectorTab = 'preview';
        draft.ui.inspectorOpen = true;
        draft.ui.reviewExpanded = false;
        draft._previewData = null;
        draft._previewLoading = true;
      });
      const current = () => get()._previewRequest === request && get().sessionId === sessionId
        && get().workspace === workspace && get().sessionEpoch === sessionEpoch;
      try {
        const data = sessionId ? await api.file(sessionId, target) : await api.workspaceFile(workspace, target);
        if (current()) set((draft) => { draft._previewData = data; draft._previewLoading = false; });
      } catch (error) {
        if (current()) {
          set((draft) => { draft._previewLoading = false; });
          get().showNotice('读取文件失败：' + errorInfo(error).message);
        }
      }
    },

    /* ---------- 命令任务 ---------- */
    async loadCommandTasks() {
      const { sessionId, sessionEpoch } = get();
      if (!sessionId) {
        return;
      }
      try {
        const data = await apiJson<{ tasks: CommandTask[] }>(`/api/sessions/${encodeURIComponent(sessionId)}/commands`);
        if (get().sessionId !== sessionId || get().sessionEpoch !== sessionEpoch) return;
        // 任务列表是数组，日志才是增量片段：早先这里把 mergeCommandLog 用在任务上，
        // 结果 commandTasks 变成 {text, offset...} 对象，面板里 .filter 直接 TypeError
        set((draft) => {
          draft.commandTasks = Array.isArray(data?.tasks) ? data.tasks : [];
          // 切了会话还留着上一条命令的详情，会对着已经不存在的任务轮询日志
          const detail = draft.commandDetail;
          if (detail && detail.sessionId && detail.sessionId !== sessionId) {
            draft.commandDetail = null;
            draft.commandLog = null;
            draft.commandLogTaskId = null;
          }
        });
      } catch { /* 命令面板不可用不阻塞 */ }
    },

    /** 选中某个命令任务并把它的日志拉到最新（offset 用上次的 nextOffset 续读）。 */
    async loadCommandLog(task) {
      const { sessionId, sessionEpoch } = get();
      const taskId = task?.taskId || task?.id;
      if (!sessionId || !taskId) {
        return;
      }
      const sameTask = get().commandLogTaskId === taskId;
      const offset = sameTask ? (get().commandLog?.offset || 0) : 0;
      set((draft) => {
        draft.commandDetail = { ...(task || {}) };
        if (!sameTask) {
          draft.commandLogTaskId = taskId;
          draft.commandLog = { text: '', offset: 0, trimmed: 0, truncated: false, eof: false };
        }
      });
      try {
        const chunk = await apiJson<S.CommandTaskManagerLogChunk>(`/api/sessions/${encodeURIComponent(sessionId)}/commands/${encodeURIComponent(taskId)}/logs?offset=${offset}&limit=200000`);
        if (get().sessionId !== sessionId || get().sessionEpoch !== sessionEpoch) return;
        set((draft) => {
          if (draft.commandLogTaskId !== taskId) {
            return;
          }
          draft.commandLog = mergeCommandLog(draft.commandLog, chunk);
        });
      } catch { /* 日志拉取失败不阻塞面板 */ }
    },

    /** 停止一个还在跑的命令任务：后端收到后返回最新任务状态，界面随即刷新。 */
    async stopCommand(task) {
      const { sessionId } = get();
      const taskId = task?.taskId || task?.id;
      if (!sessionId || !taskId) {
        return;
      }
      try {
        await api.stopCommand(sessionId, taskId);
        get().showNotice('已请求停止：' + (task.command || taskId));
        await get().loadCommandTasks();
      } catch (error) {
        get().showNotice('停止失败：' + errorInfo(error).message);
      }
    },

  };
}
