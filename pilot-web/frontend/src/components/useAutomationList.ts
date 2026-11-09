import { useCallback, useEffect, useRef, useState } from 'react';
import { errorInfo } from '../lib/errors.js';

/** 轮询保留已读取的数据。主动刷新可以取代旧请求，慢请求不会被下一轮轮询丢弃。 */
export function useAutomationList<T>(load: () => Promise<T[] | null>, interval?: number) {
  const [rows, setRows] = useState<T[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const request = useRef(0);
  const inFlight = useRef(false);
  const refresh = useCallback(async (background = false) => {
    if (background && inFlight.current) return;
    inFlight.current = true;
    const token = ++request.current;
    if (!background) setLoading(true);
    try {
      const data = await load();
      if (!Array.isArray(data)) throw new Error('服务端未返回有效的列表');
      if (token !== request.current) return;
      setRows(data);
      setError('');
    } catch (err) {
      if (token === request.current) setError(errorInfo(err).message || '请稍后重试');
    } finally {
      if (token === request.current) {
        inFlight.current = false;
        setLoading(false);
      }
    }
  }, [load]);

  useEffect(() => {
    void refresh();
    const timer = interval ? setInterval(() => { void refresh(true); }, interval) : undefined;
    return () => {
      request.current++;
      clearInterval(timer);
    };
  }, [refresh, interval]);

  return { rows, setRows, loading, error, refresh };
}
