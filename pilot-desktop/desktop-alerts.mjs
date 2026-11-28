import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';

export const ALERT_WIDTH = 384;
export const ALERT_MIN_HEIGHT = 128;
export const ALERT_MAX_HEIGHT = 540;
export const DEFAULT_AUTO_CLOSE_MS = 7000;
const MARGIN = 16;
const DEFAULT_WORK_AREA = Object.freeze({ x: 0, y: 0, width: 1280, height: 800 });

let cachedAlertHtml = null;

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function cleanText(value, maxLength) {
  const text = String(value ?? '').trim();
  return text.length > maxLength ? text.slice(0, maxLength - 1) + '…' : text;
}

function showWindowInactive(win) {
  if (typeof win.showInactive === 'function') win.showInactive();
  else win.show?.();
}

/** 根据平台与屏幕工作区计算悬浮窗坐标：macOS 右上角，Windows / Linux 右下角，防越界。 */
export function computeAlertBounds({ workArea, width = ALERT_WIDTH, height = 220, platform = process.platform } = {}) {
  const area = {
    x: Number.isFinite(workArea?.x) ? workArea.x : DEFAULT_WORK_AREA.x,
    y: Number.isFinite(workArea?.y) ? workArea.y : DEFAULT_WORK_AREA.y,
    width: Number.isFinite(workArea?.width) && workArea.width > 0 ? workArea.width : DEFAULT_WORK_AREA.width,
    height: Number.isFinite(workArea?.height) && workArea.height > 0 ? workArea.height : DEFAULT_WORK_AREA.height
  };
  const maxWidth = Math.max(240, area.width - MARGIN * 2);
  const maxHeight = Math.max(ALERT_MIN_HEIGHT, area.height - MARGIN * 2);
  const w = clamp(Math.round(Number(width) || ALERT_WIDTH), 240, maxWidth);
  const h = clamp(Math.round(Number(height) || 220), ALERT_MIN_HEIGHT, Math.min(ALERT_MAX_HEIGHT, maxHeight));
  const x = clamp(area.x + area.width - w - MARGIN, area.x, area.x + area.width - w);
  const rawY = platform === 'darwin'
    ? area.y + MARGIN
    : area.y + area.height - h - MARGIN;
  const y = clamp(rawY, area.y, area.y + area.height - h);
  return { x: Math.round(x), y: Math.round(y), width: w, height: h };
}

/** Windows 使用 pop-up-menu 可压住全屏栏外弹窗，macOS 与 Linux 使用 floating。 */
export function alertLevelForPlatform(platform = process.platform) {
  return platform === 'win32' ? 'pop-up-menu' : 'floating';
}

export function floatingWindowOptions({ width = ALERT_WIDTH, height = 220, theme = 'dark', preloadPath, icon } = {}) {
  return {
    width: Math.round(Number(width) || ALERT_WIDTH),
    height: Math.round(Number(height) || 220),
    frame: false,
    transparent: false,
    resizable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    hasShadow: true,
    show: false,
    backgroundColor: theme === 'light' ? '#ffffff' : '#1b1d22',
    ...(icon ? { icon } : {}),
    webPreferences: {
      preload: preloadPath,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false
    }
  };
}

