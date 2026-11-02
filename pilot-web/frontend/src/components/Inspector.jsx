import React, { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { normalizePath, parseDiff } from '../lib/diff.js';
import { sortChangeFiles, splitPath, statusKind } from '../lib/changes.js';
import { ErrorBoundary } from './ErrorBoundary.jsx';
import { isCommandActive, commandStatusLabel, stripAnsi } from '../lib/command-tasks.js';
import { embeddablePageUrl, frameSrc, keyCommand, pauseToggleLabel, pointFromEvent, shouldEmbedPage, userPageUrl } from '../lib/browser-mirror.js';
import { setMirrorPainter } from '../store.js';
import { formatTime } from '../thread-utils.js';
import { SideChatPanel } from './SideChat.jsx';
import { MarkdownBody } from './MarkdownBody.jsx';

const TABS = [
  { id: 'chat', label: '对话', title: '侧边对话：问和主线无关的问题，不打断正在跑的任务' },
  { id: 'changes', label: '变更', title: '工作区改动：逐个文件看差异、撤销' },
  { id: 'preview', label: '预览', title: '只读预览文件内容' },
  { id: 'commands', label: '命令', title: '命令任务、实时输出与停止' },
  { id: 'capabilities', label: '能力', title: '当前生效的工具、技能与 MCP 服务器' },
  { id: 'browser', label: '浏览器', title: '内置浏览器：直接输地址使用，也能看到智能体的操作' },
  { id: 'activity', label: '活动', title: '事件时间线' }
];

/** 选择器默认值必须稳定，否则每次返回新数组会让 zustand 反复触发重渲染。 */
const NO_TASKS = [];

/** 右侧工作区：对话 / 变更 / 预览 / 命令 / 能力 / 浏览器镜像 / 活动时间线。 */
export function Inspector() {
  const tab = useStore((state) => state.ui.inspectorTab);
  const open = useStore((state) => state.ui.inspectorOpen);
  const navRef = useRef(null);

  /*
   * 7 个页签在窄面板里放不下，横向滚动时选中的那个可能滑出视野；
   * 切换后把它滚回可见区域，用户才知道自己在哪一页。
   */
  useEffect(() => {
    const active = navRef.current?.querySelector('.tab.is-on');
    active?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
  }, [tab]);

  if (!open) {
    return null;
  }
  return (
    <aside id="activity" className="activity inspector is-rail">
      <div className="inspector-main">
      {tab === 'activity' && <div className="inspector-head"><Filters /></div>}
      <div className="inspector-body">
        {/* 每个面板单独兜底：某个页签渲染出错时只废掉这一块，别把整页卸载成白屏 */}
        <ErrorBoundary key={tab} name={TABS.find((item) => item.id === tab)?.label || '面板'} onReset={() => useStore.getState().setUi({ inspectorTab: 'activity' })}>
          {tab === 'chat' && <SideChatPanel />}
          {tab === 'changes' && <ChangesPanel />}
          {tab === 'preview' && <PreviewPanel />}
          {tab === 'commands' && <CommandsPanel />}
          {tab === 'capabilities' && <CapabilitiesPanel />}
          {tab === 'browser' && <BrowserPanel />}
          {tab === 'activity' && <ActivityPanel />}
        </ErrorBoundary>
      </div>
      </div>
      <nav id="panel-tabs" className="inspector-rail" role="tablist" aria-label="面板切换" ref={navRef}>
        {TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            className={tab === item.id ? 'is-on' : ''}
            role="tab"
            title={item.title}
            aria-label={item.label}
            aria-selected={tab === item.id}
            onClick={() => useStore.getState().setUi({ inspectorTab: item.id })}
          >
            <RailIcon id={item.id} />
            {item.id === 'changes' && <ChangeBadge />}
            {item.id === 'commands' && <CommandDot />}
            {item.id === 'browser' && <BrowserDot />}
          </button>
        ))}
      </nav>
    </aside>
  );
}

function RailIcon({ id }) {
  const common = { viewBox: '0 0 16 16', 'aria-hidden': true };
  if (id === 'chat') return <svg {...common}><path d="M3 3.5h10v7H6.2L3 12.8z" fill="none" stroke="currentColor" strokeWidth="1.3" /></svg>;
  if (id === 'changes') return <svg {...common}><path d="M4 3.5h5.2L12 6.2V12.5H4z" fill="none" stroke="currentColor" strokeWidth="1.3" /></svg>;
  if (id === 'preview') return <svg {...common}><path d="M2.5 8s2.2-3.5 5.5-3.5S13.5 8 13.5 8 11.3 11.5 8 11.5 2.5 8 2.5 8z" fill="none" stroke="currentColor" strokeWidth="1.3" /><circle cx="8" cy="8" r="1.4" fill="currentColor" /></svg>;
  if (id === 'commands') return <svg {...common}><path d="M3.5 5.2 6.2 8 3.5 10.8M7.4 11h5" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" /></svg>;
  if (id === 'capabilities') return <svg {...common}><circle cx="8" cy="8" r="2" fill="none" stroke="currentColor" strokeWidth="1.3" /><path d="M8 2.8v1.6M8 11.6v1.6M2.8 8h1.6M11.6 8h1.6" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" /></svg>;
  if (id === 'browser') return <svg {...common}><circle cx="8" cy="8" r="5" fill="none" stroke="currentColor" strokeWidth="1.3" /><path d="M3 8h10M8 3c1.6 1.8 1.6 8.2 0 10M8 3c-1.6 1.8-1.6 8.2 0 10" fill="none" stroke="currentColor" strokeWidth="1.1" /></svg>;
  return <svg {...common}><path d="M3.5 4h9M3.5 8h9M3.5 12h6" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" /></svg>;
}

function ChangeBadge() {
  const report = useStore((state) => state.changeReport);
  // 未跟踪文件动辄几百个，页签上只显示真正的改动数，否则和面板里的「N 个文件」对不上
  const files = Array.isArray(report?.files) ? report.files : [];
  const count = files.filter((file) => file.status !== '??').length;
  return count ? <span className="tab-count">{count}</span> : null;
}

/** 有命令还在跑时页签上点一下：不用切过去也知道后台没空着。 */
function CommandDot() {
  const tasks = useStore((state) => state.commandTasks || NO_TASKS);
  return tasks.some((task) => isCommandActive(task)) ? <span className="tab-dot is-pulse" /> : null;
}

function BrowserDot() {
  const dot = useStore((state) => state.ui.browserDot);
  const live = useStore((state) => state.ui.mirror.mode === 'live');
  return (dot || live) ? <span className="tab-dot" /> : null;
}

function Filters() {
  const current = useStore((state) => state.ui.filters);
  const timeline = useStore((state) => state.timeline);
  const options = [['all', '全部'], ['tool', '工具'], ['approval', '审批'], ['run', '运行']];

  // 每个筛选后面跟条数：不用切来切去试，就知道哪一类有事发生
  const counts = useMemo(() => {
    const map = { all: timeline.length, tool: 0, approval: 0, run: 0 };
    timeline.forEach((item) => {
      if (map[item.kind] != null) {
        map[item.kind] += 1;
      }
    });
    return map;
  }, [timeline]);

  return (
    <div id="filters" className="filters" role="tablist" aria-label="事件过滤">
      {options.map(([value, label]) => (
        <button
          key={value}
          type="button"
          className={`filter${current === value ? ' is-on' : ''}`}
          role="tab"
          aria-selected={current === value}
          title={`只看${label}`}
          onClick={() => useStore.getState().setFilters(value)}
        >
          {label}
          <span className="filter-count">{counts[value]}</span>
        </button>
      ))}
    </div>
  );
}

