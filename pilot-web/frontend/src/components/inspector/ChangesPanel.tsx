import { statusLetter } from './diff-format.js';
import type { ChangeFile, ChangeReport, DiffFile } from '../../types/core.js';
import { reviewQuote } from '../../lib/interaction.js';
import { useEffect, useMemo, useState } from 'react';
import { useStore } from '../../store.js';
import { api } from '../../api.js';
import { ReviewActions } from '../ReviewActions.jsx';
import { hasDiffTruncation, normalizePath, parseDiff } from '../../lib/diff.js';
import { sortChangeFiles, splitPath, statusKind } from '../../lib/changes.js';

/* ---------- 变更面板 ---------- */

interface ReviewReport extends ChangeReport { diff?: string; snapshot?: boolean }

export function ChangesPanel() {
  const workspaceReport = useStore((state) => state.changeReport);
  const expanded = useStore(state => state.ui.reviewExpanded);
  const lastRunId = useStore(state => state.running ? state.runId || state.lastRunId : state.lastRunId || state.runId);
  const reviewRunId = useStore(state => state.ui.reviewRunId);
  const activeRunId = useStore(state => state.running ? state.runId || state.lastRunId : null);
  const [preferredScope, setPreferredScope] = useState('workspace');
  const isGit = workspaceReport?.gitRepository !== false;
  const runId = reviewRunId || lastRunId;
  const scope = reviewRunId && reviewRunId !== lastRunId ? 'selected' : reviewRunId || !isGit ? 'run' : preferredScope;
  const scoped = scope !== 'workspace';
  const runActive = Boolean(runId && activeRunId === runId);
  const [runReview, setRunReview] = useState<{ runId: string; epoch: number; report?: ReviewReport; error?: string } | null>(null);
  const [refresh, setRefresh] = useState(0);
  const state = useStore((state) => state.changesState);
  const sessionId = useStore((state) => state.sessionId);
  const view = useStore((state) => state.ui.changeView);
  const sessionEpoch = useStore(state => state.sessionEpoch);
  const workspace = useStore(state => state.workspace);
  const currentReview = runReview?.runId === runId && runReview?.epoch === sessionEpoch && !runActive ? runReview : null;
  const runReport = currentReview?.report;
  const runError = currentReview?.error;
  const report = scoped ? runReport ?? null : workspaceReport;
  const loading = scoped ? Boolean(runId && !runActive && !currentReview) : state === 'loading';
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
  const empty = !files.length;
  const restoring = state === 'restoring';
  const canUndo = !scoped && isGit && !empty && state !== 'error';

  let emptyMessage = scoped && runReport?.snapshot ? '本轮没有改动' : '没有改动记录';
  if (scoped && !runId) emptyMessage = '还没有运行记录';
  if (scoped && runActive) emptyMessage = '本轮结束后显示逐行差异';
  if (loading) emptyMessage = '读取中…';
  if (scoped ? runError : state === 'error') emptyMessage = '变更读取失败';

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
    setPreferredScope('workspace'); setRunReview(null); setQuery(''); setConfirm(null);
  }, [sessionId]);
  useEffect(() => {
    if (!scoped || !runId || runActive) return;
    let alive = true;
    setRunReview(null);
    api.runChanges(runId).then(data => {
      if (!data) throw new Error('任务改动返回空响应');
      if (alive) setRunReview({ runId, epoch: sessionEpoch, report: data, error: data.error || undefined });
    }).catch(() => {
      if (alive) setRunReview({ runId, epoch: sessionEpoch, error: '读取本轮改动失败，请刷新重试。' });
    });
    return () => { alive = false; };
  }, [scoped, runId, runActive, sessionEpoch, refresh]);

  // 换了文件就把上一行的「确认撤销」收起来，免得确认框停在一个已经不显示的文件上
  useEffect(() => {
    setConfirm((current) => (current?.type === 'one' && current.path !== view ? null : current));
  }, [view]);

  /*
   * 键盘浏览（对齐 Codex 的审阅面板）：焦点在面板里时，上下键换文件、Esc 关闭差异。
   * 输入框里不抢键，方向键仍然用来挪光标。
   */
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey || document.querySelector('.modal, [data-workbench-menu-open]')) {
        return;
      }
      const panel = document.getElementById('panel-changes');
      const active = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      if (!panel || !active || !panel.contains(active) || active.closest('[role="menu"], [role="dialog"]') || active.closest('.changes-actions')?.querySelector('[aria-expanded="true"]')) {
        return;
      }
      if (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable) {
        return;
      }
      if (event.key === 'Escape') {
        event.preventDefault(); event.stopImmediatePropagation();
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
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [view, viewIndex, trackedPaths, confirm]);

  const restore = async (paths: string[], all: boolean) => {
    setConfirm(null);
    if (!canUndo || restoring || useStore.getState().running) return;
    await useStore.getState().restoreChanges(paths, all);
  };

  // 还没开始任务时没有会话，也就没有工作区可读；给一句明确的话，别显示「没有改动」
  if (!sessionId) {
    return <p className="panel-empty">开始一个任务后，这里会显示工作区的改动与差异。</p>;
  }

  return (
    <div id="panel-changes" className={`panel${expanded ? ' is-expanded' : ''}`}>
      <div className="review-toolbar">
        <label>审阅范围 <select aria-label="审阅范围" value={scope} onChange={event => {
          setPreferredScope(event.target.value); setConfirm(null);
          useStore.getState().setUi({ changeView: null, reviewRunId: null });
        }}>
          <option value="workspace" disabled={!isGit}>工作区全部改动</option>
          <option value="run" disabled={isGit && !lastRunId}>最近一轮改动</option>
          {scope === 'selected' && <option value="selected">所选轮次改动</option>}
        </select></label>
        <button type="button" className="btn" aria-pressed={expanded} onClick={() => useStore.getState().setUi({ reviewExpanded: !expanded })}>{expanded ? '退出全屏审阅' : '全屏审阅'}</button>
      </div>
      {!scoped && <p className="review-scope-note">包含你和其他任务的未提交改动。</p>}
      {scoped && runReport && !runReport.snapshot && files.length > 0 && <p className="review-scope-note">
        {runReport.gitRepository ? '这轮没有保存逐行快照，差异仍相对 HEAD，可能包含本轮之外的修改。' : '这轮只有文件记录，无法查看逐行差异。'}
      </p>}
      {scoped && runError && <p role="alert">{runError}</p>}
      {scoped && runReport?.truncated && <p className="changes-progress" role="status">本轮改动超过展示上限，部分文件或补丁已截断。</p>}
      <div className="changes-bar">
        <div className="changes-meta">
          {empty ? (
            <span id="changes-totals" className="changes-totals">
              <span className="changes-count" role={loading ? 'status' : undefined}>{emptyMessage}</span>
            </span>
          ) : (
            <>
              <span id="changes-totals" className="changes-totals">
                <span className="changes-count">{files.length} 个文件</span>
                {tracked.length > 0 && <span className="changes-line-counts" title="已跟踪文件的增删行数"><span className="add">+{totals.added}</span><span className="del">−{totals.removed}</span></span>}
              </span>
              {untracked.length > 0 && (
                <button
                  type="button"
                  className={`changes-untracked${showUntracked ? ' is-on' : ''}`}
                  title="未纳入 Git 版本管理的文件，不计入增删行"
                  aria-expanded={showUntracked}
                  onClick={() => setShowUntracked((open) => !open)}
                >
                  未跟踪 {untracked.length}
                </button>
              )}
            </>
          )}
        </div>
        <ReviewActions
          key={`${sessionId}:${sessionEpoch}:${workspace}:${scope}`}
          files={files} isGit={isGit} canUndo={canUndo}
          loading={loading} restoring={restoring}
          onRefresh={() => { useStore.getState().loadChanges(); setRefresh(value => value + 1); }}
        />
      </div>
      {restoring && <p className="changes-progress">撤销中…</p>}
      {state === 'error' && scope === 'workspace' && <p className="changes-progress" role="alert">读取失败，请刷新重试。{!empty && '当前显示上次读取的改动。'}</p>}
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
        {!scoped && state === 'loading' && empty && <p className="panel-empty change-empty">读取变更中…</p>}
        {!empty && (
          <div id="change-files" className="change-files">
            {visibleTracked.map((file) => (
              <ChangeRow
                key={file.path}
                file={file}
                active={view === file.path}
                confirming={confirm?.type === 'one' && confirm.path === file.path}
                canUndo={canUndo}
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
                canUndo={canUndo}
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
              key={`${sessionId}:${scope}:${runId}:${view}`}
              report={report}
              scoped={scoped}
              path={view}
              change={files.find((file) => file.path === view)}
              prevPath={viewIndex > 0 ? trackedPaths[viewIndex - 1] : null}
              nextPath={viewIndex >= 0 && viewIndex < trackedPaths.length - 1 ? trackedPaths[viewIndex + 1] : null}
              index={viewIndex >= 0 ? viewIndex + 1 : 0}
              total={trackedPaths.length}
              canUndo={canUndo}
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
function ChangeRow({ file, active, untracked, confirming, canUndo, busy, onAskUndo, onCancelUndo, onUndo }: { file: ChangeFile; active: boolean; untracked?: boolean; confirming?: boolean; canUndo: boolean; busy: boolean; onAskUndo: () => void; onCancelUndo: () => void; onUndo: () => void }) {
  const { name, dir } = splitPath(file.path);
  if (confirming && canUndo) {
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
      {canUndo && <button
        type="button"
        className="change-undo"
        title={`撤销 ${file.path} 的改动`}
        aria-label={`撤销 ${file.path} 的改动`}
        disabled={busy}
        onClick={onAskUndo}
      >撤销</button>}
    </div>
  );
}

/** 单文件差异最多渲染这么多行：再多滚动会明显掉帧。 */
const DIFF_LINE_LIMIT = 4000;

/** git 状态码 → 一个字母的徽标。 */

function DiffView({ report, scoped, path, change, prevPath, nextPath, index, total, canUndo, busy, onUndo }: { report: ReviewReport | null; scoped: boolean; path: string; change?: ChangeFile; prevPath: string | null; nextPath: string | null; index: number; total: number; canUndo: boolean; busy: boolean; onUndo: () => void }) {
  const sessionId = useStore((state) => state.sessionId);
  const untracked = change?.status === '??';
  /*
   * 后端把整份补丁放在 report.diff 里，files 每项只有状态与增删行。
   * 整份补丁有 200k 字符上限：改动一多，后面的文件在补丁里根本没有内容，
   * 这时按需再取一次单文件补丁，既不把正文当 diff 展示，也不用一次拉全仓库。
   */
  const summary = scoped ? change?.diff : report?.diff;
  const { summaryFile, summaryTruncated } = useMemo(() => {
    const parsed = parseDiff(summary);
    const file = scoped ? parsed[0] : parsed.find((item) => normalizePath(item.path) === normalizePath(path));
    // 运行报告逐文件返回补丁，只有工作区的整份补丁需要把末尾提示归到最后一个文件。
    const truncated = hasDiffTruncation(summary) && (scoped || (file !== undefined && file === parsed.at(-1)));
    return { summaryFile: file, summaryTruncated: truncated };
  }, [summary, scoped, path]);
  const [confirming, setConfirming] = useState(false);
  /** 按需取回的单文件补丁：{ file, truncated }。 */
  const [patch, setPatch] = useState<{ file: DiffFile | null; truncated: boolean } | null>(null);
  const [patchState, setPatchState] = useState('');
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
        setPatch({ file: files[0] || null, truncated: Boolean(data?.truncated) || hasDiffTruncation(data?.diff) });
        setPatchState('');
      })
      .catch(() => {
        if (alive) {
          setPatch(null);
          setPatchState('error');
        }
      });
    return () => { alive = false; };
  }, [summaryFile, path, sessionId, untracked, scoped, report]);

  const file = summaryFile || patch?.file || null;
  const truncated = summaryTruncated || Boolean(patch?.truncated);
  const displayableLines = (file?.lines || []).filter(line => line.kind !== 'meta');
  const lines = displayableLines.slice(0, DIFF_LINE_LIMIT);
  useEffect(() => { setSelection(null); setFeedback(''); }, [path, report]);
  const selected = selection ? lines.slice(Math.min(selection.anchor, selection.end), Math.max(selection.anchor, selection.end) + 1) : [];
  const addFeedback = () => {
    const state = useStore.getState();
    const quote = reviewQuote(path, selected, feedback);
    if (!quote || !state.composerApi) return;
    state.composerApi.appendText(quote);
    state.setUi({ reviewExpanded: false });
    setSelection(null); setFeedback('');
    requestAnimationFrame(() => state.composerApi?.focus());
  };
  const close = () => useStore.getState().setUi({ changeView: '' });
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
            <span className="add">+{change?.added ?? file.added}</span>
            <span className="del">−{change?.removed ?? file.removed}</span>
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
        {canUndo && (confirming ? (
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
        ))}
        <button type="button" className="icon-btn change-view-close" title="关闭差异" aria-label="关闭差异" onClick={close}>×</button>
      </div>
      <p className="review-line-hint">点击行号选择，Shift + 点击选择多行，再添加审阅意见。</p>
      {selection && <div className="review-feedback" onKeyDown={event => { if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setSelection(null); } }}>
        <label>对选中代码的意见<textarea aria-label="审阅意见" value={feedback} onChange={event => setFeedback(event.target.value)} placeholder="描述需要调整的地方" rows={2} /></label>
        <button type="button" className="btn" onClick={addFeedback}>引用到输入框</button>
        <button type="button" className="btn" onClick={() => { setSelection(null); setFeedback(''); }}>取消选择</button>
      </div>}
      <div id="change-diff" className="change-diff">
        {change?.note && <p className="diff-empty">{change.note}</p>}
        {truncated && <p className="diff-truncated" role="status">
          {summaryTruncated && !scoped
            ? '工作区补丁超过展示上限，当前文件可能未显示完整。'
            : lines.length ? '补丁超过展示上限，只显示了前一部分。' : '补丁超过展示上限，暂无可预览的片段。'}
        </p>}
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
              {displayableLines.length > DIFF_LINE_LIMIT && (
                <p className="diff-truncated">
                  只显示前 {DIFF_LINE_LIMIT} 行，还有 {displayableLines.length - DIFF_LINE_LIMIT} 行未渲染。
                </p>
              )}
            </>
          )
          : truncated || change?.note ? null : untracked && !scoped
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