export function normalizeAlertPayload(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const kind = ['approval', 'question', 'done', 'error'].includes(raw.kind) ? raw.kind : 'done';
  const callId = cleanText(raw.callId, 160);
  const id = cleanText(raw.id || callId, 160);
  const title = cleanText(raw.title, 160);
  if (!id && !title) return null;
  const options = Array.isArray(raw.options)
    ? raw.options.map((item) => cleanText(item, 240)).filter(Boolean).slice(0, 8)
    : [];
  let expiresAt = null;
  if (typeof raw.expiresAt === 'number' && Number.isFinite(raw.expiresAt) && raw.expiresAt > 0) {
    expiresAt = raw.expiresAt;
  } else if (typeof raw.expiresAt === 'string' && raw.expiresAt.trim()) {
    const parsed = Date.parse(raw.expiresAt);
    if (Number.isFinite(parsed) && parsed > 0) expiresAt = parsed;
  }
  const urgent = kind === 'approval' || kind === 'question';
  const rawAuto = Number(raw.autoCloseMs);
  const autoCloseMs = Number.isFinite(rawAuto) && rawAuto >= 0
    ? Math.round(rawAuto)
    : urgent ? 0 : DEFAULT_AUTO_CLOSE_MS;
  return {
    id: id || `alert_${randomBytes(4).toString('hex')}`,
    kind,
    urgent,
    sessionId: cleanText(raw.sessionId, 160),
    sessionTitle: cleanText(raw.sessionTitle, 120),
    callId,
    title: title || (kind === 'approval' ? '需要你的批准' : kind === 'question' ? '需要你的选择' : '任务提醒'),
    body: cleanText(raw.body, 1200),
    categoryLabel: cleanText(raw.categoryLabel, 40),
    highRisk: Boolean(raw.highRisk),
    options,
    expiresAt,
    theme: raw.theme === 'light' ? 'light' : 'dark',
    autoCloseMs,
    updatedAt: Date.now()
  };
}

export function validateAlertAction(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const action = cleanText(raw.action, 40);
  if (!['approve', 'reject', 'answer', 'focus-session', 'dismiss', 'clear-all'].includes(action)) {
    return null;
  }
  const normalized = {
    action,
    id: cleanText(raw.id, 160),
    sessionId: cleanText(raw.sessionId, 160),
    callId: cleanText(raw.callId, 160)
  };
  if (action === 'answer') {
    const choice = String(raw.choice ?? '').trim().slice(0, 2000);
    if (!choice) return null;
    normalized.choice = choice;
  }
  if (action === 'reject' && typeof raw.reason === 'string') {
    const reason = raw.reason.trim().slice(0, 500);
    if (reason) normalized.reason = reason;
  }
  return normalized;
}

export function alertWindowHtml(theme = 'dark') {
  if (cachedAlertHtml === null) {
    cachedAlertHtml = readFileSync(new URL('./alert-window.html', import.meta.url), 'utf8');
  }
  return theme === 'light'
    ? cachedAlertHtml.replace('data-theme="dark"', 'data-theme="light"')
    : cachedAlertHtml;
}

/**
 * 桌面跨窗口提醒管理器：负责独立于主窗口的置顶悬浮卡片、系统原生通知与任务栏/Dock 提醒。
 */
