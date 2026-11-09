import type * as S from '../types/server.js';
import { useEffect, useState } from 'react';
import { Banner } from '@astryxdesign/core/Banner';
import { Button } from '@astryxdesign/core/Button';
import { Collapsible } from '@astryxdesign/core/Collapsible';
import { EmptyState } from '@astryxdesign/core/EmptyState';
import { errorInfo } from '../lib/errors.js';
import { api } from '../api.js';
import { useStore } from '../store.js';
import './results.css';
import './research-panel.css';

function sourceUrl(value: string) {
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}

export function ResearchRecords({ records = [] }: { records?: S.ResultInboxServiceResearch[] }) {
  if (!records.length) return <EmptyState className="research-empty" title="尚无开源调研记录"
    description="任务引用来源时，可在这里查看复用决定和许可证说明。" isCompact />;
  return <div className="research-records">{records.map(record => <article key={record.id}>
    <header><strong>调研记录</strong><small>{record.observedAt ? new Date(record.observedAt).toLocaleString() : record.id}</small></header>
    {record.structured ? <>
      <ul className="research-sources">{record.sources?.map((source, index) => {
        const url = sourceUrl(source);
        return <li key={index}>{url ? <a href={url} target="_blank" rel="noreferrer">{source}</a> : <span>{source}</span>}</li>;
      })}</ul>
      <h4>复用决定</h4><p>{record.decision || '未记录'}</p>
      <h4>许可证说明</h4><p>{record.license || '未记录'}</p>
      <Collapsible trigger="来源证据">
        <p>{record.sourceEvidence?.length ? record.sourceEvidence.join(' · ') : '历史记录未保存来源证据编号'}</p>
        <small>展示任务记录的阅读依据与判断。请结合实际使用核对许可证。</small>
      </Collapsible>
    </> : <><p>历史记录格式不完整，以下保留原始内容。</p><pre tabIndex={0}>{record.raw}</pre></>}
  </article>)}</div>;
}

export default function ResearchPanel() {
  const sessionId = useStore(state => state.sessionId);
  return sessionId ? <SessionResearch key={sessionId} sessionId={sessionId} />
    : <EmptyState className="inspector-empty" title="请先打开一个任务" description="当前任务的调研依据会列在这里。" isCompact />;
}

function SessionResearch({ sessionId }: { sessionId: string }) {
  const [records, setRecords] = useState<S.ResultInboxServiceResearch[] | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [version, setVersion] = useState(0);
  useEffect(() => {
    let alive = true;
    setLoading(true);
    api.sessionResearch(sessionId).then(data => {
      if (!Array.isArray(data)) throw new Error('服务端未返回调研记录。');
      if (alive) { setRecords(data); setError(''); }
    }).catch(failure => { if (alive) setError(errorInfo(failure).message || '请刷新重试。'); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [sessionId, version]);
  return <section className="research-panel" aria-label="开源参考">
    <div className="research-toolbar">
      <span>当前任务的调研依据</span>
      <Button label="刷新调研记录" variant="ghost" isLoading={loading} onClick={() => setVersion(value => value + 1)} />
    </div>
    {error && <Banner status="error" role="alert" title="无法读取调研记录" description={error} />}
    {loading && !records && <p role="status">正在读取调研记录…</p>}
    {records && <ResearchRecords records={records} />}
  </section>;
}
