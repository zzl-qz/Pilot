import type { AlertPreferences } from '../types/store.js';
import type { DesktopBridge, DesktopFloatingAlert } from '../types/desktop.js';
import type { GlobalPendingApproval, GlobalPendingQuestion } from '../types/api.js';
import { classifyApprovalRisk, shouldNotify } from './attention.js';
import { isDndActive, playAlertSound, type AlertSoundKind } from './sound.js';

export type DispatchAlertKind = 'done' | 'error' | 'attention' | 'approval' | 'question';

const DEDUP_WINDOW_MS = 12_000;
const seenAlerts = new Map<string, number>();

export function resetAlertDedup() {
  seenAlerts.clear();
}

export function shouldShowFloatingAlert(
  mode: AlertPreferences['floatingMode'],
  backgrounded: boolean,
  hasDesktopBridge: boolean
): boolean {
  if (!hasDesktopBridge || mode === 'off') return false;
  return mode === 'always' || backgrounded;
}

export function resolveNotificationChannel({
  kind,
  hidden,
  notifyEnabled,
  desktopNotifyOnBackground,
  browserPermission,
  hasDesktopBridge
}: {
  kind: 'done' | 'error' | 'attention';
  hidden: boolean;
  notifyEnabled: boolean;
  desktopNotifyOnBackground: boolean;
  browserPermission: string;
  hasDesktopBridge: boolean;
}): 'desktop' | 'web' | 'none' {
  if (!hidden) return 'none';
  if (hasDesktopBridge && desktopNotifyOnBackground) return 'desktop';
  if (shouldNotify({ kind, hidden, enabled: notifyEnabled, permission: browserPermission })) return 'web';
  return 'none';
}

function markSeen(key: string, now = Date.now()): boolean {
  if (!key) return true;
  const previous = seenAlerts.get(key);
  if (previous != null && now - previous < DEDUP_WINDOW_MS) {
    return false;
  }
  seenAlerts.set(key, now);
  if (seenAlerts.size > 128) {
    const cutoff = now - DEDUP_WINDOW_MS;
    for (const [k, at] of seenAlerts.entries()) {
      if (at < cutoff) seenAlerts.delete(k);
    }
  }
  return true;
}

function soundKindFor(kind: DispatchAlertKind): AlertSoundKind {
  if (kind === 'approval') return 'approval';
  if (kind === 'question') return 'question';
  if (kind === 'error') return 'error';
  return 'done';
}

function noticeKindFor(kind: DispatchAlertKind): 'done' | 'error' | 'attention' {
  if (kind === 'done') return 'done';
  if (kind === 'error') return 'error';
  return 'attention';
}

export interface AlertDispatchContext {
  eventKey: string;
  kind: DispatchAlertKind;
  sessionId?: string;
  title: string;
  body?: string;
  prefs: AlertPreferences;
  backgrounded: boolean;
  notifyEnabled: boolean;
  theme?: 'light' | 'dark';
  floatingAlert?: DesktopFloatingAlert | null;
  urgentCount?: number;
  desktop?: DesktopBridge;
  onSelectSession?: (sessionId: string) => void;
  focusWindow?: () => void;
  NotificationCtor?: typeof Notification;
  now?: number;
}

