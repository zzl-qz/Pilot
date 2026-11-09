import { useEffect, useRef, useState } from 'react';
import { AlertDialog } from '@astryxdesign/core/AlertDialog';
import { Badge } from '@astryxdesign/core/Badge';
import { Banner } from '@astryxdesign/core/Banner';
import { Button } from '@astryxdesign/core/Button';
import { EmptyState } from '@astryxdesign/core/EmptyState';
import { api } from '../api.js';
import { errorInfo } from '../lib/errors.js';
import { useStore } from '../store.js';
import type { RecurringScheduleServiceSeries } from '../types/server.js';
import { AutomationDialog } from './AutomationDialog.jsx';
import { useAutomationList } from './useAutomationList.js';

const labels: Record<string, string> = { ACTIVE: '已启用', PAUSED: '已暂停', CANCELLED: '已取消', ERROR: '需要处理' };

function nextTime(row: RecurringScheduleServiceSeries) {
  if (!row.nextAt) return '—';
  try { return new Date(row.nextAt).toLocaleString('zh-CN', { timeZone: row.timezone || 'UTC', hourCycle: 'h23' }); }
  catch { return row.nextAt; }
}

export default function RecurringPlans() {
  const open = useStore(s => s.ui.recurringOpen);
  return open ? <RecurringDialog /> : null;
}

function RecurringDialog() {
  const plans = useAutomationList(api.recurring, 10000);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<{ id: string; message: string } | null>(null);
  const [confirm, setConfirm] = useState<RecurringScheduleServiceSeries | null>(null);
  const alive = useRef(true);
  const pending = useRef(false);
  const close = () => useStore.getState().setUi({ recurringOpen: false });

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  function openSchedule(target?: string) {
    useStore.getState().setUi({
      recurringOpen: false, schedulesOpen: true, scheduleDraft: target ? null : '',
      scheduleRepeat: !target, scheduleTarget: target ?? null
    });
  }

  async function control(row: RecurringScheduleServiceSeries, action: string) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setFailure(null);
    try {
      const updated = await api.controlRecurring(row.id, { version: row.version, action });
      if (!alive.current) return;
      if (updated) plans.setRows(rows => rows?.map(item => item.id === row.id ? updated : item) ?? [updated]);
      if (action === 'cancel') document.getElementById(`recurring-plan-${row.id}`)?.focus({ preventScroll: true });
      await plans.refresh();
    } catch (err) {
      if (alive.current) setFailure({ id: row.id, message: errorInfo(err).message || '操作失败，请重试' });
    } finally {
      pending.current = false;
      if (alive.current) setBusy(false);
    }
  }

  return (
    <AutomationDialog titleId="recurring-title" title="周期计划"
      description="每轮独立执行，结果进入任务收件箱。" onClose={close}>
      <div className="automation-toolbar">
        <h2 id="recurring-list-title">计划列表</h2>
        <div className="automation-actions">
          <Button label="刷新周期计划" variant="ghost" isDisabled={busy || plans.loading} onClick={() => { void plans.refresh(); }} />
          <Button label="新建周期计划" variant="primary" isDisabled={busy} onClick={() => openSchedule()} />
        </div>
      </div>
      <p className="automation-meta automation-section-note">暂停或取消只影响未来轮次。正在执行的任务可以单独控制。</p>
      {plans.loading && <p className="automation-meta" role="status">{plans.rows ? '正在刷新周期计划…' : '正在读取周期计划…'}</p>}
      {plans.error && <Banner className="automation-message" status="error" role="alert" title={`读取周期计划失败：${plans.error}`}
        description={plans.rows ? '当前显示上次成功读取的计划。' : undefined}
        endContent={<Button label="重新加载" isDisabled={busy || plans.loading} onClick={() => { void plans.refresh(); }} />} />}
      <section aria-labelledby="recurring-list-title" aria-busy={plans.loading}>
        {plans.rows?.length === 0 && !plans.loading && !plans.error && <EmptyState title="暂无周期计划" isCompact
          description="可以将重复的检查与维护安排为每天、工作日或自定义周期。" />}
        {plans.rows?.map(row => (
          <article className="automation-record recurring-plan" key={row.id}>
            <div className="automation-record-content">
              <div className="automation-record-heading">
                <h3 id={`recurring-plan-${row.id}`} tabIndex={-1}>{row.name}</h3>
                <Badge label={labels[row.state] || row.state} variant={row.state === 'ERROR' ? 'warning' : 'neutral'} />
              </div>
              {row.message && <p className="automation-meta">{row.message}</p>}
              <dl className="recurring-facts">
                <dt>{row.state === 'ACTIVE' ? '下次计划时间' : '原计划时间'}</dt>
                <dd><time dateTime={row.nextAt}>{nextTime(row)}</time> · {row.timezone || 'UTC'}</dd>
                <dt>周期与错过策略</dt>
                <dd><code>{row.cron}</code> <span>· {row.misfire === 'SKIP' ? '跳过错过轮次' : '合并补执行一次'}</span> <span>· 已创建 {row.occurrences} 轮</span></dd>
                {row.sourceWorkspace && <><dt>来源工作区</dt><dd>{row.sourceWorkspace}</dd></>}
              </dl>
            </div>
            <div className="automation-actions">
              {row.activeId && <Button label="查看最近轮次" isDisabled={busy} onClick={() => openSchedule(row.activeId)} />}
              {row.state !== 'CANCELLED' && <>
                <Button label={row.state === 'ACTIVE' ? '暂停周期' : '恢复周期'} variant="ghost" isDisabled={busy}
                  onClick={() => { void control(row, row.state === 'ACTIVE' ? 'pause' : 'resume'); }} />
                <Button label="取消周期" variant="ghost" isDisabled={busy} onClick={() => setConfirm(row)} />
              </>}
            </div>
            {failure?.id === row.id && <Banner className="automation-record-feedback" status="error" role="alert" title={failure.message}
              endContent={<Button label="刷新并核对计划" isDisabled={busy || plans.loading} onClick={() => { setFailure(null); void plans.refresh(); }} />} />}
          </article>
        ))}
      </section>
      {confirm && <AlertDialog className="automation-confirm" isOpen title={`取消“${confirm.name}”？`}
        description="取消后将停止创建新轮次。已启动的任务和历史结果会保留。"
        cancelLabel="返回" actionLabel="确认取消周期" onOpenChange={open => { if (!open) setConfirm(null); }}
        onAction={() => { setConfirm(null); void control(confirm, 'cancel'); }} />}
    </AutomationDialog>
  );
}
