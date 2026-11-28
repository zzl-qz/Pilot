/** 线程渲染共用的小工具（从旧版 app.js 抽出的纯函数）。 */

/** 用量数字：万以内原样，再大收成「万 / 亿」，避免一行被六位数字撑开。 */
export function formatCount(value: unknown) {
  const count = Number(value);
  if (!Number.isFinite(count)) {
    return '—';
  }
  const sign = count < 0 ? '-' : '';
  const abs = Math.abs(count);
  if (abs < 10000) {
    return sign + String(Math.round(abs));
  }
  if (abs < 100000000) {
    const wan = abs / 10000;
    const digits = wan >= 100 ? 0 : 1;
    return `${sign}${wan.toFixed(digits)}万`;
  }
  return `${sign}${(abs / 100000000).toFixed(1)}亿`;
}

/** 紧凑数字口径：万级以上转 k/M/B，供设置页用量与诊断指标复用。 */
export function formatCompactCount(value: unknown) {
  const number = Number(value) || 0;
  if (number >= 1_000_000_000) return `${(number / 1_000_000_000).toFixed(1)}B`;
  if (number >= 1_000_000) return `${(number / 1_000_000).toFixed(1)}M`;
  if (number >= 10_000) return `${(number / 1_000).toFixed(0)}k`;
  return number.toLocaleString('zh-CN');
}

export function preview(value: unknown, maxLength: number) {
  const single = String(value ?? '').replace(/\s+/g, ' ').trim();
  return single.length <= maxLength ? single : single.slice(0, maxLength) + '…';
}

/** 流式输出 token 的粗估（与旧版 estimateTextTokens 语义一致，直接按字符数折算）。 */
export function estimateTokens(text: unknown) {
  return Math.ceil(String(text || '').length / 3);
}

export function formatTime(value: unknown) {
  const time = Number(value);
  if (!Number.isFinite(time) || time <= 0) {
    return '';
  }
  const date = new Date(time);
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

/** 跑了多久：45 秒写成 0:45，两分半写成 2:31。 */
export function formatClock(seconds: number) {
  const whole = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(whole / 3600);
  const minutes = Math.floor(whole / 60) % 60;
  const rest = String(whole % 60).padStart(2, '0');
  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, '0')}:${rest}`;
  }
  return `${minutes}:${rest}`;
}

export function sameUserInput(left: unknown, right: unknown) {
  return String(left || '').replace(/\s+/g, ' ').trim() === String(right || '').replace(/\s+/g, ' ').trim();
}
