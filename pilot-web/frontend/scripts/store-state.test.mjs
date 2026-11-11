import test from 'node:test';
import assert from 'node:assert/strict';
globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
let fetchHandler = async () => { throw Error('unexpected network'); };
globalThis.window = { fetch: (...args) => fetchHandler(...args) };
globalThis.document = { title: '', hidden: false, querySelector: () => null };
globalThis.requestAnimationFrame = () => 1;
globalThis.cancelAnimationFrame = () => {};
const { useStore, closeStreams } = await import('../src/store.js');
const { initialThread } = await import('../src/thread-reducer.js');
const deferred = () => { let resolve; const promise = new Promise(accept => { resolve = accept; }); return { promise, resolve }; };
function reset() {
  useStore.getState().startNewSession();
  useStore.setState({ sessionId: 's', sessionEpoch: 1, thread: initialThread(),
    loadSessions: async () => [], loadTask: async () => {}, loadQueue: async () => {}, refreshContext: async () => {} });
}
test('SSE 同帧批处理保留顺序，临时预览不占正式游标', () => {
  reset(); const state = useStore.getState();
  state.handleEvent({ type: 'run.started', sessionId: 's', sequence: 1, runId: 'r', payload: { input: '任务' } });
  state.handleEvent({ type: 'assistant.preview', sessionId: 's', sequence: 2, runId: 'r', payload: { text: '预览' } });
  state.handleEvent({ type: 'assistant.delta', sessionId: 's', sequence: 2, runId: 'r', payload: { text: '结果' } });
  assert.equal(useStore.getState().thread.rows.length, 0);
  state.flushEvents();
  assert.equal(useStore.getState().thread.rows.at(-1).reply.text, '结果');
  assert.equal(useStore.getState().thread.cursor, 2);
});
test('已提交游标前的重复事件和其它会话的事件不进入当前线程', () => {
  reset(); useStore.setState(draft => { draft.thread.cursor = 5; });
  const state = useStore.getState();
  state.handleEvent({ type: 'assistant.delta', sessionId: 's', sequence: 4, payload: { text: '重复' } });
  state.handleEvent({ type: 'run.started', sequence: 6, sessionId: 'other', payload: { input: '别的会话' } });
  state.handleEvent({ type: 'run.started', sequence: 6, payload: { input: '缺少会话 ID' } });
  state.flushEvents(); assert.equal(useStore.getState().thread.rows.length, 0);
});
test('切换至新任务会清空尚未提交的 SSE 事件', () => {
  reset(); const state = useStore.getState();
  state.handleEvent({ type: 'run.started', sessionId: 's', sequence: 1, payload: { input: '旧任务' } });
  state.startNewSession(); state.flushEvents();
  assert.equal(useStore.getState().thread.rows.length, 0);
  assert.equal(useStore.getState().running, false); closeStreams();
});
test('同一帧内重复的正式 SSE 事件只能应用一次', () => {
  reset(); const state = useStore.getState();
  state.handleEvent({ type: 'run.started', sessionId: 's', sequence: 1, payload: { input: '任务' } });
  const event = { type: 'assistant.delta', sessionId: 's', sequence: 2, payload: { text: '一次' } };
  state.handleEvent(event); state.handleEvent(event); state.flushEvents();
  assert.equal(useStore.getState().thread.rows.at(-1).reply.text, '一次');
});
test('会话切换后，已排队的旧会话事件不会写入新会话', () => {
  reset(); const state = useStore.getState();
  state.handleEvent({ type: 'run.started', sessionId: 's', sequence: 1, payload: { input: '旧会话任务' } });
  useStore.setState(draft => { draft.sessionId = 'new'; draft.sessionEpoch = 2; draft.thread = initialThread(); });
  state.flushEvents();
  assert.equal(useStore.getState().thread.rows.length, 0);
  assert.equal(useStore.getState().running, false);
});
test('命令任务列表的迟到响应不能覆盖新会话', async () => {
  const pending = deferred(); const saved = fetchHandler;
  try {
    reset(); fetchHandler = () => pending.promise;
    const operation = useStore.getState().loadCommandTasks();
    useStore.setState({ sessionId: 'new', sessionEpoch: 2, commandTasks: [] });
    pending.resolve(new Response(JSON.stringify({ tasks: [{ taskId: 'old' }] }))); await operation;
    assert.deepEqual(useStore.getState().commandTasks, []);
  } finally { fetchHandler = saved; }
});
test('命令日志按服务端 Unicode 码点游标续读而不重复从零拉取', async () => {
  reset(); const offsets = []; const saved = fetchHandler;
  fetchHandler = async url => {
    offsets.push(Number(new URL(url, 'http://localhost').searchParams.get('offset')));
    const offset = offsets.length === 1 ? 0 : 2;
    return new Response(JSON.stringify({ offset, nextOffset: offset + 2, text: '中文', eof: false }));
  };
  try {
    await useStore.getState().loadCommandLog({ taskId: 'c' });
    await useStore.getState().loadCommandLog({ taskId: 'c' });
    assert.deepEqual(offsets, [0, 2]); assert.equal(useStore.getState().commandLog.text, '中文中文');
  } finally { fetchHandler = saved; }
});
test('同名命令在离开会话后返回旧日志时不会覆盖当前内容', async () => {
  reset(); const pending = deferred(); const saved = fetchHandler;
  fetchHandler = () => pending.promise;
  try {
    const operation = useStore.getState().loadCommandLog({ taskId: 'c' });
    useStore.setState({ sessionId: 'new', sessionEpoch: 2, commandLog: { text: '新日志', offset: 3 } });
    pending.resolve(new Response(JSON.stringify({ text: '旧日志', offset: 0, nextOffset: 3 })));
    await operation; assert.equal(useStore.getState().commandLog.text, '新日志');
  } finally { fetchHandler = saved; }
});


