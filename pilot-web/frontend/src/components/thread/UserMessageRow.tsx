import type { UserRow } from "../../types/thread.js";
import type { MessageAttachment } from "../../types/runtime.js";
import React, { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Dialog } from "@astryxdesign/core/Dialog";
import { IconButton } from "@astryxdesign/core/IconButton";
import { Spinner } from "@astryxdesign/core/Spinner";
import { useStore } from "../../store.js";
import { api, apiFetch } from "../../api.js";
import { errorInfo } from "../../lib/errors.js";
import { messagePeek } from "../../lib/side-chat.js";
import { splitAttachmentMarkers, attachmentUrl, isImageAttachment, fileBadge, formatBytes } from "../../lib/attachments/attachments.js";
import { StructuredDocumentView, isStructuredDocumentFormat } from "../files/StructuredDocumentView.jsx";
import { Icon } from "../Icon.jsx";

const PdfAttachmentPreview = React.lazy(() => import("../PdfAttachmentPreview.jsx"));

export function UserMessage({ row }: { row: UserRow }) {
  const text = row.text || '';
  const [open, setOpen] = useState(false);
  const [previewFile, setPreviewFile] = useState<MessageAttachment | null>(null);
  const parts = splitAttachmentMarkers(text);
  const peek = messagePeek(parts.body || text);
  const attachments = row.attachments || [];
  const images = attachments.filter(isImageAttachment);
  const files = [...(parts.files || []), ...attachments.filter(item => !isImageAttachment(item))];
  const canPeek = peek.collapsed && !images.length && !files.length;
  const actions = row.steered ? null : (
    <div className="msg-actions">
      <IconButton className="message-action msg-edit-btn" size="sm" variant="ghost"
        label="编辑并重新发送" icon={<Icon name="edit" />} onClick={() => beginEdit(row)} />
      {row.sequence != null && (
        <IconButton className="message-action msg-edit-btn" size="sm" variant="ghost" label="回退或另开分支"
          icon={<Icon name="branch" />} onClick={() => useStore.getState().openCheckpoint(row.sequence!)} />
      )}
    </div>
  );
  if (canPeek && !open) {
    return (
      <article className="msg msg-user" tabIndex={-1} data-nav-key={row.key} data-sequence={row.sequence ?? undefined}>
        <button type="button" className="msg-peek" aria-expanded="false" title="展开这条消息" onClick={() => setOpen(true)}>
          <span className="msg-peek-grip" aria-hidden="true">
            <svg viewBox="0 0 16 16"><path d="M3 6.5h10M3 9.5h10" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg>
          </span>
          <span className="msg-peek-text">{peek.preview}</span>
        </button>
        {actions}
      </article>
    );
  }
  return (
    <>
    <article className="msg msg-user" tabIndex={-1} data-nav-key={row.key} data-sequence={row.sequence ?? undefined}>
      <div className="bubble">
        {(parts.body || files.length > 0) && <div className="bubble-flow">{renderMessageBody(parts.body, files, setPreviewFile)}</div>}
        {images.length > 0 && (
          <div className="bubble-images">
            {images.map((image) => <ImageThumb key={image.url || image.path || image.name} image={image} onPreview={setPreviewFile} />)}
          </div>
        )}
      </div>
      {canPeek && (
        <button type="button" className="msg-peek-fold" onClick={() => setOpen(false)}>收起</button>
      )}
      {actions}
    </article>
    {previewFile && <AttachmentPreview file={previewFile} onClose={() => setPreviewFile(null)} />}
    </>
  );
}

function beginEdit(row: UserRow) {
  if (row.attachments?.length) {
    useStore.getState().showNotice('带附件的历史消息请作为新消息补充要求，原附件仍保留在对话中。');
    return;
  }
  const composerApi = useStore.getState().composerApi;
  const plain = row.text || '';
  if (composerApi?.getText().trim() || composerApi?.attachmentIds?.().length) {
    useStore.getState().showNotice('输入框还有未发送的草稿，请先处理草稿再编辑历史消息。');
    composerApi?.focus(); return;
  }
  composerApi?.setText(plain);

  composerApi?.focus();
  useStore.setState((draft) => { draft._editTarget = row.sequence ?? null; });
}

