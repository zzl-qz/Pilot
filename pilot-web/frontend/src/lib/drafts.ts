/**
 * 输入框草稿：按会话存在 localStorage，刷新、切换会话、误关页面都不丢。
 * 存储对象由调用方传入，方便在测试里换成内存实现；存储不可用时静默降级。
 */
const PREFIX = 'pilot.draft.';
const MAX_LENGTH = 20000;

/**
 * 还没有会话记录的「新对话」共用一个草稿槽：惰性创建下它可能一直没有 ID，
 * 但刷新、切去别的会话再回来时，刚敲的字不该丢。同一时刻只可能有一个新对话在编辑。
 */
const NEW_SESSION_DRAFT_KEY = '__new__';

export function draftKey(sessionId: unknown) {
  return PREFIX + (sessionId || NEW_SESSION_DRAFT_KEY);
}

export function readDraft(storage: Pick<Storage, 'getItem'>, sessionId: unknown) {
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
export function writeDraft(storage: Pick<Storage, 'setItem' | 'removeItem'>, sessionId: unknown, text: unknown) {
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
export function dropDraft(storage: Pick<Storage, 'setItem' | 'removeItem'>, sessionId: unknown) {
  writeDraft(storage, sessionId, '');
}
