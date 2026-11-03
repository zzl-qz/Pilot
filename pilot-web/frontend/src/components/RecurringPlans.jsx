import React, { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { useStore } from '../store.js';
import './schedules.css';
const labels = { ACTIVE: '已启用', PAUSED: '已暂停', CANCELLED: '已取消', ERROR: '需要处理' };
export default function RecurringPlans() { const open = useStore(s => s.ui.recurringOpen); return open ? <Dialog /> : null; }
function Dialog() {
  const ref = useRef(null), alive = useRef(true);
  const [rows, setRows] = useState([]), [error, setError] = useState(''), [busy, setBusy] = useState(false), [loading, setLoading] = useState(true);
  const close = () => useStore.getState().setUi({ recurringOpen: false });
  async function refresh() { try { const data = await api.recurring(); if (alive.current) { setRows(Array.isArray(data) ? data : []); setError(''); } } catch (e) { if (alive.current) setError(e.message); } finally { if (alive.current) setLoading(false); } }
  useEffect(() => { const previous = document.activeElement; alive.current = true; ref.current.showModal(); refresh(); const timer = setInterval(refresh, 10000); return () => { alive.current = false; clearInterval(timer); previous?.focus?.(); }; }, []);
  async function action(row, action) { setBusy(true); setError(''); try { await api.controlRecurring(row.id, { version: row.version, action }); await refresh(); } catch (e) { setError(e.message); } finally { setBusy(false); } }
  return <dialog className="schedule-dialog" ref={ref} aria-labelledby="recurring-title" onCancel={e => { e.preventDefault(); close(); }} onKeyDown={e => e.stopPropagation()}>
    <header className="schedule-header"><div><span className="schedule-product">Pilot / 自动执行</span><h1 id="recurring-title">周期计划</h1></div><div className="schedule-actions"><button className="btn btn-primary" onClick={() => { close(); useStore.getState().setUi({ schedulesOpen: true, scheduleDraft: '', scheduleRepeat: true }); }}>新建周期计划</button><button className="icon-btn" aria-label="关闭周期计划" onClick={close}>×</button></div></header>
    <p className="schedule-runtime">每轮复用现有任务执行器，结果进入收件箱。暂停或取消周期只影响未来轮次；正在执行的任务可以单独控制。</p>
    {error && <p className="schedule-error" role="alert">{error} <button onClick={refresh}>重新加载</button></p>}
    {loading && <p role="status">正在读取周期计划…</p>}{!loading && !rows.length && <div className="schedule-empty"><h2>暂无周期计划</h2><p>把重复的检查与维护工作安排为每天、工作日或自定义周期。</p></div>}
    {rows.map(row => <article className="recurring-row" key={row.id}><div><h2>{row.name}</h2><p>{labels[row.state]} · {row.message}</p><dl><dt>下次计划时间</dt><dd>{new Date(row.nextAt).toLocaleString('zh-CN', { timeZone: row.timezone })} · {row.timezone}</dd><dt>周期与错过策略</dt><dd>{row.cron} · {row.misfire === 'SKIP' ? '跳过错过轮次' : '合并补执行一次'} · 已创建 {row.occurrences} 轮</dd></dl></div>
      <div className="schedule-actions">{row.activeId && <button className="btn" onClick={() => { close(); useStore.getState().setUi({ schedulesOpen: true, scheduleDraft: null, scheduleTarget: row.activeId }); }}>查看最近轮次</button>}{row.state !== 'CANCELLED' && <><button className="btn" disabled={busy} onClick={() => action(row, row.state === 'ACTIVE' ? 'pause' : 'resume')}>{row.state === 'ACTIVE' ? '暂停周期' : '恢复周期'}</button><button className="btn" disabled={busy} onClick={() => action(row, 'cancel')}>取消周期</button></>}</div>
    </article>)}
  </dialog>;
}
