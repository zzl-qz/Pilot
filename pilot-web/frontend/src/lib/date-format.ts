/**
 * 安全日期格式化：非法、缺失或超出范围的时间戳不输出 "Invalid Date"，也不抛出 RangeError。
 */
export function parseValidDate(value: string | number | Date | null | undefined): Date | null {
  if (value == null || value === '') return null;
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function formatDateTime(
  value: string | number | Date | null | undefined,
  options?: { timeZone?: string; fallback?: string; locale?: string }
): string {
  const fallback = options?.fallback ?? '';
  const date = parseValidDate(value);
  if (!date) return fallback;
  try {
    return date.toLocaleString(options?.locale || 'zh-CN', {
      hourCycle: 'h23',
      ...(options?.timeZone ? { timeZone: options.timeZone } : {})
    });
  } catch {
    return typeof value === 'string' ? value : fallback;
  }
}

export function formatDate(
  value: string | number | Date | null | undefined,
  options?: { fallback?: string; locale?: string }
): string {
  const fallback = options?.fallback ?? '';
  const date = parseValidDate(value);
  if (!date) return fallback;
  try {
    return date.toLocaleDateString(options?.locale || 'zh-CN');
  } catch {
    return typeof value === 'string' ? value : fallback;
  }
}

export function safeIsoDate(value: string | number | Date | null | undefined): string | undefined {
  const date = parseValidDate(value);
  return date ? date.toISOString() : undefined;
}
