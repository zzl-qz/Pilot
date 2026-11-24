import { lookup } from 'node:dns/promises';
import { isIP } from 'node:net';
import { extname } from 'node:path';

export function publicAddress(address) {
  const ip = address.toLowerCase();
  if (isIP(ip) === 6) {
    const canonical = new URL(`http://[${ip}]`).hostname.slice(1, -1);
    // 仅接受全局单播空间；展开形式的回环、映射地址和转换前缀也拒绝。
    return /^[23]/.test(canonical) && !/^(2001:(db8|0):|2002:)/.test(canonical);
  }
  if (isIP(ip) !== 4) return false;
  const [a, b] = ip.split('.').map(Number);
  return a !== 0 && a !== 10 && a !== 127 && a < 224 && !(a === 169 && b === 254)
    && !(a === 172 && b >= 16 && b <= 31) && !(a === 192 && b === 168)
    && !(a === 100 && b >= 64 && b <= 127) && !(a === 198 && (b === 18 || b === 19));
}

/** 主机清单：逗号或空白分隔，`*.example.com` 覆盖自身与所有子域，`*` 表示任意主机。 */
export function parseHosts(value) {
  if (Array.isArray(value)) return value.flatMap(item => parseHosts(item));
  if (value == null) return [];
  return String(value).split(/[\s,]+/).map(item => item.trim().toLowerCase().replace(/\.$/, '')).filter(Boolean);
}

function hostMatches(pattern, host) {
  if (pattern === '*') return true;
  if (!pattern.startsWith('*.')) return pattern === host;
  const base = pattern.slice(2);
  return host === base || host.endsWith('.' + base);
}

/**
 * 主机三态判定：`deny` 明确拒绝、`blocked` 不在放行清单、`allow` 通过。
 *
 * <p>放行清单为空表示不额外收紧，沿用原来「公开 HTTPS 即可」的规则；
 * 一旦写了清单，清单外一律拦下。deny 始终优先，所以写错 allow 不会放过被拒的主机。
 */
export function hostDecision(host, { allow = [], deny = [] } = {}) {
  const name = String(host || '').toLowerCase().replace(/\.$/, '');
  if (!name) return 'deny';
  if (deny.some(pattern => hostMatches(pattern, name))) return 'deny';
  if (!allow.length) return 'allow';
  return allow.some(pattern => hostMatches(pattern, name)) ? 'allow' : 'blocked';
}

/** 从 sidecar 环境变量读主机清单，由 Java 侧的配置项写入。 */
export function hostRulesFromEnv(env = process.env) {
  return { allow: parseHosts(env.PILOT_BROWSER_ALLOW_HOSTS), deny: parseHosts(env.PILOT_BROWSER_DENY_HOSTS) };
}

export async function publicHttps(value, resolve = lookup, hosts = null) {
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443')) return false;
    const host = url.hostname.toLowerCase();
    if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || isIP(host) || host.includes(':')) return false;
    if (hosts && hostDecision(host, hosts) !== 'allow') return false;
    const addresses = await resolve(host, { all: true, verbatim: true });
    return addresses.length > 0 && addresses.every(item => publicAddress(item.address));
  } catch { return false; }
}

export async function externalRequestAllowed({ url, method, navigation, origin, actionApproved, hosts }, resolve = lookup) {
  if (!await publicHttps(url, resolve, hosts)) return false;
  const target = new URL(url);
  if (navigation && target.origin !== origin) return false;
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase())) {
    return actionApproved && target.origin === origin;
  }
  return true;
}

/**
 * 外部 CDP 端点只接受本机回环地址：连到网络对端等于把真实浏览器的控制权交出去。
 *
 * <p>返回规范化后的 origin，写错协议或主机一律 null。
 */
export function cdpEndpoint(value) {
  if (value == null) return null;
  const text = String(value).trim();
  if (!text) return null;
  try {
    const url = new URL(text);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
    const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, '');
    const loopback = host === 'localhost' || host.endsWith('.localhost') || host === '::1'
      || host === '127.0.0.1' || /^127\.\d+\.\d+\.\d+$/.test(host);
    return loopback ? url.origin : null;
  } catch { return null; }
}

/**
 * 外部 CDP 会话碰的是用户真实浏览器和登录态，所以必须显式写放行清单，且只认清单内主机。
 *
 * <p>与 {@link publicHttps} 的空清单语义相反：这里空清单表示「谁都不碰」。
 */
export function externalAgentHostAllowed(value, hosts) {
  let parsed;
  try {
    parsed = new URL(String(value));
  } catch { return false; }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return false;
  const allow = Array.isArray(hosts?.allow) ? hosts.allow : [];
  return allow.length > 0 && hostDecision(parsed.hostname.toLowerCase(), hosts) === 'allow';
}

const SENSITIVE_QUERY_KEY = /^(?:access[_-]?token|refresh[_-]?token|id[_-]?token|token|secret|password|passwd|authorization|auth|credential|session|cookie|api[_-]?key|client[_-]?secret|code|otp|state)$/i;

