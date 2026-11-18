import { useStore } from '../store.js';
import { formatCount } from '../thread-utils.js';
import { formatCache } from '../lib/request-usage.js';
import { formatTokens, usagePercent } from '../lib/context-usage.js';

/** 底部运行指标：复用 run.usage 与上下文状态，不额外请求模型。 */
export function RuntimeFooter() {
  const usage = useStore(state => state.runUsage);
  const context = useStore(state => state.contextStatus);
  const rows = useStore(state => state.thread.rows);
  const turns = rows.filter(row => row.type === 'turn').length;
  const steps = rows.reduce((sum, row) => sum + (row.type === 'turn' ? row.tools.length : 0), 0);
  const totalTokens = Number(usage?.inputTokens || 0) + Number(usage?.outputTokens || 0);
  const activeSeconds = usage?.startedAt
    ? Math.max(1, ((usage.finishedAt || Date.now()) - usage.startedAt) / 1000)
    : 0;
  const speed = activeSeconds > 0 ? Math.round(Number(usage?.outputTokens || 0) / activeSeconds) : 0;
  const cache = formatCache(usage?.details);
  const contextPercent = usagePercent(context);
  const items = [
    `${formatCount(turns)} 轮`,
    `${formatCount(steps)} 步`,
    speed > 0 ? `${speed} tok/s` : null,
    totalTokens > 0 ? `${formatTokens(totalTokens)} tok` : null,
    cache !== '—' ? `缓存命中 ${cache}` : null,
    Number.isFinite(contextPercent) && contextPercent > 0 ? `${contextPercent}%` : null
  ].filter((item): item is string => Boolean(item));
  if (!items.length) {
    return null;
  }
  return <footer className="runtime-footer" aria-label="运行指标">
    {items.map((item, index) => <span key={item} className="runtime-footer-item">
      {index > 0 && <span className="runtime-footer-sep" aria-hidden="true">·</span>}
      {item}
    </span>)}
  </footer>;
}