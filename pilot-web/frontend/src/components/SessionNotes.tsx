import { useEffect, useRef, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { IconButton } from '@astryxdesign/core/IconButton';
import { Icon } from './Icon.jsx';
import { useStore } from '../store.js';
import { noteInsertion } from '../lib/notes.js';
import './notes.css';

const NOTE_LIMIT = 4000;

/**
 * 当前会话的「想法」面板：随手记下暂时不发送的内容，需要时点一下带回输入框。
 *
 * <p>保存、修改、删除都只动想法本身，不发送消息、也不进入执行队列；
 * 点某条想法只把原文追加到输入框，之后发不发仍由用户决定。
 */
export function SessionNotes({ onClose }: { onClose: () => void }) {
  const sessionId = useStore(state => state.sessionId);
  const notes = useStore(state => state.notes);
  const notesState = useStore(state => state.notesState);
  const notesError = useStore(state => state.notesError);
  const saving = useStore(state => state.notesSaving);
  const noteSeed = useStore(state => state.noteSeed);
  const [draft, setDraft] = useState(() => noteSeed?.text || '');
  const [localError, setLocalError] = useState('');
  const [seed, setSeed] = useState(() => noteSeed);
  const [editing, setEditing] = useState<string | null>(null);
  const [editText, setEditText] = useState('');
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const editRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => { inputRef.current?.focus({ preventScroll: true }); }, []);
  useEffect(() => {
    if (notesState === 'idle') void useStore.getState().loadNotes();
  }, [sessionId, notesState]);
  useEffect(() => { if (editing) editRef.current?.focus({ preventScroll: true }); }, [editing]);

  async function save() {
    const text = draft.trim();
    if (!text || saving) return;
    setLocalError('');
    if (await useStore.getState().addNote(draft, seed?.source)) {
      setDraft('');
      setSeed(null);
      useStore.getState().openNoteFromSelection(null);
      inputRef.current?.focus({ preventScroll: true });
    }
  }

  function startEdit(id: string, content: string) {
    setLocalError('');
    setEditing(id);
    setEditText(content);
  }

  async function saveEdit() {
    if (!editing || !editText.trim() || saving) return;
    setLocalError('');
    if (await useStore.getState().editNote(editing, editText)) {
      setEditing(null);
      setEditText('');
    }
  }

  async function remove(id: string) {
    setLocalError('');
    await useStore.getState().removeNote(id);
  }

  /** 点想法只回填输入框：已有草稿时另起一行追加，不覆盖，也不触发发送。 */
  function insert(content: string) {
    setLocalError('');
    const input = useStore.getState().composerApi;
    if (!input) {
      setLocalError('输入框还没准备好，请稍后再试。');
      return;
    }
    const existing = input.getText();
    input.appendText(noteInsertion(existing, content));
    input.focus();
  }

  const error = localError || notesError;
  return <div id="notes-menu" className="composer-flyout notes-flyout" role="dialog" aria-label="想法">
    <header className="notes-head">
      <strong>想法</strong>
      <span className="notes-count" aria-label={`${notes.length} 条想法`}>{notes.length}</span>
      <IconButton className="notes-close" label="关闭想法" icon={<Icon name="close" />} variant="ghost" onClick={onClose} />
    </header>
    {!sessionId && <p className="notes-draft-hint">还没开始任务：想法先存在本机，发出第一条消息后自动并入当前会话。</p>}
    {seed?.source?.sequence != null && <p className="note-source is-seed" role="status">
      来自第 {seed.source.sequence} 条消息的选中文字；保存后仍可定位，来源被清理时会保留引用原文。
    </p>}
    <div className="notes-form">
      <textarea id="notes-input" ref={inputRef} aria-label="新想法" rows={3} maxLength={NOTE_LIMIT}
        placeholder="记下这一刻的想法，稍后可带回输入框…" value={draft}
        onChange={event => setDraft(event.target.value)}
        onKeyDown={event => {
          if (event.nativeEvent.isComposing || event.keyCode === 229) return;
          if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) { event.preventDefault(); event.stopPropagation(); void save(); }
        }} />
      <div className="notes-form-foot">
        <span className="notes-hint">不会发送，也不会加入执行队列</span>
        <Button id="notes-save" type="button" size="sm" label={saving === 'create' ? '保存中…' : '保存想法'}
          isDisabled={!draft.trim() || Boolean(saving)} onClick={() => void save()} />
      </div>
    </div>
    {notesState === 'loading' && !notes.length && <p className="notes-status" role="status">正在读取想法…</p>}
    {error && <p className="notes-status is-error" role="alert">{error}
      {!localError && <Button label="重试" size="sm" variant="ghost" onClick={() => void useStore.getState().loadNotes()} />}
    </p>}
    <ul className="notes-list" aria-label="已保存的想法">
      {notes.map(note => <li key={note.id} className="note-row">
        {editing === note.id
          ? <div className="note-edit">
            <textarea ref={editRef} aria-label="编辑想法" rows={3} maxLength={NOTE_LIMIT} value={editText}
              onChange={event => setEditText(event.target.value)} onKeyDown={event => {
                if (event.nativeEvent.isComposing || event.keyCode === 229) return;
                if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) { event.preventDefault(); void saveEdit(); }
                if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setEditing(null); }
              }} />
            <div className="note-edit-actions">
              <Button label="取消" size="sm" variant="ghost" isDisabled={Boolean(saving)} onClick={() => setEditing(null)} />
              <Button label={saving === note.id ? '保存中…' : '保存修改'} size="sm" type="button"
                isDisabled={!editText.trim() || Boolean(saving)} onClick={() => void saveEdit()} />
            </div>
          </div>
          : <>
            <button type="button" className="note-content" title="添加到输入框" onClick={() => insert(note.content)}>
              {note.content}
            </button>
            {note.source && <NoteSource source={note.source} />}
            <div className="note-actions">
              <IconButton label="编辑想法" icon={<Icon name="edit" />} variant="ghost"
                isDisabled={Boolean(saving)} onClick={() => startEdit(note.id, note.content)} />
              <IconButton label="删除想法" icon={<Icon name="trash" />} variant="ghost"
                isDisabled={Boolean(saving)} onClick={() => void remove(note.id)} />
            </div>
          </>}
      </li>)}
      {notesState === 'ready' && !notes.length && <li className="notes-empty">还没有想法。执行任务时可以在这里记下稍后要用的内容。</li>}
    </ul>
  </div>;
}

/** 想法来源：可用时点击滚到原消息；历史编辑/清理后只保留引用原文并说明不可用。 */
function NoteSource({ source }: { source: NonNullable<import('../types/server.js').SessionNotesNote['source']> }) {
  const sequence = source.sequence;
  if (sequence == null) {
    return null;
  }
  const role = source.role === 'user' ? '我的消息' : 'AI 回复';
  if (!source.available) {
    return <span className="note-source is-missing" title={source.excerpt || ''}>
      来源不可用（第 {sequence} 条已清理）· 已保留引用原文
    </span>;
  }
  return <button type="button" className="note-source" title={'定位到原消息：' + (source.excerpt || '')}
    onClick={() => {
      const row = document.querySelector('[data-sequence="' + sequence + '"]');
      if (!row) {
        useStore.getState().showNotice('原消息已不在当前窗口，可向上翻页后再试。');
        return;
      }
      row.scrollIntoView({ block: 'center', behavior: 'smooth' });
      row.classList.add('is-note-source');
      window.setTimeout(() => row.classList.remove('is-note-source'), 1600);
    }}>
    {role} · 第 {sequence} 条
  </button>;
}
