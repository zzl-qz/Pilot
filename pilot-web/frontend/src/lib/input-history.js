/**
 * 本会话的输入历史（↑ / ↓ 翻）。
 *
 * <p>与旧版 app.js 的 rememberInput / loadInputHistory 同语义：按会话存 localStorage，
 * 相同内容移到末尾（不重复），最多保留 100 条。抽成纯函数是为了能直接单测。
 */

const PREFIX = 'pilot.inputHistory.';
const LIMIT = 100;

export function historyKey(sessionId) {
  return sessionId ? PREFIX + sessionId : null;
}

export function loadInputHistory(storage, sessionId) {
  const key = historyKey(sessionId);
  if (!key) {
    return [];
  }
  try {
    const parsed = JSON.parse(storage.getItem(key) || '[]');
    return Array.isArray(parsed) ? parsed.filter((item) => typeof item === 'string') : [];
  } catch {
    return [];
  }
}

/** 记一条发出去的内容，返回更新后的历史（存不下也返回内存里的结果）。 */
export function rememberInput(storage, sessionId, text) {
  const value = String(text || '').trim();
  const key = historyKey(sessionId);
  if (!value || !key) {
    return null;
  }
  const history = loadInputHistory(storage, sessionId).filter((item) => item !== value);
  history.push(value);
  const next = history.slice(-LIMIT);
  try {
    storage.setItem(key, JSON.stringify(next));
  } catch {
    // 隐私模式 / 配额满：只影响下次启动能否翻到，不影响本次
  }
  return next;
}

/**
 * 在历史里走一步。
 * index = -1 表示停在草稿上；往上翻从最新一条开始，翻回底部时把草稿还回去。
 *
 * @returns {{index: number, text: string|null}} text 为 null 表示「回到草稿」
 */
export function stepHistory(history, index, direction, draft) {
  const list = Array.isArray(history) ? history : [];
  if (!list.length) {
    return { index: -1, text: null };
  }
  if (index < 0) {
    if (direction !== 'up') {
      return { index: -1, text: null };
    }
    return { index: list.length - 1, text: list[list.length - 1] };
  }
  const next = index + (direction === 'up' ? -1 : 1);
  if (next < 0) {
    // 已经是最早一条，继续往上不动
    return { index: 0, text: list[0] };
  }
  if (next >= list.length) {
    return { index: -1, text: draft == null ? null : draft };
  }
  return { index: next, text: list[next] };
}
