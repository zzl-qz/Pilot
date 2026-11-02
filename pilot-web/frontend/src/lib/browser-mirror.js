/**
 * 浏览器镜像面板的纯逻辑：帧解析、延迟文案、涟漪坐标与状态机。
 *
 * <p>SSE 连接与 DOM 更新在 app.js 的 mirrorView 里；这里只放值得单测的判定，
 * 不碰 document/EventSource。侧边栏镜像走的是瞬态流（/browser/stream），
 * 与会话事件流（带 sequence、可回放）刻意分开，帧丢失不需要恢复。
 */

/**
 * 帧事件 → <img> 的 src（dataURL）。
 *
 * <p>既接受 `{ frame: '...' }` 也接受裸的 base64 字符串——面板里直接传 mirror.frame
 * 就不会因为多套一层而拿到 null（那会让 <img> 没有 src，画面区只剩 alt 文本）。
 * 帧体缺失或不是 base64 时返回 null：调用方跳过本次渲染，避免用旧帧闪屏。
 */
/**
 * 本机 http(s) 页面可以交给浏览器自己画（和 Cursor 的内置浏览器一样是真页面，不是截图）。
 * 没写协议的 `localhost:5173` 补上 http。外网地址返回 null。
 */
/** 人在地址栏输入的地址。本机没写协议时用 http，其它主机用 https。 */
export function userPageUrl(input) {
  const text = String(input || '').trim();
  if (!text) return null;
  const hasScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(text);
  let url;
  try {
    url = new URL(hasScheme ? text : `http://${text}`);
  } catch {
    return null;
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
  if (!hasScheme) {
    const host = url.hostname.replace(/^\[|\]$/g, '');
    const local = host === 'localhost' || host.endsWith('.localhost')
      || host === '127.0.0.1' || host === '::1' || /^127\.\d+\.\d+\.\d+$/.test(host);
    if (!local) url.protocol = 'https:';
  }
  return url.href;
}

export function embeddablePageUrl(url) {
  const text = String(url || '').trim();
  if (!text || text === 'about:blank') {
    return null;
  }
  const withScheme = /^https?:\/\//i.test(text) ? text : `http://${text}`;
  let parsed;
  try {
    parsed = new URL(withScheme);
  } catch {
    return null;
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    return null;
  }
  const host = parsed.hostname.replace(/^\[|\]$/g, '');
  if (host !== 'localhost' && host !== '127.0.0.1' && host !== '::1') {
    return null;
  }
  return parsed.href;
}

/**
 * 面板是否直接嵌页面。
 * 服务端明确说嵌不了（DENY / frame-ancestors）时退回截图。
 * SAMEORIGIN 只有页面和面板同源才嵌。
 */
export function shouldEmbedPage(url, embed, frameOptions) {
  if (embed === false) {
    return false;
  }
  const page = embeddablePageUrl(url);
  if (!page) {
    return false;
  }
  if (String(frameOptions || '').toLowerCase() === 'sameorigin') {
    if (typeof location === 'undefined' || !location.origin) {
      return false;
    }
    return new URL(page).origin === location.origin;
  }
  return true;
}

export function frameSrc(payload) {
  const frame = typeof payload === 'string' ? payload : payload && payload.frame;
  if (typeof frame !== 'string' || !frame) {
    return null;
  }
  return 'data:image/jpeg;base64,' + frame;
}

/**
 * 帧延迟文案：本机回环通常毫秒级；异常变大的延迟才值得让用户看见。
 * @param frameTs 帧自带的产生时间（sidecar 的 Date.now()，与服务端同机，可直接作差）
 */
export function latencyLabel(now, frameTs) {
  if (!Number.isFinite(frameTs) || frameTs <= 0) {
    return '';
  }
  const delay = Math.max(0, now - frameTs);
  if (delay < 300) {
    return '实时';
  }
  if (delay < 2000) {
    return delay + 'ms';
  }
  return (delay / 1000).toFixed(1) + 's 前';
}

/**
 * 智能体动作坐标（页面坐标系）→ 镜像显示区里的涟漪坐标。
 *
 * <p>sidecar 的 viewport 固定 1280×800，帧事件带实际 deviceWidth/Height；
 * 显示宽度随面板伸缩，按宽度等比缩放即可（高度同比例）。
 *
 * @returns {{x: number, y: number}} 相对镜像容器左上角的像素；无坐标返回 null
 */
export function ripplePosition(action, frameW, frameH, displayW) {
  if (!action) {
    return null;
  }
  const x = Number(action.x);
  const y = Number(action.y);
  const width = Number(frameW) > 0 ? frameW : 1280;
  const display = Number(displayW) > 0 ? displayW : width;
  if (!Number.isFinite(x) || !Number.isFinite(y)) {
    return null;
  }
  const scale = display / width;
  return { x: x * scale, y: y * scale };
}

/**
 * 镜像区的显示模式。
 *
 * <p>idle（等待智能体打开页面）→ live（有帧在流）→ closed（浏览器已回收，保留最后画面虚化）。
 * 断线由 EventSource 自动重连，重连后服务端补发缓存帧，因此不引入单独的「重连」态。
 *
 * @param kind 'frame' | 'closed' | 'reset'
 */
export function nextMirrorMode(current, kind) {
  if (kind === 'frame') {
    return 'live';
  }
  if (kind === 'closed') {
    return 'closed';
  }
  if (kind === 'reset') {
    return 'idle';
  }
  return current;
}

/**
 * 是否该把审阅面板自动切到浏览器 tab。
 *
 * <p>用户偏好（localStorage）默认开；每会话只切一次，用户手动切走后本会话不再打扰。
 *
 * @param autoSwitchPref localStorage 的原始字符串（null = 未设置 = 默认开）
 * @param alreadySwitched 本会话是否已经自动切过
 */
export function shouldAutoSwitch(autoSwitchPref, alreadySwitched) {
  if (alreadySwitched) {
    return false;
  }
  return autoSwitchPref === null || autoSwitchPref === '1';
}

/** 镜像状态行的 URL 文案：空地址给个可读占位。 */
export function urlLabel(url) {
  const text = String(url || '').trim();
  return text || '等待智能体打开页面…';
}

/**
 * 接管按钮文案。服务端 paused 是唯一事实源，按钮只是它的投影：
 * 点击后不本地翻转，等 state 事件回推再变，双击/多端不会不一致。
 */
export function pauseToggleLabel(paused) {
  return paused ? '恢复智能体操作' : '暂停智能体操作';
}

/** 镜像帧的默认视口尺寸（sidecar 固定 1280×800）。 */
const DEFAULT_FRAME_W = 1280;
const DEFAULT_FRAME_H = 800;

/**
 * sidecar 里页面的 CSS 视口尺寸：鼠标/滚轮坐标按它换算。
 *
 * <p>注意别用帧的像素尺寸——帧会被服务端按 maxWidth 缩到 960 宽，
 * 但页面 viewport 始终是 1280×800，拿帧宽算会让点击整体偏到右下。
 */
export const PAGE_VIEWPORT = { width: DEFAULT_FRAME_W, height: DEFAULT_FRAME_H };

/**
 * 画面在 <img> 里用 object-fit: contain 铺开，两侧或上下会留边。
 * 换算坐标必须扣掉这段留白，否则点得越靠边、偏得越多。
 *
 * @returns {{left: number, top: number, width: number, height: number, scale: number}}
 */
export function imageContentBox(rect, frameW, frameH) {
  const width = Number(frameW) > 0 ? Number(frameW) : DEFAULT_FRAME_W;
  const height = Number(frameH) > 0 ? Number(frameH) : DEFAULT_FRAME_H;
  const boxWidth = Number(rect?.width) > 0 ? Number(rect.width) : width;
  const boxHeight = Number(rect?.height) > 0 ? Number(rect.height) : height;
  const scale = Math.min(boxWidth / width, boxHeight / height);
  const drawnWidth = width * scale;
  const drawnHeight = height * scale;
  return {
    left: (Number(rect?.left) || 0) + (boxWidth - drawnWidth) / 2,
    top: (Number(rect?.top) || 0) + (boxHeight - drawnHeight) / 2,
    width: drawnWidth,
    height: drawnHeight,
    scale
  };
}

/**
 * 面板里的指针事件 → 页面坐标（0..viewportW / 0..viewportH）。
 *
 * @param rect        镜像 <img> 的渲染框（getBoundingClientRect）
 * @param viewportW   页面 CSS 视口宽（一般传 {@link PAGE_VIEWPORT}）
 * @param viewportH   页面 CSS 视口高
 * @returns {{x: number, y: number}|null} 参数非法时返回 null
 */
export function pointFromEvent(clientX, clientY, rect, viewportW = PAGE_VIEWPORT.width, viewportH = PAGE_VIEWPORT.height) {
  if (!Number.isFinite(clientX) || !Number.isFinite(clientY) || !rect) {
    return null;
  }
  const box = imageContentBox(rect, viewportW, viewportH);
  if (box.scale <= 0) {
    return null;
  }
  const x = (clientX - box.left) / box.scale;
  const y = (clientY - box.top) / box.scale;
  const width = Number(viewportW) > 0 ? Number(viewportW) : DEFAULT_FRAME_W;
  const height = Number(viewportH) > 0 ? Number(viewportH) : DEFAULT_FRAME_H;
  const clamp = (value, max) => Math.max(0, Math.min(max, Math.round(value)));
  return { x: clamp(x, width - 1), y: clamp(y, height - 1) };
}

/** 需要整键转发（而不是当成文本字符）的按键。 */
const NAMED_KEYS = new Set([
  'Enter', 'Backspace', 'Tab', 'Escape', 'Delete', 'Insert',
  'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End', 'PageUp', 'PageDown'
]);

/**
 * 键盘事件 → 转发给页面浏览器的命令。
 *
 * <p>返回 {@code {text}} 表示一个可打印字符（连续字符由调用方合并成一次输入），
 * {@code {key}} 表示整键按下；{@code null} 表示这个键不该转发（输入法组合中、
 * 单纯修饰键、功能键等）。
 *
 * @param {{key?: string, ctrlKey?: boolean, metaKey?: boolean, altKey?: boolean, isComposing?: boolean}} event
 */
export function keyCommand(event) {
  if (!event || event.isComposing) {
    return null;
  }
  const key = event.key;
  if (!key || key === 'Process' || key === 'Unidentified') {
    return null;
  }
  const mod = Boolean(event.ctrlKey || event.metaKey);
  if (mod && !event.altKey) {
    // Ctrl/Cmd + 字母：交给页面做快捷键（Ctrl+A / Ctrl+C…）
    if (key.length === 1) {
      return { key: `${event.metaKey ? 'Meta' : 'Control'}+${key.toUpperCase()}` };
    }
    return null;
  }
  if (NAMED_KEYS.has(key)) {
    return { key };
  }
  // 单个字符：空格也算字符，直接走 insertText，避免 keypress 与输入法互相打架
  if (key.length === 1) {
    return { text: key };
  }
  return null;
}
