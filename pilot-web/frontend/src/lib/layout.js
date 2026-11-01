/**
 * 三栏布局的可调宽度：左侧会话栏与右侧审阅栏都能拖，宽度记在本地。
 *
 * <p>宽度是纯 UI 偏好，不落服务端；存不下（隐私模式）时只是下次启动回到默认值。
 * 上限除了写死的最大值，还要给中间对话区留出位置——否则把右栏拉到只剩一条缝。
 */

const STORAGE_KEY = 'pilot.layout';
/** 中间对话区至少留这么宽，两侧都不会把它挤没。 */
const MIN_MAIN_WIDTH = 420;

/** 各栏的可拖范围（像素）。 */
export const LAYOUT_LIMITS = {
  sidebar: [200, 520],
  activity: [280, 760]
};

function storageOf(storage) {
  if (storage) {
    return storage;
  }
  try {
    return typeof window !== 'undefined' ? window.localStorage : null;
  } catch {
    return null;
  }
}

/**
 * 把一次拖动得到的宽度收进允许范围。
 *
 * @param kind 'sidebar' | 'activity'
 * @param value 目标宽度
 * @param viewportWidth 应用区总宽（不含浏览器窗口外框）
 */
export function clampPanelWidth(kind, value, viewportWidth) {
  const limits = LAYOUT_LIMITS[kind];
  if (!limits) {
    return Math.round(Number(value) || 0);
  }
  const [min, max] = limits;
  const available = Number(viewportWidth);
  const ceiling = Number.isFinite(available) && available > 0
    ? Math.max(min, Math.min(max, available - MIN_MAIN_WIDTH))
    : max;
  const target = Number(value);
  if (!Number.isFinite(target)) {
    return min;
  }
  return Math.round(Math.max(min, Math.min(ceiling, target)));
}

/** 读本地保存的宽度；坏数据一律当没存过。 */
export function readLayout(storage) {
  const store = storageOf(storage);
  if (!store || typeof store.getItem !== 'function') {
    return { sidebarW: null, activityW: null };
  }
  try {
    const saved = JSON.parse(store.getItem(STORAGE_KEY) || '{}');
    return {
      sidebarW: Number.isFinite(Number(saved.sidebarW)) && Number(saved.sidebarW) > 0 ? Number(saved.sidebarW) : null,
      activityW: Number.isFinite(Number(saved.activityW)) && Number(saved.activityW) > 0 ? Number(saved.activityW) : null
    };
  } catch {
    return { sidebarW: null, activityW: null };
  }
}

export function writeLayout(layout, storage) {
  const store = storageOf(storage);
  if (!store || typeof store.setItem !== 'function') {
    return;
  }
  try {
    store.setItem(STORAGE_KEY, JSON.stringify({
      sidebarW: layout?.sidebarW || null,
      activityW: layout?.activityW || null
    }));
  } catch {
    // 存不下只影响下次启动
  }
}

/**
 * 拖动后的新宽度：左栏向右拖变宽，右栏向左拖变宽（用起始宽度 + 位移算）。
 *
 * @param kind 'sidebar' | 'activity'
 * @param startWidth 按下时的宽度
 * @param deltaX 相对按下点的水平位移
 */
export function dragWidth(kind, startWidth, deltaX, viewportWidth) {
  const direction = kind === 'activity' ? -1 : 1;
  return clampPanelWidth(kind, (Number(startWidth) || 0) + direction * (Number(deltaX) || 0), viewportWidth);
}
