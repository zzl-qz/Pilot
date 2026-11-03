import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { api } from '../api.js';
import './schedules.css';

const labels = { SCHEDULED: '待执行', DISPATCHING: '正在启动', RUNNING: '执行中', WAITING: '等待唤醒', PAUSED: '已暂停', COMPLETED: '已完成', CANCELLED: '已取消', NEEDS_ATTENTION: '需要处理', INTERRUPTED: '已中断' };
const ended = (job) => ['COMPLETED', 'CANCELLED', 'NEEDS_ATTENTION'].includes(job.state);
export function localTime(date) {
  const d = new Date(date);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}T${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}
function stamp(value, zone) {
  if (!value) return '—';
  try { return new Intl.DateTimeFormat('zh-CN', { timeZone: zone || undefined, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)); }
  catch { return value; }
}
export default function Schedules() {
  const open = useStore(s => s.ui.schedulesOpen);
  const seed = useStore(s => s.ui.scheduleDraft);
  useEffect(() => {
    let alive = true, previous = null;
    async function check() {
      try {
        const rows = await api.schedules();
        if (!alive || !Array.isArray(rows)) return;
        if (previous) for (const row of rows) {
          if (previous.has(row.id) && previous.get(row.id) !== row.state && ['COMPLETED', 'NEEDS_ATTENTION'].includes(row.state))
            useStore.getState().showNotice(`定时任务「${row.name}」${labels[row.state]}，可在定时任务中打开执行记录。`);
        }
        previous = new Map(rows.map(row => [row.id, row.state]));
      } catch { /* 面板负责呈现连接错误；后台轮询不重复打扰 */ }
    }
    check(); const timer = setInterval(check, 15000);
    return () => { alive = false; clearInterval(timer); };
  }, []);
  return open ? <ScheduleDialog seed={seed} /> : null;
}
function ScheduleDialog({ seed }) {
  const ref = useRef(null);
  const [jobs, setJobs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [filter, setFilter] = useState('all');
  const [selected, setSelected] = useState(null);
  const [editing, setEditing] = useState(seed != null);
  const [content, setContent] = useState(seed || '');
  const [name, setName] = useState('');
  const [when, setWhen] = useState(() => localTime(Date.now() + 3600000));
  const [acceptance, setAcceptance] = useState('');
  const [constraints, setConstraints] = useState('');
  const [skill, setSkill] = useState('');
  const [fullAccess, setFullAccess] = useState(true);
  const [changedTime, setChangedTime] = useState('');
  const alive = useRef(true);
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  const close = () => useStore.getState().setUi({ schedulesOpen: false, scheduleDraft: null });
  const refresh = useCallback(async () => {
    try { const rows = await api.schedules(); if (alive.current) { setJobs(Array.isArray(rows) ? rows : []); setError(''); } }
    catch (e) { if (alive.current) setError(`读取任务失败：${e.message}`); }
    finally { if (alive.current) setLoading(false); }
  }, []);
  useEffect(() => {
    const previous = document.activeElement;
    alive.current = true;
    ref.current.showModal(); refresh();
    const timer = setInterval(refresh, 5000);
    return () => { alive.current = false; clearInterval(timer); previous?.focus?.(); };
  }, [refresh]);
  const job = jobs.find(j => j.id === selected);
  const visible = jobs.filter(j => filter === 'all' || (filter === 'active' ? !ended(j) : j.state === 'NEEDS_ATTENTION'));
  async function action(id, command, at) {
    if (at) {
      const date = new Date(at);
      if (!Number.isFinite(date.getTime()) || localTime(date) !== at || date <= new Date()) { setError('请选择有效的未来时间'); return; }
    }
    setBusy(true); setError('');
    try { await api.controlSchedule(id, { action: command, ...(at ? { runAt: new Date(at).toISOString() } : {}) }); await refresh(); setChangedTime(''); }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }
  async function save(event) {
    event.preventDefault();
    const date = new Date(when);
    if (!Number.isFinite(date.getTime()) || localTime(date) !== when || date <= new Date()) { setError('请选择有效的未来时间；夏令时跳过的本地时间不可用'); return; }
    setBusy(true); setError('');
    try {
      const sourceSessionId = await useStore.getState().ensureSession();
      if (!sourceSessionId) throw new Error('请先选择工作区和对话');
      const lines = value => value.split('\n').map(v => v.trim()).filter(Boolean);
      const created = await api.createSchedule({ name, sourceSessionId, content, runAt: date.toISOString(), timezone: zone,
        requirements: { constraints: lines(constraints), acceptance: lines(acceptance).map(text => ({ text, checked: false, evidence: '' })) }, skill: skill.trim() || null, fullAccess });
      await refresh(); await useStore.getState().loadSessions();
      setSelected(created.id); setEditing(false); setContent('');
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }
  return <dialog className="schedule-dialog" ref={ref} aria-labelledby="schedule-title" onCancel={e => { e.preventDefault(); close(); }} onKeyDown={e => e.stopPropagation()}>
    <header className="schedule-header"><div><span className="schedule-product">Pilot / 自动执行</span><h1 id="schedule-title">定时任务</h1></div>
      <div className="schedule-actions">{!editing && <button className="btn btn-primary" onClick={() => { setEditing(true); setSelected(null); setError(''); }}>新建任务</button>}<button className="icon-btn" aria-label="关闭定时任务" onClick={close}>×</button></div>
    </header>
    <p className="schedule-runtime">到期后自动执行，结果保存在独立对话。需要 Pilot 服务保持运行；离线错过的任务会在服务恢复后执行。</p>
    {error && <div className="schedule-error" role="alert">{error} <button type="button" onClick={refresh}>重新加载</button></div>}
    {editing ? <form className="schedule-form" onSubmit={save} aria-busy={busy}>
      <div className="schedule-form-main">
        <label>任务名称<input aria-label="任务名称" maxLength={120} value={name} onChange={e => setName(e.target.value)} placeholder="例如：检查依赖升级并修复测试" /></label>
        <label>任务目标<textarea aria-label="任务目标" required rows={5} maxLength={100000} value={content} onChange={e => setContent(e.target.value)} placeholder="说明希望完成的工作，agent 会先调研开源方案，再执行与验证。" /></label>
        <label>验收条件<textarea aria-label="验收条件" rows={3} value={acceptance} onChange={e => setAcceptance(e.target.value)} placeholder="每行一项，例如：相关测试通过，说明复用方案与改动原因" /></label>
        <details><summary>约束与技能</summary><label>执行约束<textarea rows={2} value={constraints} onChange={e => setConstraints(e.target.value)} placeholder="每行一项" /></label><label>技能名称（可选）<input value={skill} onChange={e => setSkill(e.target.value)} /></label></details>
      </div>
      <aside className="schedule-options">
        <label>执行时间<input aria-label="执行时间" type="datetime-local" required value={when} onChange={e => setWhen(e.target.value)} /></label>
        <p>{zone} · 仅执行一次</p>
        <div className="schedule-presets">{[[10, '10 分钟后'], [60, '1 小时后'], [1440, '明天此时']].map(([minutes, title]) => <button type="button" key={minutes} onClick={() => setWhen(localTime(Date.now() + minutes * 60000))}>{title}</button>)}</div>
        <div className="schedule-policy"><strong>先调研，再实现</strong><p>必须阅读开源项目并记录复用理由；完成前自动核验证据。</p></div>
        <label className="schedule-checkbox"><input type="checkbox" checked={fullAccess} onChange={e => setFullAccess(e.target.checked)} />自主执行，允许所有已提供的工具</label>
        <p>{fullAccess ? '此任务不逐步请求审批，权限仅对本任务生效。' : '沿用当前权限规则；需要审批时可能等待你处理。'} 最多运行 24 小时，预算耗尽或无法继续时保留结果。</p>
        <p>本入口支持文字目标。文件可放在工作区中，并在目标中写明相对路径。</p>
        <div className="schedule-actions"><button type="button" className="btn" disabled={busy} onClick={() => setEditing(false)}>返回列表</button><button className="btn btn-primary" disabled={busy}>{busy ? '保存中…' : '创建计划'}</button></div>
      </aside>
    </form> : <>
      <nav className="schedule-tabs" aria-label="任务筛选">{[['all', '全部'], ['active', '未结束'], ['attention', '需要处理']].map(([key, title]) => <button key={key} aria-pressed={filter === key} onClick={() => { setFilter(key); setSelected(null); }}>{title}</button>)}<span>{visible.length} 个任务</span><button onClick={refresh} disabled={loading}>刷新</button></nav>
      <div className={`schedule-layout${job ? ' has-detail' : ''}`}>
        <section className="schedule-list" aria-label="定时任务列表" aria-busy={loading}>
          {loading && <p role="status">正在读取任务…</p>}
          {!loading && visible.length === 0 && <div className="schedule-empty"><h2>{filter === 'all' ? '安排下一件事' : '这里没有任务'}</h2><p>{filter === 'all' ? '写好目标与验收条件，选择时间后即可离开。' : '任务状态变化后会自动更新。'}</p>{filter === 'all' && <button className="btn" onClick={() => setEditing(true)}>创建第一个定时任务</button>}</div>}
          {visible.map(item => <button className={`schedule-row${item.id === selected ? ' is-selected' : ''}`} key={item.id} onClick={() => { setSelected(item.id); setChangedTime(''); }} aria-pressed={item.id === selected}>
            <span className="schedule-row-title"><strong>{item.name}</strong><small>{item.message}</small></span>
            <span className="schedule-row-time"><time dateTime={item.runAt}>{stamp(item.runAt, item.timezone)}</time><small>{item.timezone}</small></span>
            <span className={`schedule-status status-${item.state}`}>{labels[item.state] || item.state}</span>
          </button>)}
        </section>
        {job && <section className="schedule-detail" aria-label="任务详情">
          <button className="schedule-back" onClick={() => setSelected(null)}>返回任务列表</button><h2>{job.name}</h2><p className="schedule-goal">{job.content}</p>
          <dl><dt>当前状态</dt><dd>{labels[job.state]} · {job.message}</dd><dt>执行时间</dt><dd>{stamp(job.runAt, job.timezone)} · {job.timezone}</dd><dt>执行策略</dt><dd>先调研开源 · 自动核验 · {job.requirements?.automation?.fullAccess ? '自主执行' : '沿用权限'}</dd></dl>
          <div className="schedule-actions"><button className="btn" disabled={busy} onClick={async () => { try { await useStore.getState().activate(job.sessionId); close(); } catch (e) { setError(e.message); } }}>打开执行对话</button>
            {!ended(job) && <button className="btn" disabled={busy} onClick={() => action(job.id, job.state === 'PAUSED' ? 'resume' : 'pause')}>{job.state === 'PAUSED' ? '恢复计划' : '暂停任务'}</button>}
            {!ended(job) && <button className="btn" disabled={busy} onClick={() => action(job.id, 'cancel')}>取消任务</button>}
          </div>
          {['SCHEDULED', 'WAITING', 'PAUSED'].includes(job.state) && <form className="schedule-reschedule" onSubmit={e => { e.preventDefault(); action(job.id, 'reschedule', changedTime); }}><label>修改执行时间（{zone}）<input type="datetime-local" required value={changedTime} onChange={e => setChangedTime(e.target.value)} /></label><button className="btn" disabled={busy || !changedTime}>保存时间</button></form>}
          <h3>执行记录</h3>{!job.history?.length && <p>尚未开始执行。</p>}<ol className="schedule-history">{job.history?.map(h => <li key={h.runId}><time>{stamp(h.startedAt, job.timezone)}</time><span>{labels[h.status] || h.status}</span></li>)}</ol>
        </section>}
      </div>
    </>}
  </dialog>;
}
