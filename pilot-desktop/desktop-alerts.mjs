import { randomBytes } from 'node:crypto';

export const ALERT_WIDTH = 384;
export const ALERT_MIN_HEIGHT = 128;
export const ALERT_MAX_HEIGHT = 540;
export const DEFAULT_AUTO_CLOSE_MS = 7000;
const MARGIN = 16;

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function cleanText(value, maxLength) {
  const text = String(value ?? '').trim();
  return text.length > maxLength ? text.slice(0, maxLength - 1) + '…' : text;
}

/** 根据平台与屏幕工作区计算悬浮窗坐标：macOS 右上角，Windows / Linux 右下角，防越界。 */
export function computeAlertBounds({ workArea, width = ALERT_WIDTH, height = 220, platform = process.platform } = {}) {
  const area = {
    x: Number.isFinite(workArea?.x) ? workArea.x : 0,
    y: Number.isFinite(workArea?.y) ? workArea.y : 0,
    width: Number.isFinite(workArea?.width) && workArea.width > 0 ? workArea.width : 1280,
    height: Number.isFinite(workArea?.height) && workArea.height > 0 ? workArea.height : 800
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

export function floatingWindowOptions({ width = ALERT_WIDTH, height = 220, platform = process.platform, theme = 'dark', preloadPath, icon } = {}) {
  void platform;
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

export function alertWindowHtml() {
  return `<!doctype html>
<html lang="zh-CN" data-theme="dark">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<title>Pilot 提醒</title>
<style>
  :root {
    --bg: #1b1d22;
    --panel: #23262d;
    --border: rgba(255, 255, 255, 0.14);
    --ink: #f3f5f8;
    --muted: #a4acb9;
    --accent: #4fa3ea;
    --accent-soft: rgba(79, 163, 234, 0.16);
    --warn: #f2b544;
    --warn-soft: rgba(242, 181, 68, 0.16);
    --danger: #ff6f6f;
    --danger-soft: rgba(255, 111, 111, 0.16);
    --ok: #3ecf8e;
    --ok-soft: rgba(62, 207, 142, 0.16);
  }
  html[data-theme="light"] {
    --bg: #ffffff;
    --panel: #f5f7fa;
    --border: rgba(15, 23, 42, 0.12);
    --ink: #111827;
    --muted: #4b5563;
    --accent: #0969da;
    --accent-soft: rgba(9, 105, 218, 0.12);
    --warn: #b45309;
    --warn-soft: rgba(217, 119, 6, 0.14);
    --danger: #dc2626;
    --danger-soft: rgba(220, 38, 38, 0.12);
    --ok: #15803d;
    --ok-soft: rgba(21, 128, 61, 0.12);
  }
  * { box-sizing: border-box; }
  html, body {
    margin: 0;
    padding: 0;
    background: var(--bg);
    color: var(--ink);
    font: 13px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
    overflow: hidden;
    user-select: none;
  }
  .card {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 12px 14px;
    border: 1px solid var(--border);
    background: var(--bg);
  }
  .head {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
  }
  .badge {
    display: inline-flex;
    align-items: center;
    padding: 2px 8px;
    border-radius: 999px;
    font-size: 11px;
    font-weight: 600;
    background: var(--accent-soft);
    color: var(--accent);
    flex: none;
  }
  .badge.is-approval { background: var(--warn-soft); color: var(--warn); }
  .badge.is-error { background: var(--danger-soft); color: var(--danger); }
  .badge.is-done { background: var(--ok-soft); color: var(--ok); }
  .risk {
    padding: 2px 7px;
    border-radius: 999px;
    font-size: 11px;
    font-weight: 600;
    background: var(--danger-soft);
    color: var(--danger);
    flex: none;
  }
  .session {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--muted);
    font-size: 11.5px;
  }
  .pager {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    color: var(--muted);
    font-size: 11px;
    flex: none;
  }
  .icon-btn {
    border: 1px solid transparent;
    background: transparent;
    color: var(--muted);
    border-radius: 6px;
    padding: 2px 6px;
    cursor: pointer;
    font: inherit;
    line-height: 1.2;
  }
  .icon-btn:hover { background: var(--panel); color: var(--ink); border-color: var(--border); }
  .title-row {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
  }
  .title {
    margin: 0;
    font-size: 13.5px;
    font-weight: 600;
    color: var(--ink);
    overflow-wrap: anywhere;
  }
  .countdown {
    flex: none;
    font-size: 11px;
    font-variant-numeric: tabular-nums;
    color: var(--muted);
  }
  .countdown.is-urgent { color: var(--danger); font-weight: 600; }
  .body {
    margin: 0;
    padding: 8px 10px;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--panel);
    color: var(--ink);
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 12px;
    line-height: 1.45;
    max-height: 126px;
    overflow: auto;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    user-select: text;
  }
  .body.is-prose {
    font-family: inherit;
    font-size: 12.5px;
  }
  .options {
    display: flex;
    flex-direction: column;
    gap: 5px;
    max-height: 184px;
    overflow: auto;
  }
  .option-btn {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    width: 100%;
    padding: 7px 10px;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--panel);
    color: var(--ink);
    text-align: left;
    font: inherit;
    font-size: 12px;
    cursor: pointer;
  }
  .option-btn:hover { border-color: var(--accent); background: var(--accent-soft); }
  .option-num {
    flex: none;
    width: 17px;
    height: 17px;
    border-radius: 50%;
    background: var(--bg);
    color: var(--muted);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    font-size: 10.5px;
  }
  .custom-form {
    display: flex;
    gap: 6px;
  }
  .custom-input {
    flex: 1;
    min-width: 0;
    padding: 6px 9px;
    border: 1px solid var(--border);
    border-radius: 7px;
    background: var(--panel);
    color: var(--ink);
    font: inherit;
    font-size: 12px;
    user-select: text;
  }
  .custom-input:focus { outline: none; border-color: var(--accent); }
  .actions {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 6px;
    flex-wrap: wrap;
  }
  .actions .spacer { margin-right: auto; }
  .btn {
    padding: 6px 11px;
    border: 1px solid var(--border);
    border-radius: 7px;
    background: var(--panel);
    color: var(--ink);
    font: inherit;
    font-size: 12px;
    font-weight: 500;
    cursor: pointer;
  }
  .btn:hover { border-color: var(--accent); }
  .btn.is-primary { background: var(--accent); border-color: var(--accent); color: #ffffff; font-weight: 600; }
  .btn.is-danger { background: var(--danger); border-color: var(--danger); color: #ffffff; font-weight: 600; }
</style>
</head>
<body>
<main id="root" class="card"></main>
<script>
const root = document.getElementById('root');
let currentState = { items: [] };
let activeIndex = 0;
let hovered = false;
let lastKey = '';
let draftText = '';
let rejectOpen = false;
let rejectDraft = '';

function notifySize() {
  if (!window.pilotAlert || typeof window.pilotAlert.resize !== 'function') return;
  const rect = root.getBoundingClientRect();
  window.pilotAlert.resize(Math.ceil(rect.height));
}

const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(notifySize) : null;
observer?.observe(root);

root.addEventListener('mouseenter', () => { hovered = true; });
root.addEventListener('mouseleave', () => { hovered = false; });

function countdownLabel(expiresAt) {
  if (!expiresAt) return '';
  const remain = Math.max(0, Math.floor((expiresAt - Date.now()) / 1000));
  const min = Math.floor(remain / 60);
  const sec = remain % 60;
  return { text: remain > 0 ? '剩余 ' + min + ':' + String(sec).padStart(2, '0') : '即将超时', urgent: remain <= 30 };
}

function makeButton(label, cls, onClick) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = cls;
  btn.textContent = label;
  btn.addEventListener('click', onClick);
  return btn;
}

function render() {
  const items = Array.isArray(currentState.items) ? currentState.items : [];
  if (!items.length) {
    root.textContent = '';
    return;
  }
  if (activeIndex >= items.length) activeIndex = 0;
  const item = items[activeIndex];
  const itemKey = item.id + ':' + item.updatedAt;
  if (itemKey !== lastKey) {
    lastKey = itemKey;
    draftText = '';
    rejectOpen = false;
    rejectDraft = '';
  }
  document.documentElement.dataset.theme = item.theme === 'light' ? 'light' : 'dark';
  root.textContent = '';

  const head = document.createElement('div');
  head.className = 'head';

  const badge = document.createElement('span');
  badge.className = 'badge is-' + item.kind;
  badge.textContent = item.categoryLabel || (item.kind === 'approval' ? '等待批准' : item.kind === 'question' ? '等待选择' : item.kind === 'error' ? '运行异常' : '任务完成');
  head.appendChild(badge);

  if (item.highRisk) {
    const risk = document.createElement('span');
    risk.className = 'risk';
    risk.textContent = '高风险';
    head.appendChild(risk);
  }

  const session = document.createElement('span');
  session.className = 'session';
  session.textContent = item.sessionTitle || '';
  session.title = item.sessionTitle || '';
  head.appendChild(session);

  if (items.length > 1) {
    const pager = document.createElement('span');
    pager.className = 'pager';
    const prev = makeButton('‹', 'icon-btn', () => {
      activeIndex = (activeIndex - 1 + items.length) % items.length;
      render();
    });
    const count = document.createElement('span');
    count.textContent = (activeIndex + 1) + '/' + items.length;
    const next = makeButton('›', 'icon-btn', () => {
      activeIndex = (activeIndex + 1) % items.length;
      render();
    });
    pager.append(prev, count, next);
    head.appendChild(pager);
  }

  const closeBtn = makeButton('×', 'icon-btn', () => {
    window.pilotAlert.act({ action: 'dismiss', id: item.id, sessionId: item.sessionId, callId: item.callId });
  });
  closeBtn.setAttribute('aria-label', '关闭此提醒');
  head.appendChild(closeBtn);
  root.appendChild(head);

  const titleRow = document.createElement('div');
  titleRow.className = 'title-row';
  const title = document.createElement('h2');
  title.className = 'title';
  title.textContent = item.title;
  titleRow.appendChild(title);

  const cd = countdownLabel(item.expiresAt);
  if (cd) {
    const cdEl = document.createElement('span');
    cdEl.className = 'countdown' + (cd.urgent ? ' is-urgent' : '');
    cdEl.textContent = cd.text;
    cdEl.dataset.expiresAt = String(item.expiresAt);
    titleRow.appendChild(cdEl);
  }
  root.appendChild(titleRow);

  if (item.body) {
    const body = document.createElement(item.kind === 'approval' ? 'pre' : 'p');
    body.className = 'body' + (item.kind === 'approval' ? '' : ' is-prose');
    body.textContent = item.body;
    root.appendChild(body);
  }

  if (item.kind === 'question') {
    const list = document.createElement('div');
    list.className = 'options';
    (item.options || []).forEach((opt, idx) => {
      const optBtn = document.createElement('button');
      optBtn.type = 'button';
      optBtn.className = 'option-btn';
      const num = document.createElement('span');
      num.className = 'option-num';
      num.textContent = String(idx + 1);
      const txt = document.createElement('span');
      txt.textContent = opt;
      optBtn.append(num, txt);
      optBtn.addEventListener('click', () => {
        window.pilotAlert.act({ action: 'answer', id: item.id, sessionId: item.sessionId, callId: item.callId, choice: opt });
      });
      list.appendChild(optBtn);
    });
    root.appendChild(list);

    const form = document.createElement('form');
    form.className = 'custom-form';
    const input = document.createElement('input');
    input.className = 'custom-input';
    input.placeholder = '都不是，自己填写后回车';
    input.value = draftText;
    input.addEventListener('input', () => { draftText = input.value; });
    input.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && (event.isComposing || event.keyCode === 229)) {
        event.preventDefault();
      }
    });
    const submitBtn = document.createElement('button');
    submitBtn.type = 'submit';
    submitBtn.className = 'btn is-primary';
    submitBtn.textContent = '提交';
    form.addEventListener('submit', (event) => {
      event.preventDefault();
      const value = input.value.trim();
      if (!value) return;
      window.pilotAlert.act({ action: 'answer', id: item.id, sessionId: item.sessionId, callId: item.callId, choice: value });
    });
    form.append(input, submitBtn);
    root.appendChild(form);
  }

  const actions = document.createElement('div');
  actions.className = 'actions';
  if (item.kind === 'approval') {
    if (rejectOpen) {
      const rejectForm = document.createElement('form');
      rejectForm.className = 'custom-form';
      const reasonInput = document.createElement('input');
      reasonInput.className = 'custom-input';
      reasonInput.placeholder = '说明拒绝原因或修改建议…';
      reasonInput.value = rejectDraft;
      reasonInput.addEventListener('input', () => { rejectDraft = reasonInput.value; });
      reasonInput.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' && (event.isComposing || event.keyCode === 229)) {
          event.preventDefault();
        }
      });
      const sendRejectBtn = document.createElement('button');
      sendRejectBtn.type = 'submit';
      sendRejectBtn.className = 'btn is-danger';
      sendRejectBtn.textContent = '提交拒绝';
      rejectForm.addEventListener('submit', (event) => {
        event.preventDefault();
        const reason = reasonInput.value.trim();
        window.pilotAlert.act({
          action: 'reject',
          id: item.id,
          sessionId: item.sessionId,
          callId: item.callId,
          ...(reason ? { reason } : {})
        });
      });
      rejectForm.append(reasonInput, sendRejectBtn);
      root.appendChild(rejectForm);
    }
    const focusBtn = makeButton('前往对话', 'btn spacer', () => {
      window.pilotAlert.act({ action: 'focus-session', id: item.id, sessionId: item.sessionId, callId: item.callId });
    });
    const toggleReasonBtn = makeButton(rejectOpen ? '收起' : '附原因', 'btn', () => {
      rejectOpen = !rejectOpen;
      render();
    });
    const rejectBtn = makeButton('拒绝', 'btn', () => {
      window.pilotAlert.act({ action: 'reject', id: item.id, sessionId: item.sessionId, callId: item.callId });
    });
    const approveBtn = makeButton('批准', 'btn ' + (item.highRisk ? 'is-danger' : 'is-primary'), () => {
      window.pilotAlert.act({ action: 'approve', id: item.id, sessionId: item.sessionId, callId: item.callId });
    });
    actions.append(focusBtn, toggleReasonBtn, rejectBtn, approveBtn);
  } else if (item.kind === 'question') {
    const focusBtn = makeButton('前往对话', 'btn', () => {
      window.pilotAlert.act({ action: 'focus-session', id: item.id, sessionId: item.sessionId, callId: item.callId });
    });
    actions.append(focusBtn);
  } else {
    const focusBtn = makeButton('查看结果', 'btn is-primary', () => {
      window.pilotAlert.act({ action: 'focus-session', id: item.id, sessionId: item.sessionId, callId: item.callId });
    });
    const okBtn = makeButton('知道了', 'btn', () => {
      window.pilotAlert.act({ action: 'dismiss', id: item.id, sessionId: item.sessionId, callId: item.callId });
    });
    actions.append(okBtn, focusBtn);
  }
  root.appendChild(actions);
  requestAnimationFrame(notifySize);
}

setInterval(() => {
  const el = root.querySelector('.countdown');
  if (!el) return;
  const cd = countdownLabel(Number(el.dataset.expiresAt || 0));
  if (cd) {
    el.textContent = cd.text;
    el.className = 'countdown' + (cd.urgent ? ' is-urgent' : '');
  }
}, 1000);

window.pilotAlert?.onState((state) => {
  currentState = state || { items: [] };
  if (!hovered && activeIndex !== 0) activeIndex = 0;
  render();
});
</script>
</body>
</html>`;
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
    return { x: 0, y: 0, width: 1280, height: 800 };
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
      platform,
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
        if (typeof floatingWindow.showInactive === 'function') floatingWindow.showInactive();
        else floatingWindow.show?.();
      }
    });
    floatingWindow.on?.('closed', () => {
      floatingWindow = null;
      floatingReady = false;
    });
    const htmlUrl = `data:text/html;charset=utf-8,${encodeURIComponent(alertWindowHtml())}`;
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
        if (typeof win.showInactive === 'function') win.showInactive();
        else win.show?.();
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
        if (bounce && !focused && count >= 0) {
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