/** 统一分发声音、桌面悬浮窗、系统原生通知与任务栏/Dock 注意力，并按事件键去重避免双倍提醒。 */
export async function dispatchAttentionAlert(ctx: AlertDispatchContext): Promise<{
  deduped: boolean;
  sounded: boolean;
  floatingShown: boolean;
  notified: 'desktop' | 'web' | 'none';
}> {
  if (!markSeen(ctx.eventKey, ctx.now)) {
    return { deduped: true, sounded: false, floatingShown: false, notified: 'none' };
  }
  if (isDndActive(ctx.prefs, ctx.now)) {
    return { deduped: false, sounded: false, floatingShown: false, notified: 'none' };
  }
  const hasDesktopBridge = Boolean(ctx.desktop?.showFloatingAlert);
  const sounded = await playAlertSound(soundKindFor(ctx.kind), {
    prefs: ctx.prefs,
    backgrounded: ctx.backgrounded,
    now: ctx.now
  });

  let floatingShown = false;
  if (ctx.floatingAlert && shouldShowFloatingAlert(ctx.prefs.floatingMode, ctx.backgrounded, hasDesktopBridge)) {
    try {
      await ctx.desktop?.showFloatingAlert?.({
        ...ctx.floatingAlert,
        theme: ctx.floatingAlert.theme || (ctx.theme === 'light' ? 'light' : 'dark')
      });
      floatingShown = true;
    } catch {
      // 悬浮窗创建失败不能影响 Web 通知与主线程
    }
  }

  const nKind = noticeKindFor(ctx.kind);
  const browserPermission = ctx.NotificationCtor
    ? ctx.NotificationCtor.permission
    : typeof globalThis.Notification === 'function' ? globalThis.Notification.permission : 'denied';
  const channel = resolveNotificationChannel({
    kind: nKind,
    hidden: ctx.backgrounded,
    notifyEnabled: ctx.notifyEnabled,
    desktopNotifyOnBackground: ctx.prefs.desktopNotifyOnBackground,
    browserPermission,
    hasDesktopBridge
  });

  if (channel === 'desktop') {
    try {
      await ctx.desktop?.showNativeNotification?.({
        id: ctx.floatingAlert?.id || ctx.eventKey,
        title: `Pilot · ${ctx.title}`,
        body: ctx.body || '',
        sessionId: ctx.sessionId || ''
      });
    } catch {
      // 通知权限未授予时忽略
    }
  } else if (channel === 'web') {
    const N = ctx.NotificationCtor || (typeof globalThis.Notification === 'function' ? globalThis.Notification : null);
    if (N) {
      try {
        const notice = new N(`Pilot · ${ctx.title}`, {
          body: ctx.body || '',
          tag: ctx.eventKey,
          silent: true
        });
        notice.onclick = () => {
          (ctx.focusWindow || (() => globalThis.window?.focus?.()))();
          if (ctx.sessionId) ctx.onSelectSession?.(ctx.sessionId);
          notice.close?.();
        };
      } catch {
        // 通知构造失败不影响主流程
      }
    }
  }

  if (ctx.prefs.taskbarAttention && ctx.desktop?.setWindowAttention) {
    try {
      await ctx.desktop.setWindowAttention({
        urgentCount: ctx.urgentCount ?? (nKind === 'attention' ? 1 : 0),
        kind: nKind,
        bounce: ctx.backgrounded
      });
    } catch {
      // 忽略任务栏提醒异常
    }
  }

  return { deduped: false, sounded, floatingShown, notified: channel };
}

export function buildApprovalFloatingAlert(item: GlobalPendingApproval, theme: 'light' | 'dark'): DesktopFloatingAlert {
  const risk = classifyApprovalRisk(item.toolName, item.argsSummary, item.structuredArgs);
  return {
    id: `approval:${item.callId}`,
    kind: 'approval',
    sessionId: item.sessionId,
    sessionTitle: item.sessionTitle || '对话',
    callId: item.callId,
    title: `需要你批准：${risk.category}`,
    body: risk.summary || item.argsSummary || item.toolName,
    categoryLabel: risk.category,
    highRisk: risk.highRisk,
    expiresAt: item.expiresAt || null,
    theme
  };
}

export function buildQuestionFloatingAlert(item: GlobalPendingQuestion, theme: 'light' | 'dark'): DesktopFloatingAlert {
  return {
    id: `question:${item.callId}`,
    kind: 'question',
    sessionId: item.sessionId,
    sessionTitle: item.sessionTitle || '对话',
    callId: item.callId,
    title: '需要你的选择',
    body: item.question,
    categoryLabel: '等待选择',
    options: item.options || [],
    expiresAt: item.expiresAt || null,
    theme
  };
}

export function buildRunFloatingAlert(
  kind: 'done' | 'error',
  sessionId: string,
  sessionTitle: string,
  summary: string,
  theme: 'light' | 'dark'
): DesktopFloatingAlert {
  return {
    id: `run:${sessionId || 'active'}:${kind}`,
    kind,
    sessionId,
    sessionTitle: sessionTitle || '对话',
    title: kind === 'done' ? '任务已完成' : '运行出错',
    body: summary || (kind === 'done' ? '本轮执行已结束，可点击查看结果。' : '本轮运行失败，请查看详情。'),
    categoryLabel: kind === 'done' ? '已完成' : '运行失败',
    autoCloseMs: kind === 'done' ? 6500 : 8500,
    theme
  };
}
