/** 与后端一致的内容与条数上限。 */
export const NOTE_LIMIT = 4000;
export const NOTE_COUNT_LIMIT = 200;

/** 还没有会话记录时，想法先存在本机；发出第一条消息后自动并入会话。 */
export interface StoredNote {
  id: string;
  content: string;
  createdAt: string;
  updatedAt: string;
}

export const DRAFT_NOTES_KEY = 'pilot.notes.draft';

function draftId(): string {
  const crypto = globalThis.crypto;
  if (crypto && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

/** 内容校验：空内容和超长给出可读原因，界面直接显示。 */
export function noteContentError(content: string): string | null {
  if (!content || !content.trim()) return '想法内容不能为空';
  if ([...content].length > NOTE_LIMIT) return `每条想法最多 ${NOTE_LIMIT} 个字符`;
  return null;
}

/** 从本地草稿里读出想法；坏数据或旧格式直接跳过，不影响打开面板。 */
export function readDraftNotes(storage: Pick<Storage, 'getItem'>): StoredNote[] {
  try {
    const parsed = JSON.parse(String(storage.getItem(DRAFT_NOTES_KEY) || '[]')) as unknown;
    if (!Array.isArray(parsed)) return [];
    const notes: StoredNote[] = [];
    for (const item of parsed) {
      if (!item || typeof item !== 'object') continue;
      const record = item as Record<string, unknown>;
      const content = typeof record.content === 'string' ? record.content : '';
      if (noteContentError(content)) continue;
      const createdAt = typeof record.createdAt === 'string' ? record.createdAt : new Date().toISOString();
      notes.push({
        id: typeof record.id === 'string' && record.id ? record.id : 'draft_note_' + draftId(),
        content,
        createdAt,
        updatedAt: typeof record.updatedAt === 'string' ? record.updatedAt : createdAt
      });
      if (notes.length >= NOTE_COUNT_LIMIT) break;
    }
    return notes;
  } catch {
    return [];
  }
}

/** 写回本地草稿；空列表直接删键，避免 localStorage 里留一份空数组。 */
export function writeDraftNotes(storage: Pick<Storage, 'setItem' | 'removeItem'>, notes: readonly StoredNote[]) {
  try {
    if (!notes.length) {
      storage.removeItem(DRAFT_NOTES_KEY);
    } else {
      storage.setItem(DRAFT_NOTES_KEY, JSON.stringify(notes.slice(0, NOTE_COUNT_LIMIT)));
    }
  } catch {
    // 隐私模式或配额满：想法只是便利功能，不能因此打断输入
  }
}

export function createDraftNote(content: string, now: Date = new Date()): StoredNote | null {
  if (noteContentError(content)) return null;
  return {
    id: 'draft_note_' + draftId(),
    content,
    createdAt: now.toISOString(),
    updatedAt: now.toISOString()
  };
}

/**
 * 发出第一条消息、真正建立会话时，把本地草稿里的想法搬到服务端。
 *
 * <p>最早的先传，服务端按写入顺序倒序展示，迁移后列表顺序和草稿里一致；
 * 中途失败就停下并把没传的留在本地，界面可以提示还有几条没保存。
 */
export async function uploadDraftNotes(notes: readonly StoredNote[], create: (content: string) => Promise<unknown>) {
  let uploaded = 0;
  for (const note of [...notes].reverse()) {
    try {
      await create(note.content);
      uploaded += 1;
    } catch {
      break;
    }
  }
  return { uploaded, remaining: notes.slice(0, notes.length - uploaded) };
}

/**
 * 点想法回填输入框时追加的文本。
 *
 * <p>输入框已有草稿就另起一行，不覆盖也不改写原草稿；草稿本身已经以换行结尾时
 * 不再多补一个空行。空草稿（包括只有空白）直接填入想法原文。
 */
export function noteInsertion(existing: string, content: string): string {
  if (!existing.trim()) return content;
  return /\n$/.test(existing) ? content : '\n' + content;
}