export function createDesktopAlertManager({
  BrowserWindow,
  Notification,
  screen,
  app,
  getShell,
  onAction,
  platform = process.platform,
  preloadPath,
  icon,
  smoke = false
}) {
  const queue = [];
  const autoTimers = new Map();
  let floatingWindow = null;
  let floatingReady = false;
  let pendingHeight = 220;
  let destroyed = false;

  function sortQueue() {
    queue.sort((a, b) => {
      if (a.urgent !== b.urgent) return a.urgent ? -1 : 1;
      return b.updatedAt - a.updatedAt;
    });
  }

  function clearAutoTimer(id) {
    const timer = autoTimers.get(id);
    if (timer) {
      clearTimeout(timer);
      autoTimers.delete(id);
    }
  }

  function workAreaNearShell() {
    const shell = getShell?.();
    try {
      if (screen?.getCursorScreenPoint && screen?.getDisplayNearestPoint) {
        return screen.getDisplayNearestPoint(screen.getCursorScreenPoint()).workArea;
      }
      if (shell && !shell.isDestroyed() && screen?.getDisplayMatching) {
        return screen.getDisplayMatching(shell.getBounds()).workArea;
      }
      if (screen?.getPrimaryDisplay) {
        return screen.getPrimaryDisplay().workArea;
      }
    } catch {
      // 无屏幕信息时退回默认分辨率
    }
    return { ...DEFAULT_WORK_AREA };
  }

  function applyWindowBounds(height) {
    if (!floatingWindow || floatingWindow.isDestroyed?.()) return;
    const bounds = computeAlertBounds({
      workArea: workAreaNearShell(),
      width: ALERT_WIDTH,
      height: height || pendingHeight,
      platform
    });
    floatingWindow.setBounds?.(bounds, false);
  }

  function pushStateToFloating() {
    if (!floatingWindow || floatingWindow.isDestroyed?.() || !floatingReady) return;
    try {
      floatingWindow.webContents.send('pilot-alert-state', { items: queue.slice(0, 12) });
    } catch {
      // 悬浮窗已销毁时忽略
    }
  }

  function ensureFloatingWindow(theme) {
    if (destroyed || smoke || typeof BrowserWindow !== 'function') return null;
    if (floatingWindow && !floatingWindow.isDestroyed?.()) return floatingWindow;
    floatingReady = false;
    const options = floatingWindowOptions({
      width: ALERT_WIDTH,
      height: pendingHeight,
      theme,
      preloadPath,
      icon
    });
    floatingWindow = new BrowserWindow(options);
    floatingWindow.setAlwaysOnTop?.(true, alertLevelForPlatform(platform));
    if (platform === 'darwin') {
      floatingWindow.setVisibleOnAllWorkspaces?.(true, { visibleOnFullScreen: true });
    } else if (platform === 'linux') {
      floatingWindow.setVisibleOnAllWorkspaces?.(true);
    }
    floatingWindow.webContents?.once?.('did-finish-load', () => {
      floatingReady = true;
      pushStateToFloating();
      if (queue.length > 0 && floatingWindow && !floatingWindow.isDestroyed?.()) {
        applyWindowBounds(pendingHeight);
        showWindowInactive(floatingWindow);
      }
    });
    floatingWindow.on?.('closed', () => {
      floatingWindow = null;
      floatingReady = false;
    });
    const htmlUrl = `data:text/html;charset=utf-8,${encodeURIComponent(alertWindowHtml(theme))}`;
    floatingWindow.loadURL?.(htmlUrl)?.catch?.(() => {});
    return floatingWindow;
  }

  function hideFloatingIfEmpty() {
    if (queue.length === 0 && floatingWindow && !floatingWindow.isDestroyed?.()) {
      floatingWindow.hide?.();
    }
  }

  function isMainFocused() {
    const shell = getShell?.();
    if (!shell || shell.isDestroyed?.()) return false;
    return Boolean(shell.isFocused?.());
  }

  function focusMainWindow() {
    const shell = getShell?.();
    if (!shell || shell.isDestroyed?.()) return { ok: false };
    try {
      if (shell.isMinimized?.()) shell.restore?.();
      shell.show?.();
      shell.focus?.();
      shell.flashFrame?.(false);
      return { ok: true };
    } catch {
      return { ok: false };
    }
  }

  function show(raw) {
    if (destroyed) return { ok: false };
    const item = normalizeAlertPayload(raw);
    if (!item) return { ok: false, error: '无效提醒内容' };
    clearAutoTimer(item.id);
    const existing = queue.findIndex((entry) => entry.id === item.id);
    if (existing >= 0) queue[existing] = item;
    else queue.push(item);
    sortQueue();
    if (item.autoCloseMs > 0) {
      const timer = setTimeout(() => { dismiss(item.id); }, item.autoCloseMs);
      timer.unref?.();
      autoTimers.set(item.id, timer);
    }
    const win = ensureFloatingWindow(item.theme);
    if (win) {
      applyWindowBounds(pendingHeight);
      if (floatingReady) {
        pushStateToFloating();
        showWindowInactive(win);
      }
    }
    return { ok: true, id: item.id, count: queue.length };
  }

  function dismiss(id) {
    if (id === '*') {
      for (const key of autoTimers.keys()) clearAutoTimer(key);
      queue.length = 0;
      pushStateToFloating();
      hideFloatingIfEmpty();
      return { ok: true, count: 0 };
    }
    const target = cleanText(id, 160);
    if (!target) return { ok: false };
    clearAutoTimer(target);
    const next = queue.filter((entry) => entry.id !== target);
    queue.length = 0;
    queue.push(...next);
    pushStateToFloating();
    hideFloatingIfEmpty();
    return { ok: true, count: queue.length };
  }

  function showNativeNotification(raw) {
    if (destroyed || smoke || typeof Notification !== 'function') return { ok: false, unsupported: true };
    if (typeof Notification.isSupported === 'function' && !Notification.isSupported()) {
      return { ok: false, unsupported: true };
    }
    const title = cleanText(raw?.title || 'Pilot', 160);
    const body = cleanText(raw?.body || '', 400);
    const sessionId = cleanText(raw?.sessionId || '', 160);
    const id = cleanText(raw?.id || '', 160);
    try {
      const notice = new Notification({
        title,
        body,
        silent: true,
        ...(icon ? { icon } : {})
      });
      notice.on?.('click', () => {
        focusMainWindow();
        onAction?.({ action: 'focus-session', sessionId, id });
      });
      notice.show?.();
      return { ok: true };
    } catch (error) {
      return { ok: false, error: String(error?.message || error) };
    }
  }

  function setWindowAttention({ urgentCount = 0, kind = 'idle', bounce = false } = {}) {
    const count = Math.max(0, Math.floor(Number(urgentCount) || 0));
    const focused = isMainFocused();
    if (platform === 'darwin') {
      try {
        app?.dock?.setBadge?.(count > 0 ? String(count) : '');
        if (bounce && !focused && count > 0) {
          app?.dock?.bounce?.(kind === 'attention' ? 'critical' : 'informational');
        }
      } catch {
        // 无 Dock 权限时静默忽略
      }
      return { ok: true, count };
    }
    const shell = getShell?.();
    if (shell && !shell.isDestroyed?.()) {
      try {
        shell.flashFrame?.(count > 0 && !focused);
      } catch {
        // 平台不支持任务栏闪烁
      }
    }
    return { ok: true, count };
  }

  function handleAlertResize(height) {
    const clamped = clamp(Math.round(Number(height) || pendingHeight), ALERT_MIN_HEIGHT, ALERT_MAX_HEIGHT);
    pendingHeight = clamped;
    applyWindowBounds(clamped);
  }

  function handleAlertAct(raw) {
    const action = validateAlertAction(raw);
    if (!action) return null;
    if (action.action === 'dismiss') {
      dismiss(action.id);
    } else if (action.action === 'clear-all') {
      dismiss('*');
    } else if (action.action === 'focus-session') {
      focusMainWindow();
      if (action.id && !action.id.startsWith('approval:') && !action.id.startsWith('question:')) {
        dismiss(action.id);
      }
    }
    onAction?.(action);
    return action;
  }

  function webContents() {
    return floatingWindow && !floatingWindow.isDestroyed?.() ? floatingWindow.webContents : null;
  }

  function destroy() {
    destroyed = true;
    for (const timer of autoTimers.values()) clearTimeout(timer);
    autoTimers.clear();
    queue.length = 0;
    if (floatingWindow && !floatingWindow.isDestroyed?.()) {
      try { floatingWindow.destroy?.(); } catch { /* 忽略退出清理异常 */ }
    }
    floatingWindow = null;
    floatingReady = false;
  }

  return {
    show,
    dismiss,
    showNativeNotification,
    setWindowAttention,
    focusMainWindow,
    handleAlertResize,
    handleAlertAct,
    webContents,
    destroy,
    snapshot: () => queue.slice()
  };
}
