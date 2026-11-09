import type * as S from '../types/server.js';
import { errorInfo } from '../lib/errors.js';
import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { Dialog } from '@astryxdesign/core/Dialog';
import { DropdownMenu } from '@astryxdesign/core/DropdownMenu';
import { SegmentedControl, SegmentedControlItem } from '@astryxdesign/core/SegmentedControl';
import { Banner } from '@astryxdesign/core/Banner';
import { EmptyState } from '@astryxdesign/core/EmptyState';
import { Badge } from '@astryxdesign/core/Badge';
import { supportsAnchoredLayers } from '../lib/theme.js';
import { api } from '../api.js';
import { useStore } from '../store.js';
import { ResearchRecords } from './ResearchPanel.jsx';
import { verificationLabel, verificationHint } from '../lib/verification.js';
import { Icon } from './Icon.jsx';
import './results.css';

const labels: Record<string, string> = { COMPLETED: '执行结束', NEEDS_ATTENTION: '需要处理', AWAITING_REVIEW: '待验收' };
function count(items: S.ResultInboxServiceItem[]) { useStore.getState().setUi({ resultUnread: items.filter(i => !i.read && !i.handled).length }); }
export default function ResultInbox() {
  const open = useStore(s => s.ui.resultsOpen);
  const target = useStore(s => s.ui.resultTarget);
  useEffect(() => {
    if (open) return;
    let alive = true;
    const poll = async () => { try { const data = await api.results(); if (alive && Array.isArray(data?.items)) count(data!.items); } catch { /* 面板显示读取错误 */ } };
    poll(); const timer = setInterval(poll, 15000); return () => { alive = false; clearInterval(timer); };
  }, [open]);
  return open ? <InboxDialog target={target} /> : null;
}
function InboxDialog({ target }: { target?: string | null }) {
  const ref = useRef<HTMLDialogElement>(null), alive = useRef(true), request = useRef(0), listRequest = useRef(0);
  const listInFlight = useRef(false);
  const bodyRef = useRef<HTMLDivElement>(null), detailRef = useRef<HTMLElement>(null);
  const rowRefs = useRef(new Map<string, HTMLButtonElement>()), lastRow = useRef<string | null>(null), listScroll = useRef(0);
  const [items, setItems] = useState<S.ResultInboxServiceItem[]>([]), [truncated, setTruncated] = useState(false), [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState(target || null), [detail, setDetail] = useState<S.ResultInboxServiceDetail | null>(null), [detailLoading, setDetailLoading] = useState(false);
  const [reload, setReload] = useState(0);
  const [listError, setListError] = useState(''), [hasLoaded, setHasLoaded] = useState(false);
  const [filter, setFilter] = useState('pending'), [error, setError] = useState(''), [busy, setBusy] = useState(false), [preview, setPreview] = useState<S.GitWorktreesPreview | null>(null), [diff, setDiff] = useState<string | null>(null);
  const close = () => useStore.getState().setUi({ resultsOpen: false, resultTarget: null });
  const refresh = useCallback(async (background = false) => {
    // 慢连接下不让每轮轮询使上一轮失效；主动读取仍可取代旧响应。
    if (background && listInFlight.current) return;
    listInFlight.current = true;
    const token = ++listRequest.current;
    if (!background) setLoading(true);
    try {
      const data = required(await api.results());
      if (alive.current && token === listRequest.current) {
        const rows = data.items || []; setItems(rows); setTruncated(!!data.truncated); count(rows);
        setHasLoaded(true); setListError('');
      }
    } catch (e) {
      if (alive.current && token === listRequest.current) setListError(`读取结果失败：${errorInfo(e).message}`);
    } finally { if (alive.current && token === listRequest.current) { listInFlight.current = false; setLoading(false); } }
  }, []);
  useEffect(() => {
    alive.current = true; refresh();
    const timer = setInterval(() => { void refresh(true); }, 15000);
    return () => { alive.current = false; request.current++; listRequest.current++; clearInterval(timer); };
  }, [refresh]);
  useLayoutEffect(() => {
    if (selected) {
      detailRef.current?.focus({ preventScroll: true });
      if (bodyRef.current && window.matchMedia('(max-width: 900px)').matches) bodyRef.current.scrollTop = 0;
    } else if (lastRow.current) {
      const destination = rowRefs.current.get(lastRow.current)
        || ref.current?.querySelector<HTMLButtonElement>('[role="radio"][aria-checked="true"]');
      destination?.focus({ preventScroll: true });
      if (bodyRef.current) bodyRef.current.scrollTop = listScroll.current;
      lastRow.current = null;
    }
  }, [selected]);
  useEffect(() => {
    const token = ++request.current; setDetail(null); setPreview(null); setDiff(null); setError(''); setDetailLoading(!!selected);
    if (selected) (async () => {
      try {
        const data = required(await api.result(selected)); if (!alive.current || token !== request.current) return;
        if (!data.item.read) data.item = required(await api.resultReceipt(selected, { revision: data.item.revision, action: 'read' }));
        if (!alive.current || token !== request.current) return;
        setDetail(data); await refresh();
      } catch (e) { if (alive.current && token === request.current) setError(errorInfo(e).message || '操作失败'); }
      finally { if (alive.current && token === request.current) setDetailLoading(false); }
    })();
  }, [selected, refresh, reload]);
  async function act(fn: (current: () => boolean) => Promise<void>) {
    const token = request.current; setBusy(true); setError('');
    try { await fn(() => alive.current && token === request.current); }
    catch (e) { if (alive.current && token === request.current) setError(errorInfo(e).message || '操作失败'); }
    finally { if (alive.current) setBusy(false); }
  }
  const item = detail?.item;
  const visible = items.filter(i => filter === 'all' || (filter === 'pending' ? !i.handled : filter === 'unread' ? !i.read && !i.handled : i.handled));
  async function receipt(action: string) {
    if (!item) return;
    await act(async current => { const updated = required(await api.resultReceipt(item.id, { revision: item.revision, action })); if (current()) { setDetail(d => d && ({ ...d, item: updated })); await refresh(); } });
  }
  const openNotifications = () => { close(); useStore.getState().setUi({ notificationsOpen: true }); };
  return <Dialog ref={ref} isOpen onOpenChange={next => { if (!next) close(); }} purpose="form" width={1120} maxHeight="calc(100dvh - 48px)" padding={0} className="results-dialog" aria-labelledby="results-title" onKeyDown={e => e.stopPropagation()}>
    <header className="results-header">
      <div><h1 id="results-title">任务收件箱</h1><p>核对执行结果与验证依据，再标记已处理。</p></div>
      <Button label="关闭任务收件箱" icon={<Icon name="close" />} isIconOnly variant="ghost" className="results-close" onClick={close} />
    </header>
    <div ref={bodyRef} className="results-body">
    <div className="results-toolbar">
      <SegmentedControl label="结果筛选" value={filter} isDisabled={busy} onChange={value => { lastRow.current = null; setFilter(value); setSelected(null); }}>
        {[["pending", "待处理"], ["unread", "未读"], ["all", "全部"], ["handled", "已处理"]].map(([value, label]) => <SegmentedControlItem key={value} value={value} label={label} />)}
      </SegmentedControl>
      <div className="results-actions">
        {supportsAnchoredLayers() ? <DropdownMenu button={{ label: '结果操作', variant: 'secondary', isDisabled: busy || loading }} menuWidth={180} alignment="end" items={[
          { label: '刷新结果', onClick: () => { void refresh(); } },
          { label: '通知与投递', onClick: openNotifications }
        ]} /> : <><Button label="刷新结果" isDisabled={busy || loading} onClick={() => { void refresh(); }} /><Button label="通知与投递" onClick={openNotifications} /></>}
      </div>
    </div>
    <p className="results-count" role="status">{loading ? (hasLoaded ? '正在刷新结果…' : '正在读取结果…') : hasLoaded ? `${listError ? '上次读取到 ' : ''}${visible.length} 条结果` : '结果尚未读取成功'}</p>
    {listError && <Banner className="results-error" status="error" role="alert" title={listError} description={hasLoaded ? '当前显示上次成功读取的结果。' : '连接恢复后重新加载。'} endContent={<Button label="重新加载结果" isDisabled={busy || loading} onClick={() => { void refresh(); }} />} />}
    {error && <Banner className="results-error" status="error" role="alert" title={error} endContent={<Button label="重新加载详情" isDisabled={busy} onClick={() => { setError(''); setReload(v => v + 1); }} />} />}
    {truncated && <p className="results-note">仅展示最近 500 条结果；更早记录可在原对话查看。</p>}
    <div className={`results-layout${visible.length ? ' has-results' : ''}${selected ? ' has-detail' : ''}`}>
      <section className="results-list" aria-label="结果列表" aria-busy={loading}>
        {hasLoaded && !loading && !listError && !visible.length && <EmptyState title={`暂无${filter === 'handled' ? '已处理' : filter === 'unread' ? '未读' : filter === 'pending' ? '待处理' : ''}结果`} description={filter === 'all' ? '任务完成或需要处理时，结果会显示在这里。' : '可以切换到全部查看其他结果。'} isCompact />}
        {visible.map(row => <button key={row.id} ref={node => { if (node) rowRefs.current.set(row.id, node); else rowRefs.current.delete(row.id); }} className={`result-row${row.id === selected ? ' is-selected' : ''}`} disabled={busy} aria-pressed={row.id === selected} onClick={() => { lastRow.current = row.id; listScroll.current = bodyRef.current?.scrollTop || 0; setSelected(row.id); }}>
          <span className="result-row-title"><strong>{!row.read && <span className="result-unread" aria-label="未读" />}{row.title || '未命名任务'}</strong><small>{row.summary || '暂无摘要'}</small><small>{row.source === 'SCHEDULE' ? '定时任务' : '对话任务'} · {new Date(row.at).toLocaleString()}</small></span><Badge label={row.handled ? '已处理' : labels[row.state] || row.state} variant={row.state === 'NEEDS_ATTENTION' && !row.handled ? 'warning' : 'neutral'} />
        </button>)}
      </section>
      {selected && <section ref={detailRef} className="result-detail" tabIndex={-1} aria-label="结果详情" aria-busy={detailLoading}>
        <Button className="results-back" label="返回结果列表" variant="ghost" isDisabled={busy} onClick={() => { lastRow.current = selected; setSelected(null); }} />
        {detailLoading && <p role="status">正在读取详情…</p>}
        {item && detail && <><h2>{item.title || '未命名任务'}</h2><p>{labels[item.state] || item.state} · {item.handled ? '已处理' : '待处理'}</p><p className="result-summary">{item.summary || '暂无摘要，请打开执行对话查看。'}</p>
          <nav className="result-sections" aria-label="结果内容导航">{[['verification', '验收'], ['changes', '改动'], ...(item.workspace ? [['workspace', '工作区']] : []), ['research', '开源参考']].map(([key, text]) => <Button key={key} label={text} variant="ghost" onClick={() => ref.current?.querySelector(`#result-${key}`)?.scrollIntoView({ block: 'start' })} />)}</nav>
          <h3 id="result-verification">验收与验证</h3><p data-verification-phase={detail.verification}>{verificationLabel(detail.verification)}</p>
          {verificationHint(detail.verification) && <p className="results-note">{verificationHint(detail.verification)}</p>}
          {detail.verificationDetail && <p className="results-note">{detail.verificationDetail}</p>}
          {!!detail.acceptance?.length && <ul>{detail.acceptance.map((a, i) => <li key={i}>{a.checked ? '已满足' : '待核对'} · {a.text}{a.evidence && <small>{a.evidence}</small>}</li>)}</ul>}
          <ResultList title="记录的验证项" rows={item.checks} empty="没有记录验证项。" />
          {!!item.openSteps.length && <ResultList title="未完成事项" rows={item.openSteps} />}
          <div className="results-actions result-receipt-actions">
            <Button label={item.handled ? '重新打开' : '标记已处理'} variant={preview ? 'secondary' : 'primary'} isDisabled={busy} onClick={() => receipt(item.handled ? 'reopen' : 'handle')} />
            <Button label={item.read ? '标记未读' : '标记已读'} isDisabled={busy} onClick={() => receipt(item.read ? 'unread' : 'read')} />
            <Button label="打开执行对话" isDisabled={busy} onClick={() => act(async () => { await useStore.getState().activate(item.sessionId); close(); })} />
          </div>
          <h3 id="result-changes">文件改动</h3><ResultList rows={item.changedFiles} empty="没有记录文件改动。" />
          {item.runId && <Button isDisabled={busy} onClick={() => act(async current => { const data = required(await api.runChanges(item.runId)); if (current()) setDiff((data.files || []).map(f => f.diff || '').join('\n') || '没有可用的运行差异。'); })} label="查看本次运行差异" />}
          {diff !== null && <pre className="result-diff" tabIndex={0} aria-label="运行差异">{diff}</pre>}
          {item.workspace && <><h3 id="result-workspace">执行工作区</h3><dl><dt>{item.workspace.mode === 'ISOLATED' ? '独立工作区' : '原目录执行'}</dt><dd>{item.workspace.path || '默认工作区'}</dd>{item.workspace.mode === 'ISOLATED' && <><dt>交回目录</dt><dd>{item.workspace.base}</dd><dt>创建基线</dt><dd>{item.workspace.commit}</dd></>}</dl>
            {item.workspace.handedOffAt ? <p role="status">已于 {new Date(item.workspace.handedOffAt).toLocaleString()} 交回；执行目录仍保留。</p> : item.workspace.mode === 'ISOLATED' && <Button isDisabled={busy} onClick={() => act(async current => { setPreview(null); const data = await api.scheduleHandoffPreview(item.scheduleId); if (current()) setPreview(data); })} label="预览交回改动" />}
          </>}
          {preview && <div className="result-handoff"><h3>交回预览 · {preview.files.length} 个文件</h3><p>包含从创建基线开始的全部改动。交回到：{preview.base}</p><ResultList rows={preview.files} />{preview.conflict && <p role="alert">{preview.conflict}</p>}{preview.truncated && <p>差异超过展示上限；请在独立工作区查看完整内容。</p>}<pre className="result-diff" tabIndex={0} aria-label="交回差异">{preview.diff || '没有需要交回的改动。'}</pre><Button variant="primary" isDisabled={busy || !!preview.conflict || !preview.files.length} onClick={() => act(async current => { const revision = preview.revision; setPreview(null); await api.scheduleHandoff(item.scheduleId, { revision }); if (current()) { setDetail(await api.result(item.id)); await refresh(); } })} label="交回原目录" /></div>}
          <h3 id="result-research">开源参考</h3><ResearchRecords records={detail.research} />
        </>}
      </section>}
      {!selected && visible.length > 0 && <section className="result-detail result-placeholder"><EmptyState title="选择任务查看结果" description="验证记录、文件改动与工作区交回都在详情中。" isCompact /></section>}
    </div>
    </div>
  </Dialog>;
}
function ResultList({ title, rows = [], empty }: { title?: string; rows?: string[]; empty?: string }) { return <>{title && <h4>{title}</h4>}{rows.length ? <ul>{rows.map((row, i) => <li key={i}>{row}</li>)}</ul> : empty ? <p>{empty}</p> : null}</>; }

function required<T>(value: T | null): T { if (value === null) throw new Error('服务端返回空结果，请重新加载'); return value; }
