/**
 * 浏览器镜像面板的纯逻辑：帧解析、延迟文案、涟漪坐标与状态机。
 *
 * <p>SSE 连接与 DOM 更新在 app.js 的 mirrorView 里；这里只放值得单测的判定，
 * 不碰 document/EventSource。侧边栏镜像走的是瞬态流（/browser/stream），
 * 与会话事件流（带 sequence、可回放）刻意分开，帧丢失不需要恢复。
 */

/**
 * 帧事件 → <img> 的 src（dataURL）。
 * 帧体缺失或不是 base64 时返回 null：调用方跳过本次渲染，避免用旧帧闪屏。
 */
export function frameSrc(payload) {
  const frame = payload && payload.frame;
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
