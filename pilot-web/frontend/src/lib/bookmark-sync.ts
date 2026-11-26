import type { MessageLibraryBookmark } from '../types/server.js';
interface LocalBookmark { sequence: number; title: string; preview?: string; key?: string }
export interface BookmarkView extends MessageLibraryBookmark { id: string }
interface BookmarkSyncResult { values: BookmarkView[]; skipped: number[] }
import { api } from '../api.js';
import { safeLocalStorage, safeSetItem } from './safe-storage.js';
const storage = safeLocalStorage();
const key = (session: string | null) => `pilot.message-bookmarks:${session}`;
export function localBookmarks(session: string | null): LocalBookmark[] {
  try { const values = JSON.parse(storage.getItem(key(session)) || '[]'); return Array.isArray(values) ? values.filter((e: LocalBookmark) => Number.isSafeInteger(e?.sequence) && typeof e.title === 'string').slice(0, 500) : []; }
  catch { return []; }
}
export function normalizeBookmarks(values: unknown): BookmarkView[] {
  if (!Array.isArray(values) || values.some(e => !e || !Number.isSafeInteger(e.sequence) || e.sequence < 0 || typeof e.title !== 'string')) throw new Error('书签服务返回格式无效');
  return values.map((e: MessageLibraryBookmark) => ({ ...e, id: String(e.sequence), key: e.key || '' }));
}
const pending = new Map<string, Promise<BookmarkSyncResult>>();
export function syncBookmarks(session: string): Promise<BookmarkSyncResult> {
  if (pending.has(session)) return pending.get(session)!;
  const promise = (async () => {
    const local = localBookmarks(session);
    if (local.length) {
      const data = await api.importBookmarks(session, local.map(e => e.sequence));
      const values = normalizeBookmarks(data?.bookmarks);
      // 迁移成功才移走旧数据；无法定位的旧条目保存在备份，服务端迁移指纹避免恢复已删除的书签。
      try { storage.setItem(key(session) + ':migration-backup', JSON.stringify(local)); storage.removeItem(key(session)); } catch (error) { console.warn('书签迁移备份无法写入本机存储，保留服务端迁移结果', error); }
      return { values, skipped: data!.skipped || [] };
    }
    return { values: normalizeBookmarks(await api.bookmarks(session)), skipped: [] };
  })().finally(() => pending.delete(session));
  pending.set(session, promise); return promise;
}
export async function migrateLocalBookmarks() {
  const sessions = [];
  if (!storage.available()) throw new Error('无法读取旧书签存储，请检查浏览器存储权限。');
  try {
    for (let index = 0; index < storage.length; index++) {
      const name = storage.key(index);
      if (name?.startsWith('pilot.message-bookmarks:') && !name.endsWith(':migration-backup')) sessions.push(name.slice('pilot.message-bookmarks:'.length));
    }
  } catch { throw new Error('无法读取旧书签存储，请检查浏览器存储权限。'); }
  const failed = []; let count = 0;
  for (const session of sessions) {
    if (!localBookmarks(session).length) continue;
    try { await syncBookmarks(session); count++; } catch { failed.push(session); }
  }
  return { count, failed };
}
export const BOOKMARK_SIGNAL = 'pilot.bookmarks-changed';
export function bookmarksChanged(session: string) {
  window.dispatchEvent(new CustomEvent('pilot:bookmarks-changed', { detail: session }));
  // storage 事件送达同源的其它窗口；每次值不同，连续编辑也不会丢通知。
  return safeSetItem(storage, BOOKMARK_SIGNAL, JSON.stringify({ session, nonce: `${Date.now()}:${Math.random()}` }));
}
