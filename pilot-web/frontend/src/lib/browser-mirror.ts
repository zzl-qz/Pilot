import type { MirrorState } from '../types/store.js';

/** 人在地址栏输入的地址。本机没写协议时用 http，其它主机用 https。 */
export function userPageUrl(input: unknown) {
  const text = String(input || '').trim();
  if (!text) return null;
  const hasScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(text);
  let url;
  try {
    url = new URL(hasScheme ? text : `http://${text}`);
  } catch {
    return null;
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
  if (!hasScheme) {
    const host = url.hostname.replace(/^\[|\]$/g, '');
    const local = host === 'localhost' || host.endsWith('.localhost')
      || host === '127.0.0.1' || host === '::1' || /^127\.\d+\.\d+\.\d+$/.test(host);
    if (!local) url.protocol = 'https:';
  }
  return url.href;
}

/** 浏览器 SSE 只保留界面使用的状态；截图和输入转发已交给桌面浏览器。 */
export function browserStateUpdate(kind: string, payload: unknown): Partial<MirrorState> {
  if (kind === 'closed') return { mode: 'closed' };
  if (!['nav', 'state', 'status'].includes(kind)
    || !payload || typeof payload !== 'object' || Array.isArray(payload)) return {};
  const data = payload as Record<string, unknown>;
  const update: Partial<MirrorState> = {};
  if (typeof data.url === 'string' && data.url) update.url = data.url;
  if (kind === 'nav' && update.url && update.url !== 'about:blank') update.mode = 'live';
  if ((kind === 'state' || kind === 'status') && typeof data.headless === 'boolean') update.headless = data.headless;
  return update;
}
