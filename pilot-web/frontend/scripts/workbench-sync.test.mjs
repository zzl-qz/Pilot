import assert from 'node:assert/strict';
import test from 'node:test';
import { createWorkbenchRefresher } from '../src/lib/workbench-sync.js';

const tick = () => new Promise(resolve => setImmediate(resolve));

const deferred = () => {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return { promise, resolve };
};

function fixture(sessionId = 'A') {
  const state = {
    sessionId,
    sessionEpoch: 1,
    sessionTitle: '',
    workspace: null,
    currentProjectId: null,
    runId: null,
    thread: { cursor: 0 },
    ui: { refreshing: false, syncError: '', connection: { status: 'ready' }, inspectorOpen: false, inspectorTab: 'activity' },
    taskDirty: false,
    taskBusy: false,
    fileIndex: { items: [], sessionId: null, loading: false }
  };
  const calls = { details: [], reconnects: [], notices: [] };
  const pending = new Map();
  const api = {
    session: id => {
      calls.details.push(id);
      const gate = deferred();
      pending.set('detail:' + id, gate);
      return gate.promise;
    },
    activeRun: id => {
      const gate = deferred();
      pending.set('active:' + id, gate);
      return gate.promise;
    }
  };
  const get = () => state;
  const set = mutator => mutator(state);
  Object.assign(state, {
    loadProjects: async () => {},
    loadSessions: async () => {},
    loadQueue: async () => {},
    loadPlan: async () => {},
    loadTask: async () => {},
    refreshContext: async () => {},
    flushEvents: () => {},
    connectSse: (id) => { calls.reconnects.push(id); },
    showNotice: message => { calls.notices.push(message); }
  });
  const refresh = createWorkbenchRefresher({ get, set, api });
  return { state, calls, pending, refresh };
}

test('旧会话刷新未返回时切换会话并手动刷新，新会话仍会读详情并重连', async () => {
  const f = fixture('A');
  const first = f.refresh({ silent: false });
  const second = f.refresh({ silent: false }); // 同一会话合并
  assert.equal(first, second, '同一会话与代次的刷新复用一个 Promise');
  await tick();
  assert.ok(f.pending.get('detail:A'), 'A 的详情请求已发出');

  // 切到 B（代次变化）后手动刷新：不能被 A 的请求挡住
  f.state.sessionId = 'B';
  f.state.sessionEpoch = 2;
  const other = f.refresh({ silent: false });
  assert.notEqual(other, first, '不同会话的刷新必须各自执行');
  await tick();

  // 让 B 的详情/运行请求完成
  f.pending.get('detail:B').resolve({ id: 'B', title: 'B 标题' });
  f.pending.get('active:B').resolve(null);
  assert.equal(await other, true);
  assert.deepEqual(f.calls.details, ['A', 'B']);
  assert.deepEqual(f.calls.reconnects, ['B'], 'B 需要续接事件流');

  // A 的晚到响应：过期后被丢弃，返回 false，也不重连 A
  f.pending.get('detail:A').resolve({ id: 'A' });
  f.pending.get('active:A').resolve(null);
  assert.equal(await first, false);
  assert.equal(f.state.ui.refreshing, false);
  assert.ok(!f.calls.reconnects.includes('A'), '过期会话不重连');
  assert.ok(!f.calls.notices.some(text => /已同步最新数据/.test(text) && f.state.sessionId === 'B' && false));
});

test('同一会话连续刷新合并为一次详情请求，晚到的手动刷新也能重连', async () => {
  const f = fixture('A');
  const first = f.refresh({ silent: true, full: false, reconnect: false });
  const manual = f.refresh({ silent: false }); // 合并且要求重连
  assert.equal(first, manual);
  await tick();
  f.pending.get('detail:A').resolve({ id: 'A' });
  f.pending.get('active:A').resolve(null);
  assert.equal(await manual, true);
  assert.deepEqual(f.calls.details, ['A'], '只发一次详情请求');
  assert.deepEqual(f.calls.reconnects, ['A']);
  assert.ok(f.calls.notices.some(text => /已同步最新数据/.test(text)));
});

test('失败时保留内容并提示，不把错误算到后续会话头上', async () => {
  const f = fixture('A');
  f.state.loadProjects = async () => { throw new Error('网络断开'); };
  assert.equal(await f.refresh({ silent: false }), false);
  assert.ok(f.calls.notices.some(text => /刷新失败/.test(text)));
  assert.equal(f.state.ui.syncError, '网络断开');
});