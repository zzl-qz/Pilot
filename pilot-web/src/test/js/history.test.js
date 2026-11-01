import test from 'node:test';
import assert from 'node:assert/strict';
import {
  canLoadEarlier,
  HISTORY_WINDOW_LIMIT,
  historyPrefetchDistance,
  isValidEarlierHistoryPage,
  isValidLaterHistoryPage,
  isNearThreadBottom,
  mergeHistoryEvents,
  shouldAutoLoadEarlier,
  shouldAutoLoadLater,
  shouldShowThreadBottom,
  trimHistoryWindow,
  trimHistoryWindowAtRows
} from '../../../frontend/src/lib/history.js';

test('进入顶部预取区时自动加载更早历史，不等滚动停稳', () => {
  const viewportHeight = 600;
  const threshold = historyPrefetchDistance(viewportHeight);
  assert.equal(shouldAutoLoadEarlier({
    scrollTop: threshold,
    hasMore: true,
    loading: false,
    eventCount: 80,
    viewportHeight
  }), true);
  assert.equal(shouldAutoLoadEarlier({
    scrollTop: threshold + 1,
    hasMore: true,
    loading: false,
    eventCount: 80,
    viewportHeight
  }), false);
});

test('预取提前两个视口，同时保留短视口的最低距离', () => {
  assert.equal(historyPrefetchDistance(600), 1200);
  assert.equal(historyPrefetchDistance(20), 64);
  assert.equal(historyPrefetchDistance(0), 64);
});

test('预加载区内不依赖滚动方向或像素变化，避免漏触发', () => {
  assert.equal(shouldAutoLoadEarlier({
    scrollTop: 1180,
    hasMore: true,
    loading: false,
    eventCount: 80,
    viewportHeight: 600
  }), true);
  assert.equal(shouldAutoLoadEarlier({
    scrollTop: 1180,
    hasMore: true,
    loading: false,
    eventCount: 80,
    viewportHeight: 600
  }), true);
  assert.equal(shouldAutoLoadEarlier({
    scrollTop: 1201,
    hasMore: true,
    loading: false,
    eventCount: 80,
    viewportHeight: 600
  }), false);
});

test('加载中、没有更早页或没有事件游标时不会重复请求', () => {
  assert.equal(canLoadEarlier({ hasMore: true, loading: true, eventCount: 80 }), false);
  assert.equal(canLoadEarlier({ hasMore: false, loading: false, eventCount: 80 }), false);
  assert.equal(canLoadEarlier({ hasMore: true, loading: false, eventCount: 0 }), false);
});

test('只有离开消息尾部后才显示回到底部按钮', () => {
  const away = { scrollHeight: 2400, scrollTop: 800, clientHeight: 600 };
  assert.equal(shouldShowThreadBottom({ ...away, empty: false, following: false, jumping: false }), true);
  assert.equal(shouldShowThreadBottom({ ...away, empty: false, following: true, jumping: false }), false);
  assert.equal(shouldShowThreadBottom({ ...away, empty: false, following: false, jumping: true }), false);
  assert.equal(shouldShowThreadBottom({ ...away, empty: true, following: false, jumping: false }), false);
});

test('接近尾部时继续跟随，不显示多余的回到底部按钮', () => {
  const near = { scrollHeight: 2400, scrollTop: 1680, clientHeight: 600 };
  assert.equal(isNearThreadBottom(near), true);
  assert.equal(shouldShowThreadBottom({ ...near, empty: false, following: false, jumping: false }), false);
});

test('合并历史页按 sequence 排序并丢弃页间重复项', () => {
  const events = mergeHistoryEvents(
    [{ sequence: 1 }, { sequence: 2 }, { sequence: 3 }],
    [{ sequence: 3, duplicate: true }, { sequence: 4 }]
  );
  assert.deepEqual(events.map((event) => event.sequence), [1, 2, 3, 4]);
  assert.equal(events[2].duplicate, undefined);
});

test('连续历史页按严格 before 游标拼合后不重不漏', () => {
  const page1 = [{ sequence: 5 }, { sequence: 6 }, { sequence: 7 }];
  const page2 = [{ sequence: 2 }, { sequence: 3 }, { sequence: 4 }];
  const page3 = [{ sequence: 1 }];
  assert.equal(isValidEarlierHistoryPage(page2, page1[0].sequence), true);
  assert.equal(isValidEarlierHistoryPage(page3, page2[0].sequence), true);
  const all = mergeHistoryEvents(page3, mergeHistoryEvents(page2, page1));
  assert.deepEqual(all.map((event) => event.sequence), [1, 2, 3, 4, 5, 6, 7]);
});

test('拒绝不早于游标或缺少 sequence 的历史页', () => {
  assert.equal(isValidEarlierHistoryPage([{ sequence: 4 }, { sequence: 5 }], 5), false);
  assert.equal(isValidEarlierHistoryPage([{ sequence: 3 }, {}], 5), false);
  assert.equal(isValidEarlierHistoryPage([], 5), false);
});

test('双向游标页都必须严格位于当前窗口之外', () => {
  assert.equal(isValidLaterHistoryPage([{ sequence: 11 }, { sequence: 12 }], 10), true);
  assert.equal(isValidLaterHistoryPage([{ sequence: 10 }, { sequence: 12 }], 10), false);
  assert.equal(isValidLaterHistoryPage([{ sequence: 12 }, {}], 10), false);
});

test('滚到新消息窗口边缘时预取后续页', () => {
  assert.equal(shouldAutoLoadLater({
    distanceFromBottom: 1200, hasMore: true, loading: false, eventCount: 240, viewportHeight: 600
  }), true);
  assert.equal(shouldAutoLoadLater({
    distanceFromBottom: 1201, hasMore: true, loading: false, eventCount: 240, viewportHeight: 600
  }), false);
});

test('有限窗口按滚动方向保留锚点所在一侧的数据', () => {
  const events = Array.from({ length: HISTORY_WINDOW_LIMIT + 40 }, (_, index) => ({ sequence: index + 1 }));
  const older = trimHistoryWindow(events, 'older');
  assert.equal(older.events.length, HISTORY_WINDOW_LIMIT);
  assert.equal(older.events[0].sequence, 1);
  assert.equal(older.evictedAfter, true);
  const newer = trimHistoryWindow(events, 'newer');
  assert.equal(newer.events.length, HISTORY_WINDOW_LIMIT);
  assert.equal(newer.events.at(-1).sequence, events.at(-1).sequence);
  assert.equal(newer.evictedBefore, true);
});

test('窗口裁剪不会拆开跨页的 turn，而是在完整 row 边界丢弃', () => {
  const events = Array.from({ length: 12 }, (_, index) => ({ sequence: index + 1 }));
  const rows = [
    { start: 1, end: 2 },
    { start: 3, end: 6 },
    { start: 7, end: 8 },
    { start: 9, end: 12 }
  ];
  const older = trimHistoryWindowAtRows(events, rows, 'older', 7);
  assert.deepEqual(older.events.map((event) => event.sequence), [1, 2, 3, 4, 5, 6]);
  assert.equal(older.removeEndCount, 2);
  const newer = trimHistoryWindowAtRows(events, rows, 'newer', 7);
  assert.deepEqual(newer.events.map((event) => event.sequence), [7, 8, 9, 10, 11, 12]);
  assert.equal(newer.removeStartCount, 2);
});


