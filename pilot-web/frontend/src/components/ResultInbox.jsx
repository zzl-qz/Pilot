import React, { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { useStore } from '../store.js';
import { ResearchRecords } from './ResearchPanel.jsx';
import { verificationLabel, verificationHint } from '../lib/verification.js';
import './schedules.css';
import './results.css';

const labels = { COMPLETED: '执行结束', NEEDS_ATTENTION: '需要处理', AWAITING_REVIEW: '待验收' };
function count(items) { useStore.getState().setUi({ resultUnread: items.filter(i => !i.read && !i.handled).length }); }
export default function ResultInbox() {
  const open = useStore(s => s.ui.resultsOpen);
  const target = useStore(s => s.ui.resultTarget);
  useEffect(() => {
    let alive = true;
    const poll = async () => { try { const data = await api.results(); if (alive && Array.isArray(data.items)) count(data.items); } catch { /* 面板显示读取错误 */ } };
    poll(); const timer = setInterval(poll, 15000); return () => { alive = false; clearInterval(timer); };
  }, []);
  return open ? <InboxDialog target={target} /> : null;
}
function InboxDialog({ target }) {
  const ref = useRef(null), alive = useRef(true), request = useRef(0);
  const [items, setItems] = useState([]), [truncated, setTruncated] = useState(false), [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState(target || null), [detail, setDetail] = useState(null), [detailLoading, setDetailLoading] = useState(false);
  const [reload, setReload] = useState(0);
  const [filter, setFilter] = useState('pending'), [error, setError] = useState(''), [busy, setBusy] = useState(false), [preview, setPreview] = useState(null), [diff, setDiff] = useState(null);
  const close = () => useStore.getState().setUi({ resultsOpen: false, resultTarget: null });
  const refresh = useCallback(async () => {
    try { const data = await api.results(); if (alive.current) { const rows = data.items || []; setItems(rows); setTruncated(!!data.truncated); count(rows); } }
    catch (e) { if (alive.current) setError(`读取结果失败：${e.message}`); }
    finally { if (alive.current) setLoading(false); }
  }, []);
  useEffect(() => {
    const previous = document.activeElement; alive.current = true; ref.current.showModal(); refresh();
    const timer = setInterval(refresh, 15000);
    return () => { alive.current = false; request.current++; clearInterval(timer); previous?.focus?.(); };
  }, [refresh]);
  useEffect(() => {
    const token = ++request.current; setDetail(null); setPreview(null); setDiff(null); setError(''); setDetailLoading(!!selected);
    if (selected) (async () => {
      try {
        const data = await api.result(selected); if (!alive.current || token !== request.current) return;
        if (!data.item.read) data.item = await api.resultReceipt(selected, { revision: data.item.revision, action: 'read' });
        if (!alive.current || token !== request.current) return;
        setDetail(data); await refresh();
      } catch (e) { if (alive.current && token === request.current) setError(e.message); }
      finally { if (alive.current && token === request.current) setDetailLoading(false); }
    })();
  }, [selected, refresh, reload]);
  async function act(fn) {
    const token = request.current; setBusy(true); setError('');
    try { await fn(() => alive.current && token === request.current); }
    catch (e) { if (alive.current && token === request.current) setError(e.message); }
    finally { if (alive.current) setBusy(false); }
  }
  const item = detail?.item;
  const visible = items.filter(i => filter === 'all' || (filter === 'pending' ? !i.handled : filter === 'unread' ? !i.read && !i.handled : i.handled));
  async function receipt(action) {
    await act(async current => { const updated = await api.resultReceipt(item.id, { revision: item.revision, action }); if (current()) { setDetail(d => ({ ...d, item: updated })); await refresh(); } });
  }
  return <dialog ref={ref} className="schedule-dialog results-dialog" aria-labelledby="results-title" onCancel={e => { e.preventDefault(); close(); }} onKeyDown={e => e.stopPropagation()}>
    <header className="schedule-header"><div><span className="schedule-product">Pilot / 执行结果</span><h1 id="results-title">任务收件箱</h1></div><div className="schedule-actions"><button className="btn" onClick={() => { close(); useStore.getState().setUi({ notificationsOpen: true }); }}>通知与投递</button><button className="icon-btn" aria-label="关闭任务收件箱" onClick={close}>×</button></div></header>
    <p className="schedule-runtime">集中查看完成与异常结果。打开即标记已读；验收后可单独标记已处理。</p>
    <nav className="schedule-tabs" aria-label="结果筛选">{[['pending', '待处理'], ['unread', '未读'], ['all', '全部'], ['handled', '已处理']].map(([key, title]) => <button key={key} disabled={busy} aria-pressed={filter === key} onClick={() => { setFilter(key); setSelected(null); }}>{title}</button>)}<span>{visible.length} 条结果</span><button disabled={busy} onClick={() => { setError(''); refresh(); }}>刷新</button></nav>
    {error && <div className="schedule-error" role="alert">{error} <button disabled={busy} onClick={() => { setError(''); refresh(); setReload(v => v + 1); }}>重新加载</button></div>}
    {truncated && <p className="schedule-runtime">仅展示最近 500 条结果；更早记录可在原对话查看。</p>}
    <div className={`schedule-layout results-layout${selected ? ' has-detail' : ''}`}>
      <section className="schedule-list" aria-label="结果列表" aria-busy={loading}>
        {loading && <p role="status">正在读取结果…</p>}
        {!loading && !visible.length && <div className="schedule-empty"><h2>暂无{filter === 'handled' ? '已处理' : filter === 'unread' ? '未读' : ''}结果</h2><p>任务完成或需要处理时，结果会显示在这里。</p></div>}
        {visible.map(row => <button key={row.id} className={`schedule-row result-row${row.id === selected ? ' is-selected' : ''}`} disabled={busy} aria-pressed={row.id === selected} onClick={() => setSelected(row.id)}>
          <span className="schedule-row-title"><strong>{!row.read && <span className="result-unread" aria-label="未读" />}{row.title || '未命名任务'}</strong><small>{row.summary || '暂无摘要'}</small><small>{row.source === 'SCHEDULE' ? '定时任务' : '对话任务'} · {new Date(row.at).toLocaleString()}</small></span><span className={`schedule-status status-${row.state}`}>{row.handled ? '已处理' : labels[row.state] || row.state}</span>
        </button>)}
      </section>
      {selected && <section className="schedule-detail result-detail" aria-label="结果详情" aria-busy={detailLoading}>
        <button className="schedule-back" disabled={busy} onClick={() => setSelected(null)}>返回结果列表</button>
        {detailLoading && <p role="status">正在读取详情…</p>}
        {item && <><h2>{item.title || '未命名任务'}</h2><p>{labels[item.state] || item.state} · {item.handled ? '已处理' : '待处理'}</p><p className="schedule-goal">{item.summary || '暂无摘要，请打开执行对话查看。'}</p>
          <div className="schedule-actions"><button className={`btn${preview ? '' : ' btn-primary'}`} disabled={busy} onClick={() => receipt(item.handled ? 'reopen' : 'handle')}>{item.handled ? '重新打开' : '标记已处理'}</button><button className="btn" disabled={busy} onClick={() => receipt(item.read ? 'unread' : 'read')}>{item.read ? '标记未读' : '标记已读'}</button><button className="btn" disabled={busy} onClick={() => act(async () => { await useStore.getState().activate(item.sessionId); close(); })}>打开执行对话</button></div>
          <nav className="result-sections" aria-label="结果内容导航">{[['verification', '验收'], ['changes', '改动'], ...(item.workspace ? [['workspace', '工作区']] : []), ['research', '开源参考']].map(([key, text]) => <button key={key} onClick={() => ref.current.querySelector(`#result-${key}`)?.scrollIntoView({ block: 'start' })}>{text}</button>)}</nav>
          <h3 id="result-verification">验收与验证</h3><p data-verification-phase={detail.verification}>{verificationLabel(detail.verification)}</p>
          {verificationHint(detail.verification) && <p className="task-help">{verificationHint(detail.verification)}</p>}
          {detail.verificationDetail && <p className="task-help">{detail.verificationDetail}</p>}
          {!!detail.acceptance?.length && <ul>{detail.acceptance.map((a, i) => <li key={i}>{a.checked ? '已满足' : '待核对'} · {a.text}{a.evidence && <small>{a.evidence}</small>}</li>)}</ul>}
          <ResultList title="记录的验证项" rows={item.checks} empty="没有记录验证项。" />
          {!!item.openSteps.length && <ResultList title="未完成事项" rows={item.openSteps} />}
          <h3 id="result-changes">文件改动</h3><ResultList rows={item.changedFiles} empty="没有记录文件改动。" />
          {item.runId && <button className="btn" disabled={busy} onClick={() => act(async current => { const data = await api.runChanges(item.runId); if (current()) setDiff((data.files || []).map(f => f.diff || '').join('\n') || '没有可用的运行差异。'); })}>查看本次运行差异</button>}
          {diff !== null && <pre className="result-diff" tabIndex={0} aria-label="运行差异">{diff}</pre>}
          {item.workspace && <><h3 id="result-workspace">执行工作区</h3><dl><dt>{item.workspace.mode === 'ISOLATED' ? '独立工作区' : '原目录执行'}</dt><dd>{item.workspace.path || '默认工作区'}</dd>{item.workspace.mode === 'ISOLATED' && <><dt>交回目录</dt><dd>{item.workspace.base}</dd><dt>创建基线</dt><dd>{item.workspace.commit}</dd></>}</dl>
            {item.workspace.handedOffAt ? <p role="status">已于 {new Date(item.workspace.handedOffAt).toLocaleString()} 交回；执行目录仍保留。</p> : item.workspace.mode === 'ISOLATED' && <button className="btn" disabled={busy} onClick={() => act(async current => { setPreview(null); const data = await api.scheduleHandoffPreview(item.scheduleId); if (current()) setPreview(data); })}>预览交回改动</button>}
          </>}
          {preview && <div className="result-handoff"><h3>交回预览 · {preview.files.length} 个文件</h3><p>包含从创建基线开始的全部改动。交回到：{preview.base}</p><ResultList rows={preview.files} />{preview.conflict && <p role="alert">{preview.conflict}</p>}{preview.truncated && <p>差异超过展示上限；请在独立工作区查看完整内容。</p>}<pre className="result-diff" tabIndex={0} aria-label="交回差异">{preview.diff || '没有需要交回的改动。'}</pre><button className="btn btn-primary" disabled={busy || !!preview.conflict || !preview.files.length} onClick={() => act(async current => { const revision = preview.revision; setPreview(null); await api.scheduleHandoff(item.scheduleId, { revision }); if (current()) { setDetail(await api.result(item.id)); await refresh(); } })}>交回原目录</button></div>}
          <h3 id="result-research">开源参考</h3><ResearchRecords records={detail.research} />
        </>}
      </section>}
    </div>
  </dialog>;
}
function ResultList({ title, rows = [], empty }) { return <>{title && <h4>{title}</h4>}{rows.length ? <ul>{rows.map((row, i) => <li key={i}>{row}</li>)}</ul> : empty ? <p>{empty}</p> : null}</>; }
