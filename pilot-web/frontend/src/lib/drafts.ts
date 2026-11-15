import type { DraftAttachment } from '../types/input.js';

/**
 * 输入框草稿：按会话存在 localStorage，刷新、切换会话、误关页面都不丢。
 * 存储对象由调用方传入，方便在测试里换成内存实现；存储不可用时静默降级。
 */
const PREFIX = 'pilot.draft.';
const MAX_LENGTH = 20000;
const FILES_PREFIX = 'pilot.draft.files.';
/** 浏览器存储通常只有 5MB；超过这个体积就明确告诉用户刷新后无法恢复，而不是静默存半截。 */
const MAX_SNAPSHOT_CHARS = 4 * 1024 * 1024;

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

/* ---------- 附件草稿：正文、文件、图片与图片位置一起恢复 ---------- */

export interface DraftSnapshot {
  version: 1;
  text: string;
  files: DraftAttachment[];
  imagePositions: number[];
}

export function draftFilesKey(sessionId: unknown) {
  return FILES_PREFIX + (sessionId || NEW_SESSION_DRAFT_KEY);
}

export function dropDraftSnapshot(storage: Pick<Storage, 'removeItem'>, sessionId: unknown) {
  try {
    storage.removeItem(draftFilesKey(sessionId));
  } catch {
    // 存储不可用时没有可清理的副本
  }
}

/**
 * 读取草稿附件。没有副本时 snapshot 为 null；副本损坏时会清掉并返回 error，
 * 让界面提示「需要重新添加附件」，而不是每次刷新都报同一条错。
 */
export function readDraftSnapshot(storage: Pick<Storage, 'getItem' | 'removeItem'>, sessionId: unknown):
  { snapshot: DraftSnapshot | null; error?: 'corrupt' } {
  let raw: string | null = null;
  try {
    raw = storage.getItem(draftFilesKey(sessionId));
  } catch {
    return { snapshot: null };
  }
  if (!raw) {
    return { snapshot: null };
  }
  try {
    const parsed = JSON.parse(raw) as DraftSnapshot;
    const files = Array.isArray(parsed?.files) ? parsed.files : [];
    const valid = files.filter(file => file && typeof file.id === 'string' && typeof file.name === 'string'
      && typeof file.dataBase64 === 'string' && Number.isFinite(file.size));
    const positions = Array.isArray(parsed?.imagePositions)
      ? parsed.imagePositions.filter(value => Number.isFinite(value)).map(Number) : [];
    if (!valid.length) {
      throw new Error('empty');
    }
    return {
      snapshot: {
        version: 1,
        text: typeof parsed.text === 'string' ? parsed.text : '',
        files: valid as DraftAttachment[],
        imagePositions: positions
      }
    };
  } catch {
    dropDraftSnapshot(storage, sessionId);
    return { snapshot: null, error: 'corrupt' };
  }
}

/**
 * 保存草稿附件副本。文字草稿另存，这里只负责附件与图片位置。
 *
 * @returns 存储不可用或超过配额时返回原因，调用方据此提示用户
 */
export function writeDraftSnapshot(
  storage: Pick<Storage, 'setItem' | 'removeItem'>,
  sessionId: unknown,
  snapshot: { text: string; files: DraftAttachment[]; imagePositions: number[] }
): { ok: boolean; reason?: string } {
  const files = snapshot.files.filter(file => file && typeof file.dataBase64 === 'string');
  if (!files.length) {
    dropDraftSnapshot(storage, sessionId);
    return { ok: true };
  }
  const payload: DraftSnapshot = {
    version: 1,
    text: String(snapshot.text || '').slice(0, MAX_LENGTH),
    files,
    imagePositions: snapshot.imagePositions.filter(value => Number.isFinite(value)).map(Number)
  };
  const value = JSON.stringify(payload);
  if (value.length > MAX_SNAPSHOT_CHARS) {
    dropDraftSnapshot(storage, sessionId);
    return {
      ok: false,
      reason: `${files.length} 个未发送附件超过浏览器草稿上限，刷新后无法恢复；请先发送或移除后再离开页面。`
    };
  }
  try {
    storage.setItem(draftFilesKey(sessionId), value);
    return { ok: true };
  } catch {
    // 配额满或隐私模式：清掉旧副本，避免恢复出与当前草稿不一致的附件
    dropDraftSnapshot(storage, sessionId);
    return {
      ok: false,
      reason: `${files.length} 个未发送附件无法写入浏览器存储（空间不足或被禁用），刷新后无法恢复；请先发送或移除。`
    };
  }
}