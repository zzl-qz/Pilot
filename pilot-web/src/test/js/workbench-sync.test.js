import test from 'node:test';
import assert from 'node:assert/strict';
import { createWorkbenchRefresher, watchWorkbench, saveWorkbenchView, takeWorkbenchView } from '../../../frontend/src/lib/workbench-sync.js';

const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
function fixture() {
  const calls = [], notices = [];
  const state = { sessionId: 'one', sessionEpoch: 1, sessionTitle: '旧标题', workspace: 'C:\\项目', currentProjectId: 'p',
    runId: 'old-run', running: true, cancelling: true, pausing: true, taskDirty: false, taskBusy: false,
    thread: { cursor: 12, rows: [{ text: '保留历史' }] }, composerApi: { draft: '未发送文本', files: ['photo.png'] },
    ui: { connection: { status: 'ok' }, inspectorOpen: true, inspectorTab: 'changes' } };
  for (const name of ['loadProjects', 'loadSessions', 'loadQueue', 'refreshContext', 'loadPlan', 'loadTask', 'loadChanges', 'flushEvents', 'connectSse']) {
    state[name] = async (...args) => { calls.push({ name, args }); };
  }
  state.showNotice = message => notices.push(message);
  const api = { session: async id => ({ id, title: '手机修改的标题', workspace: 'C:\\项目', projectId: 'p' }), activeRun: async () => null };
  const refresh = createWorkbenchRefresher({ get: () => state, set: change => change(state), api });
  return { state, api, calls, notices, refresh };
}

test('手动同步更新元数据与运行状态，保留任务、编辑器、附件与历史窗口', async () => {
  const { state, calls, notices, refresh } = fixture();
  const history = state.thread, composer = state.composerApi;
  assert.equal(await refresh(), true);
  assert.equal(state.sessionTitle, '手机修改的标题'); assert.equal(state.runId, null); assert.equal(state.running, false);
  assert.equal(state.cancelling, false); assert.equal(state.pausing, false);
  assert.equal(state.thread, history); assert.equal(state.composerApi, composer); assert.equal(state.sessionId, 'one');
  for (const name of ['loadProjects', 'loadSessions', 'loadQueue', 'refreshContext', 'loadPlan', 'loadTask', 'loadChanges', 'connectSse']) assert.ok(calls.some(call => call.name === name), name);
  assert.deepEqual(calls.find(call => call.name === 'connectSse').args, ['one', 12]);
  assert.equal(state.ui.refreshing, false); assert.ok(state.ui.lastSyncedAt); assert.equal(state.ui.filesRevision, 1);
  assert.match(notices[0], /草稿与附件已保留/);
});

test('自动轮询不反复重连或读取大面板，合并期间的手动刷新仍会执行完整同步', async () => {
  const f = fixture(); const gate = deferred();
  f.state.loadQueue = () => gate.promise;
  const auto = f.refresh({ silent: true });
  await new Promise(resolve => setImmediate(resolve));
  const manual = f.refresh(); assert.equal(auto, manual);
  gate.resolve(); assert.equal(await manual, true);
  assert.equal(f.calls.filter(call => call.name === 'loadProjects').length, 1);
  assert.equal(f.calls.filter(call => call.name === 'loadTask').length, 1);
  assert.equal(f.calls.filter(call => call.name === 'connectSse').length, 1);
  assert.equal(f.notices.length, 1);
  f.calls.length = 0; f.notices.length = 0;
  await f.refresh({ silent: true });
  assert.equal(f.calls.some(call => ['loadPlan', 'loadTask', 'connectSse'].includes(call.name)), false);
  assert.equal(f.notices.length, 0);
});

test('刷新中的旧运行快照不能覆盖更新的 SSE 状态或本地改名', async () => {
  const f = fixture(), gate = deferred(); f.api.activeRun = () => gate.promise;
  const refresh = f.refresh(); await new Promise(resolve => setImmediate(resolve));
  f.state.thread.cursor++; f.state.runId = 'new-run'; f.state.sessionTitle = '本地改名';
  gate.resolve(null); await refresh;
  assert.equal(f.state.running, true); assert.equal(f.state.runId, 'new-run'); assert.equal(f.state.sessionTitle, '本地改名');
});

test('同一任务离开后再切回，也丢弃旧的异步响应', async () => {
  const f = fixture(), gate = deferred(); f.api.session = () => gate.promise;
  const refresh = f.refresh(); await new Promise(resolve => setImmediate(resolve));
  f.state.sessionEpoch += 2; f.state.sessionTitle = '切回后的内容';
  gate.resolve({ id: 'one', title: '旧响应' });
  assert.equal(await refresh, false); assert.equal(f.state.sessionTitle, '切回后的内容'); assert.equal(f.notices.length, 0);
  assert.equal(f.state.ui.refreshing, false);
});

