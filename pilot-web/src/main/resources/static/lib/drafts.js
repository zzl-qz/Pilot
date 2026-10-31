/**
 * 输入框草稿：按会话存在 localStorage，刷新、切换会话、误关页面都不丢。
 * 存储对象由调用方传入，方便在测试里换成内存实现；存储不可用时静默降级。
 */
const PREFIX = 'pilot.draft.';
const MAX_LENGTH = 20000;

export function draftKey(sessionId) {
  return sessionId ? PREFIX + sessionId : null;
}

export function readDraft(storage, sessionId) {
  const key = draftKey(sessionId);
  if (!key) {
    return '';
  }
  try {
    return String(storage.getItem(key) || '');
  } catch {
    return '';
  }
}

/** 空白草稿直接删掉键，避免 localStorage 里堆满空条目。 */
export function writeDraft(storage, sessionId, text) {
  const key = draftKey(sessionId);
  if (!key) {
    return;
  }
  const value = String(text || '');
  try {
    if (!value.trim()) {
      storage.removeItem(key);
    } else {
      storage.setItem(key, value.slice(0, MAX_LENGTH));
    }
  } catch {
    // 配额满或隐私模式：草稿只是便利功能，不影响正常使用
  }
}

/** 会话被删除后顺手清掉它的草稿。 */
export function dropDraft(storage, sessionId) {
  writeDraft(storage, sessionId, '');
}
