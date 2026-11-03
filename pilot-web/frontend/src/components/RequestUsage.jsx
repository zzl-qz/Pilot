import React, { useState } from 'react';
import { formatTokens } from '../lib/context-usage.js';
import { formatCache, formatCost, totalCost, requestContext, REQUEST_PURPOSES } from '../lib/request-usage.js';
import './request-usage.css';

const CHANGE_LABELS = { initial: '首次请求', append_only: '历史前缀保持一致', instructions_changed: '系统内容已变化',
  tools_changed: '工具定义已变化', history_changed: '历史已压缩或调整', model_changed: '模型已切换' };

export function RequestList({ reports = [] }) {
  if (!reports.length) return <p className="request-empty">还没有请求明细。后续模型请求会在这里记录。</p>;
  return <ol className="request-list">
    {[...reports].reverse().map(report => <li key={report.id}>
      <details className="request-item">
        <summary><span className="request-identity">{REQUEST_PURPOSES[report.purpose] || '模型请求'}<small>{report.model || '模型未提供'}{report.status !== 'completed' ? ' · 请求失败' : ''}</small></span>
          <span className="request-cost">{report.cost?.total != null ? '≈ ' : ''}{formatCost(report.cost?.total, report.cost?.currency)}</span></summary>
        <dl className="request-values">
          <div><dt>输入 / 输出</dt><dd>{report.usage?.exact ? '' : '≈ '}{formatTokens(report.usage?.inputTokens)} / {formatTokens(report.usage?.outputTokens)} tokens</dd></div>
          <div><dt>缓存命中</dt><dd>{formatCache(report.usage)}{report.usage?.cacheReadObserved ? ` · ${formatTokens(report.usage.cachedInputTokens)} tokens` : ' · 端点未提供'}</dd></div>
          <div><dt>耗时 / 首段输出</dt><dd>{(Number(report.durationMs || 0) / 1000).toFixed(2)}s / {report.firstTokenMs == null ? '—' : `${(report.firstTokenMs / 1000).toFixed(2)}s`}</dd></div>
          {report.cost?.total != null && <>
            <div><dt>普通输入</dt><dd>{formatCost(report.cost.input, report.cost.currency)}</dd></div>
            <div><dt>缓存读取</dt><dd>{formatCost(report.cost.cachedInput, report.cost.currency)}</dd></div>
            <div><dt>缓存写入</dt><dd>{formatCost(report.cost.cacheWrite, report.cost.currency)}</dd></div>
            <div><dt>输出</dt><dd>{formatCost(report.cost.output, report.cost.currency)}</dd></div>
          </>}
        </dl>
        <p className="request-note">{report.cost?.note || '费用未知'}{report.cost?.source ? ` · ${report.cost.source}` : ''}</p>
        {report.prefix && <p className="request-note">请求结构：{CHANGE_LABELS[report.prefix.change] || report.prefix.change}。实际缓存命中以端点用量为准。</p>}
      </details>
    </li>)}
  </ol>;
}

export function ContextUsagePanel({ status, report, reports, usage, compacting, onCompact, onClose, onSettings }) {
  const [view, setView] = useState('context');
  const context = requestContext(status, report);
  const latest = reports?.at(-1) || report;
  const details = usage?.details;
  const percent = context.percent;
  return <div className="chip-menu context-menu request-usage-panel" id="context-menu" role="dialog" aria-label="上下文与请求用量">
    <div className="request-panel-head">
      <span>{view === 'context' ? '上下文使用' : '请求明细'}</span>
      <button type="button" className="request-text-action" onClick={() => setView(view === 'context' ? 'requests' : 'context')}>{view === 'context' ? '查看请求明细' : '返回上下文'}</button>
      <button type="button" className="request-close" aria-label="收起用量面板" onClick={onClose}>−</button>
    </div>
    {view === 'context' ? <>
      <div className="request-capacity"><span>{percent == null ? '预算未提供' : `${Math.round(percent)}% 已用`}</span>
        <span>≈ {formatTokens(context.used)} / {formatTokens(context.limit)} tokens</span></div>
      <div className="context-composition-bar" role="img" aria-label={`上下文预算已用 ${percent == null ? '未知' : Math.round(percent) + '%'}`}>
        {context.parts.length ? context.parts.map(part => <span key={part.category} className={`context-part part-${part.category}`}
          title={`${part.label}：约 ${Number(part.tokens).toLocaleString()} tokens`}
          style={{ width: `${context.limit ? part.tokens / Math.max(context.limit, context.used) * 100 : 0}%` }} />)
          : <span className="context-part part-conversation" style={{ width: `${percent || 0}%` }} />}
      </div>
      {context.parts.length ? <ul className="context-composition-list">{context.parts.map(part => <li key={part.category}>
        <span><i className={`context-part part-${part.category}`} />{part.label}</span>
        <span title={`约 ${Number(part.tokens).toLocaleString()} tokens`}>{formatTokens(part.tokens)}</span>
      </li>)}</ul> : <p className="request-empty">尚无完整请求构成。发送任务后可查看系统提示、工具、规则和对话的占用。</p>}
      <p className="request-note">{context.fromRequest ? '最近一次主请求的组成，分项为文本估算。' : '当前仅显示已保存历史的估算。'}{context.attachments > 0 ? `含 ${context.attachments} 个媒体附件，媒体开销未计入分项。` : ''}厂商额外开销可能不同。</p>
      <dl className="request-overview">
        <div><dt>最近请求命中</dt><dd>{formatCache(latest?.usage)}</dd></div>
        <div><dt>最近请求费用</dt><dd>{latest?.cost?.total != null ? '≈ ' : ''}{formatCost(latest?.cost?.total, latest?.cost?.currency)}</dd></div>
        <div><dt>本轮缓存命中</dt><dd>{formatCache(details)}</dd></div>
        <div><dt>本轮费用</dt><dd>{details?.pricedRequests > 0 ? '≈ ' : ''}{totalCost(details)}</dd></div>
      </dl>
      {details?.requests > 0 && <p className="request-note">缓存数据 {details.observedCacheRequests}/{details.requests} 次 · 可计价 {details.pricedRequests}/{details.requests} 次{details.pricedRequests < details.requests ? '，费用合计不完整' : ''}</p>}
      {latest?.cost?.total == null && <button type="button" className="request-text-action request-price-link" onClick={onSettings}>配置当前模型单价</button>}
      <div className="request-panel-foot"><span>{context.fromRequest ? '最近请求快照' : '历史消息'} · token 估算</span>
        <button type="button" className="btn" disabled={compacting || status?.canCompact === false} onClick={onCompact}>{compacting ? '正在压缩…' : '压缩上下文'}</button></div>
    </> : <><p className="request-note">最近 {reports?.length || 0} 次请求，包含压缩与子代理。金额按各次请求使用的单价估算。</p><RequestList reports={reports} /></>}
  </div>;
}
