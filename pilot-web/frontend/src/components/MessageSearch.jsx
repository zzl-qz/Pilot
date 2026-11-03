import { restoreFocus } from '../lib/interaction.js';
import React, { useEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { api } from '../api.js';
import './schedules.css';
import { migrateLocalBookmarks, bookmarksChanged } from '../lib/bookmark-sync.js';
export default function MessageSearch() { const open = useStore(s => s.ui.searchOpen); return open ? <Dialog /> : null; }
function Dialog() {
  const ref = useRef(null), input = useRef(null), revision = useRef(0), alive = useRef(true);
  const session = useStore(s => s.sessionId);
  const [migration, setMigration] = useState(''), [syncing, setSyncing] = useState(false);
  const [query, setQuery] = useState(''), [scope, setScope] = useState('all'), [saved, setSaved] = useState(false);
  const [page, setPage] = useState({ items: [], hasMore: false, nextOffset: 0 }), [error, setError] = useState(''), [busy, setBusy] = useState(false), [searched, setSearched] = useState(false);
  const close = () => useStore.getState().setUi({ searchOpen: false });
  useEffect(() => { const previous = document.activeElement; alive.current = true; ref.current.showModal(); input.current.focus(); migrate(); return () => { alive.current = false; revision.current++; restoreFocus(previous); }; }, []);
  async function migrate() {
    setSyncing(true);
    try { const data = await migrateLocalBookmarks(); if (alive.current) setMigration(data.failed.length ? `${data.failed.length} 个对话的旧书签同步失败，备份仍在此设备，可重试。` : data.count ? `已迁移 ${data.count} 个对话的旧书签。` : '旧书签已同步。'); }
    catch (e) { if (alive.current) setMigration(e.message); }
    finally { if (alive.current) setSyncing(false); }
  }
  async function search(more = false) {
    const token = ++revision.current; setBusy(true); setError(''); setSearched(true);
    try { const data = await api.searchMessages({ q: query, sessionId: scope === 'current' ? session : '', saved, offset: more ? page.nextOffset : 0 });
      if (alive.current && token === revision.current) setPage(old => ({ ...data, items: more ? [...old.items, ...data.items] : data.items }));
    } catch (e) { if (alive.current && token === revision.current) setError(e.message); }
    finally { if (alive.current && token === revision.current) setBusy(false); }
  }
  function change(fn) { revision.current++; setBusy(false); setSearched(false); setPage({ items: [], hasMore: false, nextOffset: 0 }); fn(); }
  async function open(hit) {
    setBusy(true); setError('');
    try { const activated = await useStore.getState().activate(hit.sessionId); if (activated === false) throw new Error('无法打开该对话，请刷新搜索结果后重试。'); if (useStore.getState().sessionId !== hit.sessionId) throw new Error('对话已变化，请重新选择');
      useStore.getState().setUi({ navigationTarget: { sessionId: hit.sessionId, sequence: hit.anchor }, searchOpen: false });
    } catch (e) { if (alive.current) setError(e.message); } finally { if (alive.current) setBusy(false); }
  }
  return <dialog className="schedule-dialog search-dialog" ref={ref} aria-labelledby="message-search-title" onCancel={e => { e.preventDefault(); close(); }} onKeyDown={e => e.stopPropagation()}>
    <header className="schedule-header"><div><span className="schedule-product">Pilot / 对话资料</span><h1 id="message-search-title">搜索消息与书签</h1></div><button className="icon-btn" aria-label="关闭消息搜索" onClick={close}>×</button></header>
    <p className="schedule-runtime">检索完整历史中的用户消息与助手正文；打开结果定位到对应轮次。书签在连接同一 Pilot 服务的设备间同步。</p>
    <form onSubmit={e => { e.preventDefault(); search(); }}><label>搜索内容<input ref={input} aria-label="搜索消息内容" value={query} maxLength={200} onChange={e => change(() => setQuery(e.target.value))} placeholder="输入关键词，支持中文与代码片段" /></label><div className="search-controls"><label>范围<select value={scope} onChange={e => change(() => setScope(e.target.value))}><option value="all">所有主对话</option>{session && <option value="current">当前对话</option>}</select></label><label className="schedule-checkbox"><input type="checkbox" checked={saved} onChange={e => change(() => setSaved(e.target.checked))} />仅书签</label><button className="btn btn-primary" disabled={busy || syncing || (!query.trim() && !saved)}>搜索</button></div></form>
    <div className="search-sync"><small role="status">{syncing ? '正在同步本机旧书签…' : migration}</small><button className="btn" disabled={syncing} onClick={migrate}>同步旧书签</button></div>
    {error && <p className="schedule-error" role="alert">{error}</p>}{busy && <p role="status">正在处理…</p>}
    {searched && !busy && !error && !page.items.length && <p>没有匹配的消息，请更换关键词或搜索范围。</p>}
    <section className="search-results" aria-label="搜索结果">{page.items.map(hit => <article key={`${hit.sessionId}:${hit.sequence}`}><button className="search-hit" disabled={busy} onClick={() => open(hit)}><strong>{hit.title} · {hit.role === 'assistant' ? '助手' : '你'}</strong><p>{hit.text}</p><small>打开所在轮次 · 消息 #{hit.sequence}</small></button><button className="btn" disabled={busy} onClick={async () => { setBusy(true); try { await api.saveBookmark(hit.sessionId, hit.anchor); bookmarksChanged(hit.sessionId); useStore.getState().showNotice('已收藏所在轮次'); } catch (e) { setError(e.message); } finally { setBusy(false); } }}>收藏所在轮次</button></article>)}</section>
    {page.hasMore && <button className="btn" disabled={busy} onClick={() => search(true)}>加载更多结果</button>}
  </dialog>;
}
