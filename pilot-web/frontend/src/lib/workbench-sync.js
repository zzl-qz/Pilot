/** 不重建编辑器和历史窗口的数据同步；Web 与桌面共用，后台窗口暂停轮询。 */
export function watchWorkbench({ refresh, window: win = window, document: doc = document, interval = 15000 }) {
  let lastWake = 0;
  const wake = () => {
    if (doc.hidden || Date.now() - lastWake < 1000) return;
    lastWake = Date.now();
    refresh({ silent: true, full: true });
  };
  const online = () => { if (!doc.hidden) refresh({ silent: true, full: true, reconnect: true }); };
  const timer = win.setInterval(() => { if (!doc.hidden) refresh({ silent: true }); }, interval);
  win.addEventListener('focus', wake);
  win.addEventListener('online', online);
  doc.addEventListener('visibilitychange', wake);
  return () => {
    win.clearInterval(timer);
    win.removeEventListener('focus', wake);
    win.removeEventListener('online', online);
    doc.removeEventListener('visibilitychange', wake);
  };
}

/** 同步只读数据，不重发指令、不切换任务；同一窗口的并发刷新合并。 */
export function createWorkbenchRefresher({ get, set, api }) {
  let flight = null;
  let reconnectWanted = false;
  let notifyWanted = false;
  let fullWanted = false;
  return function refreshWorkbench({ silent = false, full = !silent, reconnect = !silent } = {}) {
    reconnectWanted ||= reconnect;
    notifyWanted ||= !silent;
    fullWanted ||= full;
    if (flight) return flight;
    set(state => { state.ui.refreshing = true; });
    flight = (async () => {
      let ok = true;
      let superseded = false;
      try {
        const base = await Promise.allSettled([get().loadProjects({ strict: true }), get().loadSessions({ strict: true })]);
        const rejected = base.find(result => result.status === 'rejected');
        if (rejected) throw rejected.reason;
        get().flushEvents();
        const { sessionId, sessionEpoch, sessionTitle, workspace, currentProjectId, runId, thread } = get();
        const cursor = thread.cursor;
        const current = () => get().sessionId === sessionId && get().sessionEpoch === sessionEpoch;
        if (sessionId) {
          const [detail, active] = await Promise.all([api.session(sessionId), api.activeRun(sessionId)]);
          if (!current()) { superseded = true; return false; }
          get().flushEvents();
          set(state => {
            if ((detail?.id || detail?.sessionId) === sessionId) {
              if (state.sessionTitle === sessionTitle) state.sessionTitle = detail.title || state.sessionTitle;
              if (state.workspace === workspace && typeof detail.workspace === 'string' && state.workspace !== detail.workspace) {
                state.workspace = detail.workspace; state.fileIndex = { items: [], sessionId: null, loading: false };
              }
              if (state.currentProjectId === currentProjectId) state.currentProjectId = detail.projectId || null;
            }
            if (state.thread.cursor !== cursor || state.runId !== runId) return;
            if (active?.runId) { state.runId = active.runId; state.running = true; }
            else { state.runId = null; state.running = false; state.cancelling = false; state.pausing = false; }
          });
          await get().loadQueue({ strict: true });
          if (!current()) { superseded = true; return false; }
          if (fullWanted) {
            const work = [get().refreshContext({ strict: true }), get().loadPlan({ strict: true })];
            if (!get().taskDirty && !get().taskBusy) work.push(get().loadTask({ strict: true }));
            if (get().ui.inspectorOpen && ['changes', 'preview'].includes(get().ui.inspectorTab)) work.push(get().loadChanges({ strict: true }));
            const results = await Promise.allSettled(work);
            const failure = results.find(result => result.status === 'rejected');
            if (failure) throw failure.reason;
          }
          if (!current()) { superseded = true; return false; }
          // 合并刷新期间追加的手动刷新也应续接持久游标，不清空滚动位置和输入。
          if (reconnectWanted || get().ui.connection.status === 'error') {
            get().flushEvents();
            get().connectSse(sessionId, get().thread.cursor);
          }
        }
        set(state => {
          state.ui.syncError = ''; state.ui.lastSyncedAt = Date.now();
          if (fullWanted) {
            state.ui.filesRevision = (state.ui.filesRevision || 0) + 1;
            state.fileIndex = { items: [], sessionId: null, loading: false };
          }
        });
      } catch (error) {
        ok = false;
        set(state => { state.ui.syncError = error?.message || '无法读取最新数据'; });
      } finally {
        if (notifyWanted && !superseded) get().showNotice(ok ? '已同步最新数据，草稿与附件已保留' : `刷新失败：${get().ui.syncError}，当前内容已保留`);
        flight = null; reconnectWanted = false; notifyWanted = false; fullWanted = false;
        set(state => { state.ui.refreshing = false; });
      }
      return ok;
    })();
    return flight;
  };
}

const RESTORE_KEY = 'pilot.workbench.restore.v1';
export function saveWorkbenchView(storage, state) {
  try {
    storage.setItem(RESTORE_KEY, JSON.stringify({ sessionId: state.sessionId, draftSession: state.draftSession,
      workspace: state.workspace, currentProjectId: state.currentProjectId }));
    return true;
  } catch { return false; }
}
export function takeWorkbenchView(storage) {
  try {
    const raw = storage.getItem(RESTORE_KEY);
    storage.removeItem(RESTORE_KEY);
    const view = raw ? JSON.parse(raw) : null;
    return view && typeof view === 'object' && (typeof view.sessionId === 'string' || view.draftSession) ? view : null;
  } catch { return null; }
}
