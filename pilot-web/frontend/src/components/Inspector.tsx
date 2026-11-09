import type { TimelineItem } from '../types/thread.js';
import type { ChangeFile, ChangeReport, DiffFile } from '../types/core.js';
import type { CommandTask } from '../types/store.js';
import type { DesktopBridge, DesktopBrowserState, BrowserRecent } from '../types/desktop.js';
import { errorInfo } from '../lib/errors.js';
interface ReviewReport extends ChangeReport { diff?: string }
import { reviewQuote } from '../lib/interaction.js';
import React, { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Badge } from '@astryxdesign/core/Badge';
import { Banner } from '@astryxdesign/core/Banner';
import { Button } from '@astryxdesign/core/Button';
import { EmptyState } from '@astryxdesign/core/EmptyState';
import { IconButton } from '@astryxdesign/core/IconButton';
import { SegmentedControl, SegmentedControlItem } from '@astryxdesign/core/SegmentedControl';
import { TabList, Tab } from '@astryxdesign/core/TabList';
import { TextArea } from '@astryxdesign/core/TextArea';
import { TextInput } from '@astryxdesign/core/TextInput';
import { ToggleButton } from '@astryxdesign/core/ToggleButton';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { Icon } from './Icon.jsx';
import { WorkspaceMenu } from './WorkspaceMenu.jsx';
import { ReviewActions } from './ReviewActions.jsx';
import { writeClipboard } from '../lib/clipboard.js';
import { normalizePath, parseDiff } from '../lib/diff.js';
import { sortChangeFiles, splitPath, statusKind } from '../lib/changes.js';
import { ErrorBoundary } from './ErrorBoundary.jsx';
import { isCommandActive, commandStatusLabel, stripAnsi } from '../lib/command-tasks.js';
import { userPageUrl } from '../lib/browser-mirror.js';
import { formatTime } from '../thread-utils.js';
import { SideChatPanel } from './SideChat.jsx';
import { MarkdownBody } from './MarkdownBody.jsx';
import FileWorkbench from './FileWorkbench.js';

import ResearchPanel from './ResearchPanel.jsx';
import WorkProductsPanel from './WorkProductsPanel.jsx';

import './inspector.css';

const TABS = [
  { id: 'changes', label: '变更' },
  { id: 'preview', label: '预览' },
  { id: 'commands', label: '命令' },
  { id: 'products', label: '成果' },
  { id: 'chat', label: '侧聊' },
];
const MORE_TABS = [
  { id: 'files', label: '审阅文件' },
  { id: 'browser', label: '浏览器' },
  { id: 'activity', label: '活动' },
  { id: 'research', label: '开源参考' },
  { id: 'capabilities', label: '能力' },
];

/** 选择器默认值必须稳定，否则每次返回新数组会让 zustand 反复触发重渲染。 */
const NO_TASKS: never[] = [];

export function Inspector() {
  const tab = useStore(state => state.ui.inspectorTab);
  const open = useStore(state => state.ui.inspectorOpen);
  const [moreOpen, setMoreOpen] = useState(false);

  if (!open) return null;
  const primary = TABS.find(item => item.id === tab);
  const current = primary || MORE_TABS.find(item => item.id === tab);
  const switchTab = (id: string) => useStore.getState().setUi({ inspectorTab: id, reviewExpanded: false });
  const close = () => {
    setMoreOpen(false);
    useStore.getState().setUi({ inspectorOpen: false, reviewExpanded: false });
    document.getElementById('toggle-activity')?.focus();
  };

  return (
    <aside id="activity" className={`activity inspector workspace-inspector is-open${tab === 'products' ? ' products-inspector' : ''}`} aria-label="工作区面板">
      <header className="inspector-panel-head">
        <h2 id="inspector-title">{current?.label || '审阅'}</h2>
        <IconButton label="关闭工作区面板" icon={<Icon name="close" />} variant="ghost" onClick={close} />
      </header>
      <div className="inspector-nav">
        <TabList id="panel-tabs" className="inspector-tabs" role="tablist" aria-label="常用面板" value={tab} onChange={switchTab} size="md">
          {TABS.map(item => <Tab
            key={item.id}
            id={`inspector-tab-${item.id}`}
            panelId={`inspector-panel-${item.id}`}
            value={item.id}
            label={item.label}
            aria-label={item.label}
            endContent={item.id === 'commands' ? <CommandDot /> : undefined}
          />)}
        </TabList>
        <WorkspaceMenu
          button={{ label: '更多工具', icon: <Icon name="more" />, isIconOnly: true, variant: 'ghost', className: 'inspector-more-trigger' }}
          menuWidth={176}
          onOpenChange={setMoreOpen}
          items={MORE_TABS.map(item => ({
            id: item.id,
            label: item.label,
            icon: <RailIcon id={item.id} />,
            endContent: item.id === 'browser' ? <BrowserDot /> : undefined,
            onClick: () => switchTab(item.id)
          }))}
        />
      </div>
      {TABS.filter(item => item.id !== tab).map(item => <div key={item.id} id={`inspector-panel-${item.id}`} role="tabpanel" aria-labelledby={`inspector-tab-${item.id}`} hidden />)}
      {tab === 'activity' && <div className="inspector-head"><Filters /></div>}
      <div className="inspector-body" id={`inspector-panel-${tab}`} role={primary ? 'tabpanel' : 'region'} aria-labelledby={primary ? `inspector-tab-${tab}` : 'inspector-title'}>
        <ErrorBoundary key={tab} name={current?.label || '面板'} onReset={() => switchTab('activity')}>
          {tab === 'changes' && <ChangesPanel />}
          {tab === 'preview' && <PreviewPanel />}
          {tab === 'commands' && <CommandsPanel />}
          {tab === 'products' && <WorkProductsPanel />}
          {tab === 'chat' && <SideChatPanel />}
          {tab === 'files' && <FileWorkbench />}
          {tab === 'browser' && <BrowserPanel coveredByTools={moreOpen} />}
          {tab === 'activity' && <ActivityPanel />}
          {tab === 'research' && <ResearchPanel />}
          {tab === 'capabilities' && <CapabilitiesPanel />}
        </ErrorBoundary>
      </div>
    </aside>
  );
}

function RailIcon({ id }: { id: string }) {
  const common = { viewBox: '0 0 16 16', 'aria-hidden': true };
  if (id === 'files') return <Icon name="file" />;
  if (id === 'research') return <svg {...common}><path d="M8 4C6 2.5 3 3 2 3v9c2-.5 4-.5 6 1 2-1.5 4-1.5 6-1V3c-2-.5-4-.5-6 1Zm0 0v9" fill="none" stroke="currentColor" strokeWidth="1.2" /></svg>;
  if (id === 'capabilities') return <svg {...common}><circle cx="8" cy="8" r="2" fill="none" stroke="currentColor" strokeWidth="1.3" /><path d="M8 2.8v1.6M8 11.6v1.6M2.8 8h1.6M11.6 8h1.6" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" /></svg>;
  if (id === 'browser') return <svg {...common}><circle cx="8" cy="8" r="5" fill="none" stroke="currentColor" strokeWidth="1.3" /><path d="M3 8h10M8 3c1.6 1.8 1.6 8.2 0 10M8 3c-1.6 1.8-1.6 8.2 0 10" fill="none" stroke="currentColor" strokeWidth="1.1" /></svg>;
  return <svg {...common}><path d="M3.5 4h9M3.5 8h9M3.5 12h6" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" /></svg>;
}

/** 有命令还在跑时页签上点一下：不用切过去也知道后台没空着。 */
function CommandDot() {
  const tasks = useStore((state) => state.commandTasks || NO_TASKS);
  return tasks.some((task) => isCommandActive(task)) ? <span className="inspector-tab-dot" aria-label="有命令正在运行" /> : null;
}

function BrowserDot() {
  const dot = useStore((state) => state.ui.browserDot);
  const live = useStore((state) => state.ui.mirror.mode === 'live');
  return (dot || live) ? <span className="inspector-tab-dot" aria-label="浏览器正在使用" /> : null;
}

function Filters() {
  const current = useStore((state) => state.ui.filters);
  const timeline = useStore((state) => state.timeline);
  const options = [['all', '全部'], ['tool', '工具'], ['approval', '审批'], ['run', '运行']];

  // 每个筛选后面跟条数：不用切来切去试，就知道哪一类有事发生
  const counts = useMemo(() => {
    const map: Record<string, number> = { all: timeline.length, tool: 0, approval: 0, run: 0 };
    timeline.forEach((item) => {
      if (map[item.kind] != null) {
        map[item.kind] += 1;
      }
    });
    return map;
  }, [timeline]);

  return (
    <SegmentedControl id="filters" className="activity-filters" label="事件过滤" value={current} size="md" onChange={value => useStore.getState().setFilters(value)}>
      {options.map(([value, label]) => <SegmentedControlItem key={value} value={value} label={`${label} ${counts[value]}`} />)}
    </SegmentedControl>
  );
}

