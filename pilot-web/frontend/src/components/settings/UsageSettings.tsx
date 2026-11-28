import type * as S from "../../types/server.js";
import type React from "react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { SegmentedControl, SegmentedControlItem } from "@astryxdesign/core/SegmentedControl";
import { errorInfo } from "../../lib/errors.js";
import { formatCache, totalCost } from "../../lib/request-usage.js";
import { useStore } from "../../store.js";
import { api } from "../../api.js";
import { formatCompactCount } from "../../thread-utils.js";

export function UsageSettings() {
  const usage = useStore(state => state.usageData);
  const days = useStore(state => state.ui.usageDays);
  const [requestedDays, setRequestedDays] = useState(days);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const sequence = useRef(0);

  const load = useCallback(async (target: number) => {
    const request = ++sequence.current;
    setRequestedDays(target);
    setLoading(true);
    setError('');
    try {
      const data = await api.usage(target);
      if (!data || !Array.isArray(data.daily)) throw new Error('服务端返回空用量信息。');
      if (request !== sequence.current) return;
      useStore.setState(draft => { draft.usageData = data; draft.ui.usageDays = target; });
    } catch (failure) {
      if (request === sequence.current) setError(errorInfo(failure).message || '无法读取用量。');
    } finally {
      if (request === sequence.current) setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load(useStore.getState().ui.usageDays);
    return () => { sequence.current++; };
  }, [load]);

  const totals = usage && [
    ['模型请求', formatCompactCount(usage.totalRequests)],
    ['输入 Token', formatCompactCount(usage.totalInputTokens)],
    ['输出 Token', formatCompactCount(usage.totalOutputTokens)],
    ['缓存命中', formatCache(usage.details)],
    ['费用估算', totalCost(usage.details)]
  ];

  return <div className="settings-usage">
    <div className="settings-inline-actions">
      <SegmentedControl id="usage-range" label="用量范围" value={String(requestedDays)} onChange={value => void load(Number(value))}>
        {[7, 14, 30].map(value => <SegmentedControlItem key={value} value={String(value)} label={`${value} 天`} />)}
      </SegmentedControl>
      {loading && <p className="settings-status" role="status">正在读取近 {requestedDays} 天的用量…</p>}
    </div>
    {error && <div className="settings-inline-actions">
      <p className="settings-status" role="alert">读取近 {requestedDays} 天的用量失败。{error}</p>
      <Button label="重新加载用量" onClick={() => void load(requestedDays)} />
    </div>}
    {usage && totals && <>
      <section className="settings-group" aria-labelledby="usage-summary-heading">
        <h3 id="usage-summary-heading">近 {days} 天用量</h3>
        <dl id="usage-summary" className="usage-summary">
          {totals.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}
        </dl>
        {usage.details?.requests > 0 && <p className="settings-status">缓存数据覆盖 {usage.details.observedCacheRequests}/{usage.details.requests} 次请求，可计价 {usage.details.pricedRequests}/{usage.details.requests} 次。缺失数据不按零计入，各币种分别汇总。</p>}
      </section>
      <section className="settings-group" id="usage-daily">
        <h3>每日 Token</h3>
        {usage.daily.length ? <UsageTrend daily={usage.daily} /> : <p className="settings-status">这个窗口内还没有用量记录。</p>}
      </section>
      <section className="settings-group">
        <h3>用量最多的对话</h3>
        <ul id="usage-sessions" className="usage-sessions">
          {(usage.sessions || []).map(session => <li key={session.sessionId}>
            <span className="usage-session-name" title={`${session.title}（${session.sessionId}）`}>{session.title}</span>
            <span className="usage-session-meta">{formatCompactCount((session.inputTokens || 0) + (session.outputTokens || 0))} Token · {session.requests} 请求</span>
          </li>)}
          {!usage.sessions?.length && <li className="panel-empty">还没有可统计的对话。</li>}
        </ul>
      </section>
    </>}
  </div>;
}

function UsageTrend({ daily }: { daily: S.UsageServiceDayTotal[] }) {
  const maxTokens = Math.max(...daily.map(day => (day.inputTokens || 0) + (day.outputTokens || 0)));
  const ticks = new Set([0, Math.floor((daily.length - 1) / 2), daily.length - 1]);
  return <>
    <figure className="usage-trend" aria-hidden="true">
      <div className="usage-axis"><span>{maxTokens > 0 ? formatCompactCount(maxTokens) : ''}</span><span>0</span></div>
      <div className="usage-series" data-dense={daily.length > 8 || undefined}
        style={{ '--usage-day-count': daily.length } as React.CSSProperties}>
        <div className="usage-series-bars">
          {daily.map(day => <div key={day.date} className="usage-series-day"
            title={`${day.date} · 输入 ${formatCompactCount(day.inputTokens)} · 输出 ${formatCompactCount(day.outputTokens)} · ${day.requests} 请求`}>
            <span style={{ height: `${(day.outputTokens || 0) / (maxTokens || 1) * 100}%` }} />
            <span style={{ height: `${(day.inputTokens || 0) / (maxTokens || 1) * 100}%` }} />
          </div>)}
        </div>
        <div className="usage-series-dates">{[...ticks].map(index => <span key={daily[index].date}
          style={{ gridColumn: index + 1 }}>{daily[index].date.slice(5)}</span>)}</div>
      </div>
      <figcaption className="usage-series-legend"><span>输入</span><span>输出</span></figcaption>
    </figure>
    <details className="settings-disclosure">
      <summary>查看每日明细</summary>
      <table className="usage-table">
        <thead><tr><th scope="col">日期</th><th scope="col">请求</th><th scope="col">输入</th><th scope="col">输出</th></tr></thead>
        <tbody>{daily.map(day => <tr key={day.date}>
          <th scope="row">{day.date}</th><td>{day.requests?.toLocaleString('zh-CN') ?? '—'}</td>
          <td>{day.inputTokens?.toLocaleString('zh-CN') ?? '—'}</td><td>{day.outputTokens?.toLocaleString('zh-CN') ?? '—'}</td>
        </tr>)}</tbody>
      </table>
    </details>
  </>;
}
