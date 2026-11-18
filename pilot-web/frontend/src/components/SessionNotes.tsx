import { useEffect, useRef, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { IconButton } from '@astryxdesign/core/IconButton';
import { Icon } from './Icon.jsx';
import { useStore } from '../store.js';
import { noteInsertion } from '../lib/notes.js';
import { api } from '../api.js';
import { errorInfo } from '../lib/errors.js';
import './notes.css';

const NOTE_LIMIT = 4000;

/** 想法的四种状态；刻意做得很少，它不是看板，只用来把「还没安排的」挑出来。 */
const NOTE_STATUS_LABELS: Record<string, string> = {
  inbox: '待安排',
  planned: '已安排',
  done: '已完成',
  dropped: '已放弃'
};

/**
 * 当前会话的「想法」面板：随手记下暂时不发送的内容，需要时点一下带回输入框。
 *
 * <p>保存、修改、删除都只动想法本身，不发送消息、也不进入执行队列；
 * 点某条想法只把原文追加到输入框，之后发不发仍由用户决定。
 */
export function SessionNotes({ onClose, embedded = false, onAsk }: { onClose?: () => void; embedded?: boolean; onAsk?: (text: string) => void }) {
  const sessionId = useStore(state => state.sessionId);
  const notes = useStore(state => state.notes);
  const notesState = useStore(state => state.notesState);
  const notesError = useStore(state => state.notesError);
  const saving = useStore(state => state.notesSaving);
  const noteSeed = useStore(state => state.noteSeed);
  const noteFocus = useStore(state => state.noteFocus);
  const projectId = useStore(state => state.currentProjectId);
  const [draft, setDraft] = useState(() => noteSeed?.text || '');
  const [localError, setLocalError] = useState('');
  const [seed, setSeed] = useState(() => noteSeed);
  const [editing, setEditing] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState('');
  const [hits, setHits] = useState<import('../types/server.js').ProjectNotesControllerNoteHit[] | null>(null);
  const searchSeq = useRef(0);
  const [editText, setEditText] = useState('');
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const editRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => { inputRef.current?.focus({ preventScroll: true }); }, []);
  useEffect(() => {
    if (notesState === 'idle') void useStore.getState().loadNotes();
  }, [sessionId, notesState]);
  useEffect(() => { if (editing) editRef.current?.focus({ preventScroll: true }); }, [editing]);
  useEffect(() => {
    if (!noteFocus) return;
    const row = document.querySelector('[data-note-id="' + noteFocus + '"]');
    if (row) {
      row.scrollIntoView({ block: 'center', behavior: 'smooth' });
      row.classList.add('is-note-focus');
      window.setTimeout(() => row.classList.remove('is-note-focus'), 1600);
      useStore.getState().focusNote(null);
    }
  }, [noteFocus, notes]);

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

  async function searchProject() {
    const term = query.trim();
    if (!term || !projectId || searching) return;
    const token = ++searchSeq.current;
    setSearching(true);
    setSearchError('');
    try {
      const data = await api.searchNotes(projectId, term);
      if (token !== searchSeq.current) return;
      setHits(Array.isArray(data?.items) ? data.items : []);
    } catch (error) {
      if (token === searchSeq.current) setSearchError(errorInfo(error).message || '搜索想法失败，请重试。');
    } finally {
      if (token === searchSeq.current) setSearching(false);
    }
  }

  async function openHit(hit: import('../types/server.js').ProjectNotesControllerNoteHit) {
    if (await useStore.getState().activate(hit.sessionId)) {
      useStore.getState().focusNote(hit.noteId);
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

  /**
   * 转为任务：取回组装好的正文放进输入框，状态由服务端标为已安排。
   *
   * <p>仍然不替用户发送——转任务只是「把随笔整理成一句能执行的话」，
   * 发不发、什么时候发，和手打一条消息完全一样。
   */
  async function promote(id: string) {
    setLocalError('');
    const text = await useStore.getState().promoteNote(id);
    if (!text) return;
    const input = useStore.getState().composerApi;
    if (!input) {
      setLocalError('任务正文已生成，但输入框还没准备好；请稍后再点一次。');
      return;
    }
    input.appendText(noteInsertion(input.getText(), text));
    input.focus();
  }

  async function changeStatus(id: string, status: string) {
    setLocalError('');
    await useStore.getState().setNoteStatus(id, status);
  }

  const error = localError || notesError;
  const [statusFilter, setStatusFilter] = useState<string>('all');
  const [searchOpen, setSearchOpen] = useState(false);
  const visible = statusFilter === 'all' ? notes : notes.filter(note => (note.status || 'inbox') === statusFilter);
  const inboxCount = notes.filter(note => (note.status || 'inbox') === 'inbox').length;
  return <div id="notes-menu" className={embedded ? 'notes-embedded' : 'composer-flyout notes-flyout'}
    role={embedded ? 'region' : 'dialog'} aria-label="想法">
    <header className="notes-head">
      <strong>想法</strong>
      <span className="notes-count" aria-label={`${notes.length} 条想法`}>{notes.length}</span>
      {projectId && <button type="button" className={`note-filter notes-search-toggle${searchOpen ? ' is-active' : ''}`}
        aria-expanded={searchOpen} onClick={() => setSearchOpen(open => !open)}>搜索</button>}
      {!embedded && onClose && <IconButton className="notes-close" label="关闭想法" icon={<Icon name="close" />} variant="ghost" onClick={onClose} />}
    </header>
    {!sessionId && <p className="notes-draft-hint">还没开始任务：想法先存在本机，发出第一条消息后自动并入当前会话。</p>}
    {seed?.source?.sequence != null && <p className="note-source is-seed" role="status">
      来自第 {seed.source.sequence} 条消息的选中文字；保存后仍可定位，来源被清理时会保留引用原文。
    </p>}
    {searchOpen && <div className="notes-search">
      <div className="notes-search-row">
        <input id="notes-search-input" type="search" value={query} maxLength={200}
          placeholder="在这个项目的想法里找" aria-label="在项目中搜索想法"
          onChange={event => setQuery(event.target.value)}
          onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); void searchProject(); } }} />
        <Button size="sm" label={searching ? '搜索中…' : '搜索'} isDisabled={!query.trim() || searching}
          onClick={() => void searchProject()} />
      </div>
      {searchError && <p className="notes-status is-error" role="alert">{searchError}</p>}
      {hits && <ul className="notes-search-hits" aria-label="项目想法搜索结果">
        {hits.map(hit => <li key={hit.sessionId + ':' + hit.noteId}>
          <div className="notes-hit">
            <span className="notes-hit-session">{hit.sessionTitle} · {noteWhen(hit.createdAt)}</span>
            <span className="notes-hit-snippet">{hit.snippet}</span>
            <span className="notes-hit-actions">
              <button type="button" onClick={() => insert(hit.content)}>放到输入框</button>
              <button type="button" onClick={() => void openHit(hit)}>打开那条对话</button>
            </span>
          </div>
        </li>)}
        {!hits.length && <li className="notes-empty">没有匹配的想法。</li>}
      </ul>}
    </div>}
    <div className="notes-scroll">
    {notesState === 'loading' && !notes.length && <p className="notes-status" role="status">正在读取想法…</p>}
    {error && <p className="notes-status is-error" role="alert">{error}
      {!localError && <Button label="重试" size="sm" variant="ghost" onClick={() => void useStore.getState().loadNotes()} />}
    </p>}
    <ul className="notes-list" aria-label="已保存的想法">
      {notes.length > 0 && <li className="notes-filters" aria-label="按状态筛选想法">
        {[['all', `全部 ${notes.length}`], ['inbox', `待安排 ${inboxCount}`], ['planned', '已安排'], ['done', '已完成'], ['dropped', '已放弃']]
          .map(([value, label]) => <button key={value} type="button" className={`note-filter${statusFilter === value ? ' is-active' : ''}`}
            aria-pressed={statusFilter === value} onClick={() => setStatusFilter(value)}>{label}</button>)}
      </li>}
      {visible.map(note => <li key={note.id} className="note-row" data-note-id={note.id} data-status={note.status || 'inbox'}>
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
            <p className="note-content">{note.content}</p>
            <div className="note-meta">
              <time dateTime={note.createdAt}>{noteWhen(note.createdAt)}</time>
              <label className="note-status-pick">
                <span className="note-status-label">状态</span>
                <select aria-label="切换想法状态" value={note.status || 'inbox'} disabled={Boolean(saving)}
                  data-status={note.status || 'inbox'}
                  onChange={event => void changeStatus(note.id, event.target.value)}>
                  {Object.entries(NOTE_STATUS_LABELS).map(([value, label]) =>
                    <option key={value} value={value}>{label}</option>)}
                </select>
              </label>
              {note.source && <NoteSource source={note.source} />}
            </div>
            <div className="note-actions">
              <Button size="sm" variant="ghost" label="放到输入框" isDisabled={Boolean(saving)} onClick={() => insert(note.content)} />
              {onAsk && (
                <Button size="sm" variant="ghost" label="问一下" isDisabled={Boolean(saving)}
                  onClick={() => { setLocalError(''); onAsk(note.content); }} />
              )}
              {note.status !== 'planned' && note.status !== 'done' && (
                <Button size="sm" variant="ghost" label="转成任务" isDisabled={Boolean(saving)}
                  onClick={() => void promote(note.id)} />
              )}
              <IconButton label="编辑想法" icon={<Icon name="edit" />} variant="ghost"
                isDisabled={Boolean(saving)} onClick={() => startEdit(note.id, note.content)} />
              <IconButton label="删除想法" icon={<Icon name="trash" />} variant="ghost"
                isDisabled={Boolean(saving)} onClick={() => void remove(note.id)} />
            </div>
          </>}
      </li>)}
      {!visible.length && notesState === 'ready' && notes.length > 0 && <li className="notes-empty">这个状态下没有想法。</li>}
      {notesState === 'ready' && !notes.length && <li className="notes-empty">还没有想法。先在下面记下，之后可以放回输入框、拿去问一句，或转成任务。</li>}
    </ul>
    </div>
    <div className="notes-form">
      <textarea id="notes-input" ref={inputRef} aria-label="新想法" rows={3} maxLength={NOTE_LIMIT}
        placeholder="记下这一刻的想法，稍后可带回输入框…" value={draft}
        onChange={event => setDraft(event.target.value)}
        onKeyDown={event => {
          if (event.nativeEvent.isComposing || event.keyCode === 229) return;
          if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) { event.preventDefault(); event.stopPropagation(); void save(); }
        }} />
      <div className="notes-form-foot">
        <span className="notes-hint">⌘/Ctrl + Enter 保存，不会发出去</span>
        <Button id="notes-save" type="button" size="sm" label={saving === 'create' ? '保存中…' : '保存'}
          isDisabled={!draft.trim() || Boolean(saving)} onClick={() => void save()} />
      </div>
    </div>
  </div>;
}

function noteWhen(iso: string) {
  const time = Date.parse(iso);
  if (!Number.isFinite(time)) return '';
  const delta = Date.now() - time;
  if (delta < 60_000) return '刚刚';
  if (delta < 3_600_000) return `${Math.floor(delta / 60_000)} 分钟前`;
  if (delta < 86_400_000) return `${Math.floor(delta / 3_600_000)} 小时前`;
  return new Date(time).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' });
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