/* ---------- 活动时间线 ---------- */

function ActivityPanel() {
  const timeline = useStore((state) => state.timeline);
  const filter = useStore((state) => state.ui.filters);
  const [query, setQuery] = useState('');
  const [openKeys, setOpenKeys] = useState(() => new Set());
  const listRef = useRef(null);
  const stickRef = useRef(true);

  const byKind = filter === 'all' ? timeline : timeline.filter((item) => item.kind === filter);
  const keyword = query.trim().toLowerCase();
  const items = keyword ? byKind.filter((item) => matchesActivity(item, keyword)) : byKind;

  const toggle = useCallback((key) => setOpenKeys((current) => {
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
    return <p id="timeline-empty" className="panel-empty">还没有活动。发一条消息试试。</p>;
  }

  /*
   * run.usage 一轮会刷几十条，连续出现时折成一行「×N」，
   * 否则时间线里全是用量事件，工具调用反而被冲走。
   */
  const groups = [];
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
        <input
          className="activity-search-input"
          placeholder="搜索事件类型 / 标题 / 详情"
          aria-label="搜索活动"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <span className="activity-search-count">
          {keyword ? `${items.length} / ${byKind.length}` : `${byKind.length} 条`}
        </span>
      </div>
      {!items.length && <p className="panel-empty">没有匹配的活动。</p>}
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

function matchesActivity(item, keyword) {
  return [item.type, item.title, item.detail, item.foot, item.status]
    .some((value) => String(value || '').toLowerCase().includes(keyword));
}

/**
 * 每条活动单独 memo：流式增量只改最后一张卡，其余几百条不该跟着每帧重渲染。
 * 点卡片展开完整内容（默认两行截断），长脚本才不需要整屏滚动。
 */
const ActivityItem = memo(function ActivityItem({ item, open, onToggle }) {
  return (
    <li
      className={`event tone-${item.tone}${open ? ' is-open' : ''}`}
      data-kind={item.kind}
      onClick={() => onToggle(item.key)}
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
      {item.actions && item.callId && <ApprovalInline callId={item.callId} />}
    </li>
  );
});

function ApprovalInline({ callId }) {
  const pending = useStore((state) => state.pendingApprovals[callId]);
  if (!pending) {
    return null;
  }
  return (
    <div className="approval-actions" onClick={(event) => event.stopPropagation()}>
      <button type="button" className="btn btn-primary" onClick={() => useStore.getState().resolveApproval(callId, true)}>批准</button>
      <button type="button" className="btn" onClick={() => useStore.getState().resolveApproval(callId, false)}>拒绝</button>
    </div>
  );
}

/* ---------- 变更面板 ---------- */

function ChangesPanel() {
  const report = useStore((state) => state.changeReport);
  const state = useStore((state) => state.changesState);
  const sessionId = useStore((state) => state.sessionId);
  const view = useStore((state) => state.ui.changeView);
  const [branchName, setBranchName] = useState('');
  const [showUntracked, setShowUntracked] = useState(false);
  const [query, setQuery] = useState('');
  /** 待确认的撤销：{ type: 'one', path } | { type: 'all' }，撤销是不可逆操作，必须二次确认。 */
  const [confirm, setConfirm] = useState(null);

  /*
   * 后端 /diff 的 files 是数组、没有顶层 totals：增删行要从每个文件累加，
   * 之前直接读 report.totals 会让摘要永远是 +0 -0。
   *
   * 未跟踪文件（git status 的 ??）单列：它们的「+行数」其实是整个文件的行数，
   * 混进总计会把 +984 变成 +35757，列表也会被几十个临时文件淹掉。
   */
  const files = useMemo(() => sortChangeFiles(Array.isArray(report?.files) ? report.files : []), [report]);
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
  const viewIndex = trackedPaths.indexOf(view);

  useEffect(() => {
    if (sessionId) {
      useStore.getState().loadChanges();
    }
  }, [sessionId]);

  // 换了文件就把上一行的「确认撤销」收起来，免得确认框停在一个已经不显示的文件上
  useEffect(() => {
    setConfirm((current) => (current?.type === 'one' && current.path !== view ? null : current));
  }, [view]);

  /*
   * 键盘浏览（对齐 Codex 的审阅面板）：焦点在面板里时，上下键换文件、Esc 关闭差异。
   * 输入框里不抢键，方向键仍然用来挪光标。
   */
  useEffect(() => {
    const onKey = (event) => {
      if (event.altKey || event.ctrlKey || event.metaKey) {
        return;
      }
      const panel = document.getElementById('panel-changes');
      const active = document.activeElement;
      if (!panel || !active || !panel.contains(active)) {
        return;
      }
      if (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable) {
        return;
      }
      if (event.key === 'Escape') {
        event.preventDefault();
        if (confirm) {
          setConfirm(null);
        } else {
          useStore.getState().setUi({ changeView: '' });
        }
        return;
      }
      if (!view || !trackedPaths.length || (event.key !== 'ArrowDown' && event.key !== 'ArrowUp')) {
        return;
      }
      event.preventDefault();
      const current = viewIndex < 0 ? 0 : viewIndex;
      const next = event.key === 'ArrowDown'
        ? Math.min(trackedPaths.length - 1, current + 1)
        : Math.max(0, current - 1);
      useStore.getState().setUi({ changeView: trackedPaths[next] });
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [view, viewIndex, trackedPaths, confirm]);

  const restore = async (paths, all) => {
    setConfirm(null);
    await useStore.getState().restoreChanges(paths, all);
  };

  // 还没开始任务时没有会话，也就没有工作区可读；给一句明确的话，别显示「没有改动」
  if (!sessionId) {
    return <p className="panel-empty">开始一个任务后，这里会显示工作区的改动与差异。</p>;
  }

  return (
    <div id="panel-changes" className="panel">
      <div className="changes-bar">
        <div className="changes-meta">
          {empty ? (
            <span id="changes-totals" className="changes-totals">
              <span className="changes-count">{isGit ? '没有改动' : '不是 git 仓库'}</span>
            </span>
          ) : (
            <>
              <span id="changes-totals" className="changes-totals">
                <span className="changes-count">{tracked.length} 个文件</span>
                <span className="add">+{totals.added}</span>
                <span className="del">−{totals.removed}</span>
              </span>
              {untracked.length > 0 && (
                <button
                  type="button"
                  className={`changes-untracked${showUntracked ? ' is-on' : ''}`}
                  title="git 里还没纳入版本管理的文件，不计入增删行"
                  onClick={() => setShowUntracked((open) => !open)}
                >
                  未跟踪 {untracked.length}
                </button>
              )}
            </>
          )}
        </div>
        <div className="changes-actions">
          {confirm?.type === 'all' ? (
            <>
              <span className="changes-confirm-hint">撤销全部 {files.length} 个改动？</span>
              <button type="button" className="btn btn-danger" disabled={restoring} onClick={() => restore([], true)}>确认撤销</button>
              <button type="button" className="btn" disabled={restoring} onClick={() => setConfirm(null)}>取消</button>
            </>
          ) : (
            <>
              <input
                className="branch-input"
                placeholder="新分支"
                aria-label="新分支名"
                value={branchName}
                onChange={(event) => setBranchName(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && branchName) {
                    useStore.getState().createBranch(branchName);
                    setBranchName('');
                  }
                }}
              />
              <button type="button" className="btn" id="branch-create" disabled={!branchName} onClick={() => { useStore.getState().createBranch(branchName); setBranchName(''); }}>建分支</button>
              {!empty && (
                <button
                  type="button"
                  className="btn btn-danger"
                  id="changes-undo-all"
                  title="把所有改动恢复成 HEAD 的样子（未跟踪文件会被删除）"
                  disabled={restoring}
                  onClick={() => { setConfirm({ type: 'all' }); setShowUntracked(false); }}
                >撤销全部</button>
              )}
              <button
                type="button"
                className="icon-btn"
                id="changes-refresh"
                title="刷新变更"
                aria-label="刷新变更"
                disabled={restoring}
                onClick={() => useStore.getState().loadChanges()}
              >
                <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M13 8a5 5 0 1 1-1.6-3.7M13 2.6V5.4h-2.8" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
              </button>
            </>
          )}
        </div>
      </div>
      {restoring && <p className="changes-progress">撤销中…</p>}
      {tracked.length > 5 && (
        <div className="changes-filter">
          <input
            className="changes-search"
            placeholder="筛选文件路径"
            aria-label="筛选变更文件"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
          <span className="changes-filter-count">
            {visibleTracked.length} / {tracked.length}{untracked.length ? ` · 未跟踪 ${untracked.length}` : ''}
          </span>
        </div>
      )}
      <div className="changes-body">
        {state === 'loading' && empty && <p className="panel-empty change-empty">读取变更中…</p>}
        {!empty && (
          <div id="change-files" className="change-files">
            {visibleTracked.map((file) => (
              <ChangeRow
                key={file.path}
                file={file}
                active={view === file.path}
                confirming={confirm?.type === 'one' && confirm.path === file.path}
                busy={restoring}
                onAskUndo={() => setConfirm({ type: 'one', path: file.path })}
                onCancelUndo={() => setConfirm(null)}
                onUndo={() => restore([file.path], false)}
              />
            ))}
            {keyword && !visibleTracked.length && <p className="change-more">没有匹配「{query}」的文件。</p>}
            {untracked.length > 0 && (
              <button
                type="button"
                className={`change-row change-untracked-toggle${showUntracked ? ' is-open' : ''}`}
                onClick={() => setShowUntracked((open) => !open)}
              >
                <span className="change-status is-untracked">?</span>
                <span className="change-row-main">
                  <span className="change-row-name">未跟踪文件 · {untracked.length}</span>
                  <span className="change-row-dir">{showUntracked ? '点击收起' : '没纳入版本管理，点开可看全文'}</span>
                </span>
                <span className="change-row-stat">{showUntracked ? '收起' : '展开'}</span>
              </button>
            )}
            {showUntracked && visibleUntracked.slice(0, UNTRACKED_LIMIT).map((file) => (
              <ChangeRow
                key={file.path}
                file={file}
                active={view === file.path}
                untracked
                confirming={confirm?.type === 'one' && confirm.path === file.path}
                busy={restoring}
                onAskUndo={() => setConfirm({ type: 'one', path: file.path })}
                onCancelUndo={() => setConfirm(null)}
                onUndo={() => restore([file.path], false)}
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
              path={view}
              status={files.find((file) => file.path === view)?.status}
              prevPath={viewIndex > 0 ? trackedPaths[viewIndex - 1] : null}
              nextPath={viewIndex >= 0 && viewIndex < trackedPaths.length - 1 ? trackedPaths[viewIndex + 1] : null}
              index={viewIndex >= 0 ? viewIndex + 1 : 0}
              total={trackedPaths.length}
              busy={restoring}
              onUndo={() => restore([view], false)}
            />
          )
          : (!empty && <p className="panel-empty change-empty">选一个文件看差异。</p>)}
      </div>
    </div>
  );
}

/** 未跟踪文件一次最多列这么多：几百个临时文件全铺出来既卡又没法看。 */
const UNTRACKED_LIMIT = 80;

/** 变更列表的一行：状态徽标 + 文件名（目录弱化）+ 增删行，悬停露出撤销。 */
function ChangeRow({ file, active, untracked, confirming, busy, onAskUndo, onCancelUndo, onUndo }) {
  const { name, dir } = splitPath(file.path);
  if (confirming) {
    return (
      <div className="change-row is-confirm">
        <span className={`change-status is-${statusKind(file.status)}`}>{statusLetter(file.status)}</span>
        <span className="change-row-main">
          <span className="change-row-confirm">
            撤销 <b>{name}</b> 的改动？
          </span>
          <span className="change-row-dir">{untracked ? '未跟踪文件会被删除' : '内容会恢复成 HEAD 的样子'}</span>
        </span>
        <span className="change-row-actions">
          <button type="button" className="btn btn-danger" disabled={busy} onClick={onUndo}>撤销</button>
          <button type="button" className="btn" disabled={busy} onClick={onCancelUndo}>取消</button>
        </span>
      </div>
    );
  }
  return (
    <div className={`change-row-wrap${active ? ' is-active' : ''}`}>
      <button
        type="button"
        className={`change-row${active ? ' is-active' : ''}`}
        title={file.path}
        onClick={() => useStore.getState().setUi({ changeView: file.path })}
      >
        <span className={`change-status is-${statusKind(file.status)}`}>{statusLetter(file.status)}</span>
        <span className="change-row-main">
          <span className="change-row-name">{name}</span>
          {dir && <span className="change-row-dir">{dir}</span>}
        </span>
        {!untracked && (
          <span className="change-row-stat">
            <span className="add">+{Number(file.added || 0)}</span>
            <span className="del">−{Number(file.removed || 0)}</span>
          </span>
        )}
      </button>
      <button
        type="button"
        className="change-undo"
        title={`撤销 ${file.path} 的改动`}
        aria-label={`撤销 ${file.path} 的改动`}
        disabled={busy}
        onClick={onAskUndo}
      >撤销</button>
    </div>
  );
}

/** 单文件差异最多渲染这么多行：再多滚动会明显掉帧。 */
const DIFF_LINE_LIMIT = 4000;

/** git 状态码 → 一个字母的徽标。 */
function statusLetter(status) {
  const raw = String(status || 'M').replace(/\?/g, 'U');
  return raw.slice(0, 1).toUpperCase();
}

function DiffView({ path, status, prevPath, nextPath, index, total, busy, onUndo }) {
  const report = useStore((state) => state.changeReport);
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
  const [patch, setPatch] = useState(null);
  const [patchState, setPatchState] = useState('');
  /** 未跟踪文件不在补丁里，直接展示正文。 */
  const [content, setContent] = useState(null);

  useEffect(() => {
    if (summaryFile || !sessionId) {
      setPatch(null);
      setPatchState('');
      return undefined;
    }
    let alive = true;
    if (untracked) {
      setContent(null);
      api.file(sessionId, path)
        .then((data) => { if (alive) setContent(data?.content ?? ''); })
        .catch(() => { if (alive) setContent('（无法读取该文件）'); });
      return () => { alive = false; };
    }
    setPatch(null);
    setPatchState('loading');
    api.fileDiff(sessionId, path)
      .then((data) => {
        if (!alive) {
          return;
        }
        const files = parseDiff(data?.diff || '');
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
  }, [summaryFile, path, sessionId, untracked]);

  const file = summaryFile || patch?.file || null;
  const close = () => useStore.getState().setUi({ changeView: '' });
  const select = (target) => {
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
        <button
          type="button"
          className="icon-btn diff-nav"
          title="上一个改动文件"
          aria-label="上一个改动文件"
          disabled={!prevPath}
          onClick={() => select(prevPath)}
        >‹</button>
        <button
          type="button"
          className="icon-btn diff-nav"
          title="下一个改动文件"
          aria-label="下一个改动文件"
          disabled={!nextPath}
          onClick={() => select(nextPath)}
        >›</button>
        {confirming ? (
          <>
            <span className="change-view-confirm">撤销这个文件？</span>
            <button type="button" className="btn btn-danger" disabled={busy} onClick={onUndo}>确认</button>
            <button type="button" className="btn" onClick={() => setConfirming(false)}>取消</button>
          </>
        ) : (
          <button
            type="button"
            className="btn btn-danger change-view-undo"
            title="撤销这个文件的改动"
            disabled={busy}
            onClick={() => setConfirming(true)}
          >撤销</button>
        )}
        <button type="button" className="icon-btn change-view-close" title="关闭差异" aria-label="关闭差异" onClick={close}>✕</button>
      </div>
      <div id="change-diff" className="change-diff">
        {file
          ? (
            <>
              {/* index/---/+++ 这些补丁元信息对读代码没帮助，Codex 也不展示 */}
              {file.lines
                .filter((line) => line.kind !== 'meta')
                .slice(0, DIFF_LINE_LIMIT)
                .map((line, index) => (
                  <div key={index} className={`diff-line is-${line.kind}`}>
                    <span className={`n${line.kind === 'del' ? ' is-old' : ''}`}>
                      {line.kind === 'hunk' ? '' : (line.kind === 'del' ? line.old : line.next)}
                    </span>
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
          : untracked
            ? <pre className="diff-raw">{content ?? '读取中…'}</pre>
            : (patchState === 'loading'
              ? <p className="diff-empty">读取差异中…</p>
              : (patchState === 'error'
                ? <p className="diff-empty">读取差异失败，点右上角的刷新再试一次。</p>
                : <p className="diff-empty">这个文件没有可显示的文本差异（可能是二进制、纯重命名或权限变更）。</p>))}
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
  const [wrap, setWrap] = useState(true);

  const changed = useMemo(() => sortChangeFiles(Array.isArray(report?.files) ? report.files : []).slice(0, 12), [report]);
  const isMarkdown = /\.(md|markdown|mdx)$/i.test(path || '');
  const content = data?.content || '';
  const lineCount = content ? content.split('\n').length : 0;

  const copy = async () => {
    if (!content) {
      return;
    }
    try {
      await navigator.clipboard.writeText(content);
      useStore.getState().showNotice('已复制文件内容');
    } catch {
      useStore.getState().showNotice('复制失败，请手动选择内容');
    }
  };

  return (
    <div id="panel-preview" className="panel">
      <div className="panel-toolbar">
        <input
          id="preview-path"
          className="preview-path"
          value={path}
          placeholder="文件路径"
          aria-label="预览文件路径"
          onChange={(event) => useStore.getState().setUi({ previewPath: event.target.value })}
          onKeyDown={(event) => { if (event.key === 'Enter') useStore.getState().openPreview(path); }}
        />
        <button
          type="button"
          className="btn"
          id="preview-refresh"
          disabled={!path.trim()}
          onClick={() => useStore.getState().openPreview(path)}
        >打开</button>
        <button
          type="button"
          className={`btn preview-wrap${wrap ? ' is-on' : ''}`}
          title="长行是否自动换行"
          aria-pressed={wrap}
          onClick={() => setWrap((value) => !value)}
        >换行</button>
        <button type="button" className="btn" disabled={!content} onClick={copy}>复制</button>
      </div>
      {data && !data.binary && (
        <div className="preview-meta">
          <span className="preview-meta-path" title={data.path || path}>{data.path || path}</span>
          <span className="preview-meta-item">{lineCount} 行</span>
          <span className="preview-meta-item">{formatBytes(data.size)}</span>
          {isMarkdown && <span className="preview-meta-tag">Markdown</span>}
          {data.truncated && <span className="preview-meta-tag is-warn">已截断</span>}
        </div>
      )}
      {loading && <p className="panel-empty">读取中…</p>}
      {!loading && data && (
        data.binary
          ? <p className="panel-empty">（二进制文件，无法预览）</p>
          : (isMarkdown
            ? (
              <div className="bubble preview-markdown">
                <MarkdownBody text={data.truncated ? `${content}\n\n…（内容过长，已截断）` : (content || '（空文件）')} />
              </div>
            )
            : (
              <pre id="preview-body" className={`preview-body${wrap ? '' : ' is-nowrap'}`}>
                {data.truncated ? `${content}\n…（内容过长，已截断）` : (content || '（空文件）')}
              </pre>
            ))
      )}
      {!loading && !data && (
        <div className="preview-empty">
          <p>选一个改动文件直接预览，或在上面输入路径。</p>
          {changed.length > 0 && (
            <ul className="preview-suggest">
              {changed.map((file) => (
                <li key={file.path}>
                  <button type="button" title={file.path} onClick={() => useStore.getState().openPreview(file.path)}>
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

function formatBytes(size) {
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
  const logRef = useRef(null);
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
      await navigator.clipboard.writeText(logText);
      useStore.getState().showNotice('已复制命令输出');
    } catch {
      useStore.getState().showNotice('复制失败，请手动选择输出');
    }
  };

  return (
    <div id="panel-commands" className="panel">
      <div className="panel-toolbar">
        <span id="commands-state" className="commands-state">{active.length ? `${active.length} 个命令运行中` : '没有运行中的命令'}</span>
        <button type="button" className="btn" onClick={() => useStore.getState().loadCommandTasks()}>刷新</button>
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
          <div id="commands-empty" className="commands-empty">
            <p>还没有命令任务</p>
            <p className="commands-empty-hint">
              智能体跑构建、测试或脚本时会在这里生成任务；命令在后台继续运行，随时可以回来看输出或停止。
            </p>
          </div>
        )}
      </div>
      {detail && (
        <div id="command-detail" className="command-detail">
          <div className="command-detail-head">
            <p id="command-title" className="command-title">{detail.command || detail.taskId}</p>
            {isCommandActive(detail) && (
              <button
                type="button"
                className="btn btn-danger"
                title="停止这个命令"
                onClick={() => useStore.getState().stopCommand(detail)}
              >停止</button>
            )}
            <button type="button" className="btn" onClick={copyLog}>复制输出</button>
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
            <button type="button" className={`btn${follow ? ' is-on' : ''}`} onClick={() => setFollow((value) => !value)}>
              {follow ? '暂停跟随' : '跟随最新'}
            </button>
          </div>
          <pre
            id="command-log"
            className={`command-log${logText ? '' : ' is-empty'}`}
            ref={logRef}
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
function durationLabel(task, running) {
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

function parseTimestamp(value) {
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

const PERMISSION_LABELS = {
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
  const available = tools.filter((tool) => tool.available).length;

  if (!sessionId) {
    return <p className="panel-empty">开始一个任务后，这里会列出当前可用的工具、技能与 MCP 服务器。</p>;
  }

  return (
    <div id="panel-capabilities" className="panel">
      <div className="panel-toolbar">
        <span className="commands-state">
          {state === 'loading' ? '读取中…' : `${available} / ${tools.length} 个工具可用`}
        </span>
        <button type="button" className="btn" onClick={() => useStore.getState().loadCapabilities()}>刷新</button>
      </div>
      <div className="cap-scroll">
        <section className="cap-section">
          <h3>运行环境</h3>
          <dl className="cap-kv">
            <div><dt>模型</dt><dd>{data?.model || '—'}</dd></div>
            <div><dt>权限</dt><dd>{PERMISSION_LABELS[data?.permissionMode] || data?.permissionMode || '—'}</dd></div>
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
        {state === 'error' && <p className="panel-empty">读取能力清单失败，右上角「刷新」再试一次。</p>}
      </div>
    </div>
  );
}

/* ---------- 内置浏览器 ---------- */

/**
 * 右侧「浏览器」页签：一个真正能用的内置浏览器。
 *
 * 本机页面直接嵌进面板，由浏览器引擎自己排版和滚动。
 * 页面禁止被嵌入时才退回截图，点击和滚轮仍转发给同一个 Playwright 页面。
 */
/** 原生页面不吃 CSS 裁剪。按祖先的 overflow 把矩形收到真正看得见的那一块。 */
function visibleStageRect(element) {
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

function recentHost(url) {
  try {
    return new URL(url).host;
  } catch {
    return String(url || '').replace(/^https?:\/\//, '');
  }
}

function RecentMark({ item }) {
  const [broken, setBroken] = useState(false);
  const mark = (item.title || item.url || '·').trim().slice(0, 1).toUpperCase();
  if (!item.favicon || broken) return <span className="browser-recent-mark">{mark}</span>;
  return <img src={item.favicon} alt="" onError={() => setBroken(true)} />;
}

function cleanLaunchNote(text) {
  return String(text || '')
    .replace(/（浏览器进程自上次使用后重启过[\s\S]*?）/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function BrowserPanel() {
  const sessionId = useStore((state) => state.sessionId);
  const mirror = useStore((state) => state.ui.mirror);
  const status = useStore((state) => state.ui.browserStatus);
  const stageRef = useRef(null);
  const imageRef = useRef(null);
  const pendingSrc = useRef('');
  const sawFrame = useRef(false);
  const [sawFrameTick, setSawFrameTick] = useState(false);
  const addressRef = useRef(null);
  const [address, setAddress] = useState('');
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  /** 导航进行中：刷新按钮变成「停止」，地址栏下方走一条进度。 */
  const [loading, setLoading] = useState(false);
  /** 选择元素模式：下一次点击不转发，而是把元素描述成引用。 */
  const [picking, setPicking] = useState(false);
  const [consoleOpen, setConsoleOpen] = useState(false);
  const [consoleText, setConsoleText] = useState('');
  const [networkLog, setNetworkLog] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [findOpen, setFindOpen] = useState(false);
  const [findText, setFindText] = useState('');
  const menuRef = useRef(null);
  const freshTabRef = useRef(false);
  const desktopOn = typeof window !== 'undefined' && !!window.pilotDesktop;
  const [desktopState, setDesktopState] = useState(null);
  /** 已经同步给页面的视口尺寸：避免重复下发 setViewportSize。 */
  const viewportSize = useRef({ width: 0, height: 0 });
  // 连续敲字符合并成一次 insertText；回车/退格这类整键立刻发
  const typing = useRef({ tokens: [], timer: null });
  const wheel = useRef({ deltaY: 0, x: 0, y: 0, timer: null });

  useEffect(() => {
    if (desktopOn) {
      window.pilotDesktop.setWorkspace(useStore.getState().workspace || '');
      if (sessionId) window.pilotDesktop.ensureTab(sessionId);
      const stopState = window.pilotDesktop.onState(setDesktopState);
      const stopFocus = window.pilotDesktop.focusAddress(() => addressRef.current?.focus());
      const stopDownload = window.pilotDesktop.onDownload?.((info) => {
        const done = info?.state === 'completed';
        useStore.getState().showNotice(done ? `已下载 ${info.name}` : `下载没有完成：${info?.name || ''}`);
      });
      const stopQuote = window.pilotDesktop.onQuote?.((text) => {
        const api = useStore.getState().composerApi;
        if (!text || !api) return;
        const quoted = String(text).split('\n').map((line) => `> ${line}`).join('\n');
        api.appendText(`\n> 页面摘录\n${quoted}\n\n`);
        api.focus();
      });
      return () => {
        stopState();
        stopFocus();
        stopDownload?.();
        stopQuote?.();
        window.pilotDesktop.setBounds({ visible: false, x: 0, y: 0, width: 0, height: 0 });
      };
    }
    if (sessionId) {
      useStore.getState().loadBrowserStatus();
      // 打开页签就确保镜像流连着：运行时可能比页面晚就绪，第一次订阅会被回 204
      useStore.getState().ensureMirror();
    }
    sawFrame.current = false;
    pendingSrc.current = '';
    setSawFrameTick(false);
    return undefined;
  }, [sessionId, desktopOn]);

  // 截图不进 React 状态：直接写到 img 上，否则每帧一份 base64 会把整个界面拖卡
  useEffect(() => {
    setMirrorPainter((payload) => {
      const src = frameSrc(payload);
      if (!src) {
        return;
      }
      pendingSrc.current = src;
      if (imageRef.current) {
        imageRef.current.src = src;
      }
      if (!sawFrame.current) {
        sawFrame.current = true;
        setSawFrameTick(true);
      }
      setLoading(false);
    });
    return () => setMirrorPainter(null);
  }, []);

  // 地址栏跟随真实页面，用户正在编辑时不要被推回覆盖
  useEffect(() => {
    if (editing) return;
    if (desktopOn) {
      const url = desktopState?.url || '';
      if (!url || url.includes('/marker/')) setAddress('');
      else setAddress(url);
      return;
    }
    setAddress(mirror.url || '');
  }, [mirror.url, editing, desktopOn, desktopState]);

  useEffect(() => {
    if (!desktopOn) return undefined;
    const stage = stageRef.current;
    if (!stage) return undefined;
    const push = () => {
      const rect = visibleStageRect(stage);
      const covered = document.querySelector('[role="dialog"][aria-modal="true"]');
      window.pilotDesktop.setBounds({
        x: rect.x,
        y: rect.y,
        width: rect.width,
        height: rect.height,
        visible: rect.width >= 40 && rect.height >= 40 && !freshTabRef.current
          && !covered && document.visibilityState === 'visible'
      });
    };
    const observer = new ResizeObserver(push);
    observer.observe(stage);
    window.addEventListener('resize', push);
    document.addEventListener('scroll', push, true);
    const timer = setInterval(push, 400);
    push();
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', push);
      document.removeEventListener('scroll', push, true);
      clearInterval(timer);
      window.pilotDesktop.setBounds({ visible: false, x: 0, y: 0, width: 0, height: 0 });
    };
  }, [desktopOn, menuOpen, desktopState?.url]);

  useEffect(() => {
    if (!menuOpen) return undefined;
    const close = (event) => {
      if (!menuRef.current?.contains(event.target)) setMenuOpen(false);
    };
    const onKey = (event) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', onKey);
    };
  }, [menuOpen]);

  useEffect(() => () => {
    if (typing.current.timer) clearTimeout(typing.current.timer);
    if (wheel.current.timer) clearTimeout(wheel.current.timer);
  }, []);

  // 来了新帧就说明页面确实在动，导航的等待可以收；再兜底 12 秒，别让「停止」一直挂着
  useEffect(() => {
    if (loading) {
      setLoading(false);
    }
  }, [mirror.frame]);
  useEffect(() => {
    if (!loading) {
      return undefined;
    }
    const timer = setTimeout(() => setLoading(false), 12000);
    return () => clearTimeout(timer);
  }, [loading]);

  const blocked = status && status.capable === false;
  const reason = status?.reason || '';
  const live = mirror.mode === 'live';
  const pageUrl = shouldEmbedPage(mirror.url, mirror.embed, mirror.frameOptions)
    ? embeddablePageUrl(mirror.url)
    : null;

  useEffect(() => {
    if (!sessionId || desktopOn) {
      return undefined;
    }
    fetch(`/api/sessions/${encodeURIComponent(sessionId)}/browser/capture`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ capture: !pageUrl })
    }).catch(() => {});
    return undefined;
  }, [sessionId, pageUrl]);

  /**
   * 把动作转给服务端。
   *
   * @param quiet 点击/滚轮/键盘这类高频动作传 true：不显示地址栏的「执行中」，
   *              否则每点一下画面地址栏就会闪一下省略号。
   */
  async function command(action, body, quiet = false) {
    if (!sessionId) {
      return null;
    }
    if (!quiet) {
      setBusy(true);
    }
    setError('');
    try {
      const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/browser/${action}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body || {})
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        const detail = data.error || data.message;
        setError(detail ? `${detail}（HTTP ${response.status}）` : `浏览器接口不可用（HTTP ${response.status}）`);
        return null;
      }
      if (data.ok === false) {
        setError(data.error || '浏览器操作失败');
        return null;
      }
      return data;
    } catch (failure) {
      setError(failure.message || '无法连接浏览器运行时');
      return null;
    } finally {
      if (!quiet) {
        setBusy(false);
      }
    }
  }

  function submitAddress(event) {
    event.preventDefault();
    const value = address.trim();
    if (!value) {
      addressRef.current?.focus();
      return;
    }
    if (desktopOn) {
      const page = userPageUrl(value);
      setEditing(false);
      if (!page) {
        setError('地址无效');
        return;
      }
      setError('');
      window.pilotDesktop.navigate(page);
      return;
    }
    setEditing(false);
    setLoading(true);
    useStore.getState().ensureMirror();
    const page = embeddablePageUrl(value);
    if (page && mirror.embed !== false) {
      // 先把真页面嵌出来，不等截图。智能体用的浏览器同时打开同一地址。
      useStore.getState().setUi({ mirror: { ...useStore.getState().ui.mirror, url: page, mode: 'live' } });
    } else {
      syncViewport();
    }
    command('navigate', { url: value });
  }

  /** 导航类动作统一置一次 loading，收到新帧就复位。 */
  function navigate(action) {
    if (desktopOn) {
      if (action === 'back') window.pilotDesktop.back();
      else if (action === 'forward') window.pilotDesktop.forward();
      else if (action === 'stop') window.pilotDesktop.stop();
      else window.pilotDesktop.reload();
      return;
    }
    setLoading(true);
    useStore.getState().ensureMirror();
    if (!pageUrl) {
      syncViewport();
    }
    command(action);
  }

  /**
   * 把面板尺寸发给页面视口，失败静默。
   *
   * <p>导航前先发一次：新页面直接按面板宽度加载，少一次「先按 1280 排版再重排」的闪动；
   * 浏览器还没起来时这次会失败，忽略即可，等第一帧到达后 ResizeObserver 会补上。
   */
  function syncViewport(width, height) {
    const rect = stageRef.current?.getBoundingClientRect();
    const nextWidth = Math.round(Number(width) || rect?.width || 0);
    const nextHeight = Math.round(Number(height) || rect?.height || 0);
    if (!sessionId || nextWidth < 240 || nextHeight < 160) {
      return;
    }
    fetch(`/api/sessions/${encodeURIComponent(sessionId)}/browser/resize`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ width: nextWidth, height: nextHeight })
    }).then(async (response) => {
      const data = await response.json().catch(() => ({}));
      // 只有真的生效才记住：浏览器还没起来时这次会失败，要留给后面的重试
      if (response.ok && data?.ok) {
        viewportSize.current = { width: nextWidth, height: nextHeight };
      }
    }).catch(() => { /* 浏览器没起来，等首帧后补 */ });
  }

  /** 选择元素：把点到的元素描述成引用，塞进主输入框待发送。 */
  async function pickAt(point) {
    setPicking(false);
    const data = await command('pick', { x: point.x, y: point.y }, true);
    if (!data?.text) {
      return;
    }
    const api = useStore.getState().composerApi;
    const quoted = data.text.split('\n').map((line) => `> ${line}`).join('\n');
    if (api) {
      api.appendText(`\n> 页面元素引用（${mirror.url || '当前页面'}）\n${quoted}\n\n`);
      api.focus();
      useStore.getState().showNotice('已把页面元素加入输入框');
    }
  }

  async function toggleConsole(network = false) {
    if (consoleOpen && networkLog === network) {
      setConsoleOpen(false);
      return;
    }
    if (desktopOn) {
      const data = await window.pilotDesktop.logs(network);
      setNetworkLog(network);
      setConsoleText(data?.text || (network ? '没有网络记录' : '没有控制台记录'));
      setConsoleOpen(true);
      return;
    }
    const data = await command('logs', network ? { network: true } : {}, true);
    setNetworkLog(network);
    setConsoleText(data?.text || (network ? '没有网络记录' : '没有控制台记录'));
    setConsoleOpen(true);
  }

  useEffect(() => {
    if (!desktopOn || !consoleOpen || !window.pilotDesktop.onLog) return undefined;
    return window.pilotDesktop.onLog((entry) => {
      if (Boolean(entry?.network) !== networkLog || !entry?.line) return;
      setConsoleText((text) => (text && !text.startsWith('没有') ? `${text}\n${entry.line}` : entry.line));
    });
  }, [desktopOn, consoleOpen, networkLog]);

  useEffect(() => {
    if (!desktopOn) return undefined;
    const onKey = (event) => {
      if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== 'l') return;
      event.preventDefault();
      addressRef.current?.focus();
      addressRef.current?.select();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [desktopOn]);

  async function openDesktopMenu() {
    const action = await window.pilotDesktop.openMenu();
    if (action === 'find') setFindOpen(true);
    if (action === 'console') toggleConsole(false);
    if (action === 'network') toggleConsole(true);
    if (action === 'pick') {
      const result = await window.pilotDesktop.pick();
      if (!result?.text) return;
      const quoted = result.text.split('\n').map((line) => `> ${line}`).join('\n');
      const composer = useStore.getState().composerApi;
      if (composer) {
        composer.appendText(`\n> 页面元素引用\n${quoted}\n\n`);
        composer.focus();
      }
    }
  }

  /**
   * 面板里的指针坐标 → 页面坐标。
   *
   * <p>视口跟着面板走（见下面的 ResizeObserver），所以用帧自带的 w/h 换算就与页面 1:1；
   * 还没收到帧时回退到 sidecar 的默认视口。
   */
  function bindMirrorImage(node) {
    imageRef.current = node;
    if (node && pendingSrc.current) {
      node.src = pendingSrc.current;
    }
  }

  function pointOf(event) {
    const rect = imageRef.current?.getBoundingClientRect();
    return pointFromEvent(event.clientX, event.clientY, rect, mirror.frameW, mirror.frameH);
  }

  function flushTyping() {
    const buffer = typing.current;
    if (buffer.timer) {
      clearTimeout(buffer.timer);
      buffer.timer = null;
    }
    const text = buffer.tokens.join('');
    buffer.tokens = [];
    if (text) {
      command('keys', { text }, true);
    }
  }

  function onStageKeyDown(event) {
    const cmd = keyCommand(event);
    if (!cmd) {
      return;
    }
    event.preventDefault();
    if (cmd.text) {
      typing.current.tokens.push(cmd.text);
      if (!typing.current.timer) {
        typing.current.timer = setTimeout(() => {
          typing.current.timer = null;
          flushTyping();
        }, 60);
      }
      return;
    }
    flushTyping();
    command('keys', { key: cmd.key }, true);
  }

  function onClick(event) {
    if (!live) {
      return;
    }
    stageRef.current?.focus();
    const point = pointOf(event);
    if (!point) {
      return;
    }
    // 选择元素模式下这一击只用来取样，不真的点到页面上
    if (picking) {
      pickAt(point);
      return;
    }
    command('pointer', { action: 'click', x: point.x, y: point.y }, true);
  }

  /*
   * 面板尺寸 → 页面视口。
   *
   * 内嵌浏览器必须这么做：视口跟着面板走，页面按真实宽度做响应式排版，镜像帧的比例
   * 就等于面板比例，画面铺满而不留大片空白，点击坐标也能 1:1 对上。
   * 拖拽分栏时尺寸会连续变化，这里防抖 260ms 再下发，避免每帧一次 setViewportSize。
   */
  useEffect(() => {
    const stage = stageRef.current;
    if (!stage || !live || pageUrl) {
      return undefined;
    }
    // 新画面/新会话：之前记下的尺寸可能属于已经失效的页面，先作废再对齐
    viewportSize.current = { width: 0, height: 0 };
    let timer = null;
    const push = () => {
      const rect = stage.getBoundingClientRect();
      const width = Math.round(rect.width);
      const height = Math.round(rect.height);
      if (width < 240 || height < 160) {
        return;
      }
      const last = viewportSize.current;
      if (last.width === width && last.height === height) {
        return;
      }
      syncViewport(width, height);
    };
    const schedule = () => {
      clearTimeout(timer);
      timer = setTimeout(push, 260);
    };
    const observer = new ResizeObserver(schedule);
    observer.observe(stage);
    schedule();
    return () => {
      observer.disconnect();
      clearTimeout(timer);
    };
  }, [live, sessionId, pageUrl]);

  /*
   * 滚轮要能 preventDefault 才不会把面板自己滚走，而 React 的 onWheel 是 passive 的，
   * 只能在原生监听里挂 non-passive；deltaY 攒 80ms 再发一次，避免一帧一个请求。
   */
  useEffect(() => {
    const stage = stageRef.current;
    if (!stage || !live || pageUrl) {
      return undefined;
    }
    const onWheel = (event) => {
      event.preventDefault();
      const point = pointOf(event);
      if (!point) {
        return;
      }
      const acc = wheel.current;
      acc.deltaY += event.deltaY;
      acc.x = point.x;
      acc.y = point.y;
      if (acc.timer) {
        return;
      }
      acc.timer = setTimeout(() => {
        const deltaY = Math.round(acc.deltaY);
        acc.deltaY = 0;
        acc.timer = null;
        if (deltaY) {
          command('pointer', { action: 'wheel', x: acc.x, y: acc.y, deltaY }, true);
        }
      }, 80);
    };
    stage.addEventListener('wheel', onWheel, { passive: false });
    return () => stage.removeEventListener('wheel', onWheel);
    // 会话切换后命令要发给新会话，所以 sessionId 参与依赖
  }, [live, sessionId, pageUrl]);

  const [launching, setLaunching] = useState(false);
  const [launchNote, setLaunchNote] = useState('');
  const freshTab = desktopOn && (!desktopState?.url || desktopState.url.includes('/marker/'));
  freshTabRef.current = freshTab;
  const pageLoading = desktopOn ? Boolean(desktopState?.loading) : loading;

  async function openDesktopWindow() {
    setLaunching(true);
    setLaunchNote('');
    try {
      const data = await api.openBrowserWindow();
      const message = cleanLaunchNote(data?.message);
      if (data?.ok === false) {
        setLaunchNote(message || '没能打开浏览器窗口');
      } else {
        setLaunchNote(message || '窗口已打开。');
      }
    } catch (failure) {
      const message = failure.message || '';
      setLaunchNote(/404|Not Found/.test(message)
        ? '服务还是刚才那一版。重新从 IDEA 启动一次后再点。'
        : (message || '没能打开浏览器窗口'));
    } finally {
      setLaunching(false);
    }
  }
  const shownUrl = desktopOn && !editing ? (desktopState?.url || '') : address;
  let localPage = false;
  try {
    const host = new URL(shownUrl).hostname.replace(/^\[|\]$/g, '');
    localPage = host === 'localhost' || host === '127.0.0.1' || host === '::1';
  } catch { /* 还不是完整地址 */ }

  if (!desktopOn) {
    return (
      <div id="panel-browser" className="panel browser-panel">
        <div className="mirror-stage">
          <div className="browser-web-launch">
            <p>画面在单独的窗口里，点一下才打开。</p>
            <button type="button" className="btn btn-primary" disabled={launching} onClick={openDesktopWindow}>
              {launching ? '正在打开…' : (/已打开|窗口已/.test(launchNote) ? '显示窗口' : '打开内置浏览器')}
            </button>
            {launchNote && <p className="browser-web-note">{launchNote}</p>}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div id="panel-browser" className={`panel browser-panel${desktopOn ? ' is-desktop' : ''}`}>
      {desktopOn && (
        <div className="browser-tabs" role="tablist" aria-label="浏览器标签">
          {(desktopState?.tabs || []).map((tab) => (
            <div key={tab.id} className={`browser-tab${tab.id === desktopState?.activeId ? ' is-on' : ''}`}>
              <button
                type="button"
                role="tab"
                aria-selected={tab.id === desktopState?.activeId}
                className="browser-tab-label"
                title={!tab.url || tab.url.includes('/marker/') ? (tab.title || '新标签') : tab.url}
                onClick={() => window.pilotDesktop.selectTab(tab.id)}
              >
                {tab.loading
                  ? <span className="browser-tab-spin" aria-hidden="true" />
                  : (tab.favicon ? <img className="browser-tab-icon" src={tab.favicon} alt="" /> : null)}
                <span className="browser-tab-label-text">{tab.title || '新标签'}</span>
              </button>
              <button
                type="button"
                className="browser-tab-close"
                aria-label={`关闭 ${tab.title || '标签'}`}
                title="关闭标签"
                onClick={() => window.pilotDesktop.closeTab(tab.id)}
              >×</button>
            </div>
          ))}
          <button type="button" className="browser-tab-add" title="新标签" aria-label="新标签" onClick={() => window.pilotDesktop.newTab()}>+</button>
        </div>
      )}
      <div className="browser-chrome" ref={menuRef}>
      <div className="browser-bar">
        <button
          type="button"
          className="icon-btn"
          aria-label="后退"
          title={mirror.canGoBack ? '后退' : '没有可后退的页面'}
          disabled={desktopOn ? !desktopState?.canGoBack : (blocked || busy || !mirror.canGoBack)}
          onClick={() => navigate('back')}
        >
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M10 3.5 5.5 8l4.5 4.5" stroke="currentColor" strokeWidth="1.5" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </button>
        <button
          type="button"
          className="icon-btn"
          aria-label="前进"
          title={mirror.canGoForward ? '前进' : '没有可前进的页面'}
          disabled={desktopOn ? !desktopState?.canGoForward : (blocked || busy || !mirror.canGoForward)}
          onClick={() => navigate('forward')}
        >
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M6 3.5 10.5 8 6 12.5" stroke="currentColor" strokeWidth="1.5" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </button>
        <button
          type="button"
          className="icon-btn"
          aria-label={pageLoading ? '停止加载' : '刷新页面'}
          title={pageLoading ? '停止加载' : '刷新当前页面'}
          disabled={desktopOn ? !desktopState?.activeId : (blocked || busy || (!loading && !mirror.url))}
          onClick={() => (pageLoading ? navigate('stop') : navigate('reload'))}
        >
          {pageLoading
            ? <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4.4 4.4l7.2 7.2M11.6 4.4l-7.2 7.2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>
            : <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M13 8a5 5 0 1 1-1.6-3.7M13 2.6V5.4h-2.8" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>}
        </button>
        <form className={`browser-address${pageLoading ? ' is-loading' : ''}`} onSubmit={submitAddress}>
          {localPage && !editing && !freshTab && <span className="browser-address-lock">本机</span>}
          <input
            ref={addressRef}
            className="browser-address-input mono"
            aria-label="地址栏"
            spellCheck={false}
            autoComplete="off"
            placeholder="搜索或输入网址"
            value={address}
            onChange={(event) => setAddress(event.target.value)}
            onFocus={(event) => { setEditing(true); event.target.select(); }}
            onBlur={() => setEditing(false)}
          />
          {busy && <span className="browser-address-busy" aria-label="正在执行">…</span>}
        </form>
        <div className="browser-menu">
          <button
            type="button"
            className={`icon-btn${menuOpen ? ' is-on' : ''}`}
            aria-label="浏览器菜单"
            aria-expanded={menuOpen}
            title="更多"
            onClick={() => (desktopOn ? openDesktopMenu() : setMenuOpen((open) => !open))}
          >
            <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="3.5" cy="8" r="1.15" fill="currentColor" /><circle cx="8" cy="8" r="1.15" fill="currentColor" /><circle cx="12.5" cy="8" r="1.15" fill="currentColor" /></svg>
          </button>
        </div>
      </div>
      {desktopOn && findOpen && (
        <form className="browser-find" onSubmit={(event) => { event.preventDefault(); window.pilotDesktop.find(findText); }}>
          <input
            autoFocus
            aria-label="页内查找"
            placeholder="在页面中查找"
            value={findText}
            onChange={(event) => setFindText(event.target.value)}
          />
          <button type="submit">查找</button>
          <button type="button" onClick={() => { setFindOpen(false); setFindText(''); window.pilotDesktop.find(''); }}>关闭</button>
        </form>
      )}
      {menuOpen && (
        <div className="browser-menu-row" role="menu">
          <button
            type="button"
            role="menuitem"
            disabled={blocked || (!desktopOn && !live) || freshTab}
            onClick={() => {
              setMenuOpen(false);
              if (desktopOn) {
                window.pilotDesktop.pick().then((result) => {
                  if (!result?.text) return;
                  const quoted = result.text.split('\n').map((line) => `> ${line}`).join('\n');
                  const api = useStore.getState().composerApi;
                  if (api) {
                    api.appendText(`\n> 页面元素引用\n${quoted}\n\n`);
                    api.focus();
                  }
                });
                return;
              }
              setPicking(true);
            }}
          >选择页面元素</button>
          {desktopOn && (
            <button type="button" role="menuitem" disabled={!desktopState?.activeId || freshTab} onClick={() => { setMenuOpen(false); window.pilotDesktop.detach(); }}>
              在独立窗口打开
            </button>
          )}
          {desktopOn && (
            <button type="button" role="menuitem" disabled={!desktopState?.activeId || freshTab} onClick={() => { setMenuOpen(false); window.pilotDesktop.devtools(); }}>
              开发工具
            </button>
          )}
          <button type="button" role="menuitem" disabled={blocked || (!desktopOn && !live) || freshTab} onClick={() => { setMenuOpen(false); toggleConsole(false); }}>
            页面控制台
          </button>
          <button type="button" role="menuitem" disabled={blocked || (!desktopOn && !live) || freshTab} onClick={() => { setMenuOpen(false); toggleConsole(true); }}>
            网络记录
          </button>
          <button
            type="button"
            role="menuitem"
            className={mirror.paused ? 'is-on' : ''}
            disabled={blocked || !sessionId}
            onClick={async () => {
              setMenuOpen(false);
              const next = !mirror.paused;
              useStore.getState().setUi({ mirror: { ...mirror, paused: next } });
              const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/browser/${next ? 'pause' : 'resume'}`, { method: 'POST' });
              if (!response.ok) {
                useStore.getState().setUi({ mirror: { ...mirror, paused: !next } });
              }
            }}
          >{pauseToggleLabel(mirror.paused)}</button>
        </div>
      )}
      </div>
      {blocked && reason && <p className="browser-reason">{reason}</p>}
      {!desktopOn && mirror.mode === 'closed' && <p className="browser-reason">浏览器已回收，重新导航即可再次打开。</p>}
      {(desktopState?.error || error) && <p className="browser-reason is-error">{desktopState?.error || error}</p>}
      {picking && !desktopOn && <p className="browser-reason is-pick">选择元素：点画面里要引用的元素，描述会加进输入框。</p>}
      <div
        id="mirror-stage"
        className={`mirror-stage is-${mirror.mode}${pageUrl ? ' is-page' : ''}${picking ? ' is-picking' : ''}${freshTab ? ' is-fresh' : ''}`}
        ref={stageRef}
        tabIndex={pageUrl ? undefined : 0}
        role="application"
        aria-label="内置浏览器画面"
        onClick={pageUrl ? undefined : onClick}
        onKeyDown={pageUrl ? undefined : onStageKeyDown}
      >
        {freshTab
          ? (
            <div className="browser-newt">
              <form onSubmit={submitAddress}>
                <input
                  aria-label="搜索或输入网址"
                  placeholder="搜索或输入网址"
                  spellCheck={false}
                  autoComplete="off"
                  value={address}
                  onChange={(event) => setAddress(event.target.value)}
                  onFocus={(event) => { setEditing(true); event.target.select(); }}
                  onBlur={() => setEditing(false)}
                />
              </form>
              {(desktopState?.recents || []).length > 0 && (
                <div className="browser-recents">
                  <p>最近访问</p>
                  {desktopState.recents.map((item) => (
                    <button
                      key={item.url}
                      type="button"
                      className="browser-recent"
                      onClick={() => window.pilotDesktop.navigate(item.url)}
                    >
                      <RecentMark item={item} />
                      <span className="browser-recent-copy">
                        <strong>{item.title || recentHost(item.url)}</strong>
                        <em>{recentHost(item.url)}</em>
                      </span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )
          : desktopOn
          ? (
            <div className="mirror-empty">
              {desktopState?.cover && <img className="browser-cover" src={desktopState.cover} alt="" />}
            </div>
          )
          : (
            <div className="mirror-empty">
              <p>完整浏览器在桌面壳里。</p>
              <p className="mirror-empty-hint">在仓库里执行 npm --prefix pilot-desktop start。用普通浏览器打开本页时，这里不再用截图或内嵌页代替。</p>
            </div>
          )}
      </div>
      {consoleOpen && (
        <div className="browser-console">
          <div className="browser-console-head">
            <span>页面控制台</span>
            <button type="button" className="icon-btn" aria-label="关闭控制台" title="关闭" onClick={() => setConsoleOpen(false)}>
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4.4 4.4l7.2 7.2M11.6 4.4l-7.2 7.2" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg>
            </button>
          </div>
          <pre className="browser-console-body">{consoleText}</pre>
        </div>
      )}
      {(desktopState?.emulated || (!desktopOn && !pageLoading)) && (
        <div className="browser-status">
          <p id="mirror-hint" className="mirror-hint">
            {desktopState?.emulated
              ? `设备模拟 ${desktopState.emulated.width}×${desktopState.emulated.height}`
              : '完整浏览器需要桌面壳。'}
            {desktopState?.emulated && (
              <button type="button" className="browser-reset" onClick={() => window.pilotDesktop.resetEmulation()}>还原</button>
            )}
          </p>
        </div>
      )}
    </div>
  );
}
