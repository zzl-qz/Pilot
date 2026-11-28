import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_WINDOW_BOUNDS,
  buildTrayMenuTemplate,
  createOfflineReconnector,
  createPowerAwakeController,
  formatTrayStatus,
  normalizeWindowBounds,
  shouldHideOnClose
} from '../desktop-shell.mjs';

test('normalizeWindowBounds clamps bounds onto available display workArea', () => {
  const displays = [{ workArea: { x: 0, y: 25, width: 1440, height: 875 } }];
  assert.deepEqual(normalizeWindowBounds(null, displays), {
    width: DEFAULT_WINDOW_BOUNDS.width,
    height: DEFAULT_WINDOW_BOUNDS.height,
    maximized: false
  });

  // 外接屏幕拔掉导致负坐标越界：应被夹回当前主屏幕工作区内
  const clamped = normalizeWindowBounds(
    { x: 3200, y: -200, width: 1200, height: 800, maximized: true },
    displays
  );
  assert.equal(clamped.x, 1440 - 1200);
  assert.equal(clamped.y, 25);
  assert.equal(clamped.maximized, true);

  // 多显示器匹配：落在第二块显示器内时保持在第二块屏内
  const multi = [
    { workArea: { x: 0, y: 0, width: 1440, height: 900 } },
    { workArea: { x: 1440, y: 0, width: 1920, height: 1080 } }
  ];
  const onSecond = normalizeWindowBounds({ x: 1500, y: 40, width: 1100, height: 760 }, multi);
  assert.equal(onSecond.x, 1500);
  assert.equal(onSecond.y, 40);
});

test('关闭窗口仅在已创建系统托盘且启用时隐藏', () => {
  assert.equal(shouldHideOnClose({ minimizeToTray: true, trayAvailable: true }), true);
  assert.equal(shouldHideOnClose({ minimizeToTray: true, trayAvailable: false }), false);
  assert.equal(shouldHideOnClose({ minimizeToTray: false, trayAvailable: true }), false);
});

test('buildTrayMenuTemplate reflects runtime counts, pending shortcut, and prefs', () => {
  let called = '';
  const items = buildTrayMenuTemplate({
    runningCount: 2,
    pendingCount: 1,
    dndActive: true,
    keepAwake: true,
    windowVisible: false,
    handlers: {
      focusPending: () => { called = 'pending'; },
      toggleDnd: () => { called = 'dnd'; }
    }
  });
  assert.match(items[0].label, /1 项待处理/);
  assert.match(items[0].label, /2 项执行中/);
  assert.equal(formatTrayStatus({ pendingCount: 1, runningCount: 2 }, { separator: ' / ', prefix: 'Pilot · ', idle: 'Pilot 工作台' }), 'Pilot · 1 项待处理 / 2 项执行中');
  assert.equal(formatTrayStatus({}, { separator: ' / ', prefix: 'Pilot · ', idle: 'Pilot 工作台' }), 'Pilot 工作台');
  const showItem = items.find((i) => i.label === '显示主窗口');
  assert.ok(showItem);
  const pendingItem = items.find((i) => i.label?.includes('处理待确认事项'));
  assert.ok(pendingItem);
  pendingItem.click();
  assert.equal(called, 'pending');
  const dndItem = items.find((i) => i.label?.includes('勿扰'));
  assert.equal(dndItem.checked, true);
});

test('power awake controller holds suspension lock only while keepAwake && runningCount > 0', () => {
  const calls = [];
  const fakeBlocker = {
    start(type) { calls.push(['start', type]); return 77; },
    isStarted(id) { return id === 77 && calls.some((c) => c[0] === 'start') && !calls.some((c) => c[0] === 'stop'); },
    stop(id) { calls.push(['stop', id]); }
  };
  const ctrl = createPowerAwakeController({ powerSaveBlocker: fakeBlocker });
  assert.equal(ctrl.update({ keepAwake: false, runningCount: 2 }), false);
  assert.equal(calls.length, 0);

  assert.equal(ctrl.update({ keepAwake: true, runningCount: 2 }), true);
  assert.equal(ctrl.isActive(), true);
  assert.deepEqual(calls, [['start', 'prevent-app-suspension']]);

  // 重复刷新 runningCount 不重复申请
  assert.equal(ctrl.update({ keepAwake: true, runningCount: 1 }), true);
  assert.equal(calls.length, 1);

  assert.equal(ctrl.update({ keepAwake: true, runningCount: 0 }), false);
  assert.equal(ctrl.isActive(), false);
  assert.deepEqual(calls.at(-1), ['stop', 77]);
});

test('offline reconnector probes healthUrl and fires onReady once healthy', async () => {
  let checks = 0;
  let recovered = 0;
  const reconnector = createOfflineReconnector({
    healthUrl: 'http://127.0.0.1:9999/api/health',
    fetchImpl: async () => {
      checks += 1;
      return { ok: checks >= 2 };
    },
    onReady: () => { recovered += 1; }
  });
  reconnector.start();
  assert.equal(reconnector.isActive(), true);
  assert.equal(await reconnector.probe(), false);
  assert.equal(recovered, 0);
  assert.equal(await reconnector.probe(), true);
  assert.equal(recovered, 1);
  assert.equal(reconnector.isActive(), false);
});
