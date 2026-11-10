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
