import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useStore } from '../store.js';
import { splitAttachmentMarkers } from '../lib/attachments.js';
import './thread-navigator.css';
import { api } from '../api.js';
import { localBookmarks, normalizeBookmarks, syncBookmarks } from '../lib/bookmark-sync.js';

const plain = text => String(text || '').replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').replace(/^[#>\s]+/gm, '').replace(/[*`_]/g, '').trim();
function entryFor(row) {
  const text = plain(splitAttachmentMarkers(row.text || '').body);
  const files = row.attachments?.map(a => a.name || a.filename || '附件').join('、');
  const lines = (text || files || '附件消息').split('\n').filter(Boolean);
  return { id: String(row.sequence ?? row.key), key: row.key, sequence: row.sequence, title: lines[0].slice(0, 100), preview: lines.slice(1).join('\n').slice(0, 350) };
}
function Bookmark({ filled = false }) {
  return <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M5 3.5h10v13l-5-3-5 3z" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" /></svg>;
}

/** 只导航用户消息；工具输出与流式片段不产生重复刻度。滚动跟随仍由原有库控制。 */
export default function ThreadNavigator({ rows, scrollRef, contentRef, stopFollowing, beforeJump }) {
  const session = useStore(s => s.sessionId);
  return <Navigation key={session || 'draft'} session={session} rows={rows} scrollRef={scrollRef} contentRef={contentRef} stopFollowing={stopFollowing} beforeJump={beforeJump} />;
}
function Navigation({ session, rows, scrollRef, contentRef, stopFollowing, beforeJump }) {
  const history = useStore(s => s.thread.history);
  const [bookmarks, setBookmarks] = useState(() => normalizeBookmarks(localBookmarks(session)));
  const [syncError, setSyncError] = useState('');
  const [saving, setSaving] = useState(false);
  const mutation = useRef(0), mounted = useRef(true);
  const navigationTarget = useStore(s => s.ui.navigationTarget);
  const [active, setActive] = useState(null);
  const [peek, setPeek] = useState(null);
  const [panel, setPanel] = useState(false);
  const [onlySaved, setOnlySaved] = useState(false);
  const [jumping, setJumping] = useState(null);
  const root = useRef(null), popup = useRef(null), closeTimer = useRef(null), operation = useRef(0);
  const entries = useMemo(() => {
    const map = new Map(bookmarks.map(e => [String(e.sequence), e]));
    for (const row of rows) if (row.type === 'user') { const e = entryFor(row); map.set(e.id, e); }
    return [...map.values()].sort((a, b) => (a.sequence ?? Infinity) - (b.sequence ?? Infinity));
  }, [rows, bookmarks]);
  const identities = entries.map(e => `${e.id}:${e.key}`).join('|');
  const saved = new Set(bookmarks.map(e => String(e.sequence)));
  const target = useCallback(entry => [...(contentRef.current?.querySelectorAll('[data-nav-key]') || [])]
    .find(el => el.dataset.navKey === entry.key || (entry.sequence != null && el.dataset.sequence === String(entry.sequence))), [contentRef]);
  useEffect(() => {
    const scroll = scrollRef.current, content = contentRef.current;
    if (!scroll || !content) return;
    let frame;
    const measure = () => {
      frame = null;
      const top = scroll.getBoundingClientRect().top + Math.min(100, scroll.clientHeight / 4);
      const nodes = [...content.querySelectorAll('[data-nav-key]')];
      const byKey = new Map(nodes.map(el => [el.dataset.navKey, el]));
      const bySequence = new Map(nodes.map(el => [el.dataset.sequence, el]));
      const rendered = entries.map(entry => ({ entry, el: byKey.get(entry.key) || bySequence.get(String(entry.sequence)) })).filter(item => item.el);
      let selected;
      for (const { entry, el } of rendered) {
        if (!selected || el.getBoundingClientRect().top <= top) selected = entry.id;
        else break;
      }
      if (scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 8) selected = rendered.at(-1)?.entry.id;
      setActive(selected);
    };
    const schedule = () => { if (frame == null) frame = requestAnimationFrame(measure); };
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(schedule) : null;
    observer?.observe(content); observer?.observe(scroll); scroll.addEventListener('scroll', schedule, { passive: true }); window.addEventListener('resize', schedule); schedule();
    return () => { cancelAnimationFrame(frame); observer?.disconnect(); scroll.removeEventListener('scroll', schedule); window.removeEventListener('resize', schedule); };
  }, [identities, scrollRef, contentRef, target]);
  useEffect(() => {
    if (peek || panel) return;
    const tick = root.current?.querySelector('[aria-current="location"]');
    const track = tick?.parentElement;
    if (!track) return;
    const item = tick.getBoundingClientRect(), view = track.getBoundingClientRect();
    if (item.top < view.top) track.scrollTop -= view.top - item.top;
    else if (item.bottom > view.bottom) track.scrollTop += item.bottom - view.bottom;
  }, [active, identities, peek, panel]);
  useEffect(() => () => { clearTimeout(closeTimer.current); operation.current++; }, []);
  async function refreshBookmarks() {
    if (!session) return;
    const token = mutation.current;
    try { const data = await syncBookmarks(session); if (mounted.current && token === mutation.current) { setBookmarks(data.values); setSyncError(''); if (data.skipped.length) useStore.getState().showNotice(`${data.skipped.length} 个旧书签无法定位，已保留本地备份。`); } }
    catch (e) { if (mounted.current && token === mutation.current) setSyncError(`书签同步失败：${e.message}。本地备份仍保留。`); }
  }
  useEffect(() => {
    mounted.current = true; refreshBookmarks(); const timer = setInterval(refreshBookmarks, 15000);
    const focus = () => refreshBookmarks(); const changed = event => { if (event.detail === session) refreshBookmarks(); }; window.addEventListener('focus', focus); window.addEventListener('pilot:bookmarks-changed', changed);
    return () => { mounted.current = false; clearInterval(timer); window.removeEventListener('focus', focus); window.removeEventListener('pilot:bookmarks-changed', changed); };
  }, [session]);
  useEffect(() => {
    if (navigationTarget?.sessionId !== session || !Number.isFinite(navigationTarget.sequence)) return;
    const entry = { id: String(navigationTarget.sequence), key: '', sequence: navigationTarget.sequence };
    jump(entry); useStore.getState().setUi({ navigationTarget: null });
  }, [navigationTarget]);
  useEffect(() => {
    if (!peek && !panel) return;
    const close = event => {
      if (event.type === 'keydown') {
        if (event.key !== 'Escape') return;
        event.preventDefault(); event.stopImmediatePropagation();
      } else if (root.current?.contains(event.target) || popup.current?.contains(event.target)) return;
      setPeek(null); setPanel(false); operation.current++; setJumping(null);
    };
    const resize = () => setPeek(null);
    document.addEventListener('keydown', close, true); document.addEventListener('pointerdown', close); window.addEventListener('resize', resize);
    return () => { document.removeEventListener('keydown', close, true); document.removeEventListener('pointerdown', close); window.removeEventListener('resize', resize); };
  }, [peek, panel]);
  function show(entry, el) {
    clearTimeout(closeTimer.current);
    const rect = el.getBoundingClientRect();
    const width = Math.min(350, window.innerWidth - 24);
    setPeek({ entry, left: Math.min(rect.right + 6, window.innerWidth - width - 12), top: Math.max(12, Math.min(rect.top - 30, window.innerHeight - 205)), width });
  }
  function laterClose() { clearTimeout(closeTimer.current); closeTimer.current = setTimeout(() => setPeek(null), 180); }
  async function toggle(entry) {
    if (saving) return;
    if (!session || !Number.isSafeInteger(entry.sequence)) { useStore.getState().showNotice('消息保存后才能添加书签。'); return; }
    const remove = bookmarks.some(e => e.sequence === entry.sequence);
    mutation.current++; setSaving(true);
    try {
      // 先同步/迁移，失败时不让旧本地书签覆盖服务端状态。
      await syncBookmarks(session);
      if (remove) await api.deleteBookmark(session, entry.sequence); else await api.saveBookmark(session, entry.sequence);
      const values = normalizeBookmarks(await api.bookmarks(session));
      if (mounted.current) { setBookmarks(values); setSyncError(''); }
    } catch (e) { if (mounted.current) { setSyncError(e.message); useStore.getState().showNotice(`书签保存失败：${e.message}`); } }
    finally { mutation.current++; if (mounted.current) setSaving(false); }
  }
  async function jump(entry) {
    const token = ++operation.current;
    stopFollowing(); beforeJump(); setJumping(entry.id);
    try {
      let el = target(entry);
      // 历史窗口会淘汰旧消息；书签仍保留，复用现有分页逐页定位，不把所有正文装进 DOM。
      while (!el && Number.isFinite(entry.sequence)) {
        const state = useStore.getState();
        if (state.sessionId !== session || token !== operation.current) return;
        const first = state.thread.windowEvents[0]?.sequence, last = state.thread.windowEvents.at(-1)?.sequence;
        const older = entry.sequence < first;
        if ((!older && entry.sequence <= last) || !(older ? state.thread.history.hasMore : state.thread.history.hasNewer)) break;
        const before = `${first}:${last}`;
        await (older ? state.requestEarlierHistory({ force: true }) : state.requestLaterHistory({ force: true }));
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        if (useStore.getState().sessionId !== session || token !== operation.current) return;
        el = target(entry);
        const current = useStore.getState().thread;
        if (`${current.windowEvents[0]?.sequence}:${current.windowEvents.at(-1)?.sequence}` === before) break;
      }
      if (!el) { useStore.getState().showNotice('未能定位这条消息，可能已被回退或历史加载失败。书签保留，可重试或移除。'); return; }
      beforeJump(); stopFollowing();
      const scroll = scrollRef.current;
      scroll.scrollTo({ top: scroll.scrollTop + el.getBoundingClientRect().top - scroll.getBoundingClientRect().top - 24, behavior: 'instant' });
      setActive(entry.id); setPeek(null); setPanel(false);
      el.classList.add('is-navigation-target'); setTimeout(() => el.classList.remove('is-navigation-target'), 1400);
    } catch (error) {
      if (useStore.getState().sessionId === session && token === operation.current) useStore.getState().showNotice(`定位消息失败：${error.message}`);
    } finally { if (token === operation.current) setJumping(null); }
  }
  function move(event, index) {
    const buttons = [...root.current.querySelectorAll('.thread-nav-tick')];
    let next;
    if (event.key === 'ArrowDown') next = Math.min(buttons.length - 1, index + 1);
    if (event.key === 'ArrowUp') next = Math.max(0, index - 1);
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = buttons.length - 1;
    if (next != null) { event.preventDefault(); event.stopPropagation(); buttons[next]?.focus(); }
  }
  if (!entries.length) return null;
  const preview = peek && entries.find(e => e.id === peek.entry.id);
  return <nav className="thread-navigator" aria-label="对话导航" ref={root}>
    <button className="thread-nav-menu" aria-label="打开对话导航和书签" aria-expanded={panel} onClick={() => { setPanel(!panel); setPeek(null); }}><Bookmark filled={bookmarks.length > 0} /></button>
    <div className="thread-nav-track" onMouseLeave={laterClose}>
      {entries.map((entry, index) => <button key={entry.id} className={`thread-nav-tick${active === entry.id ? ' is-current' : ''}${saved.has(entry.id) ? ' is-saved' : ''}`} aria-label={`跳转：${entry.title}`} aria-current={active === entry.id ? 'location' : undefined} tabIndex={active === entry.id || (!active && index === 0) ? 0 : -1} onMouseEnter={e => show(entry, e.currentTarget)} onFocus={e => show(entry, e.currentTarget)} onBlur={e => { if (!popup.current?.contains(e.relatedTarget)) laterClose(); }} onKeyDown={e => move(e, index)} onClick={() => jump(entry)}><span /></button>)}
    </div>
    {panel && <section className="thread-nav-panel" aria-label="消息导航列表">
      <header><strong>对话导航</strong><button aria-label="关闭对话导航" onClick={() => setPanel(false)}>×</button></header>
      <div className="thread-nav-filters"><button aria-pressed={!onlySaved} onClick={() => setOnlySaved(false)}>消息</button><button aria-pressed={onlySaved} onClick={() => setOnlySaved(true)}>书签 {bookmarks.length}</button></div>
      <div className="thread-nav-list">{entries.filter(e => !onlySaved || saved.has(e.id)).map(entry => <div className="thread-nav-item" key={entry.id}><button onClick={() => jump(entry)} disabled={jumping != null}>{entry.title}<small>{jumping === entry.id ? '正在定位…' : entry.preview}</small></button><button disabled={saving} aria-label={saved.has(entry.id) ? '取消书签' : '添加书签'} aria-pressed={saved.has(entry.id)} onClick={() => toggle(entry)}><Bookmark filled={saved.has(entry.id)} /></button></div>)}{onlySaved && !bookmarks.length && <p>还没有书签。悬停刻度即可收藏消息。</p>}</div>
      <footer>{history.hasMore && <button disabled={history.loading} onClick={() => { stopFollowing(); beforeJump(); useStore.getState().requestEarlierHistory({ force: true }); }}>{history.loading ? '正在加载…' : history.error ? '重试加载更早消息' : '加载更早的消息'}</button>}<small>书签已同步到当前 Pilot 服务，可在连接同一服务的设备查看。</small>{syncError && <p role="alert">{syncError} <button disabled={saving} onClick={refreshBookmarks}>重试同步</button></p>}</footer>
    </section>}
    {preview && !panel && createPortal(<div className="thread-nav-preview" ref={popup} role="dialog" aria-label="消息预览" style={{ left: peek.left, top: peek.top, width: peek.width }} onMouseEnter={() => clearTimeout(closeTimer.current)} onMouseLeave={laterClose} onFocus={() => clearTimeout(closeTimer.current)} onBlur={e => { if (!e.currentTarget.contains(e.relatedTarget)) laterClose(); }}>
      <div><button className="thread-nav-preview-title" onClick={() => jump(preview)}>{preview.title}</button><button disabled={saving} className="thread-nav-save" aria-label={saved.has(preview.id) ? '取消书签' : '添加书签'} aria-pressed={saved.has(preview.id)} onClick={() => toggle(preview)}><Bookmark filled={saved.has(preview.id)} /></button></div>
      {preview.preview && <p>{preview.preview}</p>}<small>{jumping === preview.id ? '正在定位…' : '点击标题跳转'}{saved.has(preview.id) ? ' · 已收藏' : ''}</small>
    </div>, document.body)}
  </nav>;
}
