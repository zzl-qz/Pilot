import { statusLetter } from './diff-format.js';
import type { ChangeFile, ChangeReport, DiffFile } from '../../types/core.js';
import type { GitBranchReport, GitCommitLogReport, GitStashReport } from '../../types/api.js';
import { reviewQuote } from '../../lib/interaction.js';
import { errorInfo } from '../../lib/errors.js';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useStore } from '../../store.js';
import { api } from '../../api.js';
import { ReviewActions } from '../ReviewActions.jsx';
import { hasDiffTruncation, normalizePath, parseDiff } from '../../lib/diff.js';
import { sortChangeFiles, splitPath, statusKind, sumChangeLines } from '../../lib/changes.js';
import { buildSplitDiffRows, buildWordDiffMap, extractHunkPatch, type WordSegment } from '../../lib/word-diff.js';
import { safeLocalStorage } from '../../lib/safe-storage.js';

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
  const running = useStore((state) => state.running);
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
  const [selectedPaths, setSelectedPaths] = useState<string[]>([]);
  const [selectionTouched, setSelectionTouched] = useState(false);
  const [commitMessage, setCommitMessage] = useState('');
  const [committing, setCommitting] = useState(false);
  const [drafting, setDrafting] = useState(false);
  const [commitFeedback, setCommitFeedback] = useState<{ text: string; error?: boolean } | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [gitToolsOpen, setGitToolsOpen] = useState(false);
  const [gitLog, setGitLog] = useState<GitCommitLogReport | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [branches, setBranches] = useState<GitBranchReport | null>(null);
  const [stashes, setStashes] = useState<GitStashReport | null>(null);
  const [gitBusy, setGitBusy] = useState(false);
  const [gitError, setGitError] = useState('');
  const [gitFeedback, setGitFeedback] = useState('');
  const [stashMessage, setStashMessage] = useState('');

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
  const totals = useMemo(() => sumChangeLines(tracked), [tracked]);
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
  const allPaths = useMemo(() => files.map((file) => file.path), [files]);
  const selectedSet = useMemo(() => new Set(selectedPaths), [selectedPaths]);
  const viewIndex = trackedPaths.indexOf(view || '');
  const canCommit = !scoped && isGit && !empty && state !== 'error';

  // 同步待提交文件勾选集合：首次自动全选已跟踪改动，文件列表刷新时自动剔除已消失的文件
  useEffect(() => {
    if (!canCommit) {
      setSelectedPaths([]);
      setSelectionTouched(false);
      return;
    }
    const existing = new Set(allPaths);
    setSelectedPaths((prev) => {
      if (!selectionTouched) {
        return trackedPaths.length ? trackedPaths : allPaths;
      }
      return prev.filter((item) => existing.has(item));
    });
  }, [canCommit, allPaths, trackedPaths, selectionTouched]);

  const loadGitLog = useCallback(async () => {
    if (!sessionId || !isGit) return;
    setHistoryLoading(true);
    try {
      const data = await api.gitLog(sessionId, 15);
      setGitLog(data);
    } catch {
      /* 历史读取失败不阻塞变更审阅 */
    } finally {
      setHistoryLoading(false);
    }
  }, [sessionId, isGit]);

  useEffect(() => {
    if (historyOpen && !scoped && isGit && sessionId) {
      void loadGitLog();
    }
  }, [historyOpen, scoped, isGit, sessionId, loadGitLog]);

  useEffect(() => {
    if (sessionId) {
      useStore.getState().loadChanges();
    }
  }, [sessionId]);

  useEffect(() => {
    setPreferredScope('workspace'); setRunReview(null); setQuery(''); setConfirm(null);
    setCommitMessage(''); setCommitFeedback(null); setSelectionTouched(false); setGitLog(null);
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
        if (!confirm && !view) return;
        event.preventDefault(); event.stopImmediatePropagation();
        if (confirm) {
          setConfirm(null);
        } else if (view) {
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

  const loadGitTools = useCallback(async () => {
    if (!sessionId) return;
    try {
      const [branchReport, stashReport] = await Promise.all([api.gitBranches(sessionId), api.gitStashes(sessionId)]);
      setBranches(branchReport);
      setStashes(stashReport);
      const err = branchReport?.error || stashReport?.error || '';
      if (err) setGitError(err);
    } catch (error) { setGitError(errorInfo(error).message || '读取 Git 状态失败'); }
  }, [sessionId]);

  useEffect(() => {
    setBranches(null);
    setStashes(null);
    setGitError('');
    setGitFeedback('');
    setStashMessage('');
  }, [sessionId, workspace]);

  const runGitAction = async (
    action: () => Promise<{ ok: boolean; error: string | null } | null>,
    successText?: string,
    fallbackError = 'Git 操作失败',
    refreshTools = true
  ) => {
    if (gitBusy || useStore.getState().running) return false;
    setGitBusy(true); setGitError(''); setGitFeedback('');
    try {
      const result = await action();
      if (!result?.ok) { setGitError(result?.error || fallbackError); return false; }
      if (successText) setGitFeedback(successText);
      await useStore.getState().loadChanges();
      if (refreshTools) await loadGitTools();
      if (historyOpen) void loadGitLog();
      return true;
    } catch (error) {
      setGitError(errorInfo(error).message || fallbackError);
      return false;
    } finally { setGitBusy(false); }
  };

  const restoreOne = async (path: string) => {
    setConfirm(null);
    if (!canUndo || restoring || useStore.getState().running) return false;
    setGitError(''); setGitFeedback('');
    await useStore.getState().restoreChanges([path], false);
    return true;
  };

  const discardOne = async (path: string) => {
    if (!sessionId || !canUndo || restoring) return false;
    const ok = await runGitAction(
      () => api.discardUnstaged(sessionId, path),
      `已放弃 ${path} 的未暂存改动`,
      '放弃未暂存改动失败',
      false
    );
    if (ok) setConfirm(null);
    return ok;
  };

  const toggleSelected = (filePath: string) => {
    setSelectionTouched(true);
    setSelectedPaths((prev) => prev.includes(filePath) ? prev.filter((item) => item !== filePath) : [...prev, filePath]);
  };

  const generateDraft = async () => {
    if (!sessionId || !canCommit || drafting || !selectedPaths.length) return;
    setDrafting(true);
    setCommitFeedback(null);
    try {
      const res = await api.gitDraftMessage(sessionId, { paths: selectedPaths, all: false });
      if (res?.message) setCommitMessage(res.message);
    } catch (error) {
      setCommitFeedback({ text: errorInfo(error).message || '生成草稿失败', error: true });
    } finally {
      setDrafting(false);
    }
  };

  const submitCommit = async () => {
    if (!sessionId || !canCommit || committing || useStore.getState().running) return;
    const message = commitMessage.trim();
    if (!selectedPaths.length) {
      setCommitFeedback({ text: '请先勾选至少一个要提交的文件。', error: true });
      return;
    }
    if (!message) {
      setCommitFeedback({ text: '请填写提交说明。', error: true });
      return;
    }
    setCommitting(true);
    setCommitFeedback(null);
    try {
      const res = await api.gitCommit(sessionId, { paths: selectedPaths, all: false, message });
      if (!res?.ok) {
        setCommitFeedback({ text: res?.error || '提交失败', error: true });
        return;
      }
      setCommitMessage('');
      setSelectionTouched(false);
      setCommitFeedback({ text: `已提交 ${res.shortHash || ''}：${res.subject || message.split('\n')[0]}` });
      useStore.getState().showNotice(`Git 提交成功（${res.shortHash || 'HEAD'}）`);
      await useStore.getState().loadChanges();
      if (historyOpen) void loadGitLog();
    } catch (error) {
      setCommitFeedback({ text: errorInfo(error).message || '提交失败', error: true });
    } finally {
      setCommitting(false);
    }
  };

  const renderChangeRow = (file: ChangeFile, isUntracked: boolean) => (
    <ChangeRow
      key={file.path}
      file={file}
      active={view === file.path}
      untracked={isUntracked || undefined}
      selectable={canCommit}
      selected={selectedSet.has(file.path)}
      onToggleSelect={() => toggleSelected(file.path)}
      confirming={confirm?.type === 'one' && confirm.path === file.path}
      canUndo={canUndo}
      busy={restoring || committing || gitBusy}
      onAskUndo={() => setConfirm({ type: 'one', path: file.path })}
      onCancelUndo={() => setConfirm(null)}
      onUndo={() => (isUntracked ? restoreOne(file.path) : discardOne(file.path))}
    />
  );

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
                {tracked.length > 0 && (totals.known ? (
                  <span className="changes-line-counts" title="已跟踪文件的增删行数"><span className="add">+{totals.added}</span><span className="del">−{totals.removed}</span></span>
                ) : (
                  // 部分改动无逐行差异时，标注总行数未知。
                  <span className="changes-line-counts" title={totals.note || '部分文件没算出逐行差异'}><span className="changes-unknown">行数未知</span></span>
                ))}
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
          onRefresh={() => { useStore.getState().loadChanges(); setRefresh(value => value + 1); void loadGitTools(); }}
        />
      </div>
      {!scoped && isGit && <details className="git-history-box" open={gitToolsOpen} onToggle={event => { setGitToolsOpen(event.currentTarget.open); if (event.currentTarget.open) void loadGitTools(); }}>
        <summary>本地 Git 工具{branches?.current ? ` · ${branches.current}` : ''}</summary>
        <div className="git-history-list git-tools-body">
          <label className="git-tools-branch">
            <span>切换分支</span>
            <select aria-label="切换本地分支" disabled={gitBusy || running || !branches?.branches?.length} value={branches?.current || ''}
              onChange={event => {
                const target = event.target.value;
                if (!target || target === branches?.current) return;
                void runGitAction(() => api.gitSwitch(sessionId, target), `已切换到分支 ${target}`);
              }}>
              {!branches?.current && <option value="" disabled>游离 HEAD</option>}
              {(branches?.branches || []).map(branch => <option key={branch} value={branch}>{branch}</option>)}
            </select>
          </label>
          <form className="git-tools-stash-form" onSubmit={event => {
            event.preventDefault();
            void runGitAction(async () => {
              const result = await api.gitStashPush(sessionId, stashMessage);
              if (result?.ok) setStashMessage('');
              return result;
            }, '已保存本地暂存（stash）');
          }}>
            <input aria-label="stash 说明" placeholder="可选：输入 stash 说明" value={stashMessage} onChange={event => setStashMessage(event.target.value)} disabled={gitBusy || running} />
            <button type="submit" className="btn btn-xs" disabled={gitBusy || running}>保存 stash</button>
          </form>
          {stashes && !stashes.stashes.length && <p className="git-history-empty">暂无本地暂存（stash）。</p>}
          {(stashes?.stashes || []).map(stash => <div key={stash.reference} className="git-history-item git-tools-stash-row">
            <code className="git-history-hash">{stash.reference}</code>
            <span className="git-history-subject" title={stash.message}>{stash.message}</span>
            <button type="button" className="btn btn-xs" disabled={gitBusy || running} onClick={() => void runGitAction(() => api.gitStashPop(sessionId, stash.reference), `已弹出 ${stash.reference}`)}>弹出</button>
          </div>)}
        </div>
      </details>}
      {gitError && <p role="alert" className="changes-progress git-tools-error">{gitError}</p>}
      {!gitError && gitFeedback && <p role="status" className="changes-progress">{gitFeedback}</p>}
      {restoring && <p className="changes-progress">撤销中…</p>}
      {state === 'error' && scope === 'workspace' && <p className="changes-progress" role="alert">读取失败，请刷新重试。{!empty && '当前显示上次读取的改动。'}</p>}
      {canCommit && (
        <div className="git-commit-bar" id="git-commit-panel">
          <div className="git-commit-head">
            <span>已选 {selectedPaths.length} / {files.length} 个文件</span>
            <div className="git-commit-select-actions">
              <button type="button" className="btn btn-xs" disabled={committing} onClick={() => { setSelectionTouched(true); setSelectedPaths(allPaths); }}>全选</button>
              <button type="button" className="btn btn-xs" disabled={committing} onClick={() => { setSelectionTouched(true); setSelectedPaths([]); }}>清空</button>
              <button type="button" className="btn btn-xs" disabled={committing || drafting || !selectedPaths.length} onClick={() => void generateDraft()}>
                {drafting ? '生成中…' : '生成草稿'}
              </button>
            </div>
          </div>
          <textarea
            className="git-commit-input"
            rows={2}
            aria-label="Git 提交说明"
            placeholder="输入提交说明（Ctrl/Cmd+Enter 提交选中文件）"
            disabled={committing}
            value={commitMessage}
            onChange={(event) => setCommitMessage(event.target.value)}
            onKeyDown={(event) => {
              if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
                event.preventDefault();
                void submitCommit();
              }
            }}
          />
          <div className="git-commit-foot">
            {commitFeedback && (
              <span className={`git-commit-feedback${commitFeedback.error ? ' is-error' : ''}`} role={commitFeedback.error ? 'alert' : 'status'}>
                {commitFeedback.text}
              </span>
            )}
            <button
              type="button"
              className="btn btn-primary"
              disabled={committing || !selectedPaths.length || !commitMessage.trim()}
              onClick={() => void submitCommit()}
            >
              {committing ? '提交中…' : `提交选中 (${selectedPaths.length})`}
            </button>
          </div>
        </div>
      )}
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
        {empty && !loading && !(scoped ? runError : state === 'error') && (
          <div className="changes-empty-state">
            <span className="changes-empty-icon" aria-hidden="true">
              <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="10" cy="10" r="7.5" />
                <path d="M7 10.2l2 2 4-4.2" />
              </svg>
            </span>
            <p className="changes-empty-title">{emptyMessage}</p>
            <p className="changes-empty-hint">
              {scoped && runActive
                ? '当前轮次仍在执行，完成后将展示逐行差异。'
                : 'AI 修改文件后，这里会自动展示文件列表、逐行差异与单文件撤销。'}
            </p>
          </div>
        )}
        {!empty && (
          <div id="change-files" className="change-files">
            {visibleTracked.map((file) => renderChangeRow(file, false))}
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
            {showUntracked && visibleUntracked.slice(0, UNTRACKED_LIMIT).map((file) => renderChangeRow(file, true))}
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
              busy={restoring || gitBusy}
              onUndo={() => {
                const target = files.find((file) => file.path === view);
                return target?.status === '??' ? restoreOne(view) : discardOne(view);
              }}
            />
          )
          : (!empty && <p className="panel-empty change-empty">选一个文件看差异。</p>)}
      </div>
      {!scoped && isGit && (
        <details className="git-history-box" open={historyOpen} onToggle={(event) => setHistoryOpen(event.currentTarget.open)}>
          <summary>
            <span>近期提交{gitLog?.branch ? ` · ${gitLog.branch}` : ''}</span>
            {historyOpen && <button type="button" className="btn btn-xs" disabled={historyLoading} onClick={(event) => { event.preventDefault(); void loadGitLog(); }}>刷新</button>}
          </summary>
          {historyLoading && !gitLog && <p className="git-history-empty">读取提交历史中…</p>}
          {gitLog && !gitLog.commits.length && <p className="git-history-empty">当前仓库尚无提交记录。</p>}
          {gitLog && gitLog.commits.length > 0 && (
            <ul className="git-history-list">
              {gitLog.commits.map((entry) => (
                <li key={entry.hash} className="git-history-item" title={`${entry.hash} · ${entry.author} · ${entry.date}`}>
                  <code className="git-history-hash">{entry.shortHash}</code>
                  <span className="git-history-subject">{entry.subject}</span>
                  <span className="git-history-meta">{entry.author}</span>
                </li>
              ))}
            </ul>
          )}
        </details>
      )}
    </div>
  );
}