test('任务契约保存的迟到响应不会覆盖新会话，也不会解除新会话的保存状态', async () => {
  for (const fail of [false, true]) {
    reset();
    const pending = deferred(); const saved = fetchHandler;
    fetchHandler = () => pending.promise;
    try {
      useStore.setState({ task: { goal: '原任务' }, taskDirty: true });
      const operation = useStore.getState().saveTask(['原约束'], []);
      useStore.getState().startNewSession();
      assert.equal(useStore.getState().taskBusy, false);
      useStore.setState({ sessionId: 'new', task: { goal: '新任务' }, taskDirty: true, taskBusy: true });
      pending.resolve(fail ? Promise.reject(new Error('旧请求失败')) : new Response(JSON.stringify({ goal: '原任务已保存' })));
      await operation;
      assert.equal(useStore.getState().task.goal, '新任务');
      assert.equal(useStore.getState().taskDirty, true);
      assert.equal(useStore.getState().taskBusy, true);
    } finally { fetchHandler = saved; }
  }
});

test('任务保存期间继续编辑会保留新草稿，重复保存不会并发发送', async () => {
  reset(); const pending = deferred(); const saved = fetchHandler; let calls = 0;
  fetchHandler = () => { calls++; return pending.promise; };
  try {
    useStore.setState({ task: { goal: '草稿' }, taskDirty: true });
    const operation = useStore.getState().saveTask(['提交内容'], []);
    await useStore.getState().saveTask(['重复提交'], []);
    assert.equal(calls, 1);
    useStore.setState(draft => { draft.taskDraftRevision++; });
    pending.resolve(new Response(JSON.stringify({ goal: '服务端旧内容' })));
    await operation;
    assert.equal(useStore.getState().task.goal, '草稿');
    assert.equal(useStore.getState().taskDirty, true);
    assert.equal(useStore.getState().taskBusy, false);
  } finally { fetchHandler = saved; }
});

test('同一会话保存成功会更新契约并清除未保存标记', async () => {
  reset(); const saved = fetchHandler;
  fetchHandler = async () => new Response(JSON.stringify({ goal: '已保存' }));
  try {
    useStore.setState({ taskDirty: true });
    await useStore.getState().saveTask([], []);
    assert.equal(useStore.getState().task.goal, '已保存');
    assert.equal(useStore.getState().taskDirty, false);
    assert.equal(useStore.getState().taskBusy, false);
  } finally { fetchHandler = saved; }
});

