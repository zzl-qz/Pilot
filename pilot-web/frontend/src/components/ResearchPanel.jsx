import React, { useEffect, useState } from 'react';
import { api } from '../api.js';
import { useStore } from '../store.js';
import './results.css';

function sourceUrl(value) {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) ? url.href : null; }
  catch { return null; }
}
export function ResearchRecords({ records = [] }) {
  if (!records.length) return <p className="research-empty">尚无开源调研记录。自主任务会在实现前阅读来源并记录复用决定。</p>;
  return <div className="research-records">{records.map(record => <article key={record.id}>
    <header><strong>调研记录</strong><small>{record.observedAt ? new Date(record.observedAt).toLocaleString() : record.id}</small></header>
    {record.structured ? <>
      <ul className="research-sources">{record.sources.map((source, index) => <li key={index}>{sourceUrl(source) ? <a href={sourceUrl(source)} target="_blank" rel="noreferrer">{source}</a> : <span>{source}</span>}</li>)}</ul>
      <h4>复用决定</h4><p>{record.decision || '未记录'}</p>
      <h4>许可证说明</h4><p>{record.license || '未记录'}</p>
      <details><summary>来源证据</summary><p>{record.sourceEvidence.length ? record.sourceEvidence.join(' · ') : '历史记录未保存来源证据编号'}</p><small>展示 agent 保存的阅读依据与判断；许可证说明仍需结合实际使用核对。</small></details>
    </> : <><p>历史记录格式不完整，以下保留原始内容。</p><pre>{record.raw}</pre></>}
  </article>)}</div>;
}
export default function ResearchPanel() {
  const sessionId = useStore(s => s.sessionId);
  const [records, setRecords] = useState([]), [error, setError] = useState(''), [loading, setLoading] = useState(false), [version, setVersion] = useState(0);
  useEffect(() => {
    let alive = true; setRecords([]); setError(''); setLoading(!!sessionId);
    if (sessionId) api.sessionResearch(sessionId).then(data => { if (alive) setRecords(Array.isArray(data) ? data : []); })
      .catch(e => { if (alive) setError(e.message); }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [sessionId, version]);
  return <section className="research-panel" aria-label="开源参考"><div className="research-toolbar"><span>当前任务的调研依据</span><button className="btn" disabled={loading || !sessionId} onClick={() => setVersion(v => v + 1)}>刷新</button></div>
    {error && <p role="alert">读取失败：{error}</p>}{loading ? <p role="status">正在读取调研记录…</p> : <ResearchRecords records={records} />}
  </section>;
}
