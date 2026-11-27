import assert from 'node:assert/strict';
import { test } from 'node:test';

globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
globalThis.window = { fetch: async () => { throw Error('unexpected network'); } };
globalThis.document = { title: '', hidden: false, querySelector: () => null };
globalThis.EventSource = class FakeEventSource {
  constructor() { this.readyState = 1; }
  addEventListener() {}
  close() { this.readyState = 2; }
};
globalThis.requestAnimationFrame = () => 1;
globalThis.cancelAnimationFrame = () => {};

const {
  initialThread,
  applyEvent,
  prependHistoryPage,
  appendHistoryPage,
} = await import('../src/thread-reducer.js');
const { useStore, closeStreams } = await import('../src/store.js');
const { api } = await import('../src/api.js');

const deferred = () => {
  let resolve;
  const promise = new Promise(accept => { resolve = accept; });
  return { promise, resolve };
};

function makeDraft(events = []) {
  const draft = {
    thread: initialThread(),
    timeline: [],
    pendingInputs: [],
    queue: [],
    streamCard: null,
    ui: { notice: null },
    running: false,
    cancelling: false,
    pausing: false,
    lastOutcome: null,
    pendingApprovals: {},
    pendingQuestions: {},
    pendingArbitrations: {},
  };
  for (const event of events) applyEvent(draft, event, { live: false });
  return draft;
}

test('调工具前流式叙述保持 append-only 存入 turn.commentaries，绝不抽走塞进折叠思考', () => {
  const draft = makeDraft();
  const events = [
    { sequence: 1, type: 'run.started', runId: 'r1', payload: { input: '检查代码' } },
    { sequence: 2, type: 'assistant.reasoning', runId: 'r1', payload: { text: '先思考方案' } },
    { sequence: 3, type: 'assistant.delta', runId: 'r1', payload: { text: '我先读取配置文件确认端口。' } },
    { sequence: 4, type: 'tool.started', runId: 'r1', payload: { callId: 'c1', name: 'read_file', arguments: '{}' } },
    { sequence: 5, type: 'tool.completed', runId: 'r1', payload: { callId: 'c1', resultSummary: 'ok' } },
    { sequence: 6, type: 'assistant.delta', runId: 'r1', payload: { text: '接下来检查服务端测试。' } },
    { sequence: 7, type: 'tool.started', runId: 'r1', payload: { callId: 'c2', name: 'run_command', arguments: '{}' } },
    { sequence: 8, type: 'tool.completed', runId: 'r1', payload: { callId: 'c2', resultSummary: 'pass' } },
    { sequence: 9, type: 'assistant.delta', runId: 'r1', payload: { text: '全部检查完成。' } },
    { sequence: 10, type: 'run.completed', runId: 'r1', payload: { summary: 'ok' } },
  ];
  for (const event of events) applyEvent(draft, event, { live: true });

  const turn = draft.thread.rows.find(row => row.type === 'turn');
  assert.ok(turn);
  assert.deepEqual(turn.commentaries, ['我先读取配置文件确认端口。', '接下来检查服务端测试。']);
  assert.equal(turn.thinking.text, '先思考方案', '思考文本不得混入已流出的正文叙述');
  assert.equal(turn.reply.text, '全部检查完成。');
});