test('浏览器订阅轻量状态流，忽略截图及旧会话的迟到事件', () => {
  const saved = globalThis.EventSource;
  const streams = [];
  globalThis.EventSource = class {
    readyState = 1;
    listeners = new Map();
    constructor(url) { this.url = url; streams.push(this); }
    addEventListener(kind, callback) { this.listeners.set(kind, callback); }
    emit(kind, payload) { this.listeners.get(kind)?.({ data: JSON.stringify(payload) }); }
    close() { this.readyState = 2; }
  };
  try {
    reset(); useStore.getState().ensureMirror();
    const first = streams[0];
    assert.equal(first.url, '/api/sessions/s/browser/state-stream');
    assert.equal(first.listeners.has('frame'), false);
    first.emit('state', { headless: false });
    first.emit('nav', { url: 'https://first.test' });
    assert.deepEqual(useStore.getState().ui.mirror, { mode: 'live', url: 'https://first.test', headless: false });
    first.listeners.get('state')({ data: '{invalid' });
    assert.equal(useStore.getState().ui.mirror.headless, false);
    useStore.getState().startNewSession();
    useStore.setState({ sessionId: 'new' }); useStore.getState().ensureMirror();
    const second = streams[1];
    second.emit('state', { headless: false });
    second.emit('nav', { url: 'https://second.test' });
    second.emit('frame', { url: 'https://ignored.test', frame: 'not retained' });
    first.emit('nav', { url: 'https://stale.test' }); first.emit('closed'); first.emit('state', { headless: true });
    assert.deepEqual(useStore.getState().ui.mirror, { mode: 'live', url: 'https://second.test', headless: false });
    second.emit('closed'); assert.equal(useStore.getState().ui.mirror.mode, 'closed');
  } finally { closeStreams(); globalThis.EventSource = saved; }
});

test('后台暂停 rAF 时由定时兜底应用事件，迟到帧不会重复提交', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const saved = globalThis.requestAnimationFrame; let frame;
  globalThis.requestAnimationFrame = callback => { frame = callback; return 10; };
  try {
    reset();
    useStore.getState().handleEvent({ type: 'run.started', sessionId: 's', sequence: 1, payload: { input: '后台任务' } });
    t.mock.timers.tick(50);
    assert.equal(useStore.getState().thread.cursor, 1);
    const rows = useStore.getState().thread.rows.length;
    frame();
    assert.equal(useStore.getState().thread.rows.length, rows);
  } finally { closeStreams(); globalThis.requestAnimationFrame = saved; }
});

function fakeStreams() {
  const saved = globalThis.EventSource; const streams = [];
  globalThis.EventSource = class {
    listeners = new Map(); closed = false;
    constructor(url) { this.url = url; streams.push(this); }
    addEventListener(kind, callback) { this.listeners.set(kind, callback); }
    emit(event) { this.listeners.get(event.type)?.({ data: JSON.stringify(event) }); }
    close() { this.closed = true; }
  };
  return { streams, restore() { closeStreams(); globalThis.EventSource = saved; } };
}

test('异常批次回滚游标并阻止后续越过，重复坏事件有限重试后明确停止', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const fixture = fakeStreams();
  try {
    reset(); useStore.setState({ _reconnectAttempt: 0 });
    useStore.getState().connectSse('s', 0);
    for (let attempt = 0; attempt < 4; attempt++) {
      const source = fixture.streams.at(-1);
      assert.match(source.url, /after=0/);
      source.emit({ type: 'run.started', sessionId: 's', sequence: 1, payload: { input: '不能跳过的正常事件' } });
      source.emit({ type: 'plan.updated', sessionId: 's', sequence: 2, payload: { plan: [null] } });
      useStore.getState().flushEvents();
      assert.equal(useStore.getState().thread.cursor, 0);
      assert.equal(useStore.getState().thread.rows.length, 0);
      assert.equal(source.closed, true);
      useStore.getState().handleEvent({ type: 'run.started', sessionId: 's', sequence: 3, payload: { input: '不能越过' } });
      useStore.getState().flushEvents();
      assert.equal(useStore.getState().thread.cursor, 0);
      t.mock.timers.tick(20000);
    }
    assert.equal(fixture.streams.length, 4);
    assert.equal(useStore.getState().ui.connection.text, '事件恢复失败，请刷新');
  } finally { fixture.restore(); }
});

