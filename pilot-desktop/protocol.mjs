import { createHash } from 'node:crypto';

/** 人在地址栏输入的地址。本机没写协议时用 http，其它主机用 https。 */
export function userPageUrl(input) {
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

/**
 * 桌面浏览器里，人已经打开的 http(s) 页面智能体可以点、可以输入。
 * 无头浏览器没有「眼前这一页」，仍然只许本机源。
 */
export function agentMayTouchPage(url, desktop) {
  if (!url || String(url).includes('/marker/')) return true;
  let parsed;
  try {
    parsed = new URL(String(url));
  } catch {
    return false;
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return false;
  if (desktop) return true;
  const host = parsed.hostname.replace(/^\[|\]$/g, '').toLowerCase();
  return host === 'localhost' || host.endsWith('.localhost') || host === '127.0.0.1'
    || host === '::1' || /^127\.\d+\.\d+\.\d+$/.test(host);
}

/** 智能体只能操作这些源。人自己打开的页面不受这一条限制。 */
export function agentMayOperate(url) {
  let parsed;
  try {
    parsed = new URL(String(url || ''));
  } catch {
    return false;
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return false;
  const host = parsed.hostname.replace(/^\[|\]$/g, '').toLowerCase();
  return host === 'localhost' || host.endsWith('.localhost') || host === '127.0.0.1'
    || host === '::1' || /^127\.\d+\.\d+\.\d+$/.test(host);
}

/** 工作区一个持久分区，cookie 不串到另一个工作区，也不读系统浏览器。 */
export function partitionName(workspace) {
  const digest = createHash('sha256').update(String(workspace || 'default')).digest('hex').slice(0, 16);
  return `persist:pilot-${digest}`;
}

/** 占位矩形换成物理像素对齐的视口。太小或标成不可见时把原生页面藏起来。 */
export function snapBounds(bounds) {
  const x = Math.max(0, Math.round(Number(bounds?.x) || 0));
  const y = Math.max(0, Math.round(Number(bounds?.y) || 0));
  const width = Math.max(0, Math.round(Number(bounds?.width) || 0));
  const height = Math.max(0, Math.round(Number(bounds?.height) || 0));
  const visible = Boolean(bounds?.visible) && width >= 40 && height >= 40;
  return { x, y, width, height, visible };
}

export function markerPath(tabId) {
  return `/marker/${encodeURIComponent(tabId)}`;
}

/** 桌面页自己的控制台 / 网络记录。空列表给一句固定说明，和旧面板同一句。 */
export function formatPageLogs(entries, kind) {
  const list = Array.isArray(entries) ? entries : [];
  if (kind === 'network') {
    if (!list.length) return '没有网络记录';
    return list.map((item) => {
      const status = item.statusCode || item.error || '';
      return `${item.method || 'GET'} ${status} ${item.url || ''}`.trim();
    }).join('\n');
  }
  if (!list.length) return '没有控制台记录';
  return list.map((item) => {
    const where = item.sourceId ? ` @ ${item.sourceId}${item.lineNumber ? ':' + item.lineNumber : ''}` : '';
    return `[${item.level || 'info'}] ${item.message || ''}${where}`;
  }).join('\n');
}
