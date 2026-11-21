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

export const externalMutations = new Set(['click', 'dblclick', 'right_click', 'hover', 'fill', 'type', 'press',
  'select', 'upload', 'download', 'evaluate', 'reload', 'back', 'forward']);

export function requireExternalApproval(request, currentUrl) {
  if (!request.approvedExternal || request.expected_url !== currentUrl) {
    throw new Error('外网页面操作需要本次即时确认，且 expected_url 必须与当前页面完全一致；请重新观察后申请。');
  }
}
