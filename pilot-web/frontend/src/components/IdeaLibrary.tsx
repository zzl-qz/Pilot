import { useEffect, useRef, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { useStore } from '../store.js';
import { readTransferFiles, writeClipboard } from '../lib/clipboard.js';
import { attachmentId } from '../lib/attachments/input.js';
import { fileBadge, formatBytes } from '../lib/attachments/attachments.js';
import { deleteIdea, listIdeas, saveIdea, type Idea } from '../lib/idea-library.js';
import { Icon } from './Icon.jsx';
import { ImagePreview } from './ImagePreview.jsx';
import { Modal } from './Modal.jsx';
import './idea-library.css';

const MAX_FILES = 10;
const MAX_BYTES = 25 * 1024 * 1024;
const IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/avif']);

export default function IdeaLibrary({ onClose }: { onClose: () => void }) {
  const seed = useStore(state => state.ui.ideaSeed);
  const [text, setText] = useState(seed?.text ?? '');
  const [files, setFiles] = useState<File[]>([]);
  const [ideas, setIdeas] = useState<Idea[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [legacyOpen, setLegacyOpen] = useState(false);
  const [preview, setPreview] = useState<{ name: string; src: string } | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  async function reload() {
    setLoading(true);
    setLoadFailed(false);
    try {
      setIdeas(await listIdeas());
    } catch {
      setLoadFailed(true);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void reload(); }, []);

  function addFiles(incoming: File[]) {
    if (!incoming.length || pending.current) return;
    const next = [...files, ...incoming];
    if (next.length > MAX_FILES || next.reduce((total, file) => total + file.size, 0) > MAX_BYTES) {
      setError('每条想法最多 10 个附件，合计不超过 25 MB。');
      return;
    }
    setFiles(next);
    setError('');
  }

  async function changeLibrary(change: () => Promise<void>, failure: string) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setError('');
    setStatus('');
    try {
      await change();
    } catch {
      setError(failure);
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }

  async function save() {
    if (loading || loadFailed || (!text.trim() && !files.length)) return;
    await changeLibrary(async () => {
      const idea = { id: attachmentId(), text, files, createdAt: new Date().toISOString() };
      await saveIdea(idea);
      setIdeas(current => [idea, ...current]);
      setText('');
      setFiles([]);
      setStatus('已保存到想法库。');
    }, '保存失败。请检查本机存储空间后重试。');
  }

  async function remove(id: string) {
    await changeLibrary(async () => {
      await deleteIdea(id);
      setIdeas(current => current.filter(idea => idea.id !== id));
      setConfirmId(null);
      setStatus('已删除想法。');
    }, '删除失败，请重试。');
  }

  async function copy(text: string) {
    setError('');
    setStatus('');
    if (await writeClipboard(text)) setStatus('已复制文字，可以粘贴到目标会话。');
    else setError('自动复制不可用，请选中文字手动复制。');
  }

  return <Modal id="idea-library" title="想法库" onClose={onClose} width={720} dismissible={!busy} initialFocus="#idea-text">
    <div className="idea-library">
      <p className="idea-hint">跨项目使用。文字和附件保存在当前浏览器或桌面端的本地资料中。</p>
      <form className="idea-form" onSubmit={event => { event.preventDefault(); void save(); }}>
        <textarea id="idea-text" rows={4} aria-label="想法文字" placeholder="写下想法，或在这里粘贴截图…"
          value={text} disabled={busy} onChange={event => setText(event.target.value)} onPaste={event => {
            const incoming = readTransferFiles(event.clipboardData);
            if (!incoming.length) return;
            if (!event.clipboardData.getData('text/plain')) event.preventDefault();
            addFiles(incoming);
          }} />
        <input ref={fileInput} type="file" aria-label="想法附件" multiple hidden onChange={event => {
          addFiles(Array.from(event.currentTarget.files ?? []));
          event.currentTarget.value = '';
        }} />
        {files.length > 0 && <ul className="idea-files" aria-label="待保存附件">
          {files.map((file, index) => <li key={`${file.name}:${index}`}>
            <IdeaFile file={file} disabled={busy} onOpen={src => setPreview({ name: file.name, src })}
              onRemove={() => setFiles(current => current.filter((_, i) => i !== index))} />
          </li>)}
        </ul>}
        <div className="idea-actions">
          <Button type="button" size="sm" variant="secondary" label="添加图片或文件" isDisabled={busy}
            onClick={() => fileInput.current?.click()} />
          <span className="idea-hint">最多 10 个文件，共 25 MB</span>
          <Button type="submit" size="sm" label={busy ? '处理中…' : '保存想法'}
            isDisabled={busy || loading || loadFailed || (!text.trim() && !files.length)} />
        </div>
      </form>
      {error && <p role="alert" className="idea-error">{error}</p>}
      {status && <p role="status" className="idea-hint">{status}</p>}
      {loading && <p role="status">正在读取想法库…</p>}
      {loadFailed && <div role="alert">无法读取本地想法库。 <Button size="sm" label="重试" onClick={() => void reload()} /></div>}
      {!loading && !loadFailed && !ideas.length && <p className="idea-hint">还没有保存的想法。</p>}
      <ul className="idea-list" aria-label="已保存的想法">
        {ideas.map(idea => <li className="idea-card" key={idea.id}>
          {idea.text && <p className="idea-text">{idea.text}</p>}
          {idea.files.length > 0 && <ul className="idea-files" aria-label="附件">
            {idea.files.map((file, index) => <li key={`${file.name}:${index}`}>
              <IdeaFile file={file} saved onOpen={src => setPreview({ name: file.name, src })} />
            </li>)}
          </ul>}
          <div className="idea-actions">
            {idea.text && <Button size="sm" variant="secondary" label="复制文字" onClick={() => void copy(idea.text)} />}
            {confirmId === idea.id ? <>
              <Button size="sm" variant="ghost" label="取消删除" isDisabled={busy} onClick={() => setConfirmId(null)} />
              <Button size="sm" variant="secondary" label="确认删除" isDisabled={busy} onClick={() => void remove(idea.id)} />
            </> : <Button size="sm" variant="ghost" label="删除想法" isDisabled={busy} onClick={() => setConfirmId(idea.id)} />}
          </div>
        </li>)}
      </ul>
      <details className="idea-legacy" onToggle={event => setLegacyOpen(event.currentTarget.open)}>
        <summary>查看当前会话的旧想法</summary>
        {legacyOpen && <LegacyIdeas disabled={busy || loading || loadFailed} copyToLibrary={(id, text, createdAt) => {
          const idea = { id: `legacy:${id}`, text, files: [], createdAt };
          void changeLibrary(async () => {
            await saveIdea(idea);
            setIdeas(await listIdeas());
            setStatus('已复制到想法库。');
          }, '复制到想法库失败，请重试。');
        }} />}
      </details>
      {/* 大图放在弹窗内容里：原生 dialog 在顶层，挂在外面会被它盖住 */}
      {preview && <ImagePreview name={preview.name} src={preview.src} onClose={() => setPreview(null)} />}
    </div>
  </Modal>;
}

/** 附件卡片：预览在上，名字与大小在下；待保存的缩略图右上角带一个移除按钮。 */
function IdeaFile({ file, saved = false, disabled = false, onOpen, onRemove }: {
  file: File;
  saved?: boolean;
  disabled?: boolean;
  onOpen?: (src: string) => void;
  onRemove?: () => void;
}) {
  const [url, setUrl] = useState('');
  const image = IMAGE_TYPES.has(file.type);
  useEffect(() => {
    if (!saved && !image) return;
    const objectUrl = URL.createObjectURL(file);
    setUrl(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [file, image, saved]);

  const badge = fileBadge(file.name, file.type);
  return <div className="idea-file">
    {image && url
      // 缩略图就是查看入口：点一下在弹层里看原图，和输入框附件的行为一致
      ? <button type="button" className="idea-file-thumb" title={`查看 ${file.name}`} aria-label={`查看 ${file.name}`}
        onClick={() => onOpen?.(url)}><img src={url} alt={file.name} loading="lazy" /></button>
      : <span className="idea-file-placeholder"><span className={`chip-badge ft-${badge.tone}`}>{badge.label}</span></span>}
    <span className="idea-file-detail">
      {/* 已保存的附件可以下载，文件名做成链接；大小只在有值时占位，避免空标签撑高卡片 */}
      {saved && url
        ? <a className="idea-file-name" href={url} download={file.name} title={file.name}>{file.name}</a>
        : <span className="idea-file-name" title={file.name}>{file.name}</span>}
      {file.size > 0 && <span className="idea-file-meta">{formatBytes(file.size)}</span>}
    </span>
    {onRemove && <button type="button" className="idea-file-remove" title="移除" aria-label={`移除 ${file.name}`}
      disabled={disabled} onClick={onRemove}><Icon name="close" /></button>}
  </div>;
}

function LegacyIdeas({ disabled, copyToLibrary }: {
  disabled: boolean;
  copyToLibrary: (id: string, text: string, createdAt: string) => void;
}) {
  const session = useStore(state => state.sessionId);
  const state = useStore(store => store.notesState);
  const error = useStore(store => store.notesError);
  const notes = useStore(store => store.notes);
  const load = useStore.getState().loadNotes;
  useEffect(() => { if (state === 'idle') void load(); }, [session, state, load]);
  return <>
    {(state === 'loading' || state === 'idle') && <p role="status">正在读取旧想法…</p>}
    {state === 'error' && <div role="alert">{error} <Button size="sm" label="重试" onClick={() => void load()} /></div>}
    {state === 'ready' && !notes.length && <p className="idea-hint">这里没有旧想法。</p>}
    <ul className="idea-list" aria-label="旧会话想法">
      {notes.map(note => <li className="idea-card" key={note.id}>
        <p className="idea-text">{note.content}</p>
        <Button size="sm" variant="secondary" label="复制到想法库" isDisabled={disabled}
          onClick={() => copyToLibrary(`${session ?? 'draft'}:${note.id}`, note.content, note.createdAt)} />
      </li>)}
    </ul>
  </>;
}
