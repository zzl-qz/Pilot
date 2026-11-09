import type { ChangeFile } from '../types/core.js';
type ActionDialog = { type: 'branch' | 'undo'; files: ChangeFile[]; partial?: boolean };
import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useStore } from '../store.js';
import { Icon } from './Icon.jsx';
import { Modal } from './Modals.jsx';
import { errorInfo } from '../lib/errors.js';

/** 审阅时只露出常用操作；Git 操作按需打开，不挤占文件统计。 */
export function ReviewActions({ files, isGit, canUndo, loading, restoring, onRefresh }: { files: ChangeFile[]; isGit: boolean; canUndo: boolean; loading: boolean; restoring: boolean; onRefresh: () => void }) {
  const running = useStore(state => state.running);
  const [open, setOpen] = useState(false);
  const [dialog, setDialog] = useState<ActionDialog | null>(null);
  const [branch, setBranch] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const pending = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    if (!open) return;
    const items = () => [...(root.current?.querySelectorAll<HTMLElement>('[role="menuitem"]:not(:disabled)') || [])];
    items()[0]?.focus();
    const outside = (event: Event) => { if (event.target instanceof Node && !root.current?.contains(event.target)) setOpen(false); };
    const key = (event: KeyboardEvent) => {
      if (event.isComposing) return;
      if (event.key === 'Escape') {
        event.preventDefault(); event.stopImmediatePropagation(); setOpen(false); trigger.current?.focus();
      } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
        event.preventDefault(); event.stopImmediatePropagation();
        const options = items(), index = options.findIndex(option => option === document.activeElement);
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length;
        options[next]?.focus();
      } else if (event.key === 'Tab') setOpen(false);
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('focusin', outside);
    window.addEventListener('keydown', key, true);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('focusin', outside);
      window.removeEventListener('keydown', key, true);
    };
  }, [open]);
  const show = (type: ActionDialog['type']) => {
    if (!isGit || running || restoring || pending.current || (type === 'undo' && (!canUndo || loading))) return;
    setOpen(false); setError('');
    // 固定确认时可见的文件，避免撤销刷新后新出现、尚未审阅的文件。
    setDialog({ type, files: files.map(file => ({ ...file })) });
    trigger.current?.focus();
  };
  const close = () => { if (!pending.current) setDialog(null); };
  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!dialog || !isGit || pending.current || restoring || useStore.getState().running) return;
    if (dialog.type === 'branch' && !branch.trim()) return;
    if (dialog.type === 'undo' && (!canUndo || loading || dialog.partial)) return;
    pending.current = true; setBusy(true); setError('');
    try {
      if (dialog.type === 'branch') {
        const result = await useStore.getState().createBranch(branch.trim());
        if (!mounted.current) return;
        if (!result.ok) { setError(result.error || '创建分支失败'); return; }
        setBranch('');
      } else {
        const result = await useStore.getState().restoreChanges(dialog.files.map(file => file.path), false);
        if (!mounted.current) return;
        if (!result || result.error || result.failed?.length) {
          setError(result?.error || (result?.failed?.length ? `${result.failed.length} 个文件未能撤销：${result.failed[0].path}（${result.failed[0].reason}）。请关闭弹窗，检查剩余改动后重试。` : '撤销失败，请检查连接后重试。'));
          // 部分成功后重新打开确认，不能重复删除已撤销后又被修改的文件。
          if (result?.failed?.length) setDialog(current => current && ({ ...current, partial: true }));
          return;
        }
      }
      setDialog(null);
    } catch (failure) {
      if (mounted.current) setError(errorInfo(failure).message || '操作失败，请重试');
    } finally {
      pending.current = false;
      if (mounted.current) setBusy(false);
    }
  };
  const untracked = dialog?.files.filter(file => file.status === '??').length || 0;
  return <div className="changes-actions" ref={root}>
    <button type="button" className="icon-btn" id="changes-refresh" title="刷新变更" aria-label="刷新变更" disabled={restoring || loading || busy} onClick={onRefresh}><Icon name="refresh" /></button>
    <button type="button" className="icon-btn" id="changes-more" ref={trigger} title="更多变更操作" aria-label="更多变更操作" aria-haspopup="menu" aria-expanded={open} aria-controls={open ? 'changes-menu' : undefined} disabled={!isGit || restoring || busy} onClick={() => setOpen(value => !value)}><Icon name="more" /></button>
    {open && <div id="changes-menu" className="review-menu" role="menu" aria-label="变更操作">
      <button type="button" role="menuitem" disabled={running} onClick={() => show('branch')}><Icon name="branch" />创建并切换分支…</button>
      {canUndo && <button type="button" role="menuitem" id="changes-undo-all" className="is-danger" disabled={running || loading} onClick={() => show('undo')}><Icon name="trash" />撤销全部改动…</button>}
      {running && <p className="review-menu-hint">任务结束后可操作 Git 改动</p>}
    </div>}
    {dialog && createPortal(<Modal id="review-action-dialog" title={dialog.type === 'branch' ? '创建并切换分支' : '撤销全部改动？'} onClose={close} dismissible={!busy}>
      <form onSubmit={submit} aria-busy={busy}>
        {dialog.type === 'branch' ? <>
          <p className="review-dialog-note">从当前分支创建新分支并切换，未提交的改动会保留。</p>
          <label className="task-field">分支名称<input aria-label="新 Git 分支名" placeholder="例如 feature/review" autoComplete="off" spellCheck={false} maxLength={200} value={branch} disabled={busy} onChange={event => { setBranch(event.target.value); setError(''); }} onKeyDown={event => { if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault(); }} /></label>
        </> : <p className="review-dialog-note">将撤销当前列出的 {dialog.files.length} 个文件的改动：{dialog.files.length - untracked > 0 && ` ${dialog.files.length - untracked} 个已跟踪文件恢复到 HEAD；`}{untracked > 0 && ` ${untracked} 个未跟踪文件会被删除；`}可能包含你或其他任务的修改，此操作无法撤回。</p>}
        {error && <p className="review-dialog-error" role="alert">{error}</p>}
        {running && <p role="status" className="review-dialog-note">当前任务正在运行，请等待结束后再操作。</p>}
        <footer className="modal-foot">
          <button type="button" className="btn" disabled={busy} onClick={close}>{dialog.partial ? '关闭' : '取消'}</button>
          <button type="submit" id={dialog.type === 'branch' ? 'branch-create' : 'review-undo-confirm'} className={`btn ${dialog.type === 'branch' ? 'btn-primary' : 'btn-danger'}`} disabled={busy || restoring || running || !isGit || (dialog.type === 'branch' ? !branch.trim() : !canUndo || loading || dialog.partial)}>{busy ? (dialog.type === 'branch' ? '创建中…' : '撤销中…') : dialog.type === 'branch' ? '创建并切换' : `撤销 ${dialog.files.length} 个文件的改动`}</button>
        </footer>
      </form>
    </Modal>, document.body)}
  </div>;
}