test('刷新读取失败保留当前数据，等待所有子请求结束后才能重试', async () => {
  const f = fixture(), gate = deferred();
  f.state.refreshContext = async () => { throw Error('网络不可用'); }; f.state.loadTask = () => gate.promise;
  const first = f.refresh(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.state.ui.refreshing, true); assert.equal(f.refresh(), first);
  gate.resolve(); assert.equal(await first, false); assert.match(f.state.ui.syncError, /网络不可用/);
  assert.equal(f.state.composerApi.files.length, 1); assert.match(f.notices.at(-1), /刷新失败.*当前内容已保留/);
  f.state.refreshContext = async () => {}; assert.equal(await f.refresh(), true); assert.equal(f.state.ui.syncError, '');
});

test('列表读取失败不重建界面；自动失败静默但保留可查询状态', async () => {
  const f = fixture(); f.state.loadProjects = async () => { throw Error('服务离线'); };
  assert.equal(await f.refresh({ silent: true }), false);
  assert.equal(f.state.sessionTitle, '旧标题'); assert.equal(f.state.ui.refreshing, false); assert.equal(f.notices.length, 0);
  assert.match(f.state.ui.syncError, /服务离线/);
});

test('编辑中的任务契约不被同步覆盖；断线自动同步会续接游标', async () => {
  const f = fixture(); f.state.taskDirty = true; f.state.ui.connection.status = 'error';
  await f.refresh({ silent: true, full: true });
  assert.equal(f.calls.some(call => call.name === 'loadTask'), false);
  assert.equal(f.calls.some(call => call.name === 'connectSse'), true);
});

test('新任务草稿仅同步列表和文件索引，不创建任务或读取空 ID', async () => {
  const f = fixture(); f.state.sessionId = null; f.state.draftSession = { projectId: 'p', workspace: '/home/me/project' };
  f.api.session = async () => { throw Error('不应读取'); };
  await f.refresh(); assert.equal(f.state.sessionId, null); assert.equal(f.state.draftSession.projectId, 'p');
  assert.equal(f.calls.some(call => call.name === 'loadQueue'), false); assert.equal(f.state.ui.filesRevision, 1);
});

test('恢复目标按窗口一次性保存，兼容三平台路径；损坏或禁用存储可降级', () => {
  const values = new Map(), storage = { setItem: (k, v) => values.set(k, v), getItem: k => values.get(k), removeItem: k => values.delete(k) };
  for (const workspace of ['C:\\用户\\项目', '/Users/me/项目', '/home/me/project']) {
    const view = { sessionId: 'two', draftSession: null, workspace, currentProjectId: 'p' };
    assert.equal(saveWorkbenchView(storage, view), true); assert.deepEqual(takeWorkbenchView(storage), view); assert.equal(takeWorkbenchView(storage), null);
  }
  saveWorkbenchView(storage, { sessionId: null, draftSession: { projectId: 'p' }, workspace: '/new', currentProjectId: 'p' });
  assert.equal(takeWorkbenchView(storage).sessionId, null);
  storage.setItem('pilot.workbench.restore.v1', '{'); assert.equal(takeWorkbenchView(storage), null);
  storage.setItem('pilot.workbench.restore.v1', '42'); assert.equal(takeWorkbenchView(storage), null);
  const denied = { setItem() { throw Error('denied'); }, getItem() { throw Error('denied'); } };
  assert.equal(saveWorkbenchView(denied, {}), false); assert.equal(takeWorkbenchView(denied), null);
});

test('前台轮询、焦点与网络恢复触发同步；后台暂停，卸载移除监听与计时器', () => {
  const win = new EventTarget(), doc = new EventTarget(), calls = [];
  doc.hidden = false; let tick, cleared;
  win.setInterval = (callback, interval) => { tick = callback; assert.equal(interval, 15000); return 3; };
  win.clearInterval = id => { cleared = id; };
  const stop = watchWorkbench({ window: win, document: doc, refresh: options => calls.push(options) });
  tick(); assert.deepEqual(calls.pop(), { silent: true });
  doc.hidden = true; tick(); win.dispatchEvent(new Event('focus')); assert.equal(calls.length, 0);
  doc.hidden = false; doc.dispatchEvent(new Event('visibilitychange'));
  assert.deepEqual(calls.pop(), { silent: true, full: true });
  win.dispatchEvent(new Event('focus')); assert.equal(calls.length, 0, 'visibility/focus 连续事件合并');
  win.dispatchEvent(new Event('online')); assert.deepEqual(calls.pop(), { silent: true, full: true, reconnect: true });
  stop(); assert.equal(cleared, 3); win.dispatchEvent(new Event('online')); assert.equal(calls.length, 0);
});
