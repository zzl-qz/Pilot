/** 线程渲染共用的小工具（从旧版 app.js 抽出的纯函数）。 */

export function preview(value, maxLength) {
  const single = String(value ?? '').replace(/\s+/g, ' ').trim();
  return single.length <= maxLength ? single : single.slice(0, maxLength) + '…';
}

/** 流式输出 token 的粗估（与旧版 estimateTextTokens 语义一致，直接按字符数折算）。 */
export function estimateTokens(text) {
  return Math.ceil(String(text || '').length / 3);
}

export function formatTime(value) {
  const time = Number(value);
  if (!Number.isFinite(time) || time <= 0) {
    return '';
  }
  const date = new Date(time);
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

export function formatBytes(bytes) {
  const size = Number(bytes);
  if (!Number.isFinite(size) || size < 0) {
    return '';
  }
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

export function sameUserInput(left, right) {
  return String(left || '').replace(/\s+/g, ' ').trim() === String(right || '').replace(/\s+/g, ' ').trim();
}