/* ---------- 活动时间线 ---------- */

function ActivityPanel() {
  const timeline = useStore((state) => state.timeline);
  const filter = useStore((state) => state.ui.filters);
  const [query, setQuery] = useState('');
  const [openKeys, setOpenKeys] = useState(() => new Set<string>());
  const listRef = useRef<HTMLOListElement>(null);
  const stickRef = useRef(true);

  const byKind = filter === 'all' ? timeline : timeline.filter((item) => item.kind === filter);
  const keyword = query.trim().toLowerCase();
  const items = keyword ? byKind.filter((item) => matchesActivity(item, keyword)) : byKind;

  const toggle = useCallback((key: string) => setOpenKeys((current) => {
    const next = new Set(current);
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    return next;
  }), []);

  // 新事件只在用户本来就在底部时才跟随，向上翻记录时不要被拽回去
  const itemCount = items.length;
  useEffect(() => {
    const list = listRef.current;
    if (list && stickRef.current) {
      list.scrollTop = list.scrollHeight;
    }
  }, [itemCount]);

  if (!timeline.length) {
    return <EmptyState id="timeline-empty" className="inspector-empty" title="还没有活动" description="发送消息后，工具调用和审批会记录在这里。" isCompact />;
  }

  /*
   * run.usage 一轮会刷几十条，连续出现时折成一行「×N」，
   * 否则时间线里全是用量事件，工具调用反而被冲走。
   */
  const groups: { type: string; count: number; firstSequence: number; lastSequence: number; item: TimelineItem }[] = [];
  for (const item of items) {
    const last = groups[groups.length - 1];
    if (last && item.type === 'run.usage' && last.type === 'run.usage') {
      last.count += 1;
      last.lastSequence = item.sequence;
      continue;
    }
    groups.push({ type: item.type, count: 1, firstSequence: item.sequence, lastSequence: item.sequence, item });
  }

  return (
    <div className="activity-panel">
      <div className="activity-search">
        <TextInput type="search" size="md" label="搜索活动" isLabelHidden placeholder="搜索事件类型、标题或详情"
          containerClassName="inspector-grow" value={query} onChange={setQuery} />
        <span className="activity-search-count">
          {keyword ? `${items.length} / ${byKind.length}` : `${byKind.length} 条`}
        </span>
      </div>
      {!items.length && <EmptyState className="inspector-empty" title="没有匹配的活动。" isCompact />}
      <ol
        id="timeline"
        className="timeline"
        ref={listRef}
        onScroll={(event) => {
          const el = event.currentTarget;
          stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
        }}
      >
        {groups.map((group) => (group.count > 1
          ? (
            <li key={group.item.key} className="event event-group">
              <div className="event-head">
                <span className="event-seq">#{group.firstSequence}–#{group.lastSequence}</span>
                <span className="event-type">{group.type}</span>
                <span className="event-status">×{group.count}</span>
              </div>
            </li>
          )
          : (
            <ActivityItem
              key={group.item.key}
              item={group.item}
              open={openKeys.has(group.item.key)}
              onToggle={toggle}
            />
          )))}
      </ol>
    </div>
  );
}

function matchesActivity(item: TimelineItem, keyword: string) {
  return [item.type, item.title, item.detail, item.foot, item.status]
    .some((value) => String(value || '').toLowerCase().includes(keyword));
}

/**
 * 每条活动单独 memo：流式增量只改最后一张卡，其余几百条不该跟着每帧重渲染。
 * 点卡片展开完整内容（默认两行截断），长脚本才不需要整屏滚动。
 */
const ActivityItem = memo(function ActivityItem({ item, open, onToggle }: { item: TimelineItem; open: boolean; onToggle: (key: string) => void }) {
  return (
    <li
      className={`event tone-${item.tone}${open ? ' is-open' : ''}`}
      data-kind={item.kind}
    >
      <div className="event-head">
        <span className="event-seq">#{item.sequence}</span>
        <span className="event-type">{item.type}</span>
        <span className="event-status">{item.status}</span>
        <span className="event-time">{formatTime(item.timestamp)}</span>
      </div>
      {item.title && <p className="event-title">{item.title}</p>}
      {item.detail && <p className="event-detail">{item.detail}</p>}
      {item.foot && <p className="event-foot">{item.foot}</p>}
      {(item.detail || item.foot) && <ToggleButton label={open ? '收起详情' : '展开详情'} size="sm"
        isPressed={open} onPressedChange={() => onToggle(item.key)} />}
      {item.actions && item.callId && <ApprovalInline callId={item.callId} />}
    </li>
  );
});

function ApprovalInline({ callId }: { callId: string }) {
  const pending = useStore((state) => state.pendingApprovals[callId]);
  if (!pending) {
    return null;
  }
  return (
    <div className="approval-actions">
      <Button label="批准" variant="primary" onClick={() => useStore.getState().resolveApproval(callId, true)} />
      <Button label="拒绝" onClick={() => useStore.getState().resolveApproval(callId, false)} />
    </div>
  );
}

/* ---------- 变更面板 ---------- */

