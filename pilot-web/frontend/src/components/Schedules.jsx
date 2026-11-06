import { restoreFocus } from '../lib/interaction.js';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { api } from '../api.js';
import './schedules.css';
import { DialogHeader } from './DialogHeader.jsx';

const labels = { SCHEDULED: '待执行', DISPATCHING: '正在启动', RUNNING: '执行中', WAITING: '等待唤醒', PAUSED: '已暂停', COMPLETED: '已完成', CANCELLED: '已取消', NEEDS_ATTENTION: '需要处理', INTERRUPTED: '已中断' };
const ended = (job) => ['COMPLETED', 'CANCELLED', 'NEEDS_ATTENTION'].includes(job.state);
export function localTime(date, zone) {
  const d = new Date(date);
  if (zone) {
    const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(d).map(p => [p.type, p.value]));
    return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
  }
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
  return open ? <ScheduleDialog seed={seed} /> : null;
}
function ScheduleDialog({ seed }) {
  const ref = useRef(null);
  const [jobs, setJobs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [filter, setFilter] = useState('all');
  const [selected, setSelected] = useState(() => useStore.getState().ui.scheduleTarget || null);
  const [editing, setEditing] = useState(seed != null);
  const [content, setContent] = useState(seed || '');
  const [repeat, setRepeat] = useState(() => useStore.getState().ui.scheduleRepeat ? 'daily' : 'once');
  const [cron, setCron] = useState('0 0 9 * * MON-FRI');
  const [misfire, setMisfire] = useState('ONCE');
  const [times, setTimes] = useState([]);
  const [templates, setTemplates] = useState([]);
  const [templateId, setTemplateId] = useState('');
  const [templateName, setTemplateName] = useState('');
  const [name, setName] = useState('');
  const [when, setWhen] = useState(() => localTime(Date.now() + 3600000));
  const [acceptance, setAcceptance] = useState('');
  const [constraints, setConstraints] = useState('');
  const [skill, setSkill] = useState('');
  const [isolated, setIsolated] = useState(true);
  const [fullAccess, setFullAccess] = useState(true);
  const [changedTime, setChangedTime] = useState('');
  const alive = useRef(true);
  const [zone, setZone] = useState(() => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC');
  const close = () => useStore.getState().setUi({ schedulesOpen: false, scheduleDraft: null, scheduleTarget: null, scheduleRepeat: false });
  const refresh = useCallback(async () => {
    try { const rows = await api.schedules(); if (alive.current) { setJobs(Array.isArray(rows) ? rows : []); } }
    catch (e) { if (alive.current) setError(`读取任务失败：${e.message}`); }
    finally { if (alive.current) setLoading(false); }
  }, []);
  useEffect(() => {
    const previous = document.activeElement;
    alive.current = true;
    ref.current.showModal(); refresh();
    api.templates().then(rows => { if (alive.current) setTemplates(Array.isArray(rows) ? rows : []); }).catch(e => { if (alive.current) setError(`读取模板失败：${e.message}`); });
    const timer = setInterval(refresh, 5000);
    return () => { alive.current = false; clearInterval(timer); restoreFocus(previous); };
  }, [refresh]);
  const job = jobs.find(j => j.id === selected);
  const visible = jobs.filter(j => filter === 'all' || (filter === 'active' ? !ended(j) : j.state === 'NEEDS_ATTENTION'));
  async function action(id, command, at) {
    setBusy(true); setError('');
    try {
      const timing = at ? await api.scheduleTimes({ localTime: at, timezone: zone, cron: '' }) : null;
      if (at && (!timing.runAt || new Date(timing.runAt) <= new Date())) throw new Error('请选择有效的未来时间');
      await api.controlSchedule(id, { action: command, ...(timing ? { runAt: timing.runAt } : {}) }); await refresh(); setChangedTime('');
    }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }
  function expression() {
    const date = new Date(`${when.split('T')[0]}T12:00:00Z`), [hour, minute] = (when.split('T')[1] || '09:00').split(':').map(Number);
    return repeat === 'once' ? '' : repeat === 'custom' ? cron : `0 ${minute} ${hour} * * ${repeat === 'weekdays' ? 'MON-FRI' : repeat === 'weekly' ? date.getUTCDay() : '*'}`;
  }
  function draft(sourceSessionId) {
    const lines = value => value.split('\n').map(v => v.trim()).filter(Boolean);
    return { name: name.trim() || content.trim().slice(0, 40), sourceSessionId, content, timezone: zone,
      requirements: { constraints: lines(constraints), acceptance: lines(acceptance).map(text => ({ text, checked: false, evidence: '' })) }, skill: skill.trim() || null, fullAccess, isolated };
  }
  async function previewTimes() {
    setBusy(true); setError(''); setTimes([]);
    try { const data = await api.scheduleTimes({ localTime: when, timezone: zone, cron: expression() }); setTimes(data.next || []); }
    catch (e) { setError(e.message); } finally { setBusy(false); }
  }
  async function save(event) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      const sourceSessionId = await useStore.getState().ensureSession();
      if (!sourceSessionId) throw new Error('请先选择工作区和对话');
      const timing = await api.scheduleTimes({ localTime: when, timezone: zone, cron: expression() });
      if (!timing.runAt || new Date(timing.runAt) <= new Date()) throw new Error('请选择有效的未来时间');
      const task = { ...draft(sourceSessionId), runAt: timing.runAt };
      if (repeat !== 'once') {
        await api.createRecurring({ task, cron: expression(), misfire }); close(); useStore.getState().setUi({ recurringOpen: true });
      } else {
        const created = await api.createSchedule(task);
        await refresh(); await useStore.getState().loadSessions(); setSelected(created.id); setEditing(false); setContent('');
      }
    } catch (e) { setError(e.message); } finally { setBusy(false); }
  }
  function applyTemplate(id) {
    setTemplateId(id); const template = templates.find(t => t.id === id); if (!template) return;
    const task = template.task; setTemplateName(template.name); setName(task.name || ''); setContent(task.content || '');
    setConstraints((task.requirements?.constraints || []).join('\n')); setAcceptance((task.requirements?.acceptance || []).map(a => a.text).join('\n'));
    setSkill(task.skill || ''); setFullAccess(task.fullAccess !== false); setIsolated(task.isolated !== false);
  }
  async function saveTemplate(update) {
    setBusy(true); setError('');
    try { const old = templates.find(t => t.id === templateId); const row = await api.saveTemplate({ id: update ? old?.id : null, version: update ? old?.version : 0, name: templateName, task: draft(null) });
      setTemplates(await api.templates()); setTemplateId(row.id); useStore.getState().showNotice(`模板已保存：第 ${row.version} 版`);
    } catch (e) { setError(e.message); } finally { setBusy(false); }
  }
  return <dialog className="schedule-dialog" ref={ref} aria-labelledby="schedule-title" onCancel={e => { e.preventDefault(); close(); }} onKeyDown={e => e.stopPropagation()}>
    <DialogHeader id="schedule-title" title="定时任务" subtitle="Pilot / 自动执行" closeLabel="关闭定时任务" onClose={close} />
    <div className="schedule-dialog-body">
    <div className="schedule-actions schedule-dialog-toolbar">{editing && <button type="submit" form="schedule-create-form" className="btn btn-primary" disabled={busy}>{busy ? '保存中…' : '创建计划'}</button>}<button className="btn" onClick={() => { close(); useStore.getState().setUi({ recurringOpen: true }); }}>周期计划</button>{!editing && <button className="btn btn-primary" onClick={() => { setEditing(true); setSelected(null); setError(''); }}>新建任务</button>}</div>
    <p className="schedule-runtime">到期后自动执行，结果保存在独立对话。需要 Pilot 服务保持运行；一次性任务离线后补执行；周期计划按选择的错过策略执行。</p>
    {error && <div className="schedule-error" role="alert">{error} <button type="button" onClick={() => { setError(''); refresh(); }}>重新加载</button></div>}
    {editing ? <form id="schedule-create-form" className="schedule-form" onSubmit={save} aria-busy={busy}>
      <div className="schedule-form-main">
        <details className="schedule-templates"><summary>任务模板{templates.length ? ` · ${templates.length} 个` : ''}</summary>
          <label>使用模板<select aria-label="使用模板" value={templateId} onChange={e => applyTemplate(e.target.value)}><option value="">选择模板</option>{templates.map(t => <option key={t.id} value={t.id}>{t.name} · v{t.version}</option>)}</select></label>
          <label>模板名称<input value={templateName} onChange={e => setTemplateName(e.target.value)} maxLength={120} /></label>
          <div className="schedule-actions"><button type="button" className="btn" disabled={busy || !content.trim() || !templateName.trim()} onClick={() => saveTemplate(false)}>另存为模板</button>{templateId && <><button type="button" className="btn" disabled={busy || !content.trim() || !templateName.trim()} onClick={() => saveTemplate(true)}>更新所选模板</button><button type="button" className="btn" disabled={busy} onClick={async () => { setBusy(true); try { const old = templates.find(t => t.id === templateId); await api.deleteTemplate(old.id, old.version); setTemplates(await api.templates()); setTemplateId(''); } catch (e) { setError(e.message); } finally { setBusy(false); } }}>删除模板</button></>}</div>
          <p>模板保存目标、验收与执行策略；更新不会改变已有计划。</p>
        </details>
        <label>任务名称<input aria-label="任务名称" maxLength={120} value={name} onChange={e => setName(e.target.value)} placeholder="例如：检查依赖升级并修复测试" /></label>
        <label>任务目标<textarea aria-label="任务目标" required rows={5} maxLength={100000} value={content} onChange={e => setContent(e.target.value)} placeholder="说明希望完成的工作，agent 会先调研开源方案，再执行与验证。" /></label>
        <label>验收条件<textarea aria-label="验收条件" rows={3} value={acceptance} onChange={e => setAcceptance(e.target.value)} placeholder="每行一项，例如：相关测试通过，说明复用方案与改动原因" /></label>
        <details><summary>约束与技能</summary><label>执行约束<textarea rows={2} value={constraints} onChange={e => setConstraints(e.target.value)} placeholder="每行一项" /></label><label>技能名称（可选）<input value={skill} onChange={e => setSkill(e.target.value)} /></label></details>
      </div>
      <aside className="schedule-options">
        <label>执行时间<input aria-label="执行时间" type="datetime-local" required value={when} onChange={e => { setWhen(e.target.value); setTimes([]); }} /></label>
        <label>时区<input aria-label="执行时区" value={zone} onChange={e => { setZone(e.target.value); setTimes([]); }} placeholder="Asia/Shanghai" /></label>
        <label>重复<select aria-label="重复周期" value={repeat} onChange={e => { setRepeat(e.target.value); setTimes([]); }}><option value="once">仅一次</option><option value="daily">每天</option><option value="weekdays">工作日</option><option value="weekly">每周同一天</option><option value="custom">自定义 cron</option></select></label>
        {repeat === 'custom' && <label>六段 cron<input aria-label="cron 表达式" value={cron} onChange={e => { setCron(e.target.value); setTimes([]); }} placeholder="0 0 9 * * MON-FRI" /></label>}
        {repeat !== 'once' && <><label>错过执行时<select aria-label="错过策略" value={misfire} onChange={e => setMisfire(e.target.value)}><option value="ONCE">补执行一次，合并错过的轮次</option><option value="SKIP">跳过，等下一次</option></select></label><p>首次按所选时间执行，此后按周期。同一计划不重叠；每轮创建新对话，独立工作区取当时已提交版本。跳过策略允许 60 秒调度延迟。</p></>}
        <button type="button" className="btn" disabled={busy} onClick={previewTimes}>预览执行时间</button>
        {!!times.length && <ol className="schedule-times">{times.map(at => <li key={at}>{stamp(at, zone)} · {zone}</li>)}</ol>}
        <p>时间按所填时区解释；夏令时重复时刻的首次执行采用较早偏移。</p>
        <div className="schedule-presets">{[[10, '10 分钟后'], [60, '1 小时后'], [1440, '24 小时后']].map(([minutes, title]) => <button type="button" key={minutes} onClick={() => { try { setWhen(localTime(Date.now() + minutes * 60000, zone)); setTimes([]); setError(''); } catch { setError('请填写有效时区后再选择快捷时间'); } }}>{title}</button>)}</div>
        <div className="schedule-policy"><strong>先调研，再实现</strong><p>必须阅读开源项目并记录复用理由；完成前自动核验证据。</p></div>
        <label className="schedule-checkbox"><input type="checkbox" checked={isolated} onChange={e => setIsolated(e.target.checked)} />使用独立工作区</label>
        <p>{isolated ? `${repeat === 'once' ? '从创建计划时' : '每轮从触发时'}已提交的 Git HEAD 开始，不复制未提交或忽略的文件；结果可预览后交回。需要已有提交的 Git 仓库。` : '直接在原目录执行，任务改动会影响当前工作区。'}</p>
        <label className="schedule-checkbox"><input type="checkbox" checked={fullAccess} onChange={e => setFullAccess(e.target.checked)} />自主执行，允许所有已提供的工具</label>
        <p>{fullAccess ? '此任务不逐步请求审批，权限仅对本任务生效。' : '沿用当前权限规则；需要审批时可能等待你处理。'} 最多运行 24 小时，预算耗尽或无法继续时保留结果。</p>
        <p>本入口支持文字目标。文件可放在工作区中，并在目标中写明相对路径。</p>
        <div className="schedule-actions"><button type="button" className="btn" disabled={busy} onClick={() => setEditing(false)}>返回列表</button></div>
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
          {job.workspace && <dl><dt>{job.workspace.mode === 'ISOLATED' ? '独立工作区' : '原目录执行'}</dt><dd>{job.workspace.path || '默认工作区'}</dd>{job.workspace.commit && <><dt>创建基线</dt><dd>{job.workspace.commit}</dd></>}</dl>}
          <div className="schedule-actions">{['COMPLETED', 'NEEDS_ATTENTION'].includes(job.state) && <button className="btn btn-primary" onClick={() => { close(); useStore.getState().setUi({ resultsOpen: true, resultTarget: job.id }); }}>查看结果</button>}<button className="btn" disabled={busy} onClick={async () => { try { await useStore.getState().activate(job.sessionId); close(); } catch (e) { setError(e.message); } }}>打开执行对话</button>
            {!ended(job) && <button className="btn" disabled={busy} onClick={() => action(job.id, job.state === 'PAUSED' ? 'resume' : 'pause')}>{job.state === 'PAUSED' ? '恢复计划' : '暂停任务'}</button>}
            {!ended(job) && <button className="btn" disabled={busy} onClick={() => action(job.id, 'cancel')}>取消任务</button>}
          </div>
          {['SCHEDULED', 'WAITING', 'PAUSED'].includes(job.state) && <form className="schedule-reschedule" onSubmit={e => { e.preventDefault(); action(job.id, 'reschedule', changedTime); }}><label>修改执行时间（{zone}）<input type="datetime-local" required value={changedTime} onChange={e => setChangedTime(e.target.value)} /></label><button className="btn" disabled={busy || !changedTime}>保存时间</button></form>}
          <h3>执行记录</h3>{!job.history?.length && <p>尚未开始执行。</p>}<ol className="schedule-history">{job.history?.map(h => <li key={h.runId}><time>{stamp(h.startedAt, job.timezone)}</time><span>{labels[h.status] || h.status}</span></li>)}</ol>
        </section>}
      </div>
    </>}
    </div>
  </dialog>;
}
