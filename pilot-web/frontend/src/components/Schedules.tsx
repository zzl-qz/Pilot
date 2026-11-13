import { useEffect, useId, useLayoutEffect, useRef, useState, type FormEvent } from 'react';
import { AlertDialog } from '@astryxdesign/core/AlertDialog';
import { Badge } from '@astryxdesign/core/Badge';
import { Banner } from '@astryxdesign/core/Banner';
import { Button } from '@astryxdesign/core/Button';
import { CheckboxInput } from '@astryxdesign/core/CheckboxInput';
import { EmptyState } from '@astryxdesign/core/EmptyState';
import { Field } from '@astryxdesign/core/Field';
import { SegmentedControl, SegmentedControlItem } from '@astryxdesign/core/SegmentedControl';
import { TextArea } from '@astryxdesign/core/TextArea';
import { TextInput } from '@astryxdesign/core/TextInput';
import { api } from '../api.js';
import { errorInfo } from '../lib/errors.js';
import { useStore } from '../store.js';
import type { ScheduleDraft } from '../types/api.js';
import type { ScheduledTask, RecurringScheduleServiceTemplate } from '../types/server.js';
import { AutomationDialog } from './AutomationDialog.jsx';
import { useAutomationList } from './useAutomationList.js';

const labels: Record<string, string> = {
  SCHEDULED: '待执行', DISPATCHING: '正在启动', RUNNING: '执行中', WAITING: '等待唤醒', PAUSED: '已暂停',
  COMPLETED: '已完成', CANCELLED: '已取消', NEEDS_ATTENTION: '需要处理', INTERRUPTED: '已中断'
};
const filters = [['all', '全部'], ['active', '未结束'], ['attention', '需要处理']] as const;
const periods = [['once', '仅一次'], ['daily', '每天'], ['weekdays', '工作日'], ['weekly', '每周同一天'], ['custom', '自定义 cron']] as const;
const misfires = [['ONCE', '合并补执行一次'], ['SKIP', '跳过，等下一次']] as const;
const presets = [[10, '10 分钟后'], [60, '1 小时后'], [1440, '24 小时后']] as const;
type ErrorArea = 'form' | 'time' | 'template' | 'detail';
const ended = (job: ScheduledTask) => ['COMPLETED', 'CANCELLED', 'NEEDS_ATTENTION'].includes(job.state);

