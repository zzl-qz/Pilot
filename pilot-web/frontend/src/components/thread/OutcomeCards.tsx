import type { ResultRow, SandboxRow, ArbitrationRow } from "../../types/thread.js";
import type { ApiResults } from "../../types/api.js";
import type React from "react";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Button } from "@astryxdesign/core/Button";
import { useStore } from "../../store.js";
import { api } from "../../api.js";
import { errorInfo } from "../../lib/errors.js";
import { parseDiff } from "../../lib/diff.js";
import { sumChangeLines } from "../../lib/changes.js";
import { extractFileMeta } from "../../lib/tool-meta.js";
import { MarkdownBody } from "../MarkdownBody.jsx";
import { ProcessChevron, ProcessKindIcon } from "./ProcessDetails.js";

const CHANGED_COLLAPSED_ROWS = 3;
const CHANGED_COUNT = new Intl.NumberFormat('en-US');

type ChangedFile = ResultRow['changedFiles'][number];

function changedCount(value: number) {
  return CHANGED_COUNT.format(value);
}

/**
 * 改动卡的增删行。
 *
 * <p>后端算不出逐行差异时（文件太大、二进制、完成态副本对不上）给的是「未知」，
 * 界面标注行数未知，悬停时显示原因。
 */
function ChangedCounts({ added, removed, known = true, note }: {
  added: number;
  removed: number;
  known?: boolean;
  note?: string | null;
}) {
  if (!known) {
    return (
      <span className="changed-counts" title={note || '行数未知'}>
        <span className="changed-unknown">行数未知</span>
      </span>
    );
  }
  return (
    <span className="changed-counts">
      <span className="changed-added">+{changedCount(added)}</span>
      <span className="changed-deleted">-{changedCount(removed)}</span>
    </span>
  );
}

function EditedGlyph() {
  return (
    <svg viewBox="0 0 16 16" aria-hidden="true">
      <path d="M3.2 2.6h6.1L12.8 6v7.4H3.2z" />
      <path d="M9.1 2.8V6h3.4" />
      <path d="M5.2 8.6h5.2M5.2 10.8h3.2" />
    </svg>
  );
}

function UndoGlyph() {
  return (
    <svg viewBox="0 0 16 16" aria-hidden="true">
      <path d="M3.4 6.4h4.6a3.3 3.3 0 1 1 0 6.6H6.2" />
      <path d="M5.6 3.8 3.2 6.4l2.4 2.6" />
    </svg>
  );
}

const PREVIEW_DELAY_MS = 500;
const PREVIEW_FADE_MS = 100;
const PREVIEW_GRACE_MS = 200;
const PREVIEW_MAX_HEIGHT = 420;
const PREVIEW_INSET = 24;
const PREVIEW_GAP = 8;
const PREVIEW_MARGIN = 8;

function pointerCanHover(event: React.PointerEvent) {
  if (event.pointerType === 'touch') return false;
  if (event.pointerType === 'mouse' && !window.matchMedia('(any-hover: hover)').matches) return false;
  return true;
}

