import test from 'node:test';
import assert from 'node:assert/strict';

const { DEFAULT_ALERT_PREFS, resetSoundCooldown } = await import('../src/lib/sound.ts');
const {
  buildApprovalFloatingAlert,
  buildQuestionFloatingAlert,
  buildRunFloatingAlert,
  dispatchAttentionAlert,
  resetAlertDedup,
  resolveNotificationChannel,
  shouldShowFloatingAlert
} = await import('../src/lib/alert-dispatcher.ts');

test('悬浮窗策略按桌面桥可用性、模式和后台状态决定', () => {
  assert.equal(shouldShowFloatingAlert('background', false, true), false);
  assert.equal(shouldShowFloatingAlert('background', true, true), true);
  assert.equal(shouldShowFloatingAlert('always', false, true), true);
  assert.equal(shouldShowFloatingAlert('off', true, true), false);
  assert.equal(shouldShowFloatingAlert('always', true, false), false);
});

test('通知通道在前台、桌面端与普通浏览器下准确分流', () => {
  assert.equal(resolveNotificationChannel({
    kind: 'done', hidden: false, notifyEnabled: true, desktopNotifyOnBackground: true,
    browserPermission: 'granted', hasDesktopBridge: true
  }), 'none');
  assert.equal(resolveNotificationChannel({
    kind: 'attention', hidden: true, notifyEnabled: false, desktopNotifyOnBackground: true,
    browserPermission: 'denied', hasDesktopBridge: true
  }), 'desktop');
  assert.equal(resolveNotificationChannel({
    kind: 'done', hidden: true, notifyEnabled: true, desktopNotifyOnBackground: false,
    browserPermission: 'granted', hasDesktopBridge: false
  }), 'web');
});

test('同一事件键在去重窗口内不会重复弹窗与双倍通知，各端桥接参数准确', async () => {
  resetAlertDedup();
  resetSoundCooldown();
  const floatingCalls = [];
  const nativeCalls = [];
  const windowAttentionCalls = [];
  const desktop = {
    async showFloatingAlert(payload) { floatingCalls.push(payload); return { ok: true, id: payload.id }; },
    async showNativeNotification(payload) { nativeCalls.push(payload); return { ok: true }; },
    async setWindowAttention(payload) { windowAttentionCalls.push(payload); return { ok: true }; }
  };
  const approvalAlert = buildApprovalFloatingAlert({
    callId: 'c1',
    sessionId: 's2',
    sessionTitle: '构建项目',
    toolName: 'run_command',
    argsSummary: 'rm -rf ./tmp-cache',
    structuredArgs: { command: 'rm -rf ./tmp-cache' },
    requestedAt: '2026-01-01T00:00:00Z',
    expiresAt: '2026-01-01T00:05:00Z'
  }, 'dark');
  assert.equal(approvalAlert.highRisk, true);
  assert.equal(approvalAlert.categoryLabel, '执行命令');

  const first = await dispatchAttentionAlert({
    eventKey: 'approval:c1',
    kind: 'approval',
    sessionId: 's2',
    title: '构建项目 · 等待批准',
    body: approvalAlert.body,
    prefs: { ...DEFAULT_ALERT_PREFS, soundEnabled: false },
    backgrounded: true,
    notifyEnabled: true,
    theme: 'dark',
    floatingAlert: approvalAlert,
    urgentCount: 2,
    desktop,
    now: 1000
  });
  assert.equal(first.deduped, false);
  assert.equal(first.floatingShown, true);
  assert.equal(first.notified, 'desktop');
  assert.equal(floatingCalls.length, 1);
  assert.equal(nativeCalls.length, 1);
  assert.deepEqual(windowAttentionCalls[0], { urgentCount: 2, kind: 'attention', bounce: true });

  const second = await dispatchAttentionAlert({
    eventKey: 'approval:c1',
    kind: 'approval',
    sessionId: 's2',
    title: '构建项目 · 等待批准',
    body: approvalAlert.body,
    prefs: { ...DEFAULT_ALERT_PREFS, soundEnabled: false },
    backgrounded: true,
    notifyEnabled: true,
    theme: 'dark',
    floatingAlert: approvalAlert,
    urgentCount: 2,
    desktop,
    now: 2000
  });
  assert.equal(second.deduped, true);
  assert.equal(floatingCalls.length, 1);

  const qAlert = buildQuestionFloatingAlert({
    callId: 'q1',
    sessionId: 's2',
    sessionTitle: '构建项目',
    question: '选哪项？',
    options: ['甲', '乙'],
    requestedAt: '2026-01-01T00:00:00Z'
  }, 'light');
  assert.deepEqual(qAlert.options, ['甲', '乙']);
  const runAlert = buildRunFloatingAlert('done', 's2', '构建项目', '所有测试已通过', 'light');
  assert.equal(runAlert.kind, 'done');
  assert.equal(runAlert.autoCloseMs, 6500);

  // 开启勿扰模式时：不响铃、不弹浮窗、不发原生通知
  const mutedRes = await dispatchAttentionAlert({
    eventKey: 'approval:c-dnd',
    kind: 'approval',
    sessionId: 's2',
    title: '构建项目',
    body: 'npm test',
    prefs: { ...DEFAULT_ALERT_PREFS, dndUntil: -1 },
    backgrounded: true,
    notifyEnabled: true,
    floatingAlert: approvalAlert,
    urgentCount: 1,
    desktop,
    now: 3000
  });
  assert.equal(mutedRes.floatingShown, false);
  assert.equal(mutedRes.sounded, false);
  assert.equal(mutedRes.notified, 'none');
  assert.equal(floatingCalls.length, 1);
});
