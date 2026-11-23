import test from 'node:test';
import assert from 'node:assert/strict';
import {
  ALERT_WIDTH,
  alertLevelForPlatform,
  alertWindowHtml,
  computeAlertBounds,
  createDesktopAlertManager,
  floatingWindowOptions,
  normalizeAlertPayload,
  validateAlertAction
} from '../desktop-alerts.mjs';

test('computeAlertBounds places floating card in top-right on macOS and bottom-right on Windows/Linux', () => {
  const workArea = { x: 100, y: 50, width: 1440, height: 900 };
  const mac = computeAlertBounds({ workArea, width: ALERT_WIDTH, height: 220, platform: 'darwin' });
  assert.deepEqual(mac, { x: 100 + 1440 - ALERT_WIDTH - 16, y: 50 + 16, width: ALERT_WIDTH, height: 220 });

  const win = computeAlertBounds({ workArea, width: ALERT_WIDTH, height: 220, platform: 'win32' });
  assert.deepEqual(win, { x: 100 + 1440 - ALERT_WIDTH - 16, y: 50 + 900 - 220 - 16, width: ALERT_WIDTH, height: 220 });

  const linux = computeAlertBounds({ workArea, width: ALERT_WIDTH, height: 220, platform: 'linux' });
  assert.deepEqual(linux, win);

  const tiny = computeAlertBounds({ workArea: { x: 0, y: 0, width: 320, height: 200 }, width: 420, height: 500, platform: 'win32' });
  assert.ok(tiny.x >= 0 && tiny.y >= 0);
  assert.ok(tiny.width <= 320 && tiny.height <= 200);
});

test('floating window options avoid Linux black transparency bug and pick safe always-on-top levels', () => {
  assert.equal(alertLevelForPlatform('darwin'), 'floating');
  assert.equal(alertLevelForPlatform('win32'), 'pop-up-menu');
  assert.equal(alertLevelForPlatform('linux'), 'floating');

  const opts = floatingWindowOptions({ platform: 'linux', theme: 'light', preloadPath: '/tmp/alert-preload.cjs' });
  assert.equal(opts.transparent, false);
  assert.equal(opts.skipTaskbar, true);
  assert.equal(opts.alwaysOnTop, true);
  assert.equal(opts.webPreferences.contextIsolation, true);
  assert.equal(opts.webPreferences.nodeIntegration, false);
  assert.equal(opts.webPreferences.sandbox, true);
});

test('payload normalization & action validation sanitize inputs', () => {
  const item = normalizeAlertPayload({
    id: 'question:c1',
    kind: 'question',
    sessionId: 'ses_1',
    sessionTitle: '构建修复',
    callId: 'c1',
    title: '选择方案',
    body: '请确定接下来的构建方式',
    options: Array.from({ length: 12 }, (_, i) => `选项 ${i + 1}`).concat(['', '   ']),
    highRisk: true,
    expiresAt: '2026-11-23T13:10:00Z'
  });
  assert.equal(item.urgent, true);
  assert.equal(item.autoCloseMs, 0);
  assert.equal(item.options.length, 8);
  assert.ok(typeof item.expiresAt === 'number');

  assert.equal(validateAlertAction({ action: 'evil', id: '1' }), null);
  assert.equal(validateAlertAction({ action: 'answer', callId: 'c1', choice: '   ' }), null);
  assert.deepEqual(validateAlertAction({ action: 'approve', id: 'approval:c1', sessionId: 'ses_1', callId: 'c1' }), {
    action: 'approve',
    id: 'approval:c1',
    sessionId: 'ses_1',
    callId: 'c1'
  });
  assert.deepEqual(validateAlertAction({ action: 'reject', id: 'approval:c1', sessionId: 'ses_1', callId: 'c1', reason: '  请改用 pnpm  ' }), {
    action: 'reject',
    id: 'approval:c1',
    sessionId: 'ses_1',
    callId: 'c1',
    reason: '请改用 pnpm'
  });
  assert.deepEqual(validateAlertAction({ action: 'reject', id: 'approval:c1', callId: 'c1', reason: '   ' }), {
    action: 'reject',
    id: 'approval:c1',
    sessionId: '',
    callId: 'c1'
  });

  const html = alertWindowHtml();
  assert.ok(html.includes('Content-Security-Policy'));
  assert.ok(!html.includes('innerHTML'));
});