export function localTime(date: number | string, zone?: string) {
  const d = new Date(date);
  if (zone) {
    const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(d).map(p => [p.type, p.value]));
    return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
  }
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}T${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function stamp(value?: string | null, zone?: string) {
  if (!value) return '—';
  try { return new Intl.DateTimeFormat('zh-CN', { timeZone: zone || 'UTC', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(value)); }
  catch { return value; }
}

function required<T>(value: T | null): T {
  if (value === null) throw new Error('服务端返回空计划数据，请重新加载');
  return value;
}

async function futureTime(localTime: string, timezone: string, cron = '') {
  const { runAt } = required(await api.scheduleTimes({ localTime, timezone, cron }));
  if (!(Date.parse(runAt) > Date.now())) throw new Error('请选择有效的未来时间');
  return runAt;
}

/** 原生控件可在键盘、触屏和缺少浮层能力的浏览器中使用。 */
function ScheduleTimeField({ label, description, value, onChange, disabled }: {
  label: string; description?: string; value: string; onChange: (value: string) => void; disabled: boolean;
}) {
  const id = useId();
  const descriptionId = description ? id + '-hint' : undefined;
  return <Field label={label} inputID={id} description={description} descriptionID={descriptionId} isRequired isDisabled={disabled} width="100%">
    <input id={id} aria-label={label} aria-describedby={descriptionId} className="automation-native-control" type="datetime-local" required disabled={disabled}
      placeholder="YYYY-MM-DDTHH:MM" title="可按 YYYY-MM-DDTHH:MM 输入"
      pattern="[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}" value={value} onChange={event => onChange(event.target.value)} />
  </Field>;
}

function ScheduleSelect({ label, value, options, onChange, disabled }: {
  label: string; value: string; options: readonly (readonly [string, string])[];
  onChange: (value: string) => void; disabled: boolean;
}) {
  const id = useId();
  return <Field label={label} inputID={id} isDisabled={disabled} width="100%">
    <select id={id} className="automation-native-control" disabled={disabled} value={value} onChange={event => onChange(event.target.value)}>
      {options.map(([key, text]) => <option key={key} value={key}>{text}</option>)}
    </select>
  </Field>;
}

export default function Schedules() {
  const open = useStore(s => s.ui.schedulesOpen);
  const seed = useStore(s => s.ui.scheduleDraft);
  return open ? <ScheduleDialog seed={seed} /> : null;
}

function ScheduleDialog({ seed }: { seed?: string | null }) {
  const jobs = useAutomationList(api.schedules, 5000);
  const templates = useAutomationList(api.templates);
  const [filter, setFilter] = useState('all');
  const [selected, setSelected] = useState(() => useStore.getState().ui.scheduleTarget || null);
  const [editing, setEditing] = useState(seed != null);
  const [error, setError] = useState<{ area: ErrorArea; message: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<{ title: string; description: string; label: string; action: () => void } | null>(null);
  const [name, setName] = useState('');
  const [content, setContent] = useState(seed || '');
  const [acceptance, setAcceptance] = useState('');
  const [constraints, setConstraints] = useState('');
  const [skill, setSkill] = useState('');
  const [when, setWhen] = useState(() => localTime(Date.now() + 3600000));
  const [zone, setZone] = useState(() => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC');
  const [repeat, setRepeat] = useState(() => useStore.getState().ui.scheduleRepeat ? 'daily' : 'once');
  const [cron, setCron] = useState('0 0 9 * * MON-FRI');
  const [misfire, setMisfire] = useState('ONCE');
  const [times, setTimes] = useState<string[]>([]);
  const [templateId, setTemplateId] = useState('');
  const [templateName, setTemplateName] = useState('');
  const [templateStatus, setTemplateStatus] = useState('');
  const [isolated, setIsolated] = useState(true);
  const [fullAccess, setFullAccess] = useState(true);
  const [requireResearch, setRequireResearch] = useState(false);
  const [changedTime, setChangedTime] = useState('');

  const alive = useRef(true);
  const pending = useRef(false);
  const body = useRef<HTMLDivElement>(null);
  const formTitle = useRef<HTMLHeadingElement>(null);
  const actionError = useRef<HTMLDivElement>(null);
  const detailTitle = useRef<HTMLHeadingElement>(null);
  const createButton = useRef<HTMLButtonElement>(null);
  const filtersRef = useRef<HTMLDivElement>(null);
  const lastRow = useRef<HTMLButtonElement | null>(null);
  const listScroll = useRef(0);
  const returnToCreate = useRef(false);

  const job = jobs.rows?.find(row => row.id === selected);
  const jobId = job?.id;
  const template = templates.rows?.find(row => row.id === templateId);
  const visible = (jobs.rows ?? []).filter(row => filter === 'all' || (filter === 'active' ? !ended(row) : row.state === 'NEEDS_ATTENTION'));
  const firstRun = filter === 'all' && jobs.rows?.length === 0 && !jobs.error && !selected;
  const missingTask = !!selected && jobs.rows !== null && !job && !jobs.error;
  const closeState = { schedulesOpen: false, scheduleDraft: null, scheduleTarget: null, scheduleRepeat: false };
  const close = () => useStore.getState().setUi(closeState);

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  useLayoutEffect(() => {
    if (editing) {
      formTitle.current?.focus({ preventScroll: true });
      if (body.current) body.current.scrollTop = 0;
    } else if (jobId) {
      detailTitle.current?.focus({ preventScroll: true });
      if (body.current && window.matchMedia('(max-width: 900px)').matches) body.current.scrollTop = 0;
    } else if (returnToCreate.current) {
      createButton.current?.focus();
      returnToCreate.current = false;
    } else if (lastRow.current) {
      const destination = lastRow.current.isConnected ? lastRow.current : filtersRef.current?.querySelector<HTMLElement>('[aria-checked="true"]');
      destination?.focus({ preventScroll: true });
      if (body.current) body.current.scrollTop = listScroll.current;
      lastRow.current = null;
    }
  }, [editing, jobId]);

  useLayoutEffect(() => {
    if (error) {
      actionError.current?.scrollIntoView({ block: 'nearest' });
      actionError.current?.focus({ preventScroll: true });
    }
  }, [error]);

  function feedback(area: ErrorArea) {
    return error?.area === area ? <Banner ref={actionError} tabIndex={-1} status="error" role="alert" title={error.message} /> : null;
  }

  async function act(operation: () => Promise<void>, area: ErrorArea = 'detail') {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setError(null);
    try { await operation(); }
    catch (err) { if (alive.current) setError({ area, message: errorInfo(err).message || '操作失败，请重试' }); }
    finally {
      pending.current = false;
      if (alive.current) setBusy(false);
    }
  }

  function changeTiming(update: () => void) {
    update();
    setTimes([]);
  }

  function expression() {
    if (repeat === 'once') return '';
    if (!when || !zone.trim()) throw new Error('请填写执行时间和时区');
    if (repeat === 'custom') {
      if (!cron.trim()) throw new Error('请填写六段 cron 表达式');
      return cron.trim();
    }
    const [date, time] = when.split('T');
    if (!date || !time) throw new Error('请按 YYYY-MM-DDTHH:MM 输入执行时间');
    const [hour, minute] = time.split(':').map(Number);
    const weekday = new Date(`${date}T12:00:00Z`).getUTCDay();
    const days = repeat === 'weekdays' ? 'MON-FRI' : repeat === 'weekly' ? weekday : '*';
    return `0 ${minute} ${hour} * * ${days}`;
  }

  function draft(sourceSessionId: string | null): ScheduleDraft {
    const lines = (value: string) => value.split('\n').map(line => line.trim()).filter(Boolean);
    return {
      name: name.trim() || content.trim().slice(0, 40), sourceSessionId, content, timezone: zone.trim(),
      requirements: { constraints: lines(constraints), acceptance: lines(acceptance).map(text => ({ text, checked: false, evidence: '' })) },
      skill: skill.trim() || null, fullAccess, isolated, requireResearch
    };
  }

  function previewTimes() {
    void act(async () => {
      setTimes([]);
      if (!when || !zone.trim()) throw new Error('请填写执行时间和时区');
      const timing = required(await api.scheduleTimes({ localTime: when, timezone: zone.trim(), cron: expression() }));
      if (alive.current) setTimes(timing.next || []);
    }, 'time');
  }

  function save(event: FormEvent) {
    event.preventDefault();
    void act(async () => {
      if (!content.trim() || content.length > 100000) throw new Error('请填写有效任务目标，最多 100000 字');
      const rule = expression();
      const runAt = await futureTime(when, zone.trim(), rule);
      const sourceSessionId = await useStore.getState().ensureSession();
      if (!sourceSessionId) throw new Error('请先选择工作区和对话');
      const task = { ...draft(sourceSessionId), sourceSessionId, runAt };
      if (repeat !== 'once') {
        required(await api.createRecurring({ task, cron: rule, misfire }));
        if (alive.current) useStore.getState().setUi({ ...closeState, recurringOpen: true });
      } else {
        const created = required(await api.createSchedule(task));
        if (alive.current) {
          jobs.setRows(rows => [created, ...(rows ?? []).filter(row => row.id !== created.id)]);
          setFilter('all');
          setSelected(created.id);
          setEditing(false);
          setContent('');
          void jobs.refresh();
        }
        void useStore.getState().loadSessions();
      }
    }, 'form');
  }

  function applyTemplate(id: string) {
    setTemplateId(id);
    const row = templates.rows?.find(item => item.id === id);
    if (!row) return;
    setTemplateStatus('');
    setTemplateName(row.name);
    setName(row.task.name || '');
    setContent(row.task.content || '');
    setConstraints((row.task.requirements?.constraints || []).join('\n'));
    setAcceptance((row.task.requirements?.acceptance || []).map(item => item.text).join('\n'));
    setSkill(row.task.skill || '');
    setFullAccess(row.task.fullAccess !== false);
    setRequireResearch(row.task.requireResearch ?? row.task.requirements?.automation?.requireResearch ?? false);
    setIsolated(row.task.isolated !== false);
  }

  function saveTemplate(previous?: RecurringScheduleServiceTemplate) {
    void act(async () => {
      setTemplateStatus('');
      const row = required(await api.saveTemplate({
        id: previous?.id ?? null, version: previous?.version ?? 0, name: templateName, task: draft(null)
      }));
      if (!alive.current) return;
      templates.setRows(rows => [row, ...(rows ?? []).filter(item => item.id !== row.id)]);
      setTemplateId(row.id);
      setTemplateStatus(`模板已保存，第 ${row.version} 版`);
      void templates.refresh();
    }, 'template');
  }

  function askToDeleteTemplate(row: RecurringScheduleServiceTemplate) {
    setConfirm({
      title: `删除“${row.name}”？`,
      description: '删除后无法再使用这个模板。已创建的任务和周期计划会继续执行。',
      label: '确认删除模板',
      action: () => void act(async () => {
        setTemplateStatus('');
        await api.deleteTemplate(row.id, row.version);
        if (!alive.current) return;
        templates.setRows(rows => (rows ?? []).filter(item => item.id !== row.id));
        setTemplateId('');
        setTemplateStatus('模板已删除');
        void templates.refresh();
      }, 'template')
    });
  }

  async function control(row: ScheduledTask, action: string, at?: string) {
    const runAt = at === undefined ? undefined : await futureTime(at, row.timezone || 'UTC');
    const updated = required(await api.controlSchedule(row.id, { action, ...(runAt ? { runAt } : {}) }));
    if (!alive.current) return;
    jobs.setRows(rows => rows?.map(item => item.id === row.id ? updated : item) ?? [updated]);
    setChangedTime('');
    if (action === 'cancel') detailTitle.current?.focus({ preventScroll: true });
    await jobs.refresh();
  }

  function startEditing() {
    setSelected(null);
    lastRow.current = null;
    setError(null);
    setEditing(true);
  }

  const newTask = <Button label="新建任务" variant="primary" ref={createButton} onClick={startEditing} />;
  const templateWritable = !busy && !!content.trim() && content.length <= 100000 && !!templateName.trim();

  return (
    <AutomationDialog titleId="schedule-title" title="定时任务" width={1120} bodyRef={body} onClose={close}
      description={editing ? '设置任务目标、执行时间和工作区。' : '到期自动执行，结果进入任务收件箱。'}
      footer={editing && <>
        <Button label="返回列表" isDisabled={busy} onClick={() => { returnToCreate.current = true; setEditing(false); setError(null); }} />
        <Button label="创建计划" variant="primary" type="submit" form="schedule-create-form" isDisabled={busy} />
      </>}>
      {editing ? (
        <form id="schedule-create-form" className="schedule-editor" onSubmit={save} aria-busy={busy}>
          <div className="schedule-editor-goal">
            <h2 ref={formTitle} tabIndex={-1}>任务内容</h2>
            {feedback('form')}
            <TextInput controlClassName="automation-input" label="任务名称" isOptional size="lg" width="100%" value={name} onChange={value => setName(value.slice(0, 120))}
              isDisabled={busy} placeholder="例如：检查依赖升级并修复测试" />
            <TextArea label="任务目标" isRequired size="lg" width="100%" rows={4} value={content} onChange={setContent}
              maxLength={100000} isDisabled={busy} placeholder="说明需要完成的工作。工作区文件可以用相对路径引用。" />
            <TextArea label="验收条件" description="每行一项" size="lg" width="100%" rows={2} value={acceptance} onChange={setAcceptance}
              isDisabled={busy} placeholder="例如：相关测试通过，说明复用方案与改动原因" />
            <details className="automation-disclosure">
              <summary>任务模板{templates.rows?.length ? ` · ${templates.rows.length} 个` : ''}</summary>
              <div className="schedule-editor-fields">
                {feedback('template')}
                {templates.loading && <p className="automation-meta" role="status">正在读取任务模板…</p>}
                {templates.error && <Banner status="error" role="alert" title={`读取模板失败：${templates.error}`}
                  endContent={<Button label="重新加载模板" isDisabled={templates.loading || busy} onClick={() => { void templates.refresh(); }} />} />}
                <ScheduleSelect label="使用模板" value={template?.id ?? ''} onChange={applyTemplate} disabled={busy || templates.loading}
                  options={[[ '', '选择模板' ], ...(templates.rows ?? []).map(row => [row.id, `${row.name} · v${row.version}`] as const)]} />
                <TextInput controlClassName="automation-input" label="模板名称" size="lg" width="100%" value={templateName}
                  onChange={value => setTemplateName(value.slice(0, 120))} isDisabled={busy} />
                <div className="automation-actions">
                  <Button label="另存为模板" isDisabled={!templateWritable} onClick={() => saveTemplate()} />
                  {template && <>
                    <Button label="更新所选模板" isDisabled={!templateWritable} onClick={() => saveTemplate(template)} />
                    <Button label="删除模板" variant="ghost" isDisabled={busy} onClick={() => askToDeleteTemplate(template)} />
                    <Button label="刷新模板" variant="ghost" isDisabled={busy || templates.loading} onClick={() => {
                      setError(null);
                      void templates.refresh();
                    }} />
                  </>}
                </div>
                <p className="automation-meta schedule-template-status" role="status">{templateStatus}</p>
                <p className="automation-meta">模板保存目标、验收与执行策略，更新模板只影响之后创建的计划。</p>
              </div>
            </details>
            <details className="automation-disclosure">
              <summary>约束与技能</summary>
              <div className="schedule-editor-fields">
                <TextArea label="执行约束" size="lg" width="100%" rows={2} value={constraints} onChange={setConstraints} isDisabled={busy} placeholder="每行一项" />
                <TextInput controlClassName="automation-input" label="技能名称" isOptional size="lg" width="100%" value={skill} onChange={setSkill} isDisabled={busy} />
              </div>
            </details>
          </div>
          <section className="schedule-editor-fields schedule-editor-timing" aria-labelledby="schedule-timing-title">
            <h2 id="schedule-timing-title">执行安排</h2>
            <div className="schedule-timezone">
              <ScheduleTimeField label="执行时间" value={when} onChange={value => changeTiming(() => setWhen(value))} disabled={busy} />
              <TextInput controlClassName="automation-input" label="执行时区" size="lg" width="100%" value={zone} onChange={value => changeTiming(() => setZone(value))}
                isDisabled={busy} placeholder="Asia/Shanghai" hasSpellCheck={false} autoCapitalize="none" />
            </div>
            <div className="automation-actions schedule-presets">
              {presets.map(([minutes, label]) => <Button key={minutes} label={label} variant="ghost" isDisabled={busy} onClick={() => {
                try {
                  if (!zone.trim()) throw new Error('请选择时区');
                  changeTiming(() => setWhen(localTime(Date.now() + minutes * 60000, zone.trim())));
                  setError(null);
                } catch { setError({ area: 'time', message: '请填写有效时区后再选择快捷时间' }); }
              }} />)}
            </div>
            <ScheduleSelect label="重复周期" value={repeat} options={periods} disabled={busy} onChange={value => changeTiming(() => setRepeat(value))} />
            {repeat === 'custom' && <TextInput controlClassName="automation-input" label="cron 表达式" description="六段 cron，以秒字段开头" size="lg" width="100%" value={cron}
              onChange={value => changeTiming(() => setCron(value))} isDisabled={busy} placeholder="0 0 9 * * MON-FRI" hasSpellCheck={false} />}
            {repeat !== 'once' && <>
              <ScheduleSelect label="错过策略" value={misfire} options={misfires} onChange={setMisfire} disabled={busy} />
              <p className="automation-meta">每轮新建对话，同一计划的轮次互不重叠。跳过策略允许 60 秒调度延迟。</p>
            </>}
            <div className="schedule-preview">
              <Button label="预览执行时间" isDisabled={busy} onClick={previewTimes} />
              <div aria-live="polite">{times.length > 0 && <ol className="schedule-times">
                {times.map(at => <li key={at}><time dateTime={at}>{stamp(at, zone.trim())}</time> <span>{zone.trim()}</span></li>)}
              </ol>}</div>
            </div>
            {feedback('time')}
            <p className="automation-meta">Pilot 服务在线时执行。一次性任务离线后补执行。夏令时重复时刻只执行第一次；周期中不存在的本地时刻跳过。</p>
          </section>
          <section className="schedule-editor-fields schedule-editor-policy" aria-labelledby="schedule-policy-title">
            <h2 id="schedule-policy-title">工作区与权限</h2>
            <CheckboxInput label="使用独立工作区" value={isolated} onChange={setIsolated} isDisabled={busy}
              description={isolated ? `从${repeat === 'once' ? '创建计划' : '每轮触发'}时已提交的 Git HEAD 开始，未提交和忽略的文件留在原目录。需要已有提交的仓库，可从结果中预览并交回改动。` : '直接在原目录执行，任务改动会影响当前工作区。'} />
            <CheckboxInput label="自主执行，允许所有已提供的工具" value={fullAccess} onChange={setFullAccess} isDisabled={busy}
              description={fullAccess ? '此任务无需逐步审批，权限仅对本任务生效。' : '沿用当前权限规则，任务可能等待你处理审批。'} />
            <CheckboxInput label="执行前必须完成开源调研" value={requireResearch} onChange={setRequireResearch} isDisabled={busy}
              description="需要阅读开源仓库并记录复用依据；检索不可用时暂停任务。本地检查或等待任务通常无需启用。" />
            <p className="automation-meta schedule-policy-note">完成前自动核验证据。最多运行 24 小时，预算耗尽或遇到阻塞时保留结果。</p>
          </section>
        </form>
      ) : (
        <div className={`schedule-view${job ? ' has-detail' : ''}`}>
          <div className="automation-toolbar schedule-toolbar">
            <SegmentedControl ref={filtersRef} label="任务筛选" value={filter} isDisabled={busy} onChange={value => {
              setFilter(value);
              setSelected(null);
              setChangedTime('');
              setError(null);
              lastRow.current = null;
            }}>
              {filters.map(([key, label]) => <SegmentedControlItem key={key} value={key} label={label} />)}
            </SegmentedControl>
            <div className="automation-actions">
              <Button label="刷新" variant="ghost" isDisabled={busy || jobs.loading} onClick={() => { void jobs.refresh(); }} />
              <Button label="周期计划" isDisabled={busy} onClick={() => useStore.getState().setUi({ ...closeState, recurringOpen: true })} />
              {!firstRun && newTask}
            </div>
          </div>
          <p className="automation-meta schedule-count" role="status">
            {jobs.loading ? jobs.rows ? '正在刷新任务…' : '正在读取任务…'
              : jobs.rows && `${jobs.error ? '上次读取到 ' : ''}${visible.length} 个任务`}
          </p>
          {jobs.error && <Banner status="error" role="alert" className="automation-message" title={`读取任务失败：${jobs.error}`}
            description={jobs.rows ? '当前显示上次成功读取的任务。' : '连接恢复后可以重新加载。'}
            endContent={<Button label="重新加载" isDisabled={busy || jobs.loading} onClick={() => { void jobs.refresh(); }} />} />}
          {missingTask && <Banner status="warning" role="status" className="automation-message" title="未找到这轮任务"
            description="可以刷新列表，或选择其他任务查看。" />}
          <div className={`schedule-layout${visible.length ? ' has-rows' : ''}`}>
            <section className="schedule-list" aria-label="定时任务列表" aria-busy={jobs.loading}>
              {!jobs.loading && !jobs.error && visible.length === 0 && <EmptyState isCompact
                title={firstRun ? '安排下一件事' : '当前筛选下没有任务'}
                description={firstRun ? '写好目标与验收条件，再选择执行时间。' : '任务状态会自动更新，也可以切换筛选。'}
                actions={firstRun ? newTask : undefined} />}
              {visible.map(row => <button type="button" className="automation-task-row" key={row.id} disabled={busy}
                aria-pressed={row.id === selected} onClick={event => {
                  lastRow.current = event.currentTarget;
                  listScroll.current = body.current?.scrollTop || 0;
                  setSelected(row.id);
                  setChangedTime('');
                  setError(null);
                }}>
                <span className="schedule-row-name"><strong>{row.name}</strong><small className="automation-meta">{row.message}</small></span>
                <span className="schedule-row-date"><time dateTime={row.runAt}>{stamp(row.runAt, row.timezone)}</time><small className="automation-meta">{row.timezone || 'UTC'}</small></span>
                <Badge variant={row.state === 'NEEDS_ATTENTION' ? 'warning' : 'neutral'} label={labels[row.state] || row.state} />
              </button>)}
            </section>
            {job ? (
              <section className="schedule-task-detail" aria-label="任务详情">
                <Button className="schedule-back-to-list" label="返回任务列表" variant="ghost" isDisabled={busy} onClick={() => {
                  setSelected(null);
                  setError(null);
                  if (!lastRow.current) requestAnimationFrame(() => filtersRef.current?.querySelector<HTMLElement>('[aria-checked="true"]')?.focus());
                }} />
                <h2 ref={detailTitle} tabIndex={-1}>{job.name}</h2>
                <p className="schedule-goal-text">{job.content}</p>
                {feedback('detail')}
                <dl className="schedule-facts">
                  <dt>当前状态</dt><dd>{labels[job.state] || job.state}{job.message && ` · ${job.message}`}</dd>
                  <dt>执行时间</dt><dd><time dateTime={job.runAt}>{stamp(job.runAt, job.timezone)}</time> · {job.timezone || 'UTC'}</dd>
                  <dt>执行策略</dt><dd>{job.requirements?.automation?.requireResearch ? '先调研开源 · ' : ''}自动核验 · {job.requirements?.automation?.fullAccess ? '自主执行' : '沿用权限'}</dd>
                  {job.workspace && <>
                    <dt>{job.workspace.mode === 'ISOLATED' ? '独立工作区' : '原目录执行'}</dt><dd>{job.workspace.path || '默认工作区'}</dd>
                    {job.workspace.commit && <><dt>创建基线</dt><dd><code>{job.workspace.commit}</code></dd></>}
                  </>}
                </dl>
                <div className="automation-actions">
                  {['COMPLETED', 'NEEDS_ATTENTION'].includes(job.state) && <Button label="查看结果" isDisabled={busy}
                    onClick={() => useStore.getState().setUi({ ...closeState, resultsOpen: true, resultTarget: job.id })} />}
                  <Button label="打开执行对话" variant="ghost" isDisabled={busy} onClick={() => void act(async () => {
                    if (await useStore.getState().activate(job.sessionId) === false) throw new Error('无法打开执行对话，请重试');
                    if (alive.current) close();
                  })} />
                  {!ended(job) && <>
                    <Button label={job.state === 'PAUSED' ? '恢复计划' : '暂停任务'} variant="ghost" isDisabled={busy}
                      onClick={() => void act(() => control(job, job.state === 'PAUSED' ? 'resume' : 'pause'))} />
                    <Button label="取消任务" variant="ghost" isDisabled={busy} onClick={() => setConfirm({
                      title: `取消“${job.name}”？`, description: '已启动的步骤会在安全边界停止，执行记录将保留。',
                      label: '确认取消任务', action: () => void act(() => control(job, 'cancel'))
                    })} />
                  </>}
                </div>
                {['SCHEDULED', 'WAITING', 'PAUSED'].includes(job.state) && <form className="schedule-reschedule" onSubmit={event => {
                  event.preventDefault();
                  void act(() => control(job, 'reschedule', changedTime));
                }}>
                  <ScheduleTimeField label="修改执行时间" description={`时区 ${job.timezone || 'UTC'}`} value={changedTime} onChange={setChangedTime} disabled={busy} />
                  <Button label="保存时间" type="submit" isDisabled={busy || !changedTime} />
                </form>}
                <h3>执行记录</h3>
                {job.history?.length ? <ol className="schedule-history-log">
                  {job.history.map(item => <li key={item.runId}><time dateTime={item.startedAt}>{stamp(item.startedAt, job.timezone)}</time><span>{labels[item.status] || item.status}</span></li>)}
                </ol> : <p className="automation-meta">尚未开始执行。</p>}
              </section>
            ) : visible.length > 0 && (
              <section className="schedule-detail-placeholder">
                <EmptyState title="选择任务查看执行详情" description="可查看执行时间、工作区和运行记录。" isCompact />
              </section>
            )}
          </div>
        </div>
      )}
      {confirm && <AlertDialog className="automation-confirm" isOpen title={confirm.title} description={confirm.description}
        cancelLabel="返回" actionLabel={confirm.label} onOpenChange={open => { if (!open) setConfirm(null); }}
        onAction={() => { setConfirm(null); confirm.action(); }} />}
    </AutomationDialog>
  );
}
