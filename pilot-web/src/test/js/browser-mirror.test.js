import test from 'node:test';
import assert from 'node:assert/strict';
import { browserStateUpdate, formatScreenshotFeedback, userPageUrl } from '../../../frontend/src/lib/browser-mirror.js';

test('地址栏：本机用 http，外网用 https，保留显式协议和中文路径', () => {
  for (const [input, expected] of [
    ['localhost:5173', 'http://localhost:5173/'],
    ['127.0.0.2:8080', 'http://127.0.0.2:8080/'],
    ['[::1]:8080', 'http://[::1]:8080/'],
    ['dev.localhost', 'http://dev.localhost/'],
    ['example.com', 'https://example.com/'],
    ['http://example.com/文档', 'http://example.com/%E6%96%87%E6%A1%A3'],
    ['https://localhost:5173/', 'https://localhost:5173/'],
    ['javascript:alert(1)', null], ['file:///tmp/a', null], ['', null], [null, null]
  ]) assert.equal(userPageUrl(input), expected, String(input));
});

test('状态流通过导航更新网址，并忽略截图帧', () => {
  assert.deepEqual(browserStateUpdate('frame', { url: 'https://example.test/', frame: 'QUJD', w: 1000, h: 800 }),
    {});
  assert.deepEqual(browserStateUpdate('nav', { url: 'https://example.test/', canGoBack: true, embed: false }),
    { mode: 'live', url: 'https://example.test/' });
  assert.deepEqual(browserStateUpdate('nav', { url: 'about:blank' }), { url: 'about:blank' });
  assert.deepEqual(browserStateUpdate('closed', null), {
    mode: 'closed',
    paused: false,
    agentControlled: false,
    controlStatus: ''
  });
});

test('支持 control-state、control-stopped 与 state 控制锁同步', () => {
  assert.deepEqual(browserStateUpdate('control-state', { agentControlled: true, controlStatus: '填写 e3' }), {
    agentControlled: true,
    controlStatus: '填写 e3'
  });
  assert.deepEqual(browserStateUpdate('control-stopped', { agentControlled: false }), {
    agentControlled: false,
    controlStatus: '',
    paused: true
  });
  assert.deepEqual(browserStateUpdate('state', { agentControlled: true, controlStatus: '点击 e5', paused: false }), {
    agentControlled: true,
    controlStatus: '点击 e5',
    paused: false
  });
});

test('兼容 state/status 事件，支持 headless/paused/lanes/handoffs', () => {
  for (const kind of ['state', 'status']) {
    assert.deepEqual(browserStateUpdate(kind, { headless: false, paused: true }), { headless: false, paused: true });
    assert.deepEqual(browserStateUpdate(kind, { headless: true }), { headless: true });
    assert.deepEqual(browserStateUpdate(kind, { headless: 'false', activeLane: 's1::browser::b1' }),
      { activeLane: 's1::browser::b1' });
  }
});

test('支持实时动作、接管通知与证据增量事件', () => {
  assert.deepEqual(browserStateUpdate('action', { action: 'click', status: 'done', summary: '点击 提交' }), {
    lastAction: { action: 'click', status: 'done', summary: '点击 提交' }
  });
  const pending = [{ handoffId: 'h1', sessionId: 's1', browserSessionId: 's1', kind: 'auth', reason: '请登录', requestedAt: 't' }];
  assert.deepEqual(browserStateUpdate('handoff', { event: 'requested', pending }), { handoffs: pending });
  assert.deepEqual(browserStateUpdate('evidence', { id: 'ev_1' }, { evidenceCount: 2, evidenceVersion: 4 }), {
    evidenceCount: 3,
    evidenceVersion: 5
  });
});

test('formatScreenshotFeedback 生成带坐标与比例的结构化反馈文本', () => {
  const feedback = formatScreenshotFeedback({
    url: 'http://localhost:5173/login',
    title: '登录页',
    x: 120,
    y: 240,
    width: 300,
    height: 80,
    naturalWidth: 1200,
    naturalHeight: 800,
    note: '按钮间距太窄'
  });
  assert.match(feedback, /框选区域 \(120, 240\) 尺寸 300×80px/);
  assert.match(feedback, /约画面 10%, 30%/);
  assert.match(feedback, /按钮间距太窄/);
});

test('非法事件和缺失字段不会清空已知状态', () => {
  for (const payload of [null, undefined, [], 'invalid', 7]) assert.deepEqual(browserStateUpdate('frame', payload), {});
  assert.deepEqual(browserStateUpdate('unknown', { url: 'https://example.test/' }), {});
  assert.deepEqual(browserStateUpdate('nav', { url: 123 }), {});
  assert.deepEqual(browserStateUpdate('state', {}), {});
});