function ChangesPanel() {
  const workspaceReport = useStore((state) => state.changeReport);
  const expanded = useStore(state => state.ui.reviewExpanded);
  const lastRunId = useStore(state => state.lastRunId || state.runId);
  const [scope, setScope] = useState('workspace');
  const [runReport, setRunReport] = useState<ReviewReport | null>(null);
  const [runError, setRunError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const report = scope === 'run' ? runReport : workspaceReport;
  const state = useStore((state) => state.changesState);
  const sessionId = useStore((state) => state.sessionId);
  const view = useStore((state) => state.ui.changeView);
  const sessionEpoch = useStore(state => state.sessionEpoch);
  const workspace = useStore(state => state.workspace);
  const [showUntracked, setShowUntracked] = useState(false);
  const [query, setQuery] = useState('');
  /** 单文件撤销的行内确认。 */
  const [confirm, setConfirm] = useState<{ type: 'one'; path: string } | null>(null);

  /*
   * 后端 /diff 的 files 是数组、没有顶层 totals：增删行要从每个文件累加，
   * 之前直接读 report.totals 会让摘要永远是 +0 -0。
   *
   * 未跟踪文件（git status 的 ??）单列：它们的「+行数」其实是整个文件的行数，
   * 混进总计会把 +984 变成 +35757，列表也会被几十个临时文件淹掉。
   */
  const files = useMemo(() => sortChangeFiles<ChangeFile>(Array.isArray(report?.files) ? report.files : []), [report]);
  const tracked = useMemo(() => files.filter((file) => file.status !== '??'), [files]);
  const untracked = useMemo(() => files.filter((file) => file.status === '??'), [files]);
  const totals = useMemo(() => tracked.reduce(
    (sum, file) => ({ added: sum.added + Number(file.added || 0), removed: sum.removed + Number(file.removed || 0) }),
    { added: 0, removed: 0 }
  ), [tracked]);
  const isGit = report?.gitRepository !== false;
  const empty = !files.length;
  const restoring = state === 'restoring';

  const keyword = query.trim().toLowerCase();
  const visibleTracked = keyword
    ? tracked.filter((file) => file.path.toLowerCase().includes(keyword))
    : tracked;
  const visibleUntracked = keyword
    ? untracked.filter((file) => file.path.toLowerCase().includes(keyword))
    : untracked;
  const trackedPaths = useMemo(() => tracked.map((file) => file.path), [tracked]);
  const viewIndex = trackedPaths.indexOf(view || '');

  useEffect(() => {
    if (sessionId) {
      useStore.getState().loadChanges();
    }
  }, [sessionId]);

  useEffect(() => {
    setScope('workspace'); setRunReport(null); setRunError(''); setQuery(''); setConfirm(null);
  }, [sessionId]);
  useEffect(() => {
    if (scope !== 'run' || !lastRunId) return;
    let alive = true; setRunReport(null); setRunError('');
    api.runChanges(lastRunId).then(data => {
      if (!data) throw new Error('任务改动返回空响应');
      if (!alive) return;
      if (data.error) setRunError(data.error);
      else setRunReport({ ...data, diff: (data.files || []).map(file => file.diff || '').join('\n') });
    }).catch(() => { if (alive) setRunError('读取任务改动失败，请刷新重试'); });
    return () => { alive = false; };
  }, [scope, lastRunId, sessionId, refresh]);

  // 换了文件就把上一行的「确认撤销」收起来，免得确认框停在一个已经不显示的文件上
  useEffect(() => {
    setConfirm((current) => (current?.type === 'one' && current.path !== view ? null : current));
  }, [view]);

  const canRestore = scope === 'workspace' && isGit && !empty && state !== 'error' && state !== 'loading';
  const running = useStore(state => state.running);
  const undoDisabled = !canRestore || running || restoring;

  const closeDiff = () => {
    useStore.getState().setUi({ changeView: '' });
    requestAnimationFrame(() => {
      const buttons = document.querySelectorAll<HTMLButtonElement>('#change-files button[data-change-path]');
      const row = [...buttons].find(button => button.dataset.changePath === view);
      (row || document.getElementById('inspector-tab-changes'))?.focus({ preventScroll: true });
    });
  };

  function onReviewKey(event: React.KeyboardEvent<HTMLDivElement>) {
    const target = event.target;
    if (event.defaultPrevented || event.nativeEvent.isComposing || event.altKey || event.ctrlKey || event.metaKey
      || !(target instanceof HTMLElement) || !event.currentTarget.contains(target)
      || target.closest('input, textarea, select, [contenteditable], [role="menu"], [role="radiogroup"], .changes-actions')) return;
    if (event.key === 'Escape' && (confirm || view)) {
      event.preventDefault();
      event.stopPropagation();
      if (confirm) setConfirm(null);
      else closeDiff();
      return;
    }
    if (!view || viewIndex < 0 || target.closest('#change-diff') || (event.key !== 'ArrowDown' && event.key !== 'ArrowUp')) return;
    event.preventDefault();
    const next = Math.min(trackedPaths.length - 1, Math.max(0, viewIndex + (event.key === 'ArrowDown' ? 1 : -1)));
    useStore.getState().setUi({ changeView: trackedPaths[next] });
  }

  const restore = async (path: string) => {
    if (undoDisabled || !files.some(file => file.path === path) || useStore.getState().running) return;
    setConfirm(null);
    await useStore.getState().restoreChanges([path]);
    if (document.activeElement === document.body) document.getElementById('changes-refresh')?.focus({ preventScroll: true });
  };

  // 还没开始任务时没有会话，也就没有工作区可读；给一句明确的话，别显示「没有改动」
  if (!sessionId) {
    return <EmptyState className="inspector-empty" title="还没有可以审阅的任务" description="开始任务后，这里会列出工作区的文件改动。" isCompact />;
  }

  return (
    <div id="panel-changes" className={`panel${expanded ? ' is-expanded' : ''}`} onKeyDown={onReviewKey}>
      <div className="review-toolbar">
        <SegmentedControl label="审阅范围" size="md" value={scope} onChange={value => {
          setScope(value); setConfirm(null); useStore.getState().setUi({ changeView: null });
        }}>
          <SegmentedControlItem value="workspace" label="工作区" />
          <SegmentedControlItem value="run" label="最近任务" isDisabled={!lastRunId} />
        </SegmentedControl>
        <ToggleButton className="review-expand" label={expanded ? '退出全屏审阅' : '全屏审阅'} isPressed={expanded}
          onPressedChange={value => useStore.getState().setUi({ reviewExpanded: value })} />
      </div>
      <p className="review-scope-note">{scope === 'run' ? '仅筛选最近任务涉及的文件；差异仍相对 HEAD，可能包含你或其他任务的修改。' : '包含你和其他任务的未提交改动。'}</p>
      {runError && scope === 'run' && <Banner className="inspector-banner" status="error" role="alert" title={runError} />}
      <div className="changes-bar">
        <div className="changes-meta">
          {empty ? (
            <span id="changes-totals" className="changes-totals">
              <span className="changes-count">{scope === 'run' && !report ? (runError ? '变更读取失败' : '正在读取任务改动…')
                : state === 'error' && scope === 'workspace' ? '变更读取失败'
                : state === 'loading' && scope === 'workspace' ? '读取中…'
                : isGit ? '没有改动' : '不是 Git 仓库'}</span>
            </span>
          ) : (
            <>
              <span id="changes-totals" className="changes-totals">
                <span className="changes-count">{files.length} 个文件</span>
                {tracked.length > 0 && <span className="changes-line-counts" title="已跟踪文件的增删行数"><span className="add">+{totals.added}</span><span className="del">−{totals.removed}</span></span>}
              </span>

            </>
          )}
        </div>
        <ReviewActions
          key={`${sessionId}:${sessionEpoch}:${workspace}:${scope}`}
          files={files} isGit={isGit} canUndo={scope === 'workspace' && isGit && !empty && state !== 'error'}
          loading={state === 'loading'} restoring={restoring}
          onRefresh={() => { useStore.getState().loadChanges(); setRefresh(value => value + 1); }}
        />
      </div>
      {restoring && <p className="changes-progress" role="status">撤销中…</p>}
      {running && scope === 'workspace' && !empty && <p className="changes-progress">任务运行结束后可撤销工作区改动。</p>}
      {state === 'error' && scope === 'workspace' && <Banner className="inspector-banner" role="alert" status="error"
        title="无法读取工作区变更" description={empty ? '刷新变更后重试。' : '当前显示上次成功读取的改动。'} />}
      {tracked.length > 5 && (
        <div className="changes-filter">
          <TextInput label="筛选变更文件" type="search" isLabelHidden placeholder="筛选文件路径"
            containerClassName="inspector-grow" value={query} onChange={setQuery} />
          <span className="changes-filter-count">
            {visibleTracked.length} / {tracked.length}{untracked.length ? ` · 未跟踪 ${untracked.length}` : ''}
          </span>
        </div>
      )}
      <div className="changes-body">
        {empty && !view && (scope === 'workspace' ? !!report && state !== 'error' : !!report && !runError) &&
          <EmptyState className="inspector-empty" title={isGit ? '没有文件需要审阅' : '当前工作区没有 Git 仓库'}
            description={isGit ? '工作区出现文件改动后，可在这里逐个查看差异。' : '仍可从预览页按路径打开工作区文件。'} isCompact />}
        {!empty && (
          <div id="change-files" className="change-files">
            {visibleTracked.map((file) => (
              <ChangeRow
                key={file.path}
                file={file}
                active={view === file.path}
                confirming={confirm?.type === 'one' && confirm.path === file.path}
                undoDisabled={undoDisabled}
                onAskUndo={() => setConfirm({ type: 'one', path: file.path })}
                onCancelUndo={() => setConfirm(null)}
                onUndo={() => restore(file.path)}
              />
            ))}
            {keyword && !visibleTracked.length && <p className="change-more">没有匹配「{query}」的文件。</p>}
            {untracked.length > 0 && (
              <Button className="change-untracked-toggle" variant="ghost" label={`未跟踪文件 · ${untracked.length}`}
                aria-expanded={showUntracked} icon={<Icon name="chevron" />}
                onClick={() => setShowUntracked(open => !open)} />
            )}
            {showUntracked && visibleUntracked.slice(0, UNTRACKED_LIMIT).map((file) => (
              <ChangeRow
                key={file.path}
                file={file}
                active={view === file.path}
                untracked
                confirming={confirm?.type === 'one' && confirm.path === file.path}
                undoDisabled={undoDisabled}
                onAskUndo={() => setConfirm({ type: 'one', path: file.path })}
                onCancelUndo={() => setConfirm(null)}
                onUndo={() => restore(file.path)}
              />
            ))}
            {showUntracked && visibleUntracked.length > UNTRACKED_LIMIT && (
              <p className="change-more">还有 {visibleUntracked.length - UNTRACKED_LIMIT} 个未跟踪文件没列出，建议先清理或写进 .gitignore。</p>
            )}
            {showUntracked && keyword && !visibleUntracked.length && (
              <p className="change-more">未跟踪文件里没有匹配「{query}」的。</p>
            )}
          </div>
        )}
        {view
          ? (
            <DiffView
              key={`${sessionId}:${scope}:${view}`}
              report={report}
              scoped={scope === 'run'}
              path={view}
              status={files.find((file) => file.path === view)?.status}
              prevPath={viewIndex > 0 ? trackedPaths[viewIndex - 1] : null}
              nextPath={viewIndex >= 0 && viewIndex < trackedPaths.length - 1 ? trackedPaths[viewIndex + 1] : null}
              index={viewIndex >= 0 ? viewIndex + 1 : 0}
              total={trackedPaths.length}
              undoDisabled={undoDisabled || !files.some(file => file.path === view)}
              onUndo={() => restore(view)}
              onClose={closeDiff}
            />
          )
          : (!empty && <p className="change-empty">选择文件查看差异。</p>)}
      </div>
    </div>
  );
}

/** 未跟踪文件一次最多列这么多：几百个临时文件全铺出来既卡又没法看。 */
const UNTRACKED_LIMIT = 80;

/** 变更列表的一行：状态徽标 + 文件名（目录弱化）+ 增删行，悬停露出撤销。 */
function ChangeRow({ file, active, untracked, confirming, undoDisabled, onAskUndo, onCancelUndo, onUndo }: {
  file: ChangeFile; active: boolean; untracked?: boolean; confirming?: boolean; undoDisabled: boolean;
  onAskUndo: () => void; onCancelUndo: () => void; onUndo: () => void;
}) {
  const { name, dir } = splitPath(file.path);
  const undoRef = useRef<HTMLButtonElement>(null);
  const cancel = () => { onCancelUndo(); requestAnimationFrame(() => undoRef.current?.focus()); };
  if (confirming) return (
    <div className="change-row is-confirm" onKeyDown={event => {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); cancel(); }
    }}>
      <span className={`change-status is-${statusKind(file.status)}`}>{statusLetter(file.status)}</span>
      <span className="change-row-main">
        <span className="change-row-confirm">撤销 <b>{name}</b> 的改动？</span>
        <span className="change-row-dir">{untracked ? '未跟踪文件会被删除' : '文件内容将恢复到 HEAD'}</span>
      </span>
      <span className="change-row-actions">
        <Button label="取消" size="sm" ref={focusWithoutScroll} onClick={cancel} />
        <Button label="撤销" variant="destructive" size="sm" isDisabled={undoDisabled} onClick={onUndo} />
      </span>
    </div>
  );
  return (
    <div className={`change-row-wrap${active ? ' is-active' : ''}`}>
      <button type="button" className={`change-row${active ? ' is-active' : ''}`} title={file.path} aria-pressed={active}
        data-change-path={file.path} onClick={() => useStore.getState().setUi({ changeView: file.path })}>
        <span className={`change-status is-${statusKind(file.status)}`}>{statusLetter(file.status)}</span>
        <span className="change-row-main">
          <span className="change-row-name">{name}</span>
          {dir && <span className="change-row-dir">{dir}</span>}
        </span>
        {!untracked && <span className="change-row-stat">
          <span className="add">+{Number(file.added || 0)}</span>
          <span className="del">−{Number(file.removed || 0)}</span>
        </span>}
      </button>
      {!undoDisabled && <Button ref={undoRef} label={`撤销 ${file.path} 的改动`}
        className="change-undo" size="sm" variant="ghost" onClick={onAskUndo}>撤销</Button>}
    </div>
  );
}