/** 未跟踪文件一次最多列这么多：几百个临时文件全铺出来既卡又没法看。 */
const UNTRACKED_LIMIT = 80;

/**
 * 单文件的增删行。
 *
 * <p>后端算不出逐行差异时给 {@code countsKnown: false}（文件太大、二进制、副本被清掉），
 * 界面标注行数未知，悬停时显示原因。
 */
function lineStat(file: ChangeFile, className: string) {
  if (file.countsKnown === false) {
    return (
      <span className={className} title={file.note || '没算出这个文件的逐行差异'}>
        <span className="change-unknown">行数未知</span>
      </span>
    );
  }
  return (
    <span className={className}>
      <span className="add">+{Number(file.added || 0)}</span>
      <span className="del">−{Number(file.removed || 0)}</span>
    </span>
  );
}

/** 变更列表的一行：可选复选框 + 状态徽标 + 文件名（目录弱化）+ 增删行，悬停露出撤销。 */
function ChangeRow({ file, active, untracked, selectable, selected, onToggleSelect, confirming, canUndo, busy, onAskUndo, onCancelUndo, onUndo }: { file: ChangeFile; active: boolean; untracked?: boolean; selectable?: boolean; selected?: boolean; onToggleSelect?: () => void; confirming?: boolean; canUndo: boolean; busy: boolean; onAskUndo: () => void; onCancelUndo: () => void; onUndo: () => void }) {
  const { name, dir } = splitPath(file.path);
  if (confirming && canUndo) {
    return (
      <div className="change-row is-confirm">
        <span className={`change-status is-${statusKind(file.status)}`}>{statusLetter(file.status)}</span>
        <span className="change-row-main">
          <span className="change-row-confirm">
            撤销 <b>{name}</b> 的改动？
          </span>
          <span className="change-row-dir">{untracked ? '未跟踪文件会被删除' : '放弃工作区未暂存改动（不影响已暂存内容）'}</span>
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
      {selectable && (
        <label className="change-select-cell" title={`勾选 ${file.path} 参与提交`}>
          <input
            type="checkbox"
            checked={Boolean(selected)}
            disabled={busy}
            aria-label={`选择提交 ${file.path}`}
            onChange={() => onToggleSelect?.()}
          />
        </label>
      )}
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
        {!untracked && lineStat(file, 'change-row-stat')}
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

function DiffView({ report, scoped, path, change, prevPath, nextPath, index, total, canUndo, busy, onUndo }: { report: ReviewReport | null; scoped: boolean; path: string; change?: ChangeFile; prevPath: string | null; nextPath: string | null; index: number; total: number; canUndo: boolean; busy: boolean; onUndo: () => void | Promise<boolean> }) {
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
    const truncated = hasDiffTruncation(summary ?? undefined) && (scoped || (file !== undefined && file === parsed.at(-1)));
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
  const displayableLines = useMemo(() => (file?.lines || []).filter(line => line.kind !== 'meta'), [file]);
  const lines = useMemo(() => displayableLines.slice(0, DIFF_LINE_LIMIT), [displayableLines]);
  const wordDiffMap = useMemo(() => buildWordDiffMap(lines), [lines]);
  const [diffMode, setDiffMode] = useState<'unified' | 'split'>(() => (safeLocalStorage().getItem('pilot.diffMode') === 'split' ? 'split' : 'unified'));
  const [discardingHunk, setDiscardingHunk] = useState<number | null>(null);
  const splitRows = useMemo(() => (diffMode === 'split' ? buildSplitDiffRows(lines) : []), [diffMode, lines]);
  const canDiscardHunk = Boolean(canUndo && !scoped && !untracked && sessionId && !truncated);
  const handleDiscardHunk = async (hunkIndex: number) => {
    if (!sessionId || !file || discardingHunk != null) return;
    const targetHunk = displayableLines[hunkIndex];
    const rawIndex = targetHunk ? file.lines.indexOf(targetHunk) : -1;
    const hunkPatch = rawIndex >= 0 ? extractHunkPatch(file.lines, rawIndex) : null;
    if (!hunkPatch) return;
    setDiscardingHunk(hunkIndex);
    try {
      const result = await api.discardHunk(sessionId, path, hunkPatch);
      if (result?.ok) {
        useStore.getState().showNotice('已撤销该改动块');
        await useStore.getState().loadChanges();
      } else {
        useStore.getState().showNotice(result?.error || '撤销此改动块失败');
      }
    } catch (error) {
      useStore.getState().showNotice('撤销此改动块失败：' + errorInfo(error).message);
    } finally {
      setDiscardingHunk(null);
    }
  };
  const toggleDiffMode = (next: 'unified' | 'split') => {
    setDiffMode(next);
    safeLocalStorage().setItem('pilot.diffMode', next);
  };
  useEffect(() => { setSelection(null); setFeedback(''); }, [path, report]);
  const selStart = selection ? Math.min(selection.anchor, selection.end) : -1;
  const selEnd = selection ? Math.max(selection.anchor, selection.end) : -1;
  const isLineSelected = (lineIndex: number | null | undefined) => lineIndex != null && selection !== null && lineIndex >= selStart && lineIndex <= selEnd;
  const selectLine = (lineIndex: number, shiftKey: boolean) => {
    setSelection((current) => ({ anchor: shiftKey && current ? current.anchor : lineIndex, end: lineIndex }));
  };
  const selected = selection ? lines.slice(selStart, selEnd + 1) : [];
  const renderHunkUndo = (hunkIndex: number) => canDiscardHunk && (
    <button
      type="button"
      className="diff-hunk-undo"
      disabled={busy || discardingHunk != null}
      onClick={() => { void handleDiscardHunk(hunkIndex); }}
    >
      {discardingHunk === hunkIndex ? '撤销中…' : '撤销此块'}
    </button>
  );
  const renderNoDiffFallback = () => {
    if (truncated || change?.note) return null;
    if (untracked && !scoped) return <pre className="diff-raw">{content ?? '读取中…'}</pre>;
    if (patchState === 'loading') return <p className="diff-empty">读取差异中…</p>;
    if (patchState === 'error') return <p className="diff-empty">读取差异失败，点右上角的刷新再试一次。</p>;
    return <p className="diff-empty">这个文件没有可显示的文本差异（可能是二进制、纯重命名或权限变更）。</p>;
  };
  const addFeedback = () => {
    const state = useStore.getState();
    const quote = reviewQuote(path, selected, feedback);
    if (!quote || !state.composerApi) return;
    state.composerApi.appendText(quote);
    state.setUi({ reviewExpanded: false });
    setSelection(null); setFeedback('');
    requestAnimationFrame(() => state.composerApi?.focus());
  };
  const sendFeedbackNow = () => {
    const state = useStore.getState();
    const quote = reviewQuote(path, selected, feedback);
    if (!quote) return;
    state.setUi({ reviewExpanded: false });
    setSelection(null); setFeedback('');
    void state.submitInput(quote);
  };
  const renderCodeWithWords = (lineText: string, lineIndex: number, kind: string) => {
    const segments: WordSegment[] | undefined = wordDiffMap.get(lineIndex);
    if (!segments) return lineText;
    const cls = kind === 'del' ? 'diff-word-del' : 'diff-word-add';
    return segments.map((seg, idx) => (seg.changed ? <mark key={idx} className={cls}>{seg.text}</mark> : <span key={idx}>{seg.text}</span>));
  };
  const close = () => useStore.getState().setUi({ changeView: '' });
  const select = (target: string | null) => {
    setConfirming(false);
    useStore.getState().setUi({ changeView: target });
  };
  // 能解析出这个文件的补丁时，行数来自补丁本身，一定可信；否则用报告里的结论
  const viewFile = change?.countsKnown === false && (!file?.lines.some(line => line.kind === 'hunk') || truncated)
    ? change
    : file && (change?.countsKnown !== false && change?.added != null && change.removed != null ? change : file);
  const viewStat = viewFile ? lineStat(viewFile, 'change-view-stat') : null;
  return (
    <div className="change-view">
      <div className="change-view-bar">
        <span className="change-view-path" title={path}>{path}</span>
        {viewStat ?? (untracked ? <span className="change-view-tag">未跟踪</span> : null)}
        {total > 0 && index > 0 && <span className="change-view-index">{index} / {total}</span>}
        {file && (
          <div className="diff-mode-switch" role="group" aria-label="差异展示模式">
            <button type="button" className={diffMode === 'unified' ? 'is-active' : ''} aria-pressed={diffMode === 'unified'} onClick={() => toggleDiffMode('unified')}>统一</button>
            <button type="button" className={diffMode === 'split' ? 'is-active' : ''} aria-pressed={diffMode === 'split'} onClick={() => toggleDiffMode('split')}>分栏</button>
          </div>
        )}
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
            <span className="change-view-confirm">{untracked ? '删除这个未跟踪文件？' : '放弃未暂存改动？'}</span>
            <button type="button" className="btn btn-danger" disabled={busy} onClick={() => void Promise.resolve(onUndo()).then(ok => { if (ok) setConfirming(false); })}>确认</button>
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
      {selection && <div className="review-feedback" onKeyDown={event => { if (!event.nativeEvent?.isComposing && event.keyCode !== 229 && event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setSelection(null); } }}>
        <label>对选中代码的意见<textarea aria-label="审阅意见" value={feedback} onChange={event => setFeedback(event.target.value)} placeholder="描述需要调整的地方" rows={2} /></label>
        <button type="button" className="btn" onClick={addFeedback}>引用到输入框</button>
        <button type="button" className="btn btn-primary" onClick={sendFeedbackNow}>直接发送修改</button>
        <button type="button" className="btn" onClick={() => { setSelection(null); setFeedback(''); }}>取消选择</button>
      </div>}
      <div id="change-diff" className={`change-diff${diffMode === 'split' ? ' is-split' : ''}`}>
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
              {diffMode === 'split' ? (
                <div className="diff-split-table" role="region" aria-label="左右分栏差异">
                  {splitRows.map((row, rowIdx) => {
                    if (row.kind === 'hunk') {
                      return (
                        <div key={rowIdx} className="diff-line is-hunk diff-split-hunk">
                          <span className="n" />
                          <code>{row.text}</code>
                          {renderHunkUndo(row.index)}
                        </div>
                      );
                    }
                    const leftSelected = isLineSelected(row.leftIndex);
                    const rightSelected = isLineSelected(row.rightIndex);
                    return (
                      <div key={rowIdx} className="diff-split-row">
                        <div className={`diff-line diff-split-cell ${row.left ? `is-${row.left.kind}` : 'is-empty'}${leftSelected ? ' is-selected' : ''}`}>
                          {row.left && row.leftIndex != null ? (
                            <>
                              <button type="button" className="n is-old"
                                aria-label={`选择原文件第 ${row.left.old} 行`}
                                aria-pressed={leftSelected}
                                onClick={event => selectLine(row.leftIndex!, event.shiftKey)}>
                                {row.left.old}
                              </button>
                              <code>{renderCodeWithWords(row.left.text, row.leftIndex, row.left.kind)}</code>
                            </>
                          ) : <><span className="n" /><code /></>}
                        </div>
                        <div className={`diff-line diff-split-cell ${row.right ? `is-${row.right.kind}` : 'is-empty'}${rightSelected ? ' is-selected' : ''}`}>
                          {row.right && row.rightIndex != null ? (
                            <>
                              <button type="button" className="n"
                                aria-label={`选择现文件第 ${row.right.next} 行`}
                                aria-pressed={rightSelected}
                                onClick={event => selectLine(row.rightIndex!, event.shiftKey)}>
                                {row.right.next}
                              </button>
                              <code>{renderCodeWithWords(row.right.text, row.rightIndex, row.right.kind)}</code>
                            </>
                          ) : <><span className="n" /><code /></>}
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : (
                lines.map((line, index) => {
                  const lineSelected = isLineSelected(index);
                  return (
                    <div key={index} className={`diff-line is-${line.kind}${lineSelected ? ' is-selected' : ''}`}>
                      {line.kind === 'hunk' ? <span className="n" /> : <button type="button"
                        className={`n${line.kind === 'del' ? ' is-old' : ''}`}
                        aria-label={`选择${line.kind === 'del' ? '原文件' : '现文件'}第 ${line.kind === 'del' ? line.old : line.next} 行`}
                        aria-pressed={lineSelected}
                        onClick={event => selectLine(index, event.shiftKey)}>
                        {line.kind === 'del' ? line.old : line.next}
                      </button>}
                      <code>{renderCodeWithWords(line.text, index, line.kind)}</code>
                      {line.kind === 'hunk' && renderHunkUndo(index)}
                    </div>
                  );
                })
              )}
              {/* 生成出来的大文件动辄上万行，全铺出来会卡住滚动；先给一个明确的截断提示 */}
              {displayableLines.length > DIFF_LINE_LIMIT && (
                <p className="diff-truncated">
                  只显示前 {DIFF_LINE_LIMIT} 行，还有 {displayableLines.length - DIFF_LINE_LIMIT} 行未渲染。
                </p>
              )}
            </>
          )
          : renderNoDiffFallback()}
      </div>
    </div>
  );
}
