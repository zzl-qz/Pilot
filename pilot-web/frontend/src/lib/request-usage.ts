import type { CacheMetrics, ContextStatus, RequestReport, RequestGroup } from '../types/runtime.js';
/** 缓存命中率只用有观测的请求作分母，未知数据不补零。 */
export function cacheRatio(value: CacheMetrics | null | undefined) {
  if (!value) return null;
  const observed = value.observedCacheRequests != null
    ? Number(value.observedCacheRequests) > 0 : value.cacheReadObserved === true;
  const input = Number(value.observedCacheInputTokens ?? value.inputTokens);
  const cached = Number(value.cacheReadTokens ?? value.cachedInputTokens);
  if (!observed || !Number.isFinite(input) || input <= 0 || !Number.isFinite(cached)) return null;
  return Math.max(0, Math.min(1, cached / input));
}

export function formatCache(value: CacheMetrics | null | undefined) {
  const ratio = cacheRatio(value);
  return ratio == null ? '—' : `${(ratio * 100).toFixed(1).replace(/\.0$/, '')}%`;
}

export function formatCost(value: unknown, currency = 'USD') {
  if (value == null || value === '' || !Number.isFinite(Number(value)) || Number(value) < 0) return '—';
  const amount = Number(value);
  if (amount > 0 && amount < 0.000001) return `${currency} <0.000001`;
  return `${currency} ${amount.toLocaleString('en-US', { maximumFractionDigits: 6 })}`;
}

export function totalCost(details: Partial<RequestGroup> | null | undefined) {
  const costs = Object.entries(details?.costs || {});
  return Number(details?.pricedRequests) > 0 && costs.length
    ? costs.map(([currency, amount]) => formatCost(amount, currency)).join(' · ') : '—';
}

export const REQUEST_PURPOSES: Record<string, string> = { main: '主任务', subagent: '子代理', compaction: '上下文压缩', review: '核验', finalization: '收尾' };

/** 按请求用途归类，不能用子代理请求次数推算智能体个数。未知价格不记为零，不混加币种。 */
export function groupRequests(reports: readonly (RequestReport | null | undefined)[] = []) {
  const groups = new Map<string, RequestGroup>(), seen = new Set<string>();
  for (const report of reports) {
    if (!report || (report.id && seen.has(report.id))) continue;
    if (report.id) seen.add(report.id);
    const purpose = Object.hasOwn(REQUEST_PURPOSES, report.purpose ?? '') ? report.purpose! : 'other';
    if (!groups.has(purpose)) groups.set(purpose, { purpose, reports: [], failed: 0, pricedRequests: 0, costs: {} });
    const group = groups.get(purpose)!;
    group.reports.push(report);
    if (report.status === 'failed') group.failed++;
    const amount = report.cost?.total, currency = report.cost?.currency || 'USD';
    if (amount != null && amount !== '' && Number.isFinite(Number(amount)) && Number(amount) >= 0) {
      group.pricedRequests++;
      group.costs[currency] = (group.costs[currency] || 0) + Number(amount);
    }
  }
  return ['main', 'subagent', 'compaction', 'review', 'finalization', 'other'].flatMap(key => groups.has(key) ? [groups.get(key)!] : []);
}

export function requestContext(status: ContextStatus | null | undefined, report: RequestReport | null | undefined) {
  const parts = Array.isArray(report?.context?.parts) ? report.context.parts.filter(p => Number(p.tokens) > 0) : [];
  const used = parts.length ? parts.reduce((sum, item) => sum + Number(item.tokens), 0)
    : Math.max(0, Number(report?.context ? report.context.totalTokens : status?.usedTokens) || 0);
  const limit = Number(status?.limitTokens) > 1 ? Number(status!.limitTokens) : null;
  return { parts, used, limit, percent: limit ? Math.min(100, Math.max(0, used / limit * 100)) : null,
    attachments: Number(report?.context?.attachments) || 0, fromRequest: Boolean(report?.context) };
}