test('解析失败关闭当前连接并从已确认游标恢复', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const fixture = fakeStreams();
  try {
    reset(); useStore.setState(draft => { draft.thread.cursor = 7; draft._reconnectAttempt = 0; });
    useStore.getState().connectSse('s', 7);
    fixture.streams[0].listeners.get('run.started')({ data: '{broken' });
    assert.equal(fixture.streams[0].closed, true);
    t.mock.timers.tick(1000);
    assert.equal(fixture.streams.length, 2);
    assert.match(fixture.streams[1].url, /after=7/);
    assert.equal(useStore.getState().thread.cursor, 7);
  } finally { fixture.restore(); }
});

test('前端积压达到数量预算时主动消费，切换会话取消定时兜底', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  reset();
  for (let sequence = 1; sequence <= 2050; sequence++) {
    useStore.getState().handleEvent({ type: 'assistant.delta', sessionId: 's', sequence, payload: { text: '字' } });
  }
  assert.equal(useStore.getState().thread.cursor, 2048);
  useStore.getState().startNewSession();
  t.mock.timers.tick(100);
  assert.equal(useStore.getState().thread.cursor, 0);
  assert.equal(useStore.getState().thread.rows.length, 0);
  closeStreams();
});

test('连续打开即断开的连接累积退避，稳定十秒后才重置，旧回调无效', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const fixture = fakeStreams(); const savedRandom = Math.random;
  Math.random = () => 0;
  try {
    reset(); useStore.getState().connectSse('s', 0);
    for (const delay of [800, 1600, 3200, 6400, 12800]) {
      const source = fixture.streams.at(-1); const count = fixture.streams.length;
      source.onopen(); source.onerror();
      t.mock.timers.tick(delay - 1);
      assert.equal(fixture.streams.length, count);
      t.mock.timers.tick(1);
      assert.equal(fixture.streams.length, count + 1);
      source.onopen(); source.onerror();
      assert.equal(useStore.getState()._reconnectAttempt, count);
    }
    const source = fixture.streams.at(-1); source.onopen();
    t.mock.timers.tick(9999);
    assert.equal(useStore.getState()._reconnectAttempt, 5);
    t.mock.timers.tick(1);
    assert.equal(useStore.getState()._reconnectAttempt, 0);
    source.onerror();
    t.mock.timers.tick(800);
    assert.equal(fixture.streams.length, 7);
    useStore.getState().startNewSession();
    t.mock.timers.tick(20000);
    assert.equal(fixture.streams.length, 7);
    assert.equal(useStore.getState()._reconnectAttempt, 0);
  } finally { fixture.restore(); Math.random = savedRandom; }
});

test('历史版本失效会重建显示和续订，保留输入附件草稿并拒绝旧分页响应', async () => {
  const fixture = fakeStreams(); const saved = fetchHandler; const oldPage = deferred();
  try {
    reset(); useStore.setState({ historyRevision: 0 });
    useStore.getState().handleEvent({ type: 'run.started', sessionId: 's', sequence: 5, payload: { input: '已删消息' } });
    useStore.getState().flushEvents();
    useStore.setState(draft => { draft.ui.draftFiles = [{ id: 'draft', name: '草稿附件' }]; });
    fetchHandler = url => String(url).includes('before=') ? oldPage.promise : Promise.resolve(new Response(JSON.stringify({
      historyRevision: 1, hasMore: false, events: [{ type: 'run.started', sessionId: 's', sequence: 6, payload: { input: '新历史' } }]
    })));
    const earlier = useStore.getState().loadEarlier();
    useStore.getState().connectSse('s', 5);
    fixture.streams[0].listeners.get('stream.reset')({ data: JSON.stringify({ sessionId: 's', payload: { historyRevision: 1 } }) });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(useStore.getState().historyRevision, 1);
    assert.equal(useStore.getState().thread.cursor, 6);
    assert.match(fixture.streams.at(-1).url, /after=6&revision=1/);
    assert.equal(useStore.getState().ui.draftFiles[0].name, '草稿附件');
    oldPage.resolve(new Response(JSON.stringify({ historyRevision: 0, hasMore: false, events: [{ type: 'run.started', sessionId: 's', sequence: 2, payload: { input: '旧分页' } }] })));
    assert.equal(await earlier, false);
    assert.equal(useStore.getState().thread.rows.some(row => row.text === '旧分页' || row.text === '已删消息'), false);
  } finally { fetchHandler = saved; fixture.restore(); }
});
