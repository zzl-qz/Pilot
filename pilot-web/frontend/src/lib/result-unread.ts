import { useEffect } from 'react';
import { api } from '../api.js';
import { useStore } from '../store.js';
import type { ResultInboxServiceItem } from '../types/server.js';

export function updateResultUnread(items: ResultInboxServiceItem[]) {
  const unread = items.filter(item => !item.read && !item.handled).length;
  const state = useStore.getState();
  if (unread !== state.ui.resultUnread) state.setUi({ resultUnread: unread });
}

export function useResultUnread(enabled: boolean) {
  useEffect(() => {
    if (!enabled) return;
    let current = true;
    let pending = false;
    async function poll() {
      if (pending) return;
      pending = true;
      try {
        const data = await api.results();
        if (current && Array.isArray(data?.items)) updateResultUnread(data.items);
      } catch {
        // 后台失败保留已有计数，打开收件箱后可以查看错误和重试。
      } finally {
        pending = false;
      }
    }
    void poll();
    const timer = setInterval(poll, 15000);
    return () => {
      current = false;
      clearInterval(timer);
    };
  }, [enabled]);
}
