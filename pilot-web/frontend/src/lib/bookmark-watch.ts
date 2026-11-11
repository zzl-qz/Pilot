import { BOOKMARK_SIGNAL } from './bookmark-sync.js';

type WatchOptions = {
  session: string; refresh: () => Promise<void>; invalidate: () => void;
  window?: Pick<Window, 'addEventListener' | 'removeEventListener' | 'setInterval' | 'clearInterval'>;
  document?: Pick<Document, 'hidden' | 'addEventListener' | 'removeEventListener'>;
  interval?: number;
};
/** 后台停止轮询，唤醒必刷新；写入通知作废在途读取，并在读取结束后补取最新值。 */
export function watchBookmarks({ session, refresh, invalidate, window: win = window, document: doc = document, interval = 15000 }: WatchOptions) {
  let closed = false, busy = false, again = false, initialized = false;
  const request = () => {
    if (closed) return;
    invalidate();
    // 首次挂载读一次；之后仅前台刷新，隐藏的桌面预加载窗口也能拿到初始状态。
    if (doc.hidden && initialized) return;
    initialized = true;
    if (busy) { again = true; return; }
    busy = true;
    void (async () => {
      do {
        again = false;
        try { await refresh(); } catch { /* refresh 负责显示同步失败，下一次唤醒仍可重试。 */ }
      } while (again && !closed && !doc.hidden);
    })().finally(() => { busy = false; if (again && !closed && !doc.hidden) request(); });
  };
  const changed = (event: Event) => { if ((event as CustomEvent<string>).detail === session) request(); };
  const storage = (event: Event) => {
    const value = event as StorageEvent;
    if (value.key === null) { request(); return; }
    if (value.key === `pilot.message-bookmarks:${session}`) { request(); return; }
    if (value.key !== BOOKMARK_SIGNAL) return;
    try { if (JSON.parse(value.newValue || 'null')?.session === session) request(); } catch { /* 不信任存储事件正文。 */ }
  };
  const timer = win.setInterval(() => { if (!doc.hidden) request(); }, interval);
  win.addEventListener('focus', request);
  win.addEventListener('online', request);
  win.addEventListener('storage', storage);
  win.addEventListener('pilot:bookmarks-changed', changed);
  doc.addEventListener('visibilitychange', request);
  request();
  return () => {
    closed = true; invalidate(); win.clearInterval(timer);
    win.removeEventListener('focus', request); win.removeEventListener('online', request);
    win.removeEventListener('storage', storage); win.removeEventListener('pilot:bookmarks-changed', changed);
    doc.removeEventListener('visibilitychange', request);
  };
}

/** 等待 React/布局落地；后台 requestAnimationFrame 暂停时仍能结束或取消分页定位。 */
export function afterNavigationPaint() {
  return new Promise<void>(resolve => {
    let first: number | undefined, second: number | undefined;
    const done = () => {
      clearTimeout(timer);
      if (first != null) cancelAnimationFrame(first);
      if (second != null) cancelAnimationFrame(second);
      resolve();
    };
    const timer = setTimeout(done, 100);
    first = requestAnimationFrame(() => { second = requestAnimationFrame(done); });
  });
}
