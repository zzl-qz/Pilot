import test from 'node:test';
import assert from 'node:assert/strict';
import { createCoordinationFeed } from '../../../frontend/src/lib/coordination/feed.js';

const READY = { type: 'workspace.ready', payload: { projectId: 'p1', directories: ['/repo', '/repo/shared'], earliestSequence: 1, latestSequence: 9 } };

function eventFrame(sequence, type = 'session.started') {
  return { type, sequence, timestamp: '2026-11-23T00:00:00Z', projectId: 'p1', sessionId: 's1', directory: '/repo', subject: 's1', details: {} };
}

function fakeSources() {
  const created = [];
  const factory = (url) => {
    const listeners = new Map();
    const source = {
      url, closed: false, onerror: null,
      addEventListener(type, listener) { listeners.set(type, [...(listeners.get(type) || []), listener]); },
      close() { source.closed = true; },
      emit(type, payload) { for (const listener of listeners.get(type) || []) listener({ data: JSON.stringify(payload) }); },
      emitRaw(type, data) { for (const listener of listeners.get(type) || []) listener({ data }); },
      fail() { source.onerror?.({}); }
    };
    created.push(source);
    return source;
  };
  return { created, factory, last: () => created[created.length - 1] };
}

function fixture() {
  const sources = fakeSources();
  const timers = [];
  const frames = [];
  const states = [];
  let resets = 0;
  const feed = createCoordinationFeed({
    url: (cursor) => `/api/workspace/events?workspace=%2Frepo&session=s1&after=${cursor}`,
    createSource: sources.factory,
    onEvent: (frame) => frames.push(frame),
    onState: (state) => states.push(state),
    onReset: () => { resets += 1; },
    schedule: (run, delayMs) => { const timer = { run, delayMs }; timers.push(timer); return timer; },
    cancel: (timer) => { timer.cancelled = true; }
  });
  return { feed, sources, timers, frames, states, resets: () => resets };
}

test('首次连接从游标 0 开始，ready 后进入实时并记录协调范围', () => {
  const f = fixture();
  f.feed.start();
  assert.equal(f.sources.last().url, '/api/workspace/events?workspace=%2Frepo&session=s1&after=0');
  assert.equal(f.feed.state().status, 'connecting');
  f.sources.last().emit('workspace.ready', READY);
  assert.equal(f.feed.state().status, 'live');
  assert.deepEqual(f.feed.state().directories, ['/repo', '/repo/shared']);
  assert.equal(f.feed.state().projectId, 'p1');
});

test('同一序号重复投递只交付一次，乱序旧事件被丢弃', () => {
  const f = fixture();
  f.feed.start();
  const source = f.sources.last();
  source.emit('session.started', eventFrame(3));
  source.emit('session.started', eventFrame(3));
  source.emit('session.started', eventFrame(2));
  source.emit('run.started', eventFrame(4, 'run.started'));
  assert.deepEqual(f.frames.map((frame) => frame.sequence), [3, 4]);
  assert.equal(f.feed.state().cursor, 4);
});

test('断线按指数退避重连，重连带最后序号续传不丢事件', () => {
  const f = fixture();
  f.feed.start();
  const first = f.sources.last();
  first.emit('session.started', eventFrame(5));
  first.fail();
  assert.equal(first.closed, true);
  assert.equal(f.feed.state().status, 'reconnecting');
  assert.equal(f.feed.state().attempts, 1);
  assert.equal(f.timers[0].delayMs, 1000);
  f.timers[0].run();
  assert.equal(f.sources.last().url, '/api/workspace/events?workspace=%2Frepo&session=s1&after=5');
  f.sources.last().emit('run.finished', eventFrame(6, 'run.finished'));
  assert.deepEqual(f.frames.map((frame) => frame.sequence), [5, 6]);
});

test('连续失败时退避时间翻倍并有上限', () => {
  const f = fixture();
  f.feed.start();
  for (let attempt = 0; attempt < 7; attempt += 1) {
    f.sources.last().fail();
    f.timers[f.timers.length - 1].run();
  }
  assert.deepEqual(f.timers.map((timer) => timer.delayMs).slice(0, 7), [1000, 2000, 4000, 8000, 16000, 30000, 30000]);
  assert.equal(f.feed.state().attempts, 7);
});

test('缓存滚动后重建看板并从游标 0 重新回放', () => {
  const f = fixture();
  f.feed.start();
  f.sources.last().emit('session.started', eventFrame(42));
  f.sources.last().emit('workspace.reset', { type: 'workspace.reset', payload: { earliestSequence: 80, latestSequence: 90, reason: 'cursor_expired' } });
  assert.equal(f.resets(), 1);
  assert.equal(f.feed.state().cursor, 0);
  assert.match(f.feed.state().lastError, /缓存/);
  f.timers[f.timers.length - 1].run();
  assert.equal(f.sources.last().url, '/api/workspace/events?workspace=%2Frepo&session=s1&after=0');
});

test('手动重试立即重连，停止后不再重连也不再交付事件', () => {
  const f = fixture();
  f.feed.start();
  f.sources.last().emit('session.started', eventFrame(7));
  f.sources.last().fail();
  f.feed.retry();
  // 手动重试取消排队的退避任务，不等定时器到点
  assert.equal(f.timers[0].cancelled, true);
  assert.equal(f.sources.last().url, '/api/workspace/events?workspace=%2Frepo&session=s1&after=7');
  const live = f.sources.last();
  f.feed.stop();
  assert.equal(live.closed, true);
  assert.equal(f.feed.state().status, 'stopped');
  live.emit('session.started', eventFrame(8));
  assert.deepEqual(f.frames.map((frame) => frame.sequence), [7]);
});

test('坏帧被忽略，error 控制帧只记录原因不推进游标', () => {
  const f = fixture();
  f.feed.start();
  const source = f.sources.last();
  source.emitRaw('session.started', '{不是 JSON');
  source.emit('workspace.error', { type: 'workspace.error', payload: { message: '无法建立工作区事件订阅' } });
  assert.equal(f.frames.length, 0);
  assert.equal(f.feed.state().cursor, 0);
  assert.equal(f.feed.state().lastError, '无法建立工作区事件订阅');
});