test('跨分页截断的同一 Turn 在向上/向下翻页时自动无缝合并并保留已挂载 key', () => {
  const olderEvents = [
    { sequence: 1, type: 'run.started', runId: 'r1', payload: { input: '长任务' } },
    { sequence: 2, type: 'tool.started', runId: 'r1', payload: { callId: 'c1', name: 'read_file', arguments: '{}' } },
    { sequence: 3, type: 'tool.completed', runId: 'r1', payload: { callId: 'c1', resultSummary: 'done1' } },
  ];
  const newerEvents = [
    { sequence: 4, type: 'tool.started', runId: 'r1', payload: { callId: 'c2', name: 'edit_file', arguments: '{}' } },
    { sequence: 5, type: 'tool.completed', runId: 'r1', payload: { callId: 'c2', resultSummary: 'done2' } },
    { sequence: 6, type: 'assistant.delta', runId: 'r1', payload: { text: '最终答复' } },
    { sequence: 7, type: 'run.completed', runId: 'r1', payload: { summary: 'done' } },
  ];

  // 先加载后半页（模拟截断），再 prepend 前半页
  const backDraft = makeDraft(newerEvents);
  const mountedTurnKey = backDraft.thread.rows.find(r => r.type === 'turn').key;
  prependHistoryPage(backDraft, olderEvents);

  const turns = backDraft.thread.rows.filter(r => r.type === 'turn');
  assert.equal(turns.length, 1, '跨页同一 runId 必须合并为单个 Turn');
  assert.equal(turns[0].key, mountedTurnKey, '合并后必须保留原挂载 key 以维持滚动锚定');
  assert.equal(turns[0].tools.length, 2);
  assert.equal(turns[0].reply.text, '最终答复');

  // 反向：先加载前半页，再 append 后半页
  const forwardDraft = makeDraft(olderEvents);
  const firstKey = forwardDraft.thread.rows.find(r => r.type === 'turn').key;
  appendHistoryPage(forwardDraft, newerEvents);
  const forwardTurns = forwardDraft.thread.rows.filter(r => r.type === 'turn');
  assert.equal(forwardTurns.length, 1);
  assert.equal(forwardTurns[0].key, firstKey);
  assert.equal(forwardTurns[0].tools.length, 2);
  assert.equal(forwardTurns[0].reply.text, '最终答复');
});

test('loadHistory 附带 alignTurns + includeRunChanges 同步水合文件变动（0 次 N+1 请求），支持 1 跳 around 与返回最新', async () => {
  const saved = useStore.getState();
  const originalApi = { ...api };
  const historyCalls = [];
  let runChangesCalls = 0;

  api.history = async (_id, params = {}) => {
    historyCalls.push(params);
    if (params.around) {
      return {
        events: [
          { sequence: 10, type: 'run.started', runId: 'run_old', payload: { input: '历史锚点问题' } },
          { sequence: 11, type: 'assistant.delta', runId: 'run_old', payload: { text: '历史锚点回答' } },
          { sequence: 12, type: 'run.completed', runId: 'run_old', payload: { summary: 'ok', changedFiles: ['a.ts'] } },
        ],
        hasMore: true,
        hasNewer: true,
        total: 100,
        historyRevision: 5,
        runChanges: {
          run_old: {
            runId: 'run_old',
            workspace: '/w',
            gitRepository: true,
            files: [{ path: 'a.ts', status: 'modified', added: 5, removed: 1 }],
            added: 5,
            removed: 1,
          },
        },
      };
    }
    return {
      events: [
        { sequence: 98, type: 'run.started', runId: 'run_new', payload: { input: '最新问题' } },
        { sequence: 99, type: 'assistant.delta', runId: 'run_new', payload: { text: '最新回答' } },
        { sequence: 100, type: 'run.completed', runId: 'run_new', payload: { summary: 'ok', changedFiles: ['b.ts'] } },
      ],
      hasMore: true,
      hasNewer: false,
      total: 100,
      historyRevision: 5,
      runChanges: {
        run_new: {
          runId: 'run_new',
          workspace: '/w',
          gitRepository: true,
          files: [{ path: 'b.ts', status: 'modified', added: 8, removed: 2 }],
          added: 8,
          removed: 2,
        },
      },
    };
  };
  api.runChanges = async () => {
    runChangesCalls++;
    return { files: [] };
  };

  useStore.setState({
    sessionId: 'ses-hist',
    sessionEpoch: 1,
    thread: initialThread(),
    loadSessions: async () => [],
    loadTask: async () => {},
    loadQueue: async () => {},
    refreshContext: async () => {},
  });

  try {
    await useStore.getState().loadHistory('ses-hist', 1);
    assert.equal(historyCalls[0].alignTurns, true);
    assert.equal(historyCalls[0].includeRunChanges, true);
    assert.equal(runChangesCalls, 0, '随页附带 runChanges 后不得再发起 N+1 api.runChanges 请求');
    const latestResult = useStore.getState().thread.rows.find(r => r.type === 'result');
    assert.equal(latestResult?.changedFiles?.[0]?.path, 'b.ts');
    assert.equal(latestResult?.changedFiles?.[0]?.added, 8);
    assert.equal(useStore.getState().thread.history.hasNewer, false);

    // 1 跳锚点直达第 11 条
    const jumped = await useStore.getState().loadHistoryAround(11);
    assert.equal(jumped, true);
    assert.equal(historyCalls[1].around, 11);
    assert.equal(historyCalls[1].alignTurns, true);
    assert.equal(useStore.getState().thread.history.hasNewer, true);
    assert.equal(useStore.getState().thread.rows.find(r => r.type === 'result')?.changedFiles?.[0]?.path, 'a.ts');

    // 一键回到最新
    await useStore.getState().returnToLatest();
    assert.equal(useStore.getState().thread.history.hasNewer, false);
    assert.equal(useStore.getState().thread.rows.find(r => r.type === 'result')?.changedFiles?.[0]?.path, 'b.ts');
  } finally {
    closeStreams();
    Object.assign(api, originalApi);
    useStore.setState(saved);
  }
});