/**
 * 消息正文只渲染文字与文件卡。
 * 图片不再插回输入时的字符位置：粘贴位置会随之后的编辑漂移，图片一旦是行内元素就会把
 * 正文切成绕排的碎片（文字包着图）。图片统一成正文下方一块独立区域，见 bubble-images。
 */
function renderMessageBody(body: string, files: MessageAttachment[] = [], onPreview?: (file: MessageAttachment) => void) {
  return <>
    {body && <span>{body}</span>}
    {files.map((file, index) => <FileCard key={index} file={file} onPreview={onPreview} />)}
  </>;
}

function ImageThumb({ image, onPreview }: { image: MessageAttachment; onPreview?: (image: MessageAttachment) => void }) {
  const sessionId = useStore((state) => state.sessionId);
  const [broken, setBroken] = useState(false);
  const name = image.name || '图片';
  // 读不到图时留固定尺寸的占位，不把排版撑开也不留破图框。
  if (broken) {
    return <span className="bubble-image is-broken" role="img" aria-label={`${name} 加载失败`}>图片加载失败</span>;
  }
  return (
    <span className="bubble-image">
      <button className="bubble-image-action" type="button" aria-label={`预览图片 ${name}`} onClick={event => { event.currentTarget.focus({ preventScroll: true }); onPreview?.(image); }}>
        <img src={attachmentUrl(sessionId, image)} alt={name} loading="lazy" onError={() => setBroken(true)} />
      </button>
    </span>
  );
}

function FileCard({ file, onPreview }: { file: MessageAttachment; onPreview?: (file: MessageAttachment) => void }) {
  const sessionId = useStore((state) => state.sessionId);
  const url = attachmentUrl(sessionId, file);
  const badge = fileBadge(file.name, file.mimeType);
  if (!url) return <span className="file-card" title={file.name}>
    <span className={`file-card-badge ft-${badge.tone}`}>{badge.label}</span>
    <span className="file-card-name">{file.name || file.id}</span>
    <span className="file-card-meta">附件</span>
  </span>;
  return (
    <span className="file-card" title={file.name || file.id}>
      <span className={`file-card-badge ft-${badge.tone}`}>{badge.label}</span>
      <span className="file-card-name">{file.name || file.id}</span>
      <span className="file-card-meta">{formatBytes(file.size)}</span>
      <button type="button" className="file-card-action" onClick={event => { event.currentTarget.focus({ preventScroll: true }); onPreview?.(file); }} aria-label={`预览附件 ${file.name || file.id}`} title="预览附件">查看</button>
      <a className="file-card-action" href={url} title="下载附件" aria-label={`下载附件 ${file.name || file.id}`} download={file.name || undefined}>下载</a>
    </span>
  );
}