/** 单文件差异最多渲染这么多行：再多滚动会明显掉帧。 */
const DIFF_LINE_LIMIT = 4000;

/** git 状态码 → 一个字母的徽标。 */
function focusWithoutScroll(node: HTMLButtonElement | null) {
  node?.focus({ preventScroll: true });
}

function statusLetter(status?: string) {
  const raw = String(status || 'M').replace(/\?/g, 'U');
  return raw.slice(0, 1).toUpperCase();
}

function DiffView({ report, scoped, path, status, prevPath, nextPath, index, total, undoDisabled, onUndo, onClose }: {
  report: ReviewReport | null; scoped: boolean; path: string; status?: string; prevPath: string | null; nextPath: string | null;
  index: number; total: number; undoDisabled: boolean; onUndo: () => void; onClose: () => void;
}) {
  const sessionId = useStore((state) => state.sessionId);
  const untracked = status === '??';
  /*
   * 后端把整份补丁放在 report.diff 里，files 每项只有状态与增删行。
   * 整份补丁有 200k 字符上限：改动一多，后面的文件在补丁里根本没有内容，
   * 这时按需再取一次单文件补丁，既不把正文当 diff 展示，也不用一次拉全仓库。
   */
  const parsed = useMemo(() => parseDiff(report?.diff || ''), [report]);
  const summaryFile = parsed.find((item) => normalizePath(item.path) === normalizePath(path));
  const [confirming, setConfirming] = useState(false);
  /** 按需取回的单文件补丁：{ file, truncated }。 */
  const [patch, setPatch] = useState<{ file: DiffFile | null; truncated: boolean } | null>(null);
  const [patchState, setPatchState] = useState('');
  const [retry, setRetry] = useState(0);
  const closeRef = useRef<HTMLButtonElement>(null);
  const undoRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (window.matchMedia('(max-width: 600px)').matches) closeRef.current?.focus();
  }, []);
  /** 未跟踪文件不在补丁里，直接展示正文。 */
  const [content, setContent] = useState<string | null>(null);
  const [selection, setSelection] = useState<{ anchor: number; end: number } | null>(null);
  const [feedback, setFeedback] = useState('');

  useEffect(() => {
    if (summaryFile || !sessionId || scoped) {
      setPatch(null);
      setPatchState('');
      return undefined;
    }
    let alive = true;
    if (untracked) {
      setContent(null);
      setPatchState('loading');
      api.file(sessionId, path)
        .then(data => {
          if (!data) throw new Error('文件读取为空');
          if (alive) {
            setContent(data.binary ? '（二进制文件，无法预览）' : (data.content || '（空文件）'));
            setPatchState('');
          }
        }).catch(() => { if (alive) setPatchState('error'); });
      return () => { alive = false; };
    }
    setPatch(null);
    setPatchState('loading');
    api.fileDiff(sessionId, path)
      .then((data) => {
        if (!alive) {
          return;
        }
        if (!data) throw new Error('差异读取为空');
        const files = parseDiff(data.diff || '');
        setPatch({ file: files[0] || null, truncated: Boolean(data?.truncated) });
        setPatchState('');
      })
      .catch(() => {
        if (alive) {
          setPatch(null);
          setPatchState('error');
        }
      });
    return () => { alive = false; };
  }, [summaryFile, path, sessionId, untracked, scoped, report, retry]);

  const file = summaryFile || patch?.file || null;
  const lines = (file?.lines || []).filter(line => line.kind !== 'meta').slice(0, DIFF_LINE_LIMIT);
  useEffect(() => { setSelection(null); setFeedback(''); }, [path, report]);
  const selected = selection ? lines.slice(Math.min(selection.anchor, selection.end), Math.max(selection.anchor, selection.end) + 1) : [];
  const addFeedback = () => {
    const state = useStore.getState();
    const quote = reviewQuote(path, selected, feedback);
    if (!quote || !state.composerApi) return;
    state.composerApi.appendText(quote);
    const narrow = window.matchMedia('(max-width: 960px)').matches;
    state.setUi({ reviewExpanded: false, ...(narrow ? { inspectorOpen: false } : {}) });
    setSelection(null); setFeedback('');
    requestAnimationFrame(() => state.composerApi?.focus());
  };
  const cancelUndo = () => { setConfirming(false); requestAnimationFrame(() => undoRef.current?.focus()); };
  const select = (target: string | null) => {
    setConfirming(false);
    useStore.getState().setUi({ changeView: target });
  };
  return (
    <div className="change-view">
      <div className="change-view-bar">
        <span className="change-view-path" title={path}>{path}</span>
        {file ? (
          <span className="change-view-stat">
            <span className="add">+{file.added}</span>
            <span className="del">−{file.removed}</span>
          </span>
        ) : (untracked ? <span className="change-view-tag">未跟踪</span> : null)}
        {total > 0 && index > 0 && <span className="change-view-index">{index} / {total}</span>}
        <div className="diff-file-actions">
          <IconButton className="diff-nav" label="上一个改动文件" variant="ghost" size="sm"
            icon={<Icon name="chevron" className="points-back" />} isDisabled={!prevPath} onClick={() => select(prevPath)} />
          <IconButton className="diff-nav" label="下一个改动文件" variant="ghost" size="sm"
            icon={<Icon name="chevron" />} isDisabled={!nextPath} onClick={() => select(nextPath)} />
          {!confirming && !undoDisabled && <Button ref={undoRef} label="撤销" variant="ghost" size="sm" className="change-view-undo"
            onClick={() => setConfirming(true)} />}
          <IconButton ref={closeRef} className="change-view-close" label="关闭差异" variant="ghost"
            size="sm" icon={<Icon name="close" />} onClick={onClose} />
        </div>
      </div>
      {confirming && <div className="diff-confirm" onKeyDown={event => {
        if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); cancelUndo(); }
      }}>
        <p>{untracked ? '撤销将删除此未跟踪文件。' : '撤销将把此文件恢复到 HEAD。'}</p>
        <Button label="取消" size="sm" ref={focusWithoutScroll} onClick={cancelUndo} />
        <Button label="确认撤销" variant="destructive" size="sm" isDisabled={undoDisabled} onClick={onUndo} />
      </div>}
      {file && <p className="review-line-hint">选中行号可添加意见。按住 Shift 选择一段代码。</p>}
      {selection && <div className="review-feedback" onKeyDown={event => {
        if (event.key === 'Escape' && !event.nativeEvent.isComposing) { event.preventDefault(); event.stopPropagation(); setSelection(null); }
      }}>
        <TextArea label="审阅意见" value={feedback} onChange={setFeedback} placeholder="描述需要调整的地方" rows={2}
          containerClassName="review-feedback-field" />
        <Button label="引用到输入框" onClick={addFeedback} />
        <Button label="取消选择" variant="ghost" onClick={() => { setSelection(null); setFeedback(''); }} />
      </div>}
      <div id="change-diff" className="change-diff" tabIndex={0} aria-label="文件差异">
        {file
          ? (
            <>
              {/* index/---/+++ 这些补丁元信息对读代码没帮助，Codex 也不展示 */}
              {lines.map((line, index) => (
                <div key={index} className={`diff-line is-${line.kind}${selection && index >= Math.min(selection.anchor, selection.end) && index <= Math.max(selection.anchor, selection.end) ? ' is-selected' : ''}`}>
                  {line.kind === 'hunk' ? <span className="n" /> : <button type="button"
                    className={`n${line.kind === 'del' ? ' is-old' : ''}`}
                    aria-label={`选择${line.kind === 'del' ? '原文件' : '现文件'}第 ${line.kind === 'del' ? line.old : line.next} 行`}
                    aria-pressed={Boolean(selection && index >= Math.min(selection.anchor, selection.end) && index <= Math.max(selection.anchor, selection.end))}
                    onClick={event => setSelection(current => ({ anchor: event.shiftKey && current ? current.anchor : index, end: index }))}>
                    {line.kind === 'del' ? line.old : line.next}
                  </button>}
                  <code>{line.text}</code>
                </div>
              ))}
              {/* 生成出来的大文件动辄上万行，全铺出来会卡住滚动；先给一个明确的截断提示 */}
              {file.lines.length > DIFF_LINE_LIMIT && (
                <p className="diff-truncated">
                  只显示前 {DIFF_LINE_LIMIT} 行，还有 {file.lines.length - DIFF_LINE_LIMIT} 行未渲染。
                </p>
              )}
              {patch?.truncated && (
                <p className="diff-truncated">这个文件的补丁超过 6 万字符，只显示了前一部分。</p>
              )}
            </>
          )
          : patchState === 'error'
            ? <Banner className="inspector-banner" status="error" role="alert" title={untracked ? '无法读取文件内容' : '无法读取文件差异'}
              endContent={<Button label="重试" variant="ghost" onClick={() => setRetry(value => value + 1)} />} />
            : patchState === 'loading'
              ? <p className="diff-empty" role="status">读取中…</p>
              : untracked && !scoped && content !== null
                ? <pre className="diff-raw">{content}</pre>
                : <p className="diff-empty">这个文件没有可显示的文本差异。</p>}
      </div>
    </div>
  );
}

