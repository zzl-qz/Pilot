import type { ThreadRow, UserRow } from '../types/thread.js';
interface NavEntry { id: string; key: string; sequence: number | null; title: string; preview: string }
type JumpEntry = Pick<NavEntry, 'id' | 'key' | 'sequence'>;
interface NavigatorProps { rows: ThreadRow[]; scrollRef: { current?: HTMLElement | null }; contentRef: { current?: HTMLElement | null }; stopFollowing: () => void; beforeJump: () => void }
import { errorInfo } from '../lib/errors.js';
import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { IconButton } from '@astryxdesign/core/IconButton';
import { Icon } from './Icon.jsx';
import { useStore } from '../store.js';
import { splitAttachmentMarkers } from '../lib/attachments.js';
import './thread-navigator.css';
import { api } from '../api.js';
import { bookmarksChanged, localBookmarks, normalizeBookmarks, syncBookmarks } from '../lib/bookmark-sync.js';
import { watchBookmarks, afterNavigationPaint } from '../lib/bookmark-watch.js';

const plain = (text: string) => String(text || '').replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').replace(/^[#>\s]+/gm, '').replace(/[*`_]/g, '').trim();
function entryFor(row: UserRow): NavEntry {
  const text = plain(splitAttachmentMarkers(row.text || '').body);
  const files = row.attachments?.map(a => a.name || a.filename || '附件').join('、');
  const lines = (text || files || '附件消息').split('\n').filter(Boolean);
  return { id: String(row.sequence ?? row.key), key: row.key, sequence: row.sequence, title: lines[0].slice(0, 100), preview: lines.slice(1).join('\n').slice(0, 350) };
}
function Bookmark({ filled = false }) {
  return <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M5 3.5h10v13l-5-3-5 3z" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" /></svg>;
}

