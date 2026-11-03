import { api } from '../api.js';
const key = session => `pilot.message-bookmarks:${session}`;
export function localBookmarks(session) {
  try { const values = JSON.parse(localStorage.getItem(key(session)) || '[]'); return Array.isArray(values) ? values.filter(e => Number.isSafeInteger(e?.sequence) && typeof e.title === 'string').slice(0, 500) : []; }
  catch { return []; }
}
export function normalizeBookmarks(values) {
  if (!Array.isArray(values)) throw new Error('书签服务返回格式无效');
  return values.map(e => ({ ...e, id: String(e.sequence), key: e.key || '' }));
}
const pending = new Map();
export function syncBookmarks(session) {
  if (pending.has(session)) return pending.get(session);
  const promise = (async () => {
    const local = localBookmarks(session);
    if (local.length) {
      const data = await api.importBookmarks(session, local.map(e => e.sequence));
      const values = normalizeBookmarks(data.bookmarks);
      // 迁移成功才移走旧数据；无法定位的旧条目保存在备份，服务端迁移指纹避免恢复已删除的书签。
      try { localStorage.setItem(key(session) + ':migration-backup', JSON.stringify(local)); localStorage.removeItem(key(session)); } catch { /* 服务端已有迁移标记，下次不会重复导入 */ }
      return { values, skipped: data.skipped || [] };
    }
    return { values: normalizeBookmarks(await api.bookmarks(session)), skipped: [] };
  })().finally(() => pending.delete(session));
  pending.set(session, promise); return promise;
}
export async function migrateLocalBookmarks() {
  const sessions = [];
  try {
    for (let index = 0; index < localStorage.length; index++) {
      const name = localStorage.key(index);
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
export function bookmarksChanged(session) { window.dispatchEvent(new CustomEvent('pilot:bookmarks-changed', { detail: session })); }