/* ---------- 预览面板 ---------- */

function PreviewPanel() {
  const path = useStore((state) => state.ui.previewPath);
  const data = useStore((state) => state._previewData);
  const loading = useStore((state) => state._previewLoading);
  const report = useStore((state) => state.changeReport);
  const hasWorkspace = useStore(state => Boolean(state.sessionId || state.workspace));
  const [draftPath, setDraftPath] = useState(path);
  const [wrap, setWrap] = useState(false);
  useEffect(() => setDraftPath(path), [path]);
  const open = (target: string) => { setDraftPath(target.trim()); void useStore.getState().openPreview(target); };

  const changed = useMemo(() => sortChangeFiles<ChangeFile>(Array.isArray(report?.files) ? report.files : []).slice(0, 12), [report]);
  const isMarkdown = /\.(md|markdown|mdx)$/i.test(data?.path || path);
  const content = data?.content || '';
  const lineCount = content ? content.split('\n').length : 0;

  const copy = async () => {
    if (!content) {
      return;
    }
    try {
      if (!await writeClipboard(content)) throw new Error('clipboard unavailable');
      useStore.getState().showNotice('已复制文件内容');
    } catch {
      useStore.getState().showNotice('复制失败，请手动选择内容');
    }
  };

  return (
    <div id="panel-preview" className={`panel${data && !data.binary ? ' has-content' : ''}`}>
      <form className="panel-toolbar" onSubmit={event => { event.preventDefault(); open(draftPath); }}>
        <TextInput id="preview-path" label="预览文件路径" isLabelHidden placeholder="工作区内的文件路径"
          autoComplete="off" hasSpellCheck={false} value={draftPath} onChange={setDraftPath}
          containerClassName="preview-path-field" isDisabled={!hasWorkspace}
          onKeyDown={event => { if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault(); }} />
        <Button type="submit" id="preview-refresh" label="打开" isDisabled={!draftPath.trim() || !hasWorkspace} />
        <ToggleButton className="preview-wrap" label="换行"
          isPressed={wrap} isDisabled={!content || isMarkdown} onPressedChange={setWrap} />
        <Button label="复制" variant="ghost" isDisabled={!content} onClick={copy} />
      </form>
      {data && !data.binary && (
        <div className="preview-meta">
          <span className="preview-meta-path" title={data.path || path}>{data.path || path}</span>
          <span className="preview-meta-item">{lineCount} 行</span>
          <span className="preview-meta-item">{formatBytes(data.size)}</span>
          {isMarkdown && <Badge label="Markdown" variant="neutral" />}
          {data.truncated && <Badge label="已截断" variant="warning" />}
        </div>
      )}
      {loading && <p className="inspector-status" role="status">正在读取文件…</p>}
      {!loading && data && (
        data.binary
          ? <EmptyState className="inspector-empty" title="此文件没有文本预览" description="可从工作区用对应应用打开二进制文件。" isCompact />
          : (isMarkdown
            ? (
              <div className="bubble preview-markdown">
                <MarkdownBody text={data.truncated ? `${content}\n\n…（内容过长，已截断）` : (content || '（空文件）')} />
              </div>
            )
            : (
              <pre id="preview-body" className={`preview-body${wrap ? '' : ' is-nowrap'}`} tabIndex={0} aria-label="文件内容">
                {data.truncated ? `${content}\n…（内容过长，已截断）` : (content || '（空文件）')}
              </pre>
            ))
      )}
      {!loading && !data && path && <Banner className="inspector-banner" status="error" role="alert"
        title="无法读取文件" description={`${path} · 核对路径和读取权限后重试。`}
        endContent={<Button label="重试预览" variant="ghost" onClick={() => open(path)} />} />}
      {!loading && !data && (
        <div className="preview-empty">
          {!path && <EmptyState className="inspector-empty" title="选择要预览的文件"
            description={hasWorkspace ? '可输入工作区内的路径，或从下方选择一个改动文件。' : '先选择工作区，再打开文件预览。'} isCompact />}
          {changed.length > 0 && (
            <ul className="preview-suggest">
              {changed.map((file) => (
                <li key={file.path}>
                  <button type="button" title={file.path} onClick={() => open(file.path)}>
                    <span className={`change-status is-${statusKind(file.status)}`}>{statusLetter(file.status)}</span>
                    <span className="preview-suggest-name">{splitPath(file.path).name}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

function formatBytes(size: unknown) {
  const value = Number(size);
  if (!Number.isFinite(value) || value <= 0) {
    return '0 B';
  }
  if (value < 1024) {
    return `${value} B`;
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KB`;
  }
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

/* ---------- 命令面板 ---------- */

function CommandsPanel() {
  // 任务列表必须是数组：早先 store 把日志合并函数用在了任务上，这里会直接 TypeError 白屏
  const tasks = useStore((state) => state.commandTasks || NO_TASKS);
  const detail = useStore((state) => state.commandDetail);
  const log = useStore((state) => state.commandLog);
  const logRef = useRef<HTMLPreElement>(null);
  const [follow, setFollow] = useState(true);
  const active = tasks.filter((task) => isCommandActive(task));
  const sessionId = useStore((state) => state.sessionId);
  const selectedId = detail?.taskId || detail?.id;

  // 打开面板先主动拉一次：只靠轮询的话，第一次进来会有空白
  useEffect(() => {
    if (sessionId) {
      useStore.getState().loadCommandTasks();
      useStore.getState().loadBrowserStatus();
    }
  }, [sessionId]);

  /*
   * 进来先替你选一条：优先运行中的，其次最近一条。
   * 否则面板要等你点一下才有内容，Codex 那边是直接给最新终端的输出。
   */
  useEffect(() => {
    if (detail || !tasks.length) {
      return;
    }
    const target = tasks.find((task) => isCommandActive(task)) || tasks[0];
    useStore.getState().loadCommandLog(target);
  }, [tasks, detail]);

  /*
   * 只有真的有命令在跑才在这里加班轮询（1.5s）；空闲时交给 store 的 5s 全局轮询，
   * 避免面板开着就双份请求。
   */
  useEffect(() => {
    if (!sessionId || !active.length) {
      return undefined;
    }
    const timer = setInterval(async () => {
      await useStore.getState().loadCommandTasks();
      const current = useStore.getState().commandDetail;
      if (!current) {
        return;
      }
      const taskId = current.taskId || current.id;
      // 用刷新后的任务对象再去拉日志：否则「停止」按钮和状态会停在旧值
      const fresh = (useStore.getState().commandTasks || NO_TASKS)
        .find((task) => (task.taskId || task.id) === taskId);
      useStore.getState().loadCommandLog(fresh || current);
    }, 1500);
    return () => clearInterval(timer);
  }, [sessionId, active.length]);

  useEffect(() => {
    if (follow && logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [log, follow]);

  // 终端颜色码在 <pre> 里只会变成 `[32m` 这种噪音，展示前统一剥掉
  const logText = useMemo(() => stripAnsi(log?.text || ''), [log]);

  const copyLog = async () => {
    try {
      if (!await writeClipboard(logText)) throw new Error('clipboard unavailable');
      useStore.getState().showNotice('已复制命令输出');
    } catch {
      useStore.getState().showNotice('复制失败，请手动选择输出');
    }
  };

  return (
    <div id="panel-commands" className="panel">
      <div className="panel-toolbar">
        <span id="commands-state" className="commands-state">{active.length ? `${active.length} 个命令运行中` : '没有运行中的命令'}</span>
        <IconButton label="刷新命令" icon={<Icon name="refresh" />} variant="ghost"
          isDisabled={!sessionId} onClick={() => useStore.getState().loadCommandTasks()} />
      </div>
      <div id="commands-list" className="commands-list">
        {tasks.map((task) => {
          const taskId = task.taskId || task.id;
          const running = isCommandActive(task);
          return (
            <button
              key={taskId}
              type="button"
              className={`command-item${selectedId === taskId ? ' is-active' : ''}`}
              title={task.command || ''}
              aria-pressed={selectedId === taskId}
              onClick={() => useStore.getState().loadCommandLog(task)}
            >
              <span className={`command-status is-${String(task.status || 'run').toLowerCase()}`} />
              <span className="command-name">{task.command || taskId}</span>
              <span className="command-row-meta">
                {task.exitCode != null && <span className={`command-exit${task.exitCode ? ' is-bad' : ''}`}>exit {task.exitCode}</span>}
                <span className="command-duration">{durationLabel(task, running)}</span>
              </span>
              <span className="command-state">{commandStatusLabel(task)}</span>
            </button>
          );
        })}
        {!tasks.length && (
          <EmptyState id="commands-empty" className="inspector-empty" title="还没有命令任务"
            description="任务启动构建、测试或脚本时，可在这里查看输出和停止命令。" isCompact />
        )}
      </div>
      {detail && (
        <div id="command-detail" className="command-detail">
          <div className="command-detail-head">
            <p id="command-title" className="command-title">{detail.command || detail.taskId}</p>
            {isCommandActive(detail) && (
              <Button label="停止" variant="destructive"
                clickAction={() => useStore.getState().stopCommand(detail)} />
            )}
            <Button label="复制输出" variant="ghost" isDisabled={!logText} onClick={copyLog} />
          </div>
          <dl className="command-facts">
            <div><dt>状态</dt><dd>{commandStatusLabel(detail)}</dd></div>
            {detail.workdir && <div><dt>目录</dt><dd className="command-fact-path" title={detail.workdir}>{detail.workdir}</dd></div>}
            <div><dt>耗时</dt><dd>{durationLabel(detail, isCommandActive(detail)) || '—'}</dd></div>
            {detail.exitCode != null && <div><dt>退出码</dt><dd>{detail.exitCode}</dd></div>}
            {detail.pid != null && <div><dt>PID</dt><dd>{detail.pid}</dd></div>}
          </dl>
          {detail.reason && <p className="command-reason">{detail.reason}</p>}
          {log?.truncated && <p className="command-log-note">输出超过上限，已截断。</p>}
          <div className="command-log-head">
            <span className="command-log-note">{follow ? '自动跟随最新输出' : '已暂停跟随'}</span>
            <ToggleButton label={follow ? '暂停跟随' : '跟随最新'} isPressed={follow} onPressedChange={setFollow} />
          </div>
          <pre
            id="command-log"
            className={`command-log${logText ? '' : ' is-empty'}`}
            ref={logRef}
            tabIndex={0}
            aria-label="命令输出"
            onScroll={(event) => {
              const el = event.currentTarget;
              const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
              if (follow && !atBottom) {
                setFollow(false);
              }
            }}
          >{logText || '这条命令没有留下输出。'}</pre>
        </div>
      )}
    </div>
  );
}

/** 命令耗时：运行中的任务算到现在，结束的按 startedAt→finishedAt。 */
function durationLabel(task: CommandTask | null | undefined, running: boolean) {
  const start = parseTimestamp(task?.startedAt);
  if (!start) {
    return '';
  }
  const end = parseTimestamp(task?.finishedAt) || (running ? Date.now() : 0);
  if (!end || end < start) {
    return '';
  }
  const ms = end - start;
  if (ms < 1000) {
    return `${ms}ms`;
  }
  if (ms < 60000) {
    return `${(ms / 1000).toFixed(1)}s`;
  }
  const minutes = Math.floor(ms / 60000);
  const seconds = Math.round((ms % 60000) / 1000);
  return `${minutes}m ${seconds}s`;
}

function parseTimestamp(value: string | number | null | undefined) {
  if (value == null || value === '') {
    return 0;
  }
  if (typeof value === 'number') {
    return value;
  }
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

/* ---------- 能力清单 ---------- */

const PERMISSION_LABELS: Record<string, string> = {
  AUTO: '完全放行',
  AUTO_EDIT: '自动改文件',
  ASK: '每次确认',
  READ_ONLY: '只读'
};

function CapabilitiesPanel() {
  const sessionId = useStore((state) => state.sessionId);
  const data = useStore((state) => state.capabilities);
  const state = useStore((state) => state.capabilitiesState);
  const skills = useStore((state) => state.skills || NO_TASKS);

  useEffect(() => {
    if (sessionId) {
      useStore.getState().loadCapabilities();
    }
  }, [sessionId]);

  // 可用的排前面：不可用的一多，最该看到的东西会被挤到列表底部
  const tools = useMemo(() => (Array.isArray(data?.tools) ? [...data.tools] : [])
    .sort((a, b) => Number(b.available) - Number(a.available) || String(a.name).localeCompare(String(b.name))), [data]);
  const servers = Array.isArray(data?.mcpServers) ? data.mcpServers : [];
  const localCapabilities = Array.isArray(data?.localCapabilities) ? data.localCapabilities : [];
  const available = tools.filter((tool) => tool.available).length;

  if (!sessionId) {
    return <EmptyState className="inspector-empty" title="还没有任务运行环境"
      description="开始任务后，这里会列出工具、技能和 MCP 服务器。" isCompact />;
  }

  return (
    <div id="panel-capabilities" className="panel">
      <div className="panel-toolbar">
        <span className="commands-state">
          {state === 'loading' ? '读取中…' : `${available} / ${tools.length} 个工具可用`}
        </span>
        <IconButton label="刷新能力清单" icon={<Icon name="refresh" />} variant="ghost"
          isDisabled={state === 'loading'} onClick={() => useStore.getState().loadCapabilities()} />
      </div>
      {state === 'error' && <Banner className="inspector-banner" status="error" role="alert"
        title="无法读取能力清单" description="刷新清单后重试。" />}
      {data && <div className="cap-scroll">
        <section className="cap-section">
          <h3>运行环境</h3>
          <dl className="cap-kv">
            <div><dt>模型</dt><dd>{data?.model || '—'}</dd></div>
            <div><dt>权限</dt><dd>{PERMISSION_LABELS[data?.permissionMode || ''] || data?.permissionMode || '—'}</dd></div>
            <div><dt>工作区</dt><dd className="cap-path" title={data?.workspace || ''}>{data?.workspace || '—'}</dd></div>
          </dl>
        </section>
        <section className="cap-section">
          <h3>工具<span className="cap-count">{tools.length}</span></h3>
          {tools.length ? (
            <ul className="cap-list">
              {tools.map((tool) => (
                <li key={tool.name} className={`cap-item${tool.available ? '' : ' is-off'}`}>
                  <div className="cap-item-head">
                    <code className="cap-name">{tool.name}</code>
                    <span className={`cap-tag${tool.readOnly ? ' is-readonly' : ''}`}>{tool.readOnly ? '只读' : '可写'}</span>
                    {!tool.available && <span className="cap-tag is-off">不可用</span>}
                  </div>
                  {tool.description && <p className="cap-desc" title={tool.description}>{tool.description}</p>}
                  {!tool.available && tool.reason && <p className="cap-reason">{tool.reason}</p>}
                </li>
              ))}
            </ul>
          ) : <p className="panel-empty">没有注册任何工具。</p>}
        </section>
        <section className="cap-section">
          <h3>本机资源<span className="cap-count">{localCapabilities.filter((item) => item.available).length} / {localCapabilities.length}</span></h3>
          <p className="cap-desc">只检查已存在的程序，不会启动、下载或安装。需要补充环境时，先确认任务是否有可用替代方案。</p>
          {localCapabilities.length ? (
            <ul className="cap-list">
              {localCapabilities.map((item) => (
                <li key={item.id} className={`cap-item${item.available ? '' : ' is-off'}`}>
                  <div className="cap-item-head">
                    <span className="cap-name">{item.name}</span>
                    <span className={`cap-tag${item.available ? ' is-on' : ' is-off'}`}>{item.available ? '已发现' : '未发现'}</span>
                  </div>
                  <p className="cap-desc">{item.purpose}</p>
                  {item.available ? (
                    <p className="cap-desc cap-mono" title={item.executable}>{item.executable}</p>
                  ) : (
                    <p className="cap-reason">{item.preparationAdvice}</p>
                  )}
                </li>
              ))}
            </ul>
          ) : <p className="panel-empty">本机资源清单尚未加载。</p>}
        </section>
        <section className="cap-section">
          <h3>MCP 服务器<span className="cap-count">{servers.length}</span></h3>
          {servers.length ? (
            <ul className="cap-list">
              {servers.map((server) => (
                <li key={server.name} className="cap-item">
                  <div className="cap-item-head">
                    <code className="cap-name">{server.name}</code>
                    <span className={`cap-tag${server.running ? ' is-on' : ''}`}>{server.running ? '运行中' : (server.usable ? '未启动' : '不可用')}</span>
                    {server.running && <span className="cap-tag is-readonly">{server.tools?.length || 0} 个工具</span>}
                  </div>
                  {server.command && <p className="cap-desc cap-mono">{server.command}</p>}
                  {server.unsupported && <p className="cap-reason">{server.unsupported}</p>}
                  {server.failure && <p className="cap-reason">上次启动失败：{server.failure}</p>}
                </li>
              ))}
            </ul>
          ) : (
            <p className="panel-empty">没有配置 MCP 服务器。在工作区放一个 .mcp.json 就能接上外部工具。</p>
          )}
        </section>
        <section className="cap-section">
          <h3>技能<span className="cap-count">{skills.length}</span></h3>
          {skills.length ? (
            <ul className="cap-list">
              {skills.map((skill) => (
                <li key={skill.name} className="cap-item">
                  <div className="cap-item-head">
                    <code className="cap-name">/{skill.command || skill.name}</code>
                    {skill.source && <span className="cap-tag">{skill.source === 'project' ? '项目级' : '用户级'}</span>}
                  </div>
                  {skill.description && <p className="cap-desc" title={skill.description}>{skill.description}</p>}
                </li>
              ))}
            </ul>
          ) : <p className="panel-empty">还没有安装技能。在设置 → 技能里可以从 git 仓库安装。</p>}
        </section>
      </div>}
    </div>
  );
}

/* ---------- 内置浏览器 ---------- */

/** 原生页面不吃 CSS 裁剪。按祖先的 overflow 把矩形收到真正看得见的那一块。 */
function visibleStageRect(element: HTMLElement) {
  const rect = element.getBoundingClientRect();
  let top = rect.top;
  let left = rect.left;
  let right = rect.right;
  let bottom = rect.bottom;
  let node = element.parentElement;
  while (node && node !== document.documentElement) {
    const style = getComputedStyle(node);
    const clipsX = /hidden|clip|scroll|auto/.test(style.overflowX);
    const clipsY = /hidden|clip|scroll|auto/.test(style.overflowY);
    if (clipsX || clipsY) {
      const box = node.getBoundingClientRect();
      if (clipsX) {
        left = Math.max(left, box.left);
        right = Math.min(right, box.right);
      }
      if (clipsY) {
        top = Math.max(top, box.top);
        bottom = Math.min(bottom, box.bottom);
      }
    }
    node = node.parentElement;
  }
  for (const host of [document.getElementById('activity'), element.closest('#panel-browser')]) {
    if (!host) continue;
    const box = host.getBoundingClientRect();
    left = Math.max(left, box.left);
    top = Math.max(top, box.top);
    right = Math.min(right, box.right);
    bottom = Math.min(bottom, box.bottom);
  }
  left = Math.max(0, left);
  top = Math.max(0, top);
  right = Math.min(window.innerWidth, right);
  bottom = Math.min(window.innerHeight, bottom);
  return {
    x: left,
    y: top,
    width: Math.max(0, right - left),
    height: Math.max(0, bottom - top)
  };
}

function recentHost(url: string) {
  try {
    return new URL(url).host;
  } catch {
    return String(url || '').replace(/^https?:\/\//, '');
  }
}

function RecentMark({ item }: { item: BrowserRecent }) {
  const [broken, setBroken] = useState(false);
  const mark = (item.title || item.url || '·').trim().slice(0, 1).toUpperCase();
  if (!item.favicon || broken) return <span className="browser-recent-mark">{mark}</span>;
  return <img src={item.favicon} alt="" onError={() => setBroken(true)} />;
}

function cleanLaunchNote(text?: string) {
  return String(text || '')
    .replace(/（浏览器进程自上次使用后重启过[\s\S]*?）/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function BrowserPanel({ coveredByTools }: { coveredByTools: boolean }) {
  const bridge = window.pilotDesktop;
  return bridge
    ? <DesktopBrowserPanel bridge={bridge} coveredByTools={coveredByTools} />
    : <WebBrowserPanel />;
}

function WebBrowserPanel() {
  const sessionId = useStore(state => state.sessionId);
  const mirror = useStore(state => state.ui.mirror);
  const status = useStore(state => state.ui.browserStatus);
  const [launching, setLaunching] = useState(false);
  const [opened, setOpened] = useState(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const target = /^https?:\/\//i.test(mirror.url || '') ? userPageUrl(mirror.url) : null;
  const page = target ? new URL(target) : null;
  const canOpenTab = page && !page.username && !page.password;

  useEffect(() => {
    if (!sessionId) return;
    useStore.getState().loadBrowserStatus();
    useStore.getState().ensureMirror();
  }, [sessionId]);

  async function openWindow() {
    setLaunching(true); setError(''); setNotice('');
    try {
      const data = await api.openBrowserWindow();
      const message = cleanLaunchNote(data?.message);
      if (!data?.ok) {
        setError(message || '无法打开内置浏览器');
      } else {
        setOpened(true); setNotice(message || '浏览器窗口已打开。');
      }
    } catch (failure) {
      setError(errorInfo(failure).message || '无法打开内置浏览器');
    } finally {
      setLaunching(false);
    }
  }

  return <div id="panel-browser" className="panel browser-panel browser-web">
    <EmptyState className="inspector-empty" title="在单独窗口查看浏览器"
      description="内置浏览器窗口由运行 Pilot 的电脑打开。"
      actions={<Button label={opened ? '显示窗口' : '打开内置浏览器'} variant="primary" isLoading={launching} onClick={openWindow} />} isCompact />
    {error && <Banner className="inspector-banner" status="error" role="alert" title={error}
      description={canOpenTab ? '可用普通浏览器打开当前网址。' : '可用 Pilot 桌面版浏览，或在普通浏览器中新开标签页。'} />}
    {notice && <p role="status" className="inspector-status">{notice}</p>}
    {canOpenTab && <div className="browser-web-alternative"><Button label="在新标签页打开当前网址" href={page.href} target="_blank" rel="noreferrer" /></div>}
    {!error && status?.capable === false && status.reason && <p className="inspector-status">{status.reason}</p>}
    {!error && !notice && mirror.headless && <p className="inspector-status">
      智能体的浏览器在后台运行。需要共看时，可将 pilot.browser.headless 设为 false 后重启。
    </p>}
  </div>;
}

function DesktopBrowserPanel({ bridge, coveredByTools }: { bridge: DesktopBridge; coveredByTools: boolean }) {
  const sessionId = useStore(state => state.sessionId);
  const workspace = useStore(state => state.workspace);
  const stageRef = useRef<HTMLDivElement>(null);
  const addressRef = useRef<HTMLInputElement>(null);
  const menuRef = useRef<HTMLButtonElement>(null);
  const [state, setState] = useState<DesktopBrowserState | null>(null);
  const [address, setAddress] = useState('');
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState('');
  const [findOpen, setFindOpen] = useState(false);
  const [findText, setFindText] = useState('');
  const [log, setLog] = useState<{ network: boolean; text: string } | null>(null);
  const tabs = state?.tabs || [];
  const active = tabs.find(tab => tab.id === state?.activeId);
  const freshTab = !state?.url || state.url.includes('/marker/');
  const networkLog = log?.network;

  const invoke = async <T,>(action: () => Promise<T>): Promise<T | null> => {
    setError('');
    try {
      const result = await action();
      if (result && typeof result === 'object' && 'ok' in result && result.ok === false) {
        throw new Error('error' in result && typeof result.error === 'string' ? result.error : '浏览器操作失败');
      }
      return result;
    } catch (failure) {
      setError(errorInfo(failure).message || '无法连接内置浏览器');
      return null;
    }
  };

  useEffect(() => {
    bridge.setWorkspace(workspace || '');
  }, [bridge, workspace]);

  useEffect(() => {
    const stopState = bridge.onState(setState);
    const stopFocus = bridge.focusAddress(() => { addressRef.current?.focus(); addressRef.current?.select(); });
    const stopDownload = bridge.onDownload?.(info => {
      const done = info?.state === 'completed';
      useStore.getState().showNotice(done ? `已下载 ${info.name}` : `下载没有完成：${info?.name || ''}`);
      if (!done) setError('下载没有完成，请从页面重新下载。');
    });
    const stopQuote = bridge.onQuote?.(text => addPageQuote('页面摘录', text));
    return () => { stopState(); stopFocus(); stopDownload?.(); stopQuote?.(); };
  }, [bridge]);

  useEffect(() => {
    let active = true;
    setError('');
    if (sessionId) bridge.ensureTab(sessionId).catch(failure => {
      if (active) setError(errorInfo(failure).message || '无法打开当前任务的浏览器');
    });
    return () => { active = false; };
  }, [bridge, sessionId]);

  useEffect(() => {
    if (!editing) setAddress(freshTab ? '' : (state?.url || ''));
  }, [editing, freshTab, state?.url]);

  useLayoutEffect(() => {
    const stage = stageRef.current;
    if (!stage) return;
    const hasPopoverSelector = typeof CSS !== 'undefined' && CSS.supports?.('selector(:popover-open)');
    const push = () => {
      const rect = visibleStageRect(stage);
      const covered = coveredByTools
        || document.querySelector('dialog[open], [role="dialog"][aria-modal="true"]:not([hidden])')
        || (hasPopoverSelector && document.querySelector('[popover]:popover-open'));
      bridge.setBounds({
        ...rect,
        visible: rect.width >= 40 && rect.height >= 40 && !freshTab
          && !covered && document.visibilityState === 'visible'
      });
    };
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(push) : null;
    observer?.observe(stage);
    window.addEventListener('resize', push);
    document.addEventListener('scroll', push, true);
    document.addEventListener('visibilitychange', push);
    const timer = setInterval(push, 400);
    push();
    return () => {
      observer?.disconnect();
      window.removeEventListener('resize', push);
      document.removeEventListener('scroll', push, true);
      document.removeEventListener('visibilitychange', push);
      clearInterval(timer);
      bridge.setBounds({ visible: false, x: 0, y: 0, width: 0, height: 0 });
    };
  }, [bridge, coveredByTools, freshTab]);

  useEffect(() => {
    if (networkLog === undefined) return;
    return bridge.onLog?.(entry => {
      if (Boolean(entry?.network) !== networkLog || !entry?.line) return;
      setLog(current => current && { network: current.network, text: current.text ? `${current.text}\n${entry.line}` : entry.line });
    });
  }, [bridge, networkLog]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || !(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== 'l') return;
      if (document.querySelector('dialog[open], [role="dialog"][aria-modal="true"]')) return;
      event.preventDefault();
      addressRef.current?.focus();
      addressRef.current?.select();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  function submitAddress(event: React.FormEvent) {
    event.preventDefault();
    if (!address.trim()) { addressRef.current?.focus(); return; }
    const page = userPageUrl(address);
    if (!page) { setError('地址无效，请输入有效网址。'); return; }
    setEditing(false);
    void invoke(() => bridge.navigate(page));
  }

  async function openMenu() {
    const action = await invoke(() => bridge.openMenu());
    if (action === 'find') setFindOpen(true);
    if (action === 'console' || action === 'network') {
      const network = action === 'network';
      if (log?.network === network) { setLog(null); return; }
      const result = await invoke(() => bridge.logs(network));
      if (result) setLog({ network, text: result.text || '' });
    }
    if (action === 'pick') {
      const result = await invoke(() => bridge.pick());
      if (result?.text) addPageQuote('页面元素引用', result.text);
    }
  }

  function closeFind() {
    setFindOpen(false); setFindText('');
    void invoke(() => bridge.find(''));
    menuRef.current?.focus();
  }

  return <div id="panel-browser" className="panel browser-panel is-desktop">
    <div className="browser-tabbar">
      <TabList role="tablist" aria-label="浏览器标签" className="browser-tablist" size="sm" value={state?.activeId || ''}
        onChange={id => { void invoke(() => bridge.selectTab(id)); }}>
        {tabs.map(tab => <Tab key={tab.id} value={tab.id} label={tab.title || '新标签'}
          panelId="mirror-stage"
          icon={tab.favicon ? <img className="browser-tab-icon" src={tab.favicon} alt="" /> : undefined} />)}
      </TabList>
      {active && <IconButton label={`关闭 ${active.title || '标签'}`} icon={<Icon name="close" />}
        variant="ghost" size="sm" onClick={() => void invoke(() => bridge.closeTab(active.id))} />}
      <IconButton label="新标签" icon={<span aria-hidden="true">+</span>}
        variant="ghost" size="sm" onClick={() => void invoke(() => bridge.newTab())} />
    </div>
    <div className="browser-chrome">
      <div className="browser-bar">
        <IconButton label="后退" icon={<Icon name="chevron" className="points-back" />} variant="ghost" size="sm"
          isDisabled={!state?.canGoBack} onClick={() => void invoke(() => bridge.back())} />
        <IconButton label="前进" icon={<Icon name="chevron" />} variant="ghost" size="sm"
          isDisabled={!state?.canGoForward} onClick={() => void invoke(() => bridge.forward())} />
        <IconButton label={state?.loading ? '停止加载' : '刷新页面'}
          icon={<Icon name={state?.loading ? 'close' : 'refresh'} />} variant="ghost" size="sm"
          isDisabled={!active} onClick={() => void invoke(() => state?.loading ? bridge.stop() : bridge.reload())} />
        <form className="browser-address-form" onSubmit={submitAddress} aria-busy={!!state?.loading}>
          <TextInput ref={addressRef} label="地址栏" isLabelHidden placeholder="输入网址" value={address} onChange={setAddress}
            autoComplete="off" inputMode="url" enterKeyHint="go" hasSpellCheck={false} hasAutoCorrect={false}
            containerClassName="inspector-grow" onFocus={() => { setEditing(true); addressRef.current?.select(); }} onBlur={() => setEditing(false)}
            onKeyDown={event => { if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault(); }} />
        </form>
        <IconButton ref={menuRef} label="浏览器菜单" icon={<Icon name="more" />} variant="ghost" size="sm" onClick={openMenu} />
      </div>
      {findOpen && <form className="browser-find-form" onSubmit={event => { event.preventDefault(); void invoke(() => bridge.find(findText)); }}
        onKeyDown={event => { if (event.key === 'Escape' && !event.nativeEvent.isComposing) { event.preventDefault(); event.stopPropagation(); closeFind(); } }}>
        <TextInput label="页内查找" isLabelHidden placeholder="在页面中查找" value={findText} onChange={setFindText}
          containerClassName="inspector-grow" hasAutoFocus
          onKeyDown={event => { if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault(); }} />
        <Button type="submit" label="查找" />
        <IconButton label="关闭页内查找" icon={<Icon name="close" />} variant="ghost" onClick={closeFind} />
      </form>}
    </div>
    {(error || state?.error) && <Banner className="inspector-banner" status="error" role="alert" title={error || state?.error} />}
    <div id="mirror-stage" className={`mirror-stage${freshTab ? ' is-fresh' : ''}`} ref={stageRef} role="tabpanel" aria-label="内置浏览器画面">
      {freshTab ? <div className="browser-newt">
        <EmptyState title="新标签页" description="从上方地址栏打开网页，或继续最近访问的页面。" isCompact />
        {!!state?.recents?.length && <div className="browser-recents">
          <p>最近访问</p>
          {state.recents.map(item => <button key={item.url} type="button" className="browser-recent"
            onClick={() => void invoke(() => bridge.navigate(item.url))}>
            <RecentMark item={item} />
            <span className="browser-recent-copy"><strong>{item.title || recentHost(item.url)}</strong><em>{recentHost(item.url)}</em></span>
          </button>)}
        </div>}
      </div> : <div className="mirror-empty">{state?.cover && <img className="browser-cover" src={state.cover} alt="" />}</div>}
    </div>
    {log && <div className="browser-console">
      <div className="browser-console-head">
        <span>{log.network ? '网络记录' : '页面控制台'}</span>
        <IconButton label="关闭控制台" icon={<Icon name="close" />} variant="ghost" size="sm" onClick={() => setLog(null)} />
      </div>
      <pre className="browser-console-body" tabIndex={0}>{log.text || (log.network ? '没有网络记录' : '没有控制台记录')}</pre>
    </div>}
    {state?.emulated && <div className="browser-status">
      <span id="mirror-hint">设备模拟 ${state.emulated.width}×${state.emulated.height}</span>
      <Button label="还原" variant="ghost" size="sm" onClick={() => void invoke(() => bridge.resetEmulation())} />
    </div>}
  </div>;
}

function addPageQuote(label: string, text: string) {
  const state = useStore.getState();
  if (!text || !state.composerApi) return;
  const quoted = text.split('\n').map(line => `> ${line}`).join('\n');
  state.composerApi.appendText(`\n> ${label}\n${quoted}\n\n`);
  if (window.matchMedia('(max-width: 960px)').matches) state.setUi({ inspectorOpen: false });
  requestAnimationFrame(() => state.composerApi?.focus());
}