test('alert manager prioritizes urgent items, respects focus, and drives macOS dock and Win/Linux flashFrame', async () => {
  const sentStates = [];
  const createdWindows = [];
  const actions = [];
  let finishLoadCb = null;
  let shellFocused = false;
  const flashCalls = [];
  const dockCalls = [];

  class FakeWindow {
    constructor(options) {
      this.options = options;
      this.bounds = null;
      this.visible = false;
      this.shownInactive = 0;
      this.hidden = 0;
      this.topArgs = null;
      this.workspaceArgs = null;
      this.webContents = {
        send: (ch, data) => sentStates.push({ ch, data }),
        once: (ev, cb) => { if (ev === 'did-finish-load') finishLoadCb = cb; }
      };
      createdWindows.push(this);
    }
    setAlwaysOnTop(flag, level) { this.topArgs = [flag, level]; }
    setVisibleOnAllWorkspaces(flag, opts) { this.workspaceArgs = [flag, opts]; }
    setBounds(b) { this.bounds = b; }
    showInactive() { this.visible = true; this.shownInactive++; }
    hide() { this.visible = false; this.hidden++; }
    isDestroyed() { return false; }
    destroy() {}
    on() {}
    loadURL() { return Promise.resolve(); }
  }

  const fakeNotifications = [];
  class FakeNotification {
    static isSupported() { return true; }
    constructor(opts) {
      this.opts = opts;
      this.handlers = {};
      fakeNotifications.push(this);
    }
    on(ev, cb) { this.handlers[ev] = cb; }
    show() { this.shown = true; }
  }

  const fakeShell = {
    isDestroyed: () => false,
    isFocused: () => shellFocused,
    isMinimized: () => false,
    restore() {},
    show() {},
    focus() {},
    flashFrame(v) { flashCalls.push(v); },
    getBounds: () => ({ x: 0, y: 0, width: 1200, height: 800 })
  };

  const fakeApp = {
    dock: {
      setBadge(v) { dockCalls.push(['badge', v]); },
      bounce(t) { dockCalls.push(['bounce', t]); }
    }
  };

  const fakeScreen = {
    getCursorScreenPoint: () => ({ x: 500, y: 300 }),
    getDisplayNearestPoint: () => ({ workArea: { x: 0, y: 0, width: 1440, height: 900 } })
  };

  const manager = createDesktopAlertManager({
    BrowserWindow: FakeWindow,
    Notification: FakeNotification,
    screen: fakeScreen,
    app: fakeApp,
    getShell: () => fakeShell,
    onAction: (act) => actions.push(act),
    platform: 'darwin',
    preloadPath: '/tmp/alert-preload.cjs'
  });

  manager.show({ id: 'done:r1', kind: 'done', sessionId: 's1', title: '完成', autoCloseMs: 60000 });
  manager.show({ id: 'approval:c1', kind: 'approval', sessionId: 's2', callId: 'c1', title: '批准命令', body: 'npm test' });
  finishLoadCb?.();

  assert.equal(createdWindows.length, 1);
  assert.deepEqual(createdWindows[0].topArgs, [true, 'floating']);
  assert.equal(createdWindows[0].shownInactive > 0, true);
  const lastState = sentStates.at(-1).data.items;
  assert.equal(lastState[0].id, 'approval:c1');
  assert.equal(lastState[1].id, 'done:r1');

  manager.setWindowAttention({ urgentCount: 2, kind: 'attention', bounce: true });
  assert.deepEqual(dockCalls, [['badge', '2'], ['bounce', 'critical']]);

  manager.showNativeNotification({ title: 'Pilot', body: '需要批准', sessionId: 's2', id: 'approval:c1' });
  assert.equal(fakeNotifications.length, 1);
  assert.equal(fakeNotifications[0].opts.silent, true);
  fakeNotifications[0].handlers.click();
  assert.deepEqual(actions.at(-1), { action: 'focus-session', sessionId: 's2', id: 'approval:c1' });

  manager.dismiss('*');
  assert.equal(createdWindows[0].visible, false);
  manager.destroy();

  // Windows / Linux 任务栏闪烁校验
  flashCalls.length = 0;
  const winManager = createDesktopAlertManager({
    BrowserWindow: FakeWindow,
    Notification: FakeNotification,
    screen: fakeScreen,
    app: fakeApp,
    getShell: () => fakeShell,
    onAction: () => {},
    platform: 'win32',
    preloadPath: '/tmp/alert-preload.cjs'
  });
  shellFocused = false;
  winManager.setWindowAttention({ urgentCount: 1, kind: 'attention', bounce: true });
  shellFocused = true;
  winManager.setWindowAttention({ urgentCount: 1, kind: 'attention', bounce: false });
  assert.deepEqual(flashCalls, [true, false]);
  winManager.destroy();
});
