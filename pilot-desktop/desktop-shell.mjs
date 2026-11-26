/**
 * 桌面壳纯逻辑助手：窗口位置恢复、系统托盘菜单、防休眠控制与离线服务自动重连。
 * 所有函数尽量纯函数化，确保 Windows / macOS / Linux 下都可离线单测。
 */

export const DEFAULT_WINDOW_BOUNDS = { width: 1280, height: 840, maximized: false };
const MIN_WIDTH = 720;
const MIN_HEIGHT = 520;

export function shouldHideOnClose({ minimizeToTray, trayAvailable }) {
  return Boolean(minimizeToTray && trayAvailable);
}

export function normalizeWindowBounds(saved, displays, fallback = DEFAULT_WINDOW_BOUNDS) {
  const baseWidth = Math.max(MIN_WIDTH, Math.round(Number(fallback?.width) || DEFAULT_WINDOW_BOUNDS.width));
  const baseHeight = Math.max(MIN_HEIGHT, Math.round(Number(fallback?.height) || DEFAULT_WINDOW_BOUNDS.height));
  const list = Array.isArray(displays) ? displays.filter((d) => d && d.workArea && d.workArea.width > 0 && d.workArea.height > 0) : [];

  if (!saved || typeof saved !== 'object') {
    const firstArea = list[0]?.workArea;
    if (!firstArea) return { width: baseWidth, height: baseHeight, maximized: false };
    return {
      width: Math.min(baseWidth, firstArea.width),
      height: Math.min(baseHeight, firstArea.height),
      maximized: false
    };
  }

  const rawWidth = Math.round(Number(saved.width) || baseWidth);
  const rawHeight = Math.round(Number(saved.height) || baseHeight);
  const hasPos = Number.isFinite(saved.x) && Number.isFinite(saved.y);
  const maximized = Boolean(saved.maximized);

  if (!list.length) {
    return {
      ...(hasPos ? { x: Math.round(saved.x), y: Math.round(saved.y) } : {}),
      width: Math.max(MIN_WIDTH, rawWidth),
      height: Math.max(MIN_HEIGHT, rawHeight),
      maximized
    };
  }

  // 查找与保存矩形重叠面积最大的显示器；拔掉外接屏时回退到主显示器工作区
  let matched = list[0];
  if (hasPos) {
    let bestOverlap = 0;
    const rx1 = saved.x;
    const ry1 = saved.y;
    const rx2 = rx1 + rawWidth;
    const ry2 = ry1 + rawHeight;
    for (const disp of list) {
      const a = disp.workArea;
      const ox = Math.max(0, Math.min(rx2, a.x + a.width) - Math.max(rx1, a.x));
      const oy = Math.max(0, Math.min(ry2, a.y + a.height) - Math.max(ry1, a.y));
      const area = ox * oy;
      if (area > bestOverlap) {
        bestOverlap = area;
        matched = disp;
      }
    }
  }

  const area = matched.workArea;
  const width = Math.min(Math.max(Math.min(MIN_WIDTH, area.width), rawWidth), area.width);
  const height = Math.min(Math.max(Math.min(MIN_HEIGHT, area.height), rawHeight), area.height);
  if (!hasPos) {
    return { width, height, maximized };
  }
  const x = Math.max(area.x, Math.min(Math.round(saved.x), area.x + area.width - width));
  const y = Math.max(area.y, Math.min(Math.round(saved.y), area.y + area.height - height));
  return { x, y, width, height, maximized };
}

export function buildTrayMenuTemplate({
  runningCount = 0,
  pendingCount = 0,
  dndActive = false,
  keepAwake = false,
  windowVisible = true,
  handlers = {}
} = {}) {
  const statusParts = [];
  if (pendingCount > 0) statusParts.push(`${pendingCount} 项待处理`);
  if (runningCount > 0) statusParts.push(`${runningCount} 项执行中`);
  const statusLabel = statusParts.length ? `状态：${statusParts.join(' · ')}` : '状态：空闲就绪';

  const items = [
    { label: statusLabel, enabled: false },
    { type: 'separator' },
    {
      label: windowVisible ? '隐藏主窗口' : '显示主窗口',
      click: () => handlers.toggleWindow?.()
    },
    ...(pendingCount > 0
      ? [{ label: `处理待确认事项 (${pendingCount})`, click: () => handlers.focusPending?.() }]
      : []),
    { label: '新建任务', click: () => handlers.newTask?.() },
    { type: 'separator' },
    {
      label: '勿扰模式（暂停声音与浮窗）',
      type: 'checkbox',
      checked: Boolean(dndActive),
      click: () => handlers.toggleDnd?.()
    },
    {
      label: '任务执行时防系统休眠',
      type: 'checkbox',
      checked: Boolean(keepAwake),
      click: () => handlers.toggleKeepAwake?.()
    },
    { type: 'separator' },
    { label: '退出 Pilot', click: () => handlers.quit?.() }
  ];
  return items;
}

export function createPowerAwakeController({ powerSaveBlocker } = {}) {
  let blockerId = null;

  function release() {
    if (blockerId == null) return false;
    try {
      if (powerSaveBlocker?.isStarted?.(blockerId) !== false) {
        powerSaveBlocker?.stop?.(blockerId);
      }
    } catch {
      // 平台接口受限时忽略停止错误
    }
    blockerId = null;
    return true;
  }

  function update({ keepAwake = false, runningCount = 0 } = {}) {
    const shouldHold = Boolean(keepAwake && runningCount > 0);
    if (!shouldHold) {
      release();
      return false;
    }
    if (blockerId != null && powerSaveBlocker?.isStarted?.(blockerId) !== false) {
      return true;
    }
    if (typeof powerSaveBlocker?.start !== 'function') return false;
    try {
      blockerId = powerSaveBlocker.start('prevent-app-suspension');
      return blockerId != null;
    } catch {
      blockerId = null;
      return false;
    }
  }

  return {
    update,
    release,
    destroy: release,
    isActive: () => blockerId != null
  };
}

export function createOfflineReconnector({
  healthUrl,
  intervalMs = 2500,
  timeoutMs = 1500,
  fetchImpl = globalThis.fetch,
  onReady
} = {}) {
  let timer = null;
  let active = false;
  let checking = false;

  async function probe() {
    if (!active || checking || typeof fetchImpl !== 'function' || !healthUrl) return false;
    checking = true;
    const controller = typeof AbortController === 'function' ? new AbortController() : null;
    const abortTimer = controller ? setTimeout(() => controller.abort(), timeoutMs) : null;
    try {
      const response = await fetchImpl(healthUrl, {
        cache: 'no-store',
        ...(controller ? { signal: controller.signal } : {})
      });
      if (active && response?.ok) {
        stop();
        onReady?.();
        return true;
      }
    } catch {
      // 服务尚未恢复，继续下轮探测
    } finally {
      clearTimeout(abortTimer);
      checking = false;
    }
    return false;
  }

  function start() {
    if (active || !healthUrl) return;
    active = true;
    timer = setInterval(() => { void probe(); }, intervalMs);
    timer.unref?.();
  }

  function stop() {
    active = false;
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  return {
    start,
    stop,
    probe,
    isActive: () => active
  };
}
