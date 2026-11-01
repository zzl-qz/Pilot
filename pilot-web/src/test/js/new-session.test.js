import test from 'node:test';
import assert from 'node:assert/strict';

/*
 * 「点新任务不落库、发第一条消息才创建会话」这条链路。
 * store/api 在加载阶段就会碰 localStorage、window.fetch、EventSource，先补齐最小桩。
 */
const storage = new Map();
globalThis.localStorage = {
  getItem: (key) => (storage.has(key) ? storage.get(key) : null),
  setItem: (key, value) => storage.set(key, String(value)),
  removeItem: (key) => storage.delete(key)
};

let calls = [];
globalThis.EventSource = class {
  constructor(url) {
    this.url = url;
    this.listeners = new Map();
  }
  addEventListener(type, handler) { this.listeners.set(type, handler); }
  removeEventListener(type) { this.listeners.delete(type); }
  close() {}
};

globalThis.window = {
  fetch: async (input, init = {}) => {
    const url = String(input);
    const method = (init.method || 'GET').toUpperCase();
    calls.push({ url, method, body: init.body ? JSON.parse(init.body) : null });
    if (url === '/api/sessions' && method === 'POST') {
      const body = init.body ? JSON.parse(init.body) : {};
      return json({ sessionId: 'ses_new', id: 'ses_new', title: body.title || '新任务', workspace: body.workspace || '' });
    }
    if (url === '/api/sessions' && method === 'GET') {
      return json([{ sessionId: 'ses_1', id: 'ses_1', title: '旧对话', createdAt: '2026-01-01T00:00:00Z' }]);
    }
    if (url.includes('/history')) {
      return json({ events: [], hasMore: false });
    }
    if (url.includes('/messages')) {
      return json({ ok: true });
    }
    return json({});
  }
};

function json(data) {
  return { ok: true, status: 200, json: async () => data };
}

const { useStore } = await import('../../../frontend/src/store.js');
const { writeDraft, readDraft, draftKey } = await import('../../../frontend/src/lib/drafts.js');

function sessionsPosts() {
  return calls.filter((call) => call.url === '/api/sessions' && call.method === 'POST');
}

function reset() {
  calls = [];
  useStore.setState({
    sessionId: null,
    draftSession: null,
    sessionTitle: '',
    workspace: '',
    sessions: [],
    thread: { rows: [], windowEvents: [], cursor: 0, replaying: false, history: { hasMore: false, hasNewer: false, loading: false, forwardError: false, error: false, pageSize: 80, loadId: 0 }, activityCount: 0 }
  });
}

test('点新任务不落库，只进入草稿态', () => {
  reset();
  useStore.getState().startNewSession();
  assert.equal(sessionsPosts().length, 0, '不该创建会话记录');
  assert.equal(useStore.getState().sessionId, null);
  assert.deepEqual(useStore.getState().draftSession, { projectId: null, workspace: null });
});

test('项目里新建对话会把 projectId 带上，不再掉进未分组', () => {
  reset();
  useStore.getState().startNewSession({ projectId: 'prj_1', workspace: 'E:\\Pilot' });
  assert.deepEqual(useStore.getState().draftSession, { projectId: 'prj_1', workspace: 'E:\\Pilot' });
});

test('第一条消息触发创建，标题取输入开头，随后才发消息', async () => {
  reset();
  useStore.getState().startNewSession();
  await useStore.getState().submitInput('帮我看看 pom 里有哪些模块');

  const created = sessionsPosts();
  assert.equal(created.length, 1);
  assert.equal(created[0].body.projectId, undefined);
  assert.equal(created[0].body.replaceBlank, true, '旧版遗留的空白对话要顺手归档');
  assert.equal(created[0].body.title, '帮我看看 pom 里有哪些模块');

  const sent = calls.filter((call) => call.url.includes('/messages'));
  assert.equal(sent.length, 1);
  assert.match(sent[0].url, /ses_new\/messages$/);
  assert.equal(useStore.getState().sessionId, 'ses_new');
  assert.equal(useStore.getState().draftSession, null);
});

test('已有会话时不重复创建', async () => {
  reset();
  useStore.setState({ sessionId: 'ses_1' });
  await useStore.getState().submitInput('继续');
  assert.equal(sessionsPosts().length, 0);
  const sent = calls.filter((call) => call.url.includes('/messages'));
  assert.equal(sent.length, 1);
  assert.match(sent[0].url, /ses_1\/messages$/);
});

test('切回历史会话会退出草稿态', async () => {
  reset();
  useStore.getState().startNewSession();
  await useStore.getState().activate('ses_1');
  assert.equal(useStore.getState().draftSession, null);
  assert.equal(useStore.getState().sessionId, 'ses_1');
});

test('新对话草稿有自己的存储槽，刷新不丢', () => {
  const memory = {
    map: new Map(),
    getItem(key) { return this.map.has(key) ? this.map.get(key) : null; },
    setItem(key, value) { this.map.set(key, String(value)); },
    removeItem(key) { this.map.delete(key); }
  };
  assert.equal(draftKey(null), 'pilot.draft.__new__');
  writeDraft(memory, null, '还没发出去的想法');
  assert.equal(readDraft(memory, null), '还没发出去的想法');
  assert.equal(readDraft(memory, 'ses_1'), '');
});
