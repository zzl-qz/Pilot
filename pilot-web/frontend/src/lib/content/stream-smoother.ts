/**
 * 流式正文的自适应平滑吐字步进算法（对齐 Codex / 桌面终端渲染节拍）。
 *
 * <p>上游 SSE 常因网络分包或模型推理抖动一次吐出数十到数百字符；
 * 直接整块拍进 DOM 会让文本区域瞬间蹦高多行。按每帧 35% 积压自适应推进：
 * - 小增量（<= 6 字符）或开启减少动效时立即落位，零延迟；
 * - 常规突发在 3~6 帧（50~100ms）内匀速舒展，不拖慢整体完成时间；
 * - 文本缩短、重置或结束流式时瞬间对齐，不残留旧尾字。
 */
export function nextSmoothLength(current: number, target: number, reducedMotion = false): number {
  if (reducedMotion || !Number.isFinite(current) || !Number.isFinite(target)) {
    return Math.max(0, target);
  }
  if (target <= current) {
    return Math.max(0, target);
  }
  const backlog = target - current;
  if (backlog <= 6) {
    return target;
  }
  const step = Math.max(8, Math.ceil(backlog * 0.35));
  return Math.min(target, current + step);
}

/**
 * 避免在 UTF-16 代理对（emoji 等 4 字节字符）中间截断，导致流式帧短暂出现乱码替换符。
 */
export function safeSliceLength(text: string, desired: number): number {
  if (desired <= 0) return 0;
  if (desired >= text.length) return text.length;
  const code = text.charCodeAt(desired - 1);
  if (code >= 0xd800 && code <= 0xdbff) {
    return Math.min(text.length, desired + 1);
  }
  return desired;
}
