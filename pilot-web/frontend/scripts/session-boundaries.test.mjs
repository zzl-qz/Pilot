import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
globalThis.window = { fetch: async () => Response.json({ tasks: [] }) };
globalThis.document = { title: '', hidden: false, querySelector: () => null };
globalThis.EventSource = class { addEventListener() {} close() {} };
const { useStore, closeStreams } = await import('../src/store.js');
const { api } = await import('../src/api.js');
const { initialThread } = await import('../src/thread-reducer.js');
const originalApi = { ...api };
const originalState = useStore.getState();
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const tick = () => new Promise(resolve => setImmediate(resolve));
let notices;
test.beforeEach(() => {
  closeStreams();
  notices = [];
  useStore.setState({ ...originalState, thread: initialThread(), sessionId: 'a', sessionEpoch: 1,
    showNotice: text => notices.push(text), loadSessions: async () => [], loadPlan: async () => {},
    loadQueue: async () => {}, loadTask: async () => {}, refreshContext: async () => {}, refreshActiveRun: async () => {} });
});
test.afterEach(() => { closeStreams(); Object.assign(api, originalApi); });

for (const [action, busy] of [['pauseRun', 'pausing'], ['cancelRun', 'cancelling']]) {
  test(`${action} 同一运行只提交一次，失败后恢复按钮并允许重试`, async () => {
    const gate = deferred();
    let calls = 0;
    api[action] = () => { calls++; return gate.promise; };
    useStore.setState({ runId: 'current' });
    const first = useStore.getState()[action]();
    await useStore.getState()[action]();
    assert.equal(calls, 1);
    gate.reject(Error('暂时不可用'));
    await first;
    assert.equal(useStore.getState()[busy], false);
    assert.match(notices[0], /暂时不可用/);
    api[action] = async () => { calls++; };
    await useStore.getState()[action]();
    assert.equal(calls, 2);
  });
  for (const fails of [false, true]) test(`${action} 的迟到${fails ? '失败' : '成功'}不能清除新运行的忙碌状态`, async () => {
    const old = deferred(), current = deferred();
    api[action] = runId => runId === 'old' ? old.promise : current.promise;
    useStore.setState({ runId: 'old' });
    const first = useStore.getState()[action]();
    useStore.setState({ sessionEpoch: 2, runId: 'new', [busy]: false });
    const second = useStore.getState()[action]();
    try {
      if (fails) old.reject(Error('旧运行失败')); else old.resolve({});
      await first;
      assert.equal(useStore.getState()[busy], true);
      assert.deepEqual(notices, []);
    } finally { current.resolve({}); await second; }
    assert.equal(useStore.getState()[busy], false);
  });
}

test('同一草稿并发创建只发送一次，失败后可以明确重试', async () => {
  useStore.getState().startNewSession();
  let calls = 0;
  const gate = deferred();
  api.createSession = () => { calls++; return gate.promise; };
  const first = useStore.getState().ensureSession();
  const second = useStore.getState().ensureSession();
  try { assert.equal(calls, 1); }
  finally { gate.reject(Error('创建失败')); await Promise.allSettled([first, second]); }
  api.createSession = async () => { calls++; return { sessionId: 'created' }; };
  assert.equal(await useStore.getState().ensureSession(), 'created');
  assert.equal(calls, 2);
});

test('旧草稿创建完成不能抢占新草稿，也不能清掉新草稿创建中的请求', async () => {
  const old = deferred(), next = deferred();
  let calls = 0;
  api.createSession = () => ++calls === 1 ? old.promise : next.promise;
  useStore.getState().startNewSession();
  const first = useStore.getState().ensureSession();
  useStore.getState().startNewSession();
  const second = useStore.getState().ensureSession();
  old.resolve({ sessionId: 'old' });
  await assert.rejects(first, /会话已切换/);
  const third = useStore.getState().ensureSession();
  try { assert.equal(calls, 2); }
  finally { next.resolve({ sessionId: 'new' }); await Promise.allSettled([second, third]); }
  assert.equal(useStore.getState().sessionId, 'new');
});