function AttachmentPreview({ file, onClose }: { file: MessageAttachment; onClose: () => void }) {
  const sessionId = useStore(state => state.sessionId);
  const [text, setText] = useState('');
  const [blobUrl, setBlobUrl] = useState('');
  const [officeFallback, setOfficeFallback] = useState(false);
  const [pdfData, setPdfData] = useState<ArrayBuffer | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const name = file.name || '附件';
  const ext = name.split('.').pop()?.toLowerCase() || '';
  const office = ['docx', 'xlsx', 'pptx'].includes(ext);
  const textual = ['txt', 'md', 'json', 'csv', 'xml', 'yaml', 'yml', 'log', 'js', 'ts', 'tsx', 'css', 'html', 'svg', 'java', 'py', 'sh', 'sql'].includes(ext);
  const image = isImageAttachment(file);
  const url = attachmentUrl(sessionId, file);

  useEffect(() => {
    let alive = true;
    let currentUrl = '';
    const controller = new AbortController();
    setLoading(true); setError(''); setText(''); setBlobUrl(''); setPdfData(null); setOfficeFallback(false);
    void (async () => {
      try {
        if (office && sessionId) {
          const storedName = String(file.path || '').split(/[\\/]/).pop() || '';
          const result = await api.attachmentPreviewText(sessionId, storedName, controller.signal);
          if (!result || !alive) throw new Error('附件内容为空或无法读取');
          setOfficeFallback(true);
          setText(result.text || '（没有可读文本内容）');
        } else if (image || ext === 'pdf' || textual) {
          const response = await apiFetch(url, { signal: controller.signal });
          if (!response.ok) throw new Error(`读取附件失败（HTTP ${response.status}）`);
          const blob = await response.blob();
          if (ext === 'pdf') { if (alive) setPdfData(await blob.arrayBuffer()); }
          else if (image) { currentUrl = URL.createObjectURL(blob); if (alive) setBlobUrl(currentUrl); }
          else setText((await blob.text()).slice(0, 250000));
        } else {
          setError('此格式暂不支持应用内预览，请下载后使用本机应用打开。');
        }
      } catch (failure) {
        if (alive && !controller.signal.aborted) setError(errorInfo(failure).message || '预览失败，请下载附件后重试。');
      } finally { if (alive) setLoading(false); }
    })();
    return () => { alive = false; controller.abort(); if (currentUrl) URL.revokeObjectURL(currentUrl); };
  }, [file, sessionId, url, ext, office, textual, image]);

  const supportsModal = typeof HTMLDialogElement !== 'undefined' && typeof HTMLDialogElement.prototype.showModal === 'function';
  const closeRef = useRef<HTMLButtonElement>(null);
  useLayoutEffect(() => {
    if (supportsModal) return;
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeRef.current?.focus();
    return () => { if (trigger?.isConnected) trigger.focus({ preventScroll: true }); };
  }, [supportsModal]);

  const preview = <section className={'attachment-preview conversation-attachment-preview' + (supportsModal ? '' : ' is-inline')}
    aria-label={supportsModal ? undefined : '附件预览：' + name} onKeyDown={event => {
      if (event.key !== 'Escape' || event.nativeEvent.isComposing || event.keyCode === 229) return;
      event.stopPropagation();
      if (!supportsModal) { event.preventDefault(); onClose(); }
    }}>
    <header>
      <strong title={name}>{name}</strong>
      <div>
        {url && <a className="btn" href={url} download={name}>下载</a>}
        <IconButton ref={closeRef} variant="ghost" label="关闭预览" icon={<Icon name="close" />} onClick={onClose} />
      </div>
    </header>
    <div className={'attachment-preview-content' + (pdfData ? ' is-pdf' : '')} aria-busy={loading}>
      {loading ? <p role="status"><Spinner size="sm" shade="subtle" aria-hidden="true" />正在读取附件…</p>
        : error ? <p role="alert">{error}</p>
        : blobUrl && image ? <img src={blobUrl} alt={name} />
        : pdfData ? <React.Suspense fallback={<p role="status">正在加载 PDF 阅读器…</p>}>
          <PdfAttachmentPreview data={pdfData} name={name} />
        </React.Suspense> : isStructuredDocumentFormat(name)
          ? <StructuredDocumentView text={text} filename={name} />
          : <pre tabIndex={0}>{text}</pre>}
    </div>
    {office && officeFallback && <footer role="status">这里只展示可提取文本，不还原 Office 版式；完整文件可用右上角下载。</footer>}
  </section>;
  if (!supportsModal) return preview;
  return <Dialog isOpen padding={0} width="min(1000px, calc(100vw - 24px))" maxHeight="calc(100dvh - 24px)"
    aria-label={'附件预览：' + name} className="conversation-attachment-dialog"
    onOpenChange={open => { if (!open) onClose(); }}>
    {preview}
  </Dialog>;
}
