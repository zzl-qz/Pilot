import { useStore } from '../store.js';
import { formatCount } from '../thread-utils.js';
import { formatCache } from '../lib/request-usage.js';
import { formatTokens } from '../lib/context-usage.js';

/** 底部运行指标：复用 run.usage 与对话轮次，空态隐藏，不重复展示输入区已有的上下文占比。 */
export function RuntimeFooter() {
  const usage = useStore(state => state.runUsage);
  const rows = useStore(state => state.thread.rows);
  const turns = rows.filter(row => row.type === 'turn').length;
  const steps = rows.reduce((sum, row) => sum + (row.type === 'turn' ? row.tools.length : 0), 0);
  const totalTokens = Number(usage?.inputTokens || 0) + Number(usage?.outputTokens || 0);
  if (turns === 0 && steps === 0 && totalTokens === 0) return null;
  const activeSeconds = usage?.startedAt
    ? Math.max(1, ((usage.finishedAt || Date.now()) - usage.startedAt) / 1000)
    : 0;
  const speed = activeSeconds > 0 ? Math.round(Number(usage?.outputTokens || 0) / activeSeconds) : 0;
  const cache = formatCache(usage?.details);
  const items = [
    turns > 0 ? `${formatCount(turns)} 轮` : null,
    steps > 0 ? `${formatCount(steps)} 步` : null,
    speed > 0 ? `${speed} tok/s` : null,
    totalTokens > 0 ? `${formatTokens(totalTokens)} tok` : null,
    cache !== '—' ? `缓存命中 ${cache}` : null
  ].filter((item): item is string => Boolean(item));
  if (!items.length) return null;
  return <footer className="runtime-footer" aria-label="运行指标">
    {items.map((item, index) => <span key={item} className="runtime-footer-item">
      {index > 0 && <span className="runtime-footer-sep" aria-hidden="true">·</span>}
      {item}
    </span>)}
  </footer>;
}