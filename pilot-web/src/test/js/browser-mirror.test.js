import test from 'node:test';
import assert from 'node:assert/strict';

import {
  frameSrc, latencyLabel, nextMirrorMode, ripplePosition, shouldAutoSwitch, urlLabel
} from '../../main/resources/static/lib/browser-mirror.js';

test('frameSrc 拼 dataURL，帧体缺失返回 null', () => {
  assert.equal(frameSrc({ frame: 'QUJD' }), 'data:image/jpeg;base64,QUJD');
  assert.equal(frameSrc({}), null);
  assert.equal(frameSrc(null), null);
  assert.equal(frameSrc({ frame: '' }), null);
});

test('latencyLabel 分档：实时 / 毫秒 / 秒前 / 无时间戳', () => {
  const now = 10_000;
  assert.equal(latencyLabel(now, now - 120), '实时');
  assert.equal(latencyLabel(now, now - 800), '800ms');
  assert.equal(latencyLabel(now, now - 4200), '4.2s 前');
  assert.equal(latencyLabel(now, 0), '');
  assert.equal(latencyLabel(now, undefined), '');
});

test('ripplePosition 按帧尺寸等比缩放到显示宽度', () => {
  // 1280×800 的帧缩放到 420px 宽的面板：同一缩放系数，宽高各自换算
  const spot = ripplePosition({ x: 640, y: 400 }, 1280, 800, 420);
  assert.deepEqual(spot, { x: 210, y: 131.25 });
});

test('ripplePosition 容缺省与非法坐标', () => {
  // 帧尺寸缺失时按 sidecar 固定 viewport 1280×800 兜底
  assert.deepEqual(ripplePosition({ x: 1280, y: 800 }, 0, 0, 640), { x: 640, y: 400 });
  assert.equal(ripplePosition({ x: 'abc', y: 10 }, 1280, 800, 420), null);
  assert.equal(ripplePosition(null, 1280, 800, 420), null);
});

test('nextMirrorMode 状态机：帧到直播、回收置灰、会话切换复位', () => {
  assert.equal(nextMirrorMode('idle', 'frame'), 'live');
  assert.equal(nextMirrorMode('live', 'closed'), 'closed');
  assert.equal(nextMirrorMode('closed', 'frame'), 'live');
  assert.equal(nextMirrorMode('live', 'reset'), 'idle');
  // 未知 kind 保持现状
  assert.equal(nextMirrorMode('live', 'whatever'), 'live');
});

test('shouldAutoSwitch 默认开、可关、每会话只一次', () => {
  assert.equal(shouldAutoSwitch(null, false), true);
  assert.equal(shouldAutoSwitch('1', false), true);
  assert.equal(shouldAutoSwitch('0', false), false);
  // 已经自动切过：即便偏好开着也不再打扰
  assert.equal(shouldAutoSwitch('1', true), false);
  assert.equal(shouldAutoSwitch(null, true), false);
});

test('urlLabel 空地址给可读占位', () => {
  assert.equal(urlLabel('http://localhost:9999/'), 'http://localhost:9999/');
  assert.equal(urlLabel(''), '等待智能体打开页面…');
  assert.equal(urlLabel(undefined), '等待智能体打开页面…');
  assert.equal(urlLabel('   '), '等待智能体打开页面…');
});