function ChangedFilePreview({ file, runId }: { file: ChangedFile; runId?: string }) {
  const [patch, setPatch] = useState<string>();
  const [failed, setFailed] = useState(false);
  const deferred = file.diff === null && Boolean(runId);
  useEffect(() => {
    if (!deferred || !runId) return;
    let active = true;
    api.runChanges(runId).then(report => {
      const diff = report?.files?.find(item => item.path === file.path)?.diff;
      if (active) setPatch(diff ?? '');
    }).catch(() => { if (active) setFailed(true); });
    return () => { active = false; };
  }, [deferred, file.path, runId]);
  const parsed = parseDiff(file.diff ?? patch)[0];
  const lines = (parsed?.lines || []).filter((line) => line.kind === 'add' || line.kind === 'del' || line.kind === 'ctx').slice(0, 240);
  return (
    <div className="changed-hover" data-changes-hover-preview>
      <div className="changed-hover-path" data-changes-preview-path>{file.path}</div>
      {deferred && patch === undefined
        ? <p className="changed-hover-empty" role="status">{failed ? '预览加载失败，可点击文件查看变更。' : '正在加载差异…'}</p>
        : lines.length === 0
        ? <p className="changed-hover-empty" data-diff-note="empty">{file.note || '暂无逐行差异'}</p>
        : (
          <div className="changed-hover-body" data-review-view="unified">
            {lines.map((line, index) => (
              <div key={index} className={`changed-hover-line is-${line.kind}`} data-diff-line={line.kind}>
                <span className="changed-hover-no">{line.old === '' ? '' : line.old}</span>
                <span className="changed-hover-no">{line.next === '' ? '' : line.next}</span>
                <span className="changed-hover-sign">{line.kind === 'add' ? '+' : line.kind === 'del' ? '-' : ''}</span>
                <span className="changed-hover-text">{line.text}</span>
              </div>
            ))}
          </div>
        )}
    </div>
  );
}

