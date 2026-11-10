import test from 'node:test';
import assert from 'node:assert/strict';
import { browserStateUpdate, userPageUrl } from '../../../frontend/src/lib/browser-mirror.js';

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

test('帧和导航只更新活跃状态及网址，不保存截图和过期的镜像字段', () => {
  assert.deepEqual(browserStateUpdate('frame', { url: 'https://example.test/', frame: 'QUJD', w: 1000, h: 800 }),
    { mode: 'live', url: 'https://example.test/' });
  assert.deepEqual(browserStateUpdate('nav', { url: 'https://example.test/', canGoBack: true, embed: false }),
    { mode: 'live', url: 'https://example.test/' });
  assert.deepEqual(browserStateUpdate('nav', { url: 'about:blank' }), { url: 'about:blank' });
  assert.deepEqual(browserStateUpdate('closed', null), { mode: 'closed' });
});

test('兼容 state/status 事件并保留明确的 headless=false', () => {
  for (const kind of ['state', 'status']) {
    assert.deepEqual(browserStateUpdate(kind, { headless: false, paused: true }), { headless: false });
    assert.deepEqual(browserStateUpdate(kind, { headless: true }), { headless: true });
    assert.deepEqual(browserStateUpdate(kind, { headless: 'false' }), {});
  }
});

test('非法事件和缺失字段不会清空已知状态', () => {
  for (const payload of [null, undefined, [], 'invalid', 7]) assert.deepEqual(browserStateUpdate('frame', payload), {});
  assert.deepEqual(browserStateUpdate('unknown', { url: 'https://example.test/' }), {});
  assert.deepEqual(browserStateUpdate('nav', { url: 123 }), {});
  assert.deepEqual(browserStateUpdate('state', {}), {});
});
