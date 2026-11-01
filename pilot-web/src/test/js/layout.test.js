import test from 'node:test';
import assert from 'node:assert/strict';

import { clampPanelWidth, dragWidth, readLayout, writeLayout, LAYOUT_LIMITS } from '../../../frontend/src/lib/layout.js';

function memoryStorage(initial) {
  const data = new Map(Object.entries(initial || {}));
  return {
    getItem: (key) => (data.has(key) ? data.get(key) : null),
    setItem: (key, value) => { data.set(key, String(value)); },
    data
  };
}

test('clampPanelWidth 收在各自区间内', () => {
  assert.equal(clampPanelWidth('sidebar', 100, 1600), LAYOUT_LIMITS.sidebar[0]);
  assert.equal(clampPanelWidth('sidebar', 9000, 1600), LAYOUT_LIMITS.sidebar[1]);
  assert.equal(clampPanelWidth('activity', 10, 1600), LAYOUT_LIMITS.activity[0]);
  assert.equal(clampPanelWidth('activity', 5000, 1600), LAYOUT_LIMITS.activity[1]);
});

test('clampPanelWidth 给中间对话区留位置', () => {
  // 视口只有 800px 时，右栏最多 380px（800 - 420），不能再按上限 760 走
  assert.equal(clampPanelWidth('activity', 700, 800), 380);
  // 视口窄到放不下最小值时，退回下限，不返回负数
  assert.equal(clampPanelWidth('activity', 700, 500), LAYOUT_LIMITS.activity[0]);
  // 非法输入退回下限
  assert.equal(clampPanelWidth('sidebar', Number.NaN, 1600), LAYOUT_LIMITS.sidebar[0]);
});

test('dragWidth 左栏向右变宽、右栏向左变宽', () => {
  assert.equal(dragWidth('sidebar', 264, 60, 1600), 324);
  assert.equal(dragWidth('activity', 400, -50, 1600), 450);
  assert.equal(dragWidth('activity', 400, 50, 1600), 350);
});

test('readLayout 读回保存的宽度，坏数据当没存过', () => {
  const storage = memoryStorage();
  writeLayout({ sidebarW: 300, activityW: 460 }, storage);
  assert.deepEqual(readLayout(storage), { sidebarW: 300, activityW: 460 });

  assert.deepEqual(readLayout(memoryStorage({ 'pilot.layout': 'not json' })), { sidebarW: null, activityW: null });
  assert.deepEqual(readLayout(memoryStorage({ 'pilot.layout': '{"sidebarW":-5}' })), { sidebarW: null, activityW: null });
  assert.deepEqual(readLayout(null), { sidebarW: null, activityW: null });
});