test('会话切换：冷切显示 sessionLoading 骨架且并发拉取，回切命中 LRU 快照缓存 0ms 即显', async () => {
  const saved = useStore.getState();
  const originalApi = { ...api };
  const sessionGate = deferred();
  const historyGate = deferred();
  let sessionReqCount = 0;
  let historyReqCount = 0;

  api.session = async id => {
    sessionReqCount++;
    if (id === 'ses-cold') await sessionGate.promise;
    return {
      id,
      title: id === 'ses-cold' ? '冷切换会话' : '原会话',
      workspace: '/workspace/pilot',
      status: 'IDLE',
      running: false,
      historyRevision: 1,
    };
  };
  api.history = async id => {
    historyReqCount++;
    if (id === 'ses-cold') await historyGate.promise;
    return {
      events: [
        { sequence: 1, type: 'run.started', runId: `run_${id}`, payload: { input: `来自 ${id}` } },
        { sequence: 2, type: 'assistant.delta', runId: `run_${id}`, payload: { text: `回复 ${id}` } },
        { sequence: 3, type: 'run.completed', runId: `run_${id}`, payload: { summary: 'ok' } },
      ],
      hasMore: false,
      hasNewer: false,
      total: 3,
      historyRevision: 1,
      runChanges: {},
    };
  };
  api.task = async () => null;
  api.queue = async () => ({ items: [], messages: [], held: false, waitingReason: '' });
  api.context = async () => null;
  api.sessions = async () => [];
  api.projects = async () => ({ items: [] });

  const initialDraft = makeDraft([
    { sequence: 1, type: 'run.started', runId: 'run_a', payload: { input: '会话 A 问题' } },
    { sequence: 2, type: 'assistant.delta', runId: 'run_a', payload: { text: '会话 A 回复' } },
    { sequence: 3, type: 'run.completed', runId: 'run_a', payload: { summary: 'ok' } },
  ]);

  useStore.setState({
    sessionId: 'ses-a',
    sessionTitle: '原会话',
    sessionEpoch: 1,
    historyRevision: 1,
    sessionLoading: false,
    thread: initialDraft.thread,
    connectEvents: () => {},
    loadSessions: async () => [],
    loadTask: async () => {},
    loadQueue: async () => {},
    refreshContext: async () => {},
    loadFiles: async () => {},
    loadWorkspaceChanges: async () => {},
    loadNotes: async () => {},
  });

  try {
    // 1. 冷切换到 ses-cold：立即进入 sessionLoading 骨架态，并同时并发发出 session + history 请求
    const coldPromise = useStore.getState().activate('ses-cold');
    assert.equal(useStore.getState().sessionLoading, true, '冷切期间应标记 sessionLoading 骨架态');
    assert.equal(useStore.getState().sessionId, 'ses-cold');
    assert.equal(sessionReqCount, 1);
    assert.equal(historyReqCount, 1, 'session 与 history 应在冷切时并发启动而非串行等待');

    sessionGate.resolve();
    historyGate.resolve();
    await coldPromise;
    assert.equal(useStore.getState().sessionLoading, false);
    assert.equal(useStore.getState().thread.rows.find(r => r.type === 'turn')?.reply?.text, '回复 ses-cold');

    // 2. 切回已访问的 ses-a：同步命中 LRU 快照缓存，不闪烁 sessionLoading，立即呈现原会话消息
    const backPromise = useStore.getState().activate('ses-a');
    assert.equal(useStore.getState().sessionLoading, false, '命中缓存回切不得闪回骨架或空态');
    assert.equal(useStore.getState().thread.rows.find(r => r.type === 'turn')?.reply?.text, '会话 A 回复');
    await backPromise;
  } finally {
    Object.assign(api, originalApi);
    useStore.setState(saved);
  }
});
