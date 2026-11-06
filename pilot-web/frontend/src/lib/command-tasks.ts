import type { CommandLog, CommandLogChunk } from '../types/runtime.js';
const ACTIVE_STATUSES = new Set(['RUNNING', 'STOPPING']);
const STATUS_LABELS: Record<string, string> = {
  RUNNING: '运行中',
  STOPPING: '停止中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  TIMED_OUT: '已超时',
  INTERRUPTED: '已中断',
  OUTPUT_LIMIT: '输出已达上限'
};

function normalizedStatus(status: unknown) {
  return String(status || '').trim().toUpperCase();
}

/** 同时接受状态字符串和后端任务对象：传错形态时给出「未知」而不是静默判成非运行中。 */
function statusOf(value: string | { status?: string } | null | undefined) {
  return typeof value === 'string' ? value : (value?.status || '');
}

export function isCommandActive(value: string | { status?: string } | null | undefined) {
  return ACTIVE_STATUSES.has(normalizedStatus(statusOf(value)));
}

export function commandStatusLabel(value: string | { status?: string } | null | undefined) {
  return STATUS_LABELS[normalizedStatus(statusOf(value))] || '未知状态';
}

/**
 * 命令输出里带的是终端控制序列：直接塞进 <pre> 会显示成一堆 `[0m`、`[32m`。
 * 这里剥掉颜色、光标移动、窗口标题（OSC）和进度条用的裸回车，只留人能读的文字。
 */
export function stripAnsi(text: unknown) {
  return String(text || '')
    .replace(/\u001b\][^\u0007\u001b]*(?:\u0007|\u001b\\)/g, '')
    .replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, '')
    .replace(/\u001b[()#][0-9A-Za-z]/g, '')
    .replace(/\u001b[@-Z\\-_]/g, '')
    .replace(/\r(?!\n)/g, '');
}

function validOffset(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function codePointLength(text: string) {
  let count = 0;
  for (const character of text) count++;
  return count;
}

/**
 * 合并增量日志；服务端游标按 Unicode 码点计数，始终以 nextOffset 为准。
 * text 保留最近 limit 个 UTF-16 单元，trimmed 统计已省略的码点数。
 */
export function mergeCommandLog(current: Partial<CommandLog> | null | undefined, chunk: CommandLogChunk | null | undefined, limit = 200000): CommandLog {
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