/** 只导航用户消息；工具输出与流式片段不产生重复刻度。滚动跟随仍由原有库控制。 */
export default function ThreadNavigator({ rows, scrollRef, contentRef, stopFollowing, beforeJump }: NavigatorProps) {
  const session = useStore(s => s.sessionId);
  return <Navigation key={session || 'draft'} session={session} rows={rows} scrollRef={scrollRef} contentRef={contentRef} stopFollowing={stopFollowing} beforeJump={beforeJump} />;
}
function Navigation({ session, rows, scrollRef, contentRef, stopFollowing, beforeJump }: NavigatorProps & { session: string | null }) {
  const history = useStore(s => s.thread.history);
  const [bookmarks, setBookmarks] = useState(() => normalizeBookmarks(localBookmarks(session)));
  const [syncError, setSyncError] = useState('');
  const [saving, setSaving] = useState(false);
  const mutation = useRef(0), mounted = useRef(true);
  const navigationTarget = useStore(s => s.ui.navigationTarget);
  const [active, setActive] = useState<string | null | undefined>(null);
  const [peek, setPeek] = useState<{ entry: NavEntry; source: HTMLButtonElement; left: number; top: number; width: number } | null>(null);
  const [panel, setPanel] = useState(false);
  const [onlySaved, setOnlySaved] = useState(false);
  const [jumping, setJumping] = useState<string | null>(null);
  const root = useRef<HTMLElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const savedFilterRef = useRef<HTMLButtonElement>(null);
  const popup = useRef<HTMLDivElement>(null);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const operation = useRef(0);
  const entries = useMemo(() => {
    const map = new Map<string, NavEntry>(bookmarks.map(e => [String(e.sequence), e]));
    for (const row of rows) if (row.type === 'user') { const e = entryFor(row); map.set(e.id, e); }
    return [...map.values()].sort((a, b) => (a.sequence ?? Infinity) - (b.sequence ?? Infinity));
  }, [rows, bookmarks]);
  const identities = entries.map(e => `${e.id}:${e.key}`).join('|');
  const saved = new Set(bookmarks.map(e => String(e.sequence)));
  const target = useCallback((entry: JumpEntry) => [...(contentRef.current?.querySelectorAll<HTMLElement>('[data-nav-key]') || [])]
    .find(el => el.dataset.navKey === entry.key || (entry.sequence != null && el.dataset.sequence === String(entry.sequence))), [contentRef]);
  useEffect(() => {
    const scroll = scrollRef.current, content = contentRef.current;
    if (!scroll || !content) return;
    let frame: number | undefined;
    const measure = () => {
      frame = undefined;
      const top = scroll.getBoundingClientRect().top + Math.min(100, scroll.clientHeight / 4);
      const nodes = [...content.querySelectorAll<HTMLElement>('[data-nav-key]')];
      const byKey = new Map(nodes.map(el => [el.dataset.navKey, el]));
      const bySequence = new Map(nodes.map(el => [el.dataset.sequence, el]));
      const rendered = entries.map(entry => ({ entry, el: byKey.get(entry.key) || bySequence.get(String(entry.sequence)) })).filter(item => item.el);
      let selected;
      for (const { entry, el } of rendered) {
        if (!selected || el!.getBoundingClientRect().top <= top) selected = entry.id;
        else break;
      }
      if (scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 8) selected = rendered.at(-1)?.entry.id;
      setActive(selected);
    };
    const schedule = () => { if (frame == null) frame = requestAnimationFrame(measure); };
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(schedule) : null;
    observer?.observe(content); observer?.observe(scroll); scroll.addEventListener('scroll', schedule, { passive: true }); window.addEventListener('resize', schedule); schedule();
    return () => { if (frame != null) cancelAnimationFrame(frame); observer?.disconnect(); scroll.removeEventListener('scroll', schedule); window.removeEventListener('resize', schedule); };
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
    catch (e) { if (mounted.current && token === mutation.current) setSyncError(`书签同步失败：${errorInfo(e).message}。本地备份仍保留。`); }
  }
  useEffect(() => {
    mounted.current = true;
    const stop = session ? watchBookmarks({ session, refresh: refreshBookmarks, invalidate: () => { mutation.current++; } }) : () => {};
    return () => { mounted.current = false; stop(); };
  }, [session]);
  useEffect(() => {
    if (!navigationTarget || navigationTarget.sessionId !== session || !Number.isFinite(navigationTarget.sequence)) return;
    const entry = { id: String(navigationTarget.sequence), key: '', sequence: navigationTarget.sequence };
    jump(entry); useStore.getState().setUi({ navigationTarget: null });
  }, [navigationTarget]);
  function closePanel(restoreFocus = false) {
    clearTimeout(closeTimer.current);
    if (restoreFocus && panel) triggerRef.current?.focus({ preventScroll: true });
    if (restoreFocus && peek && popup.current?.contains(document.activeElement)) peek.source.focus({ preventScroll: true });
    setPeek(null);
    setPanel(false);
    operation.current += 1;
    setJumping(null);
  }

  useLayoutEffect(() => {
    if (panel) headingRef.current?.focus({ preventScroll: true });
  }, [panel]);

  useEffect(() => {
    if (!peek && !panel) return;
    const close = (event: KeyboardEvent | PointerEvent) => {
      if (event instanceof KeyboardEvent) {
        if (event.key !== 'Escape' || event.isComposing || event.keyCode === 229 || event.defaultPrevented) return;
        if (document.querySelector('dialog[open], [aria-modal="true"]')?.contains(document.activeElement)) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        closePanel(true);
      } else if (!(event.target instanceof Node) || (!root.current?.contains(event.target) && !popup.current?.contains(event.target))) {
        closePanel();
      }
    };
    const resize = () => setPeek(null);
    document.addEventListener('keydown', close, true); document.addEventListener('pointerdown', close); window.addEventListener('resize', resize);
    return () => { document.removeEventListener('keydown', close, true); document.removeEventListener('pointerdown', close); window.removeEventListener('resize', resize); };
  }, [peek, panel]);
  function show(entry: NavEntry, el: HTMLButtonElement) {
    clearTimeout(closeTimer.current);
    const rect = el.getBoundingClientRect();
    const width = Math.min(350, window.innerWidth - 24);
    setPeek({ entry, source: el, left: Math.min(rect.right + 6, window.innerWidth - width - 12), top: Math.max(12, Math.min(rect.top - 30, window.innerHeight - 205)), width });
  }
  function laterClose() {
    clearTimeout(closeTimer.current);
    closeTimer.current = setTimeout(() => {
      if (!popup.current?.contains(document.activeElement)) setPeek(null);
    }, 180);
  }
  async function toggle(entry: NavEntry) {
    if (saving) return;
    if (!session || entry.sequence == null || !Number.isSafeInteger(entry.sequence)) { useStore.getState().showNotice('消息保存后才能添加书签。'); return; }
    const remove = bookmarks.some(e => e.sequence === entry.sequence);
    mutation.current++; setSaving(true);
    let written = false;
    try {
      // 先同步/迁移，失败时不让旧本地书签覆盖服务端状态。
      await syncBookmarks(session);
      if (remove) await api.deleteBookmark(session, entry.sequence); else await api.saveBookmark(session, entry.sequence);
      written = true;
      const token = mutation.current;
      const values = normalizeBookmarks(await api.bookmarks(session));
      if (mounted.current && token === mutation.current) {
        if (remove && onlySaved && document.activeElement?.closest('.thread-nav-item')) {
          savedFilterRef.current?.focus({ preventScroll: true });
        }
        setBookmarks(values);
        setSyncError('');
      }
    } catch (e) { if (mounted.current) { setSyncError(errorInfo(e).message || '书签保存失败'); useStore.getState().showNotice(`书签保存失败：${errorInfo(e).message}`); } }
    finally {
      mutation.current++;
      if (mounted.current) setSaving(false);
      if (written && !bookmarksChanged(session) && mounted.current) setSyncError('书签已保存。跨窗口即时通知不可用，切回窗口或稍后会自动刷新。');
    }
  }
  async function jump(entry: JumpEntry, focusTarget = false) {
    const token = ++operation.current;
    stopFollowing(); beforeJump(); setJumping(entry.id);
    try {
      let el = target(entry);
      // 历史窗口会淘汰旧消息；书签仍保留，复用现有分页逐页定位，不把所有正文装进 DOM。
      while (!el && typeof entry.sequence === 'number' && Number.isFinite(entry.sequence)) {
        const state = useStore.getState();
        if (state.sessionId !== session || token !== operation.current) return;
        const first = state.thread.windowEvents[0]?.sequence, last = state.thread.windowEvents.at(-1)?.sequence;
        if (first == null || last == null) break;
        const older = entry.sequence < first;
        if ((!older && entry.sequence <= last) || !(older ? state.thread.history.hasMore : state.thread.history.hasNewer)) break;
        const before = `${first}:${last}`;
        await (older ? state.requestEarlierHistory({ force: true }) : state.requestLaterHistory({ force: true }));
        await afterNavigationPaint();
        if (useStore.getState().sessionId !== session || token !== operation.current) return;
        el = target(entry);
        const current = useStore.getState().thread;
        if (`${current.windowEvents[0]?.sequence}:${current.windowEvents.at(-1)?.sequence}` === before) break;
      }
      if (!el) { useStore.getState().showNotice('未能定位这条消息，可能已被回退或历史加载失败。书签保留，可重试或移除。'); return; }
      beforeJump(); stopFollowing();
      const scroll = scrollRef.current;
      if (!scroll) return;
      scroll.scrollTop += el.getBoundingClientRect().top - scroll.getBoundingClientRect().top - 24;
      if (focusTarget) el.focus({ preventScroll: true });
      setActive(entry.id); setPeek(null); setPanel(false);
      const located = el; located.classList.add('is-navigation-target'); setTimeout(() => located.classList.remove('is-navigation-target'), 1400);
    } catch (error) {
      if (useStore.getState().sessionId === session && token === operation.current) useStore.getState().showNotice(`定位消息失败：${errorInfo(error).message}`);
    } finally { if (token === operation.current) setJumping(null); }
  }
  function move(event: React.KeyboardEvent, index: number) {
    const buttons = [...(root.current?.querySelectorAll<HTMLElement>('.thread-nav-tick') || [])];
    let next;
    if (event.key === 'ArrowDown') next = Math.min(buttons.length - 1, index + 1);
    if (event.key === 'ArrowUp') next = Math.max(0, index - 1);
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = buttons.length - 1;
    if ((event.key === 'ArrowRight' || (event.key === 'Tab' && !event.shiftKey)) && peek?.source === event.currentTarget && popup.current) {
      event.preventDefault();
      event.stopPropagation();
      clearTimeout(closeTimer.current);
      popup.current.querySelector('button')?.focus();
      return;
    }
    if (next != null) { event.preventDefault(); event.stopPropagation(); buttons[next]?.focus(); }
  }
  if (!entries.length) return null;
  const preview = peek && entries.find(e => e.id === peek.entry.id);
  const tabStop = entries.some(entry => entry.id === active) ? active : entries[0].id;
  return <>
    <nav className="thread-navigator" aria-label="对话导航" ref={root}>
      <IconButton ref={triggerRef} className="thread-nav-menu" size="sm" variant="ghost"
        label="打开对话导航和书签" icon={<Bookmark filled={bookmarks.length > 0} />}
        aria-expanded={panel} aria-controls={panel ? 'thread-nav-panel' : undefined}
        onClick={() => {
          if (panel) closePanel(true);
          else { clearTimeout(closeTimer.current); setPanel(true); setPeek(null); }
        }} />
      <div className="thread-nav-track" onMouseLeave={laterClose}>
        {entries.map((entry, index) => (
          <button key={entry.id} type="button"
            className={'thread-nav-tick' + (active === entry.id ? ' is-current' : '') + (saved.has(entry.id) ? ' is-saved' : '')}
            aria-label={'跳转：' + entry.title} aria-current={active === entry.id ? 'location' : undefined}
            tabIndex={tabStop === entry.id ? 0 : -1}
            onMouseEnter={event => { if (!panel) show(entry, event.currentTarget); }}
            onFocus={event => { if (!panel) show(entry, event.currentTarget); }}
            onBlur={event => { if (!(event.relatedTarget instanceof Node && popup.current?.contains(event.relatedTarget))) laterClose(); }}
            onKeyDown={event => move(event, index)} onClick={() => jump(entry, true)}><span />
          </button>
        ))}
      </div>
      {panel && <section id="thread-nav-panel" className="thread-nav-panel" aria-labelledby="thread-nav-heading">
        <header>
          <h2 id="thread-nav-heading" tabIndex={-1} ref={headingRef}>对话导航</h2>
          <IconButton label="关闭对话导航" icon={<Icon name="close" />} size="sm" variant="ghost" onClick={() => closePanel(true)} />
        </header>
        <div className="thread-nav-filters">
          <Button size="sm" variant={onlySaved ? 'ghost' : 'secondary'} label="消息" aria-pressed={!onlySaved} onClick={() => setOnlySaved(false)} />
          <Button ref={savedFilterRef} size="sm" variant={onlySaved ? 'secondary' : 'ghost'}
            label={'书签 ' + bookmarks.length} aria-pressed={onlySaved} onClick={() => setOnlySaved(true)} />
        </div>
        <div className="thread-nav-list">
          {entries.filter(entry => !onlySaved || saved.has(entry.id)).map(entry => (
            <div className="thread-nav-item" key={entry.id}>
              <button className="thread-nav-jump" type="button" onClick={() => jump(entry, true)} disabled={jumping != null}>
                {entry.title}<small>{jumping === entry.id ? '正在定位…' : entry.preview}</small>
              </button>
              <IconButton size="sm" variant="ghost" isDisabled={saving} label={saved.has(entry.id) ? '取消书签' : '添加书签'}
                aria-pressed={saved.has(entry.id)} icon={<Bookmark filled={saved.has(entry.id)} />} onClick={() => toggle(entry)} />
            </div>
          ))}
          {onlySaved && !bookmarks.length && <p>还没有书签。在消息列表中可为任意已保存的消息添加书签。</p>}
        </div>
        <footer>
          {history.hasMore && <Button className="thread-nav-retry" size="sm" variant="ghost" isDisabled={history.loading}
            label={history.loading ? '正在加载…' : history.error ? '重试加载更早消息' : '加载更早的消息'}
            onClick={() => { stopFollowing(); beforeJump(); void useStore.getState().requestEarlierHistory({ force: true }); }} />}
          <small>连接到同一个 Pilot 服务的设备共享书签。</small>
          {syncError && <div className="thread-nav-error"><p role="alert">{syncError}</p>
            <Button size="sm" variant="ghost" isDisabled={saving} label="重试同步" onClick={refreshBookmarks} />
          </div>}
        </footer>
      </section>}
    </nav>
    {preview && peek && !panel && (
      <div className="thread-nav-preview" ref={popup} role="dialog" aria-label="消息预览"
        style={{ left: peek.left, top: peek.top, width: peek.width }}
        onMouseEnter={() => clearTimeout(closeTimer.current)} onMouseLeave={laterClose}
        onFocus={() => clearTimeout(closeTimer.current)}
        onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) laterClose(); }}
        onKeyDown={event => {
          if (event.key !== 'ArrowLeft' && !(event.key === 'Tab' && event.shiftKey && event.target === popup.current?.querySelector('button'))) return;
          event.preventDefault();
          peek.source.focus({ preventScroll: true });
        }}>
        <div>
          <button type="button" className="thread-nav-preview-title" onClick={() => jump(preview, true)}>{preview.title}</button>
          <IconButton size="sm" variant="ghost" className="thread-nav-save" isDisabled={saving}
            label={saved.has(preview.id) ? '取消书签' : '添加书签'} aria-pressed={saved.has(preview.id)}
            icon={<Bookmark filled={saved.has(preview.id)} />} onClick={() => toggle(preview)} />
        </div>
        {preview.preview && <p>{preview.preview}</p>}
        <small>{jumping === preview.id ? '正在定位…' : '选择标题跳转'}{saved.has(preview.id) ? ' · 已收藏' : ''}</small>
      </div>
    )}
  </>;
}
