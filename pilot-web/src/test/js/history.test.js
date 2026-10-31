import test from 'node:test';
import assert from 'node:assert/strict';
import {
  canLoadEarlier,
  HISTORY_AUTO_LOAD_THRESHOLD,
  isNearThreadBottom,
  shouldShowThreadBottom,
  shouldAutoLoadEarlier
} from '../../main/resources/static/lib/history.js';

test('接近消息顶部时自动加载更早历史', () => {
  assert.equal(shouldAutoLoadEarlier({
    scrollTop: HISTORY_AUTO_LOAD_THRESHOLD,
    previousScrollTop: HISTORY_AUTO_LOAD_THRESHOLD + 20,
    hasMore: true,
    loading: false,
    eventCount: 80
  }), true);
  assert.equal(shouldAutoLoadEarlier({
    scrollTop: HISTORY_AUTO_LOAD_THRESHOLD + 1,
    previousScrollTop: HISTORY_AUTO_LOAD_THRESHOLD + 20,
    hasMore: true,
    loading: false,
    eventCount: 80
  }), false);
});

test('首次程序滚到底部和用户向下滚动不会误触发自动分页', () => {
  assert.equal(shouldAutoLoadEarlier({
    scrollTop: 0,
    previousScrollTop: 0,
    hasMore: true,
    loading: false,
    eventCount: 80
  }), false);
  assert.equal(shouldAutoLoadEarlier({
    scrollTop: 150,
    previousScrollTop: 100,
    hasMore: true,
    loading: false,
    eventCount: 80
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
