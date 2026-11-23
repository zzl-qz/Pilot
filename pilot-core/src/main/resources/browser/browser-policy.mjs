import { lookup } from 'node:dns/promises';
import { isIP } from 'node:net';

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
    if (url.hash && SENSITIVE_QUERY_KEY.test(url.hash)) url.hash = '#[已遮挡]';
    return sanitizeDiagnosticText(url.toString());
  } catch {
    return sanitizeDiagnosticText(value);
  }
}

/** Identity, payment, and authentication pages are withheld from model-readable page output. */
export function sensitivePageUrl(value) {
  try {
    const url = new URL(String(value));
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return false;
    const host = url.hostname.toLowerCase();
    const knownHosts = ['accounts.google.com', 'login.microsoftonline.com', 'login.live.com', 'appleid.apple.com',
      'idmsa.apple.com', 'checkout.stripe.com', 'paypal.com', 'www.paypal.com'];
    return knownHosts.includes(host) || /(^|\.)(?:accounts?|auth|identity|login|signin|checkout|payments?)\./.test(host)
      || /\/(?:login|log-in|sign-?in|oauth|authorize|checkout|payment|billing)(?:\/|$)/i.test(url.pathname);
  } catch {
    return false;
  }
}

export const externalMutations = new Set(['click', 'dblclick', 'right_click', 'hover', 'fill', 'type', 'press',
  'select', 'upload', 'download', 'evaluate', 'reload', 'back', 'forward']);

export function requireExternalApproval(request, currentUrl) {
  if (!request.approvedExternal || request.expected_url !== currentUrl) {
    throw new Error('外网页面操作需要本次即时确认，且 expected_url 必须与当前页面完全一致；请重新观察后申请。');
  }
}
