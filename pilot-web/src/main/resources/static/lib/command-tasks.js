const ACTIVE_STATUSES = new Set(['RUNNING', 'STOPPING']);
const STATUS_LABELS = {
  RUNNING: '运行中',
  STOPPING: '停止中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  TIMED_OUT: '已超时',
  INTERRUPTED: '已中断',
  OUTPUT_LIMIT: '输出已达上限'
};

function normalizedStatus(status) {
  return String(status || '').trim().toUpperCase();
}

export function isCommandActive(status) {
  return ACTIVE_STATUSES.has(normalizedStatus(status));
}

export function commandStatusLabel(status) {
  return STATUS_LABELS[normalizedStatus(status)] || '未知状态';
}

function validOffset(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

function codePointLength(text) {
  let count = 0;
  for (const character of text) count++;
  return count;
}

/**
 * 合并增量日志；服务端游标按 Unicode 码点计数，始终以 nextOffset 为准。
 * text 保留最近 limit 个 UTF-16 单元，trimmed 统计已省略的码点数。
 */
export function mergeCommandLog(current, chunk, limit = 200000) {
  const result = {
    text: String(current?.text || ''),
    offset: validOffset(current?.offset) ? current.offset : 0,
    trimmed: validOffset(current?.trimmed) ? current.trimmed : 0,
    truncated: Boolean(current?.truncated || chunk?.truncated),
    eof: Boolean(current?.eof)
  };
  if (!validOffset(chunk?.offset) || !validOffset(chunk?.nextOffset)
      || chunk.nextOffset < chunk.offset || chunk.nextOffset < result.offset) {
    return result;
  }

  const incoming = String(chunk.text || '');
  if (chunk.offset > result.offset) {
    // 缺失的区间不能和已有内容直接拼接，保留新片段并说明省略了前面的内容。
    result.trimmed += codePointLength(result.text) + chunk.offset - result.offset;
    result.text = incoming;
  } else if (chunk.nextOffset > result.offset) {
    const overlap = result.offset - chunk.offset;
    result.text += overlap ? Array.from(incoming).slice(overlap).join('') : incoming;
  }

  // 同游标的重复响应不能清除 EOF；只有追加新内容才可能重新变成未结束。
  result.eof = chunk.nextOffset === result.offset
    ? result.eof || Boolean(chunk.eof)
    : Boolean(chunk.eof);
  result.offset = chunk.nextOffset;

  const maxLength = Number.isFinite(limit) ? Math.max(0, Math.floor(limit)) : 200000;
  let removed = Math.max(0, result.text.length - maxLength);
  if (removed && removed < result.text.length) {
    const previous = result.text.charCodeAt(removed - 1);
    const first = result.text.charCodeAt(removed);
    if (previous >= 0xd800 && previous <= 0xdbff && first >= 0xdc00 && first <= 0xdfff) removed++;
  }
  if (removed) {
    result.trimmed += codePointLength(result.text.slice(0, removed));
    result.text = result.text.slice(removed);
  }
  return result;
}