test('切换会话后旧历史预取不触发新会话分页，也不解除新请求的去重', async () => {
  const old = deferred(), next = deferred(), calls = [];
  const setPage = id => useStore.setState(draft => {
    draft.sessionId = id;
    draft.thread.history.hasMore = true;
    draft.thread.windowEvents = [{ type: 'run.started', sessionId: id, sequence: 30, payload: { input: id } }];
  });
  api.history = id => { calls.push(id); return id === 'a' ? old.promise : next.promise; };
  setPage('a');
  void useStore.getState().prefetchEarlier();
  const first = useStore.getState().requestEarlierHistory();
  useStore.getState().startNewSession();
  setPage('b');
  const second = useStore.getState().requestEarlierHistory();
  old.resolve({ hasMore: false, events: [] });
  await tick();
  const third = useStore.getState().requestEarlierHistory();
  try { assert.deepEqual(calls, ['a', 'b']); }
  finally { next.resolve({ hasMore: false, events: [] }); await Promise.allSettled([first, second, third]); }
  assert.equal(await first, false);
});

test('A→B→A 快速切换只应用最后一次会话详情和历史', async () => {
  const old = deferred();
  let calls = 0;
  api.session = id => ++calls === 1 ? old.promise : Promise.resolve({ sessionId: id, title: '最新-' + id });
  api.history = async id => ({ hasMore: false, historyRevision: 0, events: [
    { type: 'run.started', sessionId: id, sequence: 5, payload: { input: '最新消息-' + id } }
  ] });
  useStore.setState({ loadSkills: async () => {} });
  const first = useStore.getState().activate('a');
  await useStore.getState().activate('b');
  await useStore.getState().activate('a');
  old.resolve({ sessionId: 'a', title: '过期标题' });
  assert.equal(await first, false);
  assert.equal(useStore.getState().sessionTitle, '最新-a');
  assert.equal(useStore.getState().thread.cursor, 5);
  assert.equal(useStore.getState().thread.rows.filter(row => row.type === 'user').length, 1);
  assert.equal(useStore.getState().thread.rows.find(row => row.type === 'user').text, '最新消息-a');
});

test('活跃运行查询的迟到响应不能把已经完成的运行重新标为执行中', async () => {
  const gate = deferred();
  api.activeRun = () => gate.promise;
  const pending = originalState.refreshActiveRun();
  for (const [sequence, type] of [[1, 'run.started'], [2, 'run.completed']]) {
    useStore.getState().handleEvent({ sessionId: 'a', runId: 'finished', sequence, type, payload: { input: '任务', summary: '完成' } });
  }
  useStore.getState().flushEvents();
  assert.equal(useStore.getState().running, false);
  const completedRunId = useStore.getState().runId;
  gate.resolve({ runId: 'finished' });
  await pending;
  assert.equal(useStore.getState().running, false);
  assert.equal(useStore.getState().runId, completedRunId);
});

test('活跃运行查询正常恢复状态，并只接受最后发出的查询', async () => {
  const old = deferred(), next = deferred();
  let calls = 0;
  api.activeRun = () => ++calls === 1 ? old.promise : next.promise;
  const first = originalState.refreshActiveRun(), second = originalState.refreshActiveRun();
  next.resolve({ runId: 'newest' }); await second;
  old.resolve({ runId: 'older' }); await first;
  assert.equal(useStore.getState().runId, 'newest');
  assert.equal(useStore.getState().running, true);
});

test('断线重连也不能用迟到的活跃查询覆盖结束事件', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const gate = deferred();
  let calls = 0;
  api.activeRun = () => { calls++; return gate.promise; };
  useStore.setState({ refreshActiveRun: originalState.refreshActiveRun });
  useStore.getState().scheduleReconnect('a');
  t.mock.timers.tick(1000);
  assert.equal(calls, 1);
  useStore.getState().handleEvent({ sessionId: 'a', runId: 'finished', sequence: 2, type: 'run.completed', payload: { summary: '完成' } });
  useStore.getState().flushEvents();
  gate.resolve({ runId: 'finished' }); await tick();
  assert.equal(useStore.getState().running, false);
});