/** Remove credential-like values before browser diagnostics are retained or returned to the model. */
export function sanitizeDiagnosticText(value) {
  return String(value ?? '')
    .replace(/-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----/g, '[已遮挡私钥]')
    .replace(/\b(Bearer|Basic)\s+[A-Za-z0-9._~+/-]{6,}={0,2}/gi, '$1 [已遮挡]')
    .replace(/\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}(?:\.[A-Za-z0-9_-]{8,})?/g, '[已遮挡令牌]')
    .replace(/(?<![A-Za-z0-9_-])(?:sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9]{8,}|github_pat_[A-Za-z0-9_]{16,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|glpat-[A-Za-z0-9_-]{10,}|hf_[A-Za-z0-9]{12,})/g, '[已遮挡密钥]')
    .replace(/([?&#](?:token|access_token|refresh_token|id_token|secret|password|passwd|authorization|auth|credential|session|cookie|api[_-]?key|client[_-]?secret|code|otp|state)=)[^&#\s]*/gi, '$1[已遮挡]')
    .replace(/((?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|passwd|authorization|secret|token)\s*[=:]\s*)(["']?)[^\s"',;&]{4,}/gi, '$1$2[已遮挡]');
}

/** Remove URL userinfo and credential-like query/fragment values while preserving useful diagnostics. */
export function sanitizeDiagnosticUrl(value) {
  try {
    const url = new URL(String(value));
    url.username = '';
    url.password = '';
    for (const key of [...url.searchParams.keys()]) {
      if (SENSITIVE_QUERY_KEY.test(key)) url.searchParams.set(key, '[已遮挡]');
    }
    if (url.hash && url.hash.length > 1) {
      const rawHash = url.hash.slice(1);
      const parts = rawHash.split('&').map((part) => {
        const eq = part.indexOf('=');
        if (eq < 0) return SENSITIVE_QUERY_KEY.test(part) ? '[已遮挡]' : part;
        const key = part.slice(0, eq);
        return SENSITIVE_QUERY_KEY.test(key) ? `${key}=[已遮挡]` : part;
      });
      url.hash = '#' + parts.join('&');
    }
    return sanitizeDiagnosticText(url.toString());
  } catch {
    return sanitizeDiagnosticText(value);
  }
}

const KNOWN_PAYMENT_HOSTS = ['checkout.stripe.com', 'paypal.com', 'www.paypal.com'];
const KNOWN_AUTH_HOSTS = ['accounts.google.com', 'login.microsoftonline.com', 'login.live.com',
  'appleid.apple.com', 'idmsa.apple.com'];

/** 从环境变量读取敏感主机补充与例外清单（例外仅对非支付页生效）。 */
export function sensitiveRulesFromEnv(env = process.env) {
  return {
    extra: parseHosts(env.PILOT_BROWSER_SENSITIVE_HOSTS),
    allow: parseHosts(env.PILOT_BROWSER_SENSITIVE_ALLOW_HOSTS)
  };
}

/**
 * 识别敏感支付页（payment）或认证/登录页（auth）；普通页面返回 null。
 * 支付页始终优先拦截，不受 sensitiveAllow 例外放行。
 */
export function classifySensitivePage(value, rules = null) {
  try {
    const url = new URL(String(value));
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
    const host = url.hostname.toLowerCase();
    if (KNOWN_PAYMENT_HOSTS.includes(host)
      || /(^|\.)(?:checkout|payments?)\./.test(host)
      || /\/(?:checkout|payment|billing)(?:\/|$)/i.test(url.pathname)) {
      return 'payment';
    }
    const allow = Array.isArray(rules?.allow) ? rules.allow : [];
    if (allow.some((pattern) => hostMatches(pattern, host))) return null;
    const extra = Array.isArray(rules?.extra) ? rules.extra : [];
    if (KNOWN_AUTH_HOSTS.includes(host)
      || /(^|\.)(?:accounts?|auth|identity|login|signin)\./.test(host)
      || /\/(?:login|log-in|sign-?in|oauth|authorize)(?:\/|$)/i.test(url.pathname)
      || extra.some((pattern) => hostMatches(pattern, host))) {
      return 'auth';
    }
    return null;
  } catch {
    return null;
  }
}

/** Identity, payment, and authentication pages are withheld from model-readable page output. */
export function sensitivePageUrl(value, rules = null) {
  return classifySensitivePage(value, rules) !== null;
}

/** 返回规范化的本机回环 origin（含端口），非回环地址返回 null。 */
export function loopbackOrigin(value) {
  try {
    const url = new URL(String(value));
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
    const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, '');
    const loopback = host === 'localhost' || host.endsWith('.localhost') || host === '::1'
      || host === '127.0.0.1' || /^127\.\d+\.\d+\.\d+$/.test(host);
    return loopback ? url.origin : null;
  } catch {
    return null;
  }
}

export const DEFAULT_MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024;

export function maxDownloadBytesFromEnv(env = process.env) {
  const parsed = Number(env.PILOT_BROWSER_MAX_DOWNLOAD_BYTES);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : DEFAULT_MAX_DOWNLOAD_BYTES;
}

const DANGEROUS_DOWNLOAD_EXTENSIONS = new Set([
  '.app', '.appx', '.apk', '.applescript', '.bat', '.cmd', '.com', '.command', '.cpl', '.deb',
  '.dll', '.dmg', '.exe', '.gadget', '.hta', '.img', '.iso', '.jar', '.js', '.jse', '.lnk',
  '.msi', '.msp', '.pkg', '.ps1', '.psm1', '.reg', '.rpm', '.scf', '.scpt', '.scr', '.sh',
  '.sys', '.vb', '.vbe', '.vbs', '.workflow', '.wsf'
]);

const DANGEROUS_DOWNLOAD_MIMES = new Set([
  'application/x-msdownload', 'application/x-executable', 'application/x-mach-binary',
  'application/x-sh', 'application/x-bat', 'application/x-csh', 'application/java-archive',
  'application/vnd.microsoft.portable-executable', 'application/x-dosexec', 'application/x-msi',
  'application/x-apple-diskimage', 'text/javascript', 'application/javascript',
  'application/x-javascript', 'text/vbscript'
]);

function detectBinarySignature(headBytes) {
  if (!headBytes || headBytes.length < 2) return '';
  const b = headBytes instanceof Uint8Array ? headBytes : Uint8Array.from(headBytes);
  if (b[0] === 0x4d && b[1] === 0x5a) return 'PE executable (MZ)';
  if (b[0] === 0x23 && b[1] === 0x21) return 'Shell/script shebang (#!)';
  if (b.length >= 4) {
    if (b[0] === 0x7f && b[1] === 0x45 && b[2] === 0x4c && b[3] === 0x46) return 'ELF executable';
    const isMachO = (b[0] === 0xfe && b[1] === 0xed && b[2] === 0xfa && (b[3] === 0xce || b[3] === 0xcf))
      || ((b[0] === 0xce || b[0] === 0xcf) && b[1] === 0xfa && b[2] === 0xed && b[3] === 0xfe)
      || (b[0] === 0xca && b[1] === 0xfe && b[2] === 0xba && b[3] === 0xbe);
    if (isMachO) return 'Mach-O executable';
  }
  if (b.length >= 8 && b[0] === 0x4c && b[1] === 0x00 && b[2] === 0x00 && b[3] === 0x00
    && b[4] === 0x01 && b[5] === 0x14 && b[6] === 0x02 && b[7] === 0x00) {
    return 'Windows shortcut (.lnk)';
  }
  return '';
}

/**
 * 综合审查下载文件安全性：文件大小上限、扩展名、响应 MIME 与文件头魔数/内容签名。
 */
export function inspectDownloadSafety({
  filename = '',
  mimeType = '',
  headBytes = null,
  sizeBytes = null,
  maxBytes = DEFAULT_MAX_DOWNLOAD_BYTES
} = {}) {
  const cleanName = String(filename || 'download');
  const extension = extname(cleanName).toLowerCase();
  const normalizedMime = String(mimeType || '').split(';')[0].trim().toLowerCase();
  const signature = detectBinarySignature(headBytes);
  const basis = `扩展名=${extension || '无'}，MIME=${normalizedMime || '未知'}，内容签名=${signature || '未命中危险魔数'}`;

  if (Number.isFinite(sizeBytes) && maxBytes > 0 && sizeBytes > maxBytes) {
    return {
      allowed: false,
      extension,
      mimeType: normalizedMime,
      signature,
      basis,
      reason: `下载文件超过大小上限（${sizeBytes} 字节 > ${maxBytes} 字节）：${cleanName}`
    };
  }
  if (DANGEROUS_DOWNLOAD_EXTENSIONS.has(extension)) {
    return {
      allowed: false,
      extension,
      mimeType: normalizedMime,
      signature,
      basis,
      reason: `已阻止下载危险文件类型：${cleanName}（${extension}）。请由用户在浏览器中自行检查并下载。`
    };
  }
  if (normalizedMime && DANGEROUS_DOWNLOAD_MIMES.has(normalizedMime)) {
    return {
      allowed: false,
      extension,
      mimeType: normalizedMime,
      signature,
      basis,
      reason: `已阻止下载危险 MIME 类型：${cleanName}（${normalizedMime}）。请由用户在浏览器中自行检查并下载。`
    };
  }
  if (signature) {
    return {
      allowed: false,
      extension,
      mimeType: normalizedMime,
      signature,
      basis,
      reason: `已阻止下载伪装或可执行内容：${cleanName}（命中内容签名 ${signature}）。请由用户在浏览器中自行检查并下载。`
    };
  }
  return { allowed: true, extension, mimeType: normalizedMime, signature, basis, reason: '' };
}

export const externalMutations = new Set(['click', 'dblclick', 'right_click', 'hover', 'fill', 'type', 'press',
  'select', 'check', 'uncheck', 'scroll', 'drag', 'dialog', 'batch', 'upload', 'download', 'evaluate', 'reload',
  'back', 'forward']);

export function requireExternalApproval(request, currentUrl) {
  if (!request.approvedExternal || request.expected_url !== currentUrl) {
    throw new Error('外网页面操作需要本次即时确认，且 expected_url 必须与当前页面完全一致；请重新观察后申请。');
  }
}