function ChangedFilesCard({ files, runId, canRollback, onOpen, onRollback }: {
  files: ResultRow['changedFiles'];
  runId?: string;
  canRollback: boolean;
  onOpen: (file: ChangedFile) => void;
  onRollback: () => void;
}) {
  const cardRef = useRef<HTMLElement>(null);
  const anchorRef = useRef<HTMLElement | null>(null);
  const popRef = useRef<HTMLDivElement>(null);
  const openTimer = useRef<number | null>(null);
  const closeTimer = useRef<number | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [preview, setPreview] = useState<number | null>(null);
  const [closing, setClosing] = useState(false);
  const [place, setPlace] = useState<{ left: number; top: number; width: number; maxHeight: number } | null>(null);
  const { added, removed, known, note } = sumChangeLines(files);
  const foldable = files.length > CHANGED_COLLAPSED_ROWS;
  const rows = foldable && !expanded ? files.slice(0, CHANGED_COLLAPSED_ROWS) : files;
  const hiddenCount = files.length - rows.length;

  const clearOpen = () => {
    if (openTimer.current != null) window.clearTimeout(openTimer.current);
    openTimer.current = null;
  };
  const clearClose = () => {
    if (closeTimer.current != null) window.clearTimeout(closeTimer.current);
    closeTimer.current = null;
  };
  const dismissPreview = useCallback((immediate = false) => {
    clearOpen();
    clearClose();
    if (immediate) {
      setClosing(false);
      setPreview(null);
      return;
    }
    setClosing(true);
    closeTimer.current = window.setTimeout(() => {
      closeTimer.current = null;
      setClosing(false);
      setPreview(null);
    }, PREVIEW_FADE_MS);
  }, []);
  const armClose = () => {
    clearOpen();
    if (preview == null) return;
    clearClose();
    closeTimer.current = window.setTimeout(() => {
      closeTimer.current = null;
      dismissPreview();
    }, PREVIEW_GRACE_MS);
  };
  const schedulePreview = (index: number, anchor: HTMLElement, event: React.PointerEvent) => {
    if (!pointerCanHover(event)) return;
    clearClose();
    anchorRef.current = anchor;
    if (preview === index) {
      setClosing(false);
      return;
    }
    clearOpen();
    openTimer.current = window.setTimeout(() => {
      openTimer.current = null;
      setClosing(false);
      setPreview(index);
    }, PREVIEW_DELAY_MS);
  };
  useEffect(() => () => { clearOpen(); clearClose(); }, []);
  useEffect(() => {
    if (preview == null) return undefined;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') dismissPreview();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [preview, dismissPreview]);
  useLayoutEffect(() => {
    if (preview == null) {
      setPlace(null);
      return undefined;
    }
    const measure = () => {
      const anchor = anchorRef.current;
      const card = cardRef.current;
      if (!anchor || !card) return;
      const row = anchor.getBoundingClientRect();
      const bounds = card.getBoundingClientRect();
      const height = popRef.current?.offsetHeight ?? 0;
      const width = Math.max(0, Math.min(bounds.width - PREVIEW_INSET * 2, window.innerWidth - PREVIEW_MARGIN * 2));
      const belowTop = Math.max(PREVIEW_MARGIN, row.bottom + PREVIEW_GAP);
      const above = Math.max(0, row.top - PREVIEW_GAP - PREVIEW_MARGIN);
      const below = Math.max(0, window.innerHeight - belowTop - PREVIEW_MARGIN);
      const onTop = above >= Math.min(PREVIEW_MAX_HEIGHT, below);
      const maxHeight = Math.min(PREVIEW_MAX_HEIGHT, onTop ? above : below);
      const shown = Math.min(height || maxHeight, maxHeight);
      setPlace({
        left: Math.max(PREVIEW_MARGIN, Math.min(bounds.left + PREVIEW_INSET, window.innerWidth - width - PREVIEW_MARGIN)),
        top: onTop ? Math.max(PREVIEW_MARGIN, row.top - shown - PREVIEW_GAP) : belowTop,
        width,
        maxHeight
      });
    };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    if (popRef.current) observer?.observe(popRef.current);
    if (cardRef.current) observer?.observe(cardRef.current);
    window.addEventListener('scroll', measure, true);
    window.addEventListener('resize', measure);
    return () => {
      observer?.disconnect();
      window.removeEventListener('scroll', measure, true);
      window.removeEventListener('resize', measure);
    };
  }, [preview]);

  return (
    <section ref={cardRef} className="changed-card turn-card-files" data-changed-files>
      <div className="changed-bar">
        <span className="changed-tile"><EditedGlyph /></span>
        <span className="changed-titles">
          <span className="changed-title">{`已编辑 ${files.length} 个文件`}</span>
          <ChangedCounts added={added} removed={removed} known={known} note={note} />
        </span>
        <span className="changed-actions">
          {canRollback && (
            <button type="button" className="changed-undo" title="撤回这一轮写进工作区的文件" onClick={onRollback}>
              撤销
              <UndoGlyph />
            </button>
          )}
          <button
            type="button"
            className="changed-review"
            onClick={() => { dismissPreview(true); onOpen(files[0]); }}
          >
            查看变更
          </button>
        </span>
      </div>
      <ul className="changed-list">
        {rows.map((file, index) => (
          <li key={file.path}>
            <button
              type="button"
              className="changed-row"
              aria-label={`查看 ${file.path} 的改动`}
              onClick={() => { dismissPreview(true); onOpen(file); }}
              onPointerEnter={(event) => schedulePreview(index, event.currentTarget, event)}
              onPointerLeave={() => armClose()}
            >
              <span className="changed-file-label">
                <ProcessKindIcon kind={extractFileMeta(file.path).kind} />
                <span className="changed-path">{file.path}</span>
              </span>
              <ChangedCounts
                added={Number(file.added || 0)}
                removed={Number(file.removed || 0)}
                known={file.countsKnown !== false}
                note={file.note}
              />
            </button>
          </li>
        ))}
      </ul>
      {foldable && (
        <button
          type="button"
          className="changed-toggle"
          aria-expanded={expanded}
          aria-label={expanded ? '收起改动文件列表' : `再显示 ${hiddenCount} 个改动文件`}
          onClick={() => setExpanded((value) => !value)}
        >
          <span>{expanded ? '收起' : `再显示 ${hiddenCount} 个文件`}</span>
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d={expanded ? 'M4 10l4-4 4 4' : 'M4 6l4 4 4-4'} /></svg>
        </button>
      )}
      {preview != null && files[preview] && place && createPortal(
        <div
          ref={popRef}
          className="changed-hover-card"
          data-closing={closing || undefined}
          style={{ left: place.left, top: place.top, width: place.width, maxHeight: place.maxHeight }}
          onPointerEnter={() => { clearClose(); setClosing(false); }}
          onPointerLeave={() => armClose()}
        >
          <ChangedFilePreview key={files[preview].path} file={files[preview]} runId={runId} />
        </div>,
        document.body
      )}
    </section>
  );
}

export function ResultCard({ row, duplicate, hasPrecedingReply = false }: { row: ResultRow; duplicate: boolean; hasPrecedingReply?: boolean }) {
  const rollbackRun = useStore((state) => state.rollbackRun);
  const files = row.changedFiles || [];
  const settled = row.tone === 'ok' || row.tone === 'text';
  // 失败时结论收成一行，改动卡不跟着失败原因换样子。
  const compact = !row.detail || duplicate || row.continued || row.resumable || (!settled && files.length > 0);
  // 纯文本问答已经有回复正文时，不再重复一行「执行结束」。改过的文件仍然单独留下。
  const hideStatus = compact && hasPrecedingReply && (row.tone === 'ok' || row.tone === 'text') && !row.continued && !row.resumable;
  if (hideStatus && !files.length) return null;
  const filesCard = files.length > 0 && (
    <ChangedFilesCard
      files={files}
      runId={row.runIds.at(-1)}
      canRollback={!row.continued}
      onRollback={() => {
        const runId = row.key.startsWith('result:run_') ? row.key.slice('result:'.length) : row.runIds.at(-1);
        if (runId) rollbackRun(runId);
      }}
      onOpen={(file) => {
        const state = useStore.getState();
        const runId = row.runIds.at(-1);
        if (runId) state.setUi({ inspectorOpen: true, inspectorTab: 'changes', reviewRunId: runId, changeView: file.path });
        else state.openPreview(file.path);
      }}
    />
  );
  if (hideStatus) return filesCard;
  return (
    <>
      <article className={`turn-card is-${row.tone}${compact ? ' is-compact' : ''}${row.continued ? ' is-continued' : ''}`}>
        <div className="turn-card-head">
          <span className="turn-card-title">{row.title}</span>
          {row.continued && <span className="turn-resolved">已继续执行</span>}
        </div>
        {row.detail && !duplicate && (
          row.continued || row.resumable || (!settled && files.length > 0)
            ? (
              <details className="turn-card-more">
                <summary>
                  <span>{row.continued ? '查看错误详情' : '查看运行详情'}</span>
                  <span className="process-chevron" aria-hidden="true"><ProcessChevron /></span>
                </summary>
                <div className="turn-card-detail"><MarkdownBody text={row.detail} /></div>
              </details>
            )
            : String(row.detail).length > 280
            ? (
              <details className="turn-card-more">
                <summary>
                  <span>查看结论</span>
                  <span className="process-chevron" aria-hidden="true"><ProcessChevron /></span>
                </summary>
                <div className="turn-card-detail"><MarkdownBody text={row.detail} /></div>
              </details>
            )
            : <div className="turn-card-detail"><MarkdownBody text={row.detail} /></div>
        )}
      </article>
      {filesCard}
    </>
  );
}

export function SandboxCard({ row }: { row: SandboxRow }) {
  const [decision, setDecision] = useState('');
  const [busy, setBusy] = useState<string | false>(false);
  /** 产物清单与校验结论：让「接受」不是盲批。 */
  const [report, setReport] = useState<ApiResults['sandboxManifest'] | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [loadError, setLoadError] = useState('');
  const sessionId = useStore((state) => state.sessionId);

  useEffect(() => {
    setReport(null);
    setLoadError('');
    setDecision('');
    if (!row.sandboxId || !sessionId) {
      return undefined;
    }
    let alive = true;
    api.sandboxManifest(sessionId, row.sandboxId)
      .then((data) => { if (alive && data) { setReport(data); setDecision(({accepted:'已接受',rejected:'已拒绝',closed:'已处理',empty:'没有待处理变更'} as Record<string, string>)[data.disposition] || ''); } })
      .catch((error) => { if (alive) setLoadError(errorInfo(error).message || '读取候选版本失败'); });
    return () => { alive = false; };
  }, [row.sandboxId, sessionId, refresh]);

  const decide = async (action: string) => {
    setBusy(action);
    try {
      const ok = await useStore.getState().decideSandbox(row.sandboxId, action, report?.reviewHash);
      if (useStore.getState().sessionId !== sessionId) return;
      if (ok && action !== 'recover') setDecision(action === 'accept' ? '已接受' : '已拒绝');
      else setRefresh((value) => value + 1);
    } finally { setBusy(false); }
  };

  const verification = report?.verification || null;
  const verified = Boolean(verification?.current) && String(verification?.status).toLowerCase() === 'passed';
  const files = report?.manifest?.files?.length ?? 0;

  return (
    <aside className="sandbox-card">
      <header className="sandbox-head">{row.title}</header>
      <pre className="sandbox-body">{row.detail}</pre>
      {report && (
        <p className={`sandbox-verify${verified ? ' is-ok' : ' is-warn'}`}>
          {verified ? '校验通过' : (verification ? `校验：${({passed:'通过',failed:'失败',skipped:'未执行',missing:'尚未校验'} as Record<string, string>)[String(verification.status).toLowerCase()] || '状态未知'}${verification.reason ? ` · ${verification.reason}` : ''}` : '尚未校验')}
          {` · 产物 ${files} 个文件`}
          {(report.dependencies || []).length > 0 && ` · 依赖 ${report.dependencies.length} 个`}
        </p>
      )}
      {loadError && <p role="alert">{loadError} <Button size="sm" variant="ghost" label="重新加载" onClick={() => setRefresh(value => value + 1)} /></p>}
      {report?.recovery && <p role="status" className="sandbox-verify is-warn">
        上次变更写入中断，需先恢复。恢复遇到外部修改时会停止并保留备份。
        {report.recovery.sessionId === sessionId && report.recovery.sandboxId === row.sandboxId
          ? <Button size="sm" label="恢复写入状态" isLoading={busy === 'recover'} isDisabled={Boolean(busy)} onClick={() => decide('recover')} />
          : `请先处理 ${report.recovery.sandboxId} 的恢复。`}
      </p>}
      {row.sandboxId && (
        <div className="sandbox-actions">
          <span className="sandbox-label mono">{row.sandboxId}</span>
          <Button size="sm" label={busy === 'accept' ? '校验并写入中…' : '接受变更'} isLoading={busy === 'accept'}
            isDisabled={Boolean(busy) || Boolean(decision) || !report?.reviewHash || Boolean(report?.recovery)} onClick={() => decide('accept')} />
          <Button size="sm" variant="ghost" label="拒绝"
            isDisabled={Boolean(busy) || Boolean(decision) || !report?.reviewHash || Boolean(report?.recovery)} onClick={() => decide('reject')} />
          {decision && <span className="sandbox-decision">{decision}</span>}
        </div>
      )}
    </aside>
  );
}

export function ArbitrationCard({ row }: { row: ArbitrationRow }) {
  const resolve = useStore((state) => state.resolveArbitration);
  return (
    <aside className={`arb-card${row.resolved ? ' is-resolved' : ''}`} data-arb-id={row.arbitrationId}>
      <header className="arb-head">{row.title}{row.resolved ? ' · 已裁决' : ''}</header>
      <p className="arb-summary">{row.detail}</p>
      {!row.resolved && (
        <div className="arb-options">
          {(row.options || []).map((option) => (
            <Button
              key={typeof option === 'string' ? option : option.id}
              label={typeof option === 'string' ? option : option.label}
              size="sm"
              onClick={() => resolve(row.arbitrationId, typeof option === 'string' ? option : option.id)}
            />
          ))}
        </div>
      )}
    </aside>
  );
}



