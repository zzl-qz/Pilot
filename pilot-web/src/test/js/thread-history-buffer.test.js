import test from 'node:test';
import assert from 'node:assert/strict';

/*
 * store.js 在模块加载阶段就会碰浏览器全局（api.js 绑定 window.fetch、initialUi 读 localStorage），
 * 所以先补最小桩，再动态 import。测试只关心「更早历史的一页缓冲」这一条链路。
 */
const storage = new Map();
globalThis.localStorage = {
  getItem: (key) => (storage.has(key) ? storage.get(key) : null),
  setItem: (key, value) => storage.set(key, String(value)),
  removeItem: (key) => storage.delete(key)
};

let pages = new Map();
let calls = [];
let delayMs = 0;
globalThis.window = {
  fetch: async (input) => {
    const url = String(input);
    calls.push(url);
    if (delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
    const before = Number(new URL(url, 'http://localhost').searchParams.get('before'));
    const page = pages.get(before) || { events: [], hasMore: false };
    return { ok: true, status: 200, json: async () => page };
  }
};

const { useStore } = await import('../../../frontend/src/store.js');
const { initialThread } = await import('../../../frontend/src/thread-reducer.js');

function event(sequence) {
  return { type: 'user.message', sequence, text: `m${sequence}` };
}

function seed(sessionId, sequences, hasMore = true) {
  const thread = initialThread();
  thread.windowEvents = sequences.map(event);
  thread.history.hasMore = hasMore;
  useStore.setState((draft) => {
    draft.sessionId = sessionId;
    draft.thread = thread;
  });
}

function reset() {
  pages = new Map();
  calls = [];
  delayMs = 0;
}

test('预取把更早一页拉进内存，同一游标不重复请求', async () => {
  reset();
  seed('buf-a', [100, 101, 102]);
  pages.set(100, { events: [event(98), event(99)], hasMore: true });

  assert.equal(await useStore.getState().prefetchEarlier(), true);
  assert.equal(calls.length, 1);
  // 缓冲命中：再进预取区不再打后端
  assert.equal(await useStore.getState().prefetchEarlier(), true);
  assert.equal(calls.length, 1);
  // 预取不改变 DOM
  assert.equal(useStore.getState().thread.windowEvents.length, 3);
});

test('越过边界时用缓冲直接插入，不再等网络', async () => {
  reset();
  seed('buf-b', [200, 201]);
  pages.set(200, { events: [event(198), event(199)], hasMore: false });

  await useStore.getState().prefetchEarlier();
  const before = calls.length;
  assert.equal(await useStore.getState().requestEarlierHistory(), true);
  // 关键：插入这一趟没有新的网络请求
  assert.equal(calls.length, before);
  assert.deepEqual(
    useStore.getState().thread.windowEvents.map((item) => item.sequence),
    [198, 199, 200, 201]
  );
  assert.equal(useStore.getState().thread.history.hasMore, false);
});

test('缓冲用完立刻补预取下一页，连续上滑不断档', async () => {
  reset();
  seed('buf-c', [300, 301]);
  pages.set(300, { events: [event(298), event(299)], hasMore: true });
  pages.set(298, { events: [event(296), event(297)], hasMore: true });

  await useStore.getState().prefetchEarlier();
  assert.equal(calls.length, 1);
  await useStore.getState().requestEarlierHistory();
  await new Promise((resolve) => setTimeout(resolve, 0));
  // 补预取：第二页已经在路上，下一次贴顶同样不用等
  assert.equal(calls.length, 2);
  assert.match(calls[1], /before=298/);
});

test('预取还在路上时贴到顶部，只等这一趟并复用结果', async () => {
  reset();
  delayMs = 30;
  seed('buf-d', [400, 401]);
  pages.set(400, { events: [event(398), event(399)], hasMore: true });
  pages.set(398, { events: [], hasMore: false });

  const inflight = useStore.getState().prefetchEarlier();
  const applied = useStore.getState().requestEarlierHistory();
  const [ready, done] = await Promise.all([inflight, applied]);
  assert.equal(ready, true);
  assert.equal(done, true);
  // 一次预取 + 插入后补的那页，不能因为「贴顶触发」再多发一趟
  const historyCalls = calls.filter((url) => url.includes('before=400'));
  assert.equal(historyCalls.length, 1);
  assert.deepEqual(
    useStore.getState().thread.windowEvents.map((item) => item.sequence),
    [398, 399, 400, 401]
  );
});

test('缓冲对不上当前窗口游标时不插入，退回正常请求', async () => {
  reset();
  seed('buf-e', [500, 501]);
  pages.set(500, { events: [event(498), event(499)], hasMore: true });
  pages.set(450, { events: [event(448)], hasMore: true });

  await useStore.getState().prefetchEarlier();
  // 模拟别的路径把窗口头换了（裁剪 / 恢复检查点）
  useStore.setState((draft) => {
    draft.thread.windowEvents.unshift(event(450));
  });
  assert.equal(useStore.getState().applyEarlierPage(), false);
  assert.equal(await useStore.getState().requestEarlierHistory(), true);
  assert.deepEqual(
    useStore.getState().thread.windowEvents.map((item) => item.sequence),
    [448, 450, 500, 501]
  );
});
