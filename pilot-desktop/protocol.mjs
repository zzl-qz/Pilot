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
    if (!isLoopback(url.hostname)) url.protocol = 'https:';
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
  return isLoopback(parsed.hostname);
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
  return isLoopback(parsed.hostname);
}

function isLoopback(hostname) {
  const host = hostname.replace(/^\[|\]$/g, '').toLowerCase();
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

const DANGEROUS_DOWNLOAD_EXTENSIONS = new Set([
  '.exe', '.bat', '.cmd', '.com', '.msi', '.scr', '.pif', '.vbs', '.vbe',
  '.js', '.jse', '.wsf', '.wsh', '.ps1', '.psm1', '.sh', '.bash', '.zsh',
  '.command', '.app', '.dmg', '.pkg', '.deb', '.rpm', '.appimage', '.run',
  '.jar', '.lnk', '.reg', '.hta', '.cpl', '.msc', '.desktop', '.url', '.webloc'
]);

const SENSITIVE_PARAM_PATTERN = /([?&#])(token|access_token|id_token|refresh_token|auth|code|password|passwd|key|api_key|apikey|secret|sig|signature|session|sessionid|sid|jwt|credential|oauth_token)=([^&#\s]*)/gi;
const BEARER_PATTERN = /(Bearer\s+)[A-Za-z0-9._~+/=-]{6,}/gi;

export function sanitizeDownloadFilename(raw) {
  const leaf = String(raw || '')
    .replace(/\\/g, '/')
    .split('/')
    .filter(Boolean)
    .pop() || '';
  const cleaned = leaf
    .replace(/[\x00-\x1f<>:"|?*]/g, '_')
    .replace(/^\.+/, '')
    .trim();
  if (!cleaned || cleaned === '.' || cleaned === '..') {
    return 'download.bin';
  }
  if (cleaned.length <= 120) {
    return cleaned;
  }
  const dot = cleaned.lastIndexOf('.');
  if (dot > 0 && cleaned.length - dot <= 16) {
    const ext = cleaned.slice(dot);
    return cleaned.slice(0, 120 - ext.length) + ext;
  }
  return cleaned.slice(0, 120);
}

export function isDangerousDownloadExtension(filename) {
  const clean = String(filename || '').trim().toLowerCase().replace(/[\s.]+$/, '');
  const dot = clean.lastIndexOf('.');
  return dot >= 0 && DANGEROUS_DOWNLOAD_EXTENSIONS.has(clean.slice(dot));
}

function scrubLogText(text) {
  return String(text || '')
    .replace(SENSITIVE_PARAM_PATTERN, '$1$2=***')
    .replace(BEARER_PATTERN, '$1***');
}

/** 桌面页自己的控制台 / 网络记录。空列表给一句固定说明，和旧面板同一句。 */
export function formatPageLogs(entries, kind) {
  const list = Array.isArray(entries) ? entries : [];
  if (kind === 'network') {
    if (!list.length) return '没有网络记录';
    return list.map((item) => {
      const status = item.statusCode || scrubLogText(item.error || '');
      return `${item.method || 'GET'} ${status} ${scrubLogText(item.url || '')}`.trim();
    }).join('\n');
  }
  if (!list.length) return '没有控制台记录';
  return list.map((item) => {
    const where = item.sourceId ? ` @ ${scrubLogText(item.sourceId)}${item.lineNumber ? ':' + item.lineNumber : ''}` : '';
    return `[${item.level || 'info'}] ${scrubLogText(item.message || '')}${where}`;
  }).join('\n');
}
