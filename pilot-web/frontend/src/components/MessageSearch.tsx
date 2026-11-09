import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Banner } from '@astryxdesign/core/Banner';
import { Button } from '@astryxdesign/core/Button';
import { CheckboxInput } from '@astryxdesign/core/CheckboxInput';
import { Dialog } from '@astryxdesign/core/Dialog';
import { TextInput } from '@astryxdesign/core/TextInput';
import type { MessageLibraryPage, MessageLibraryHit } from '../types/server.js';
import { errorInfo } from '../lib/errors.js';
import { restoreFocus } from '../lib/interaction.js';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { migrateLocalBookmarks, bookmarksChanged } from '../lib/bookmark-sync.js';
import { Icon } from './Icon.jsx';
import { navigationDialogKeys } from './NavigationMenu.jsx';
import './message-search.css';

const emptyPage: MessageLibraryPage = { items: [], hasMore: false, nextOffset: 0 };
const anchorKey = (hit: MessageLibraryHit) => `${hit.sessionId}:${hit.anchor}`;

export default function MessageSearch() {
  const open = useStore(state => state.ui.searchOpen);
  return open ? <MessageSearchDialog /> : null;
}

function MessageSearchDialog() {
  const revision = useRef(0);
  const alive = useRef(false);
  const resultsHeading = useRef<HTMLHeadingElement>(null);
  const nextPageButton = useRef<HTMLButtonElement>(null);
  const [session] = useState(() => useStore.getState().sessionId);
  const [query, setQuery] = useState('');
  const [scope, setScope] = useState<'all' | 'current'>('all');
  const [onlySaved, setOnlySaved] = useState(false);
  const [savedAnchors, setSavedAnchors] = useState(() => new Set<string>());
  const [page, setPage] = useState(emptyPage);
  const [searched, setSearched] = useState(false);
  const [loading, setLoading] = useState(false);
  const [action, setAction] = useState<'open' | 'bookmark' | null>(null);
  const [error, setError] = useState('');
  const [feedback, setFeedback] = useState('');
  const [syncing, setSyncing] = useState(true);
  const [migration, setMigration] = useState({ text: '', failed: false });
  const close = () => useStore.getState().setUi({ searchOpen: false });
  const term = query.trim();
  const tooLong = term.length > 200;
  const busy = loading || Boolean(action);
  const canSearch = !busy && !syncing && !tooLong && (Boolean(term) || onlySaved);

  useLayoutEffect(() => {
    const previous = document.getElementById('nav-message-search');
    return () => {
      // 手机端跳转可能收起抽屉，原搜索入口随之隐藏。
      requestAnimationFrame(() => {
        if (document.activeElement === document.body) restoreFocus(previous);
      });
    };
  }, []);

  useEffect(() => {
    alive.current = true;
    void migrate();
    return () => { alive.current = false; revision.current++; };
  }, []);

  async function migrate() {
    setSyncing(true);
    setMigration({ text: '', failed: false });
    try {
      const data = await migrateLocalBookmarks();
      if (!alive.current) return;
      setMigration({
        text: data.failed.length ? `${data.failed.length} 个对话的旧书签同步失败，可重试。`
          : data.count ? `已同步 ${data.count} 个对话的旧书签。` : '本机没有待同步的旧书签。',
        failed: data.failed.length > 0,
      });
    } catch (failure) {
      if (alive.current) setMigration({ text: errorInfo(failure).message || '旧书签同步失败，可重试。', failed: true });
    } finally {
      if (alive.current) setSyncing(false);
    }
  }

  function changeFilters(update: () => void) {
    revision.current++;
    setLoading(false);
    setSearched(false);
    setPage(emptyPage);
    setError('');
    setFeedback('');
    update();
  }

  async function search(more = false) {
    if (!canSearch) return;
    const token = ++revision.current;
    setLoading(true);
    setSearched(true);
    setError('');
    setFeedback('');
    try {
      const data = await api.searchMessages({
        q: term,
        sessionId: scope === 'current' ? session || '' : '',
        saved: onlySaved,
        offset: more ? page.nextOffset : 0,
      });
      if (!data || !Array.isArray(data.items)) throw new Error('搜索返回无效结果，请重试。');
      if (!alive.current || token !== revision.current) return;
      const restore = more && !data.hasMore && document.activeElement === nextPageButton.current;
      setPage(current => ({ ...data, items: more ? [...current.items, ...data.items] : data.items }));
      if (restore) requestAnimationFrame(() => {
        if (alive.current && token === revision.current && document.activeElement === document.body) {
          resultsHeading.current?.focus({ preventScroll: true });
        }
      });
    } catch (failure) {
      if (alive.current && token === revision.current) setError(errorInfo(failure).message || '搜索失败，请重试。');
    } finally {
      if (alive.current && token === revision.current) setLoading(false);
    }
  }

  async function runAction(kind: 'open' | 'bookmark', task: () => Promise<void>) {
    if (busy) return;
    setAction(kind);
    setError('');
    setFeedback('');
    try {
      await task();
    } catch (failure) {
      if (alive.current) setError(errorInfo(failure).message || '操作失败，请重试。');
    } finally {
      if (alive.current) setAction(null);
    }
  }

  function openResult(hit: MessageLibraryHit) {
    void runAction('open', async () => {
      const activated = await useStore.getState().activate(hit.sessionId);
      if (!alive.current) return;
      if (activated === false) throw new Error('无法打开该对话，请刷新搜索结果后重试。');
      if (useStore.getState().sessionId !== hit.sessionId) throw new Error('对话已变化，请重新选择。');
      useStore.getState().setUi({ navigationTarget: { sessionId: hit.sessionId, sequence: hit.anchor }, searchOpen: false });
    });
  }

  function bookmark(hit: MessageLibraryHit) {
    void runAction('bookmark', async () => {
      const result = await api.saveBookmark(hit.sessionId, hit.anchor);
      if (!result) throw new Error('书签保存失败，请重试。');
      bookmarksChanged(hit.sessionId);
      if (!alive.current) return;
      setSavedAnchors(current => new Set(current).add(anchorKey(hit)));
      setFeedback('已收藏所在轮次。');
    });
  }

  const status = loading ? '正在搜索…'
    : action === 'open' ? '正在打开对话…'
    : action === 'bookmark' ? '正在收藏轮次…'
    : feedback || (page.items.length ? `已显示 ${page.items.length} 条消息` : '');

  return (
    <Dialog isOpen onOpenChange={open => { if (!open) close(); }} width={640}
      maxHeight="calc(100dvh - 32px)" padding={0} purpose="form"
      className="search-dialog pilot-message-search" aria-labelledby="message-search-title"
      onKeyDown={event => navigationDialogKeys(event, close)}>
      <header className="message-search-head">
        <h2 id="message-search-title">搜索消息与书签</h2>
        <Button label="关闭消息搜索" icon={<Icon name="close" />} isIconOnly variant="ghost" onClick={close} />
      </header>
      <div className="message-search-scroll">
        <form className="message-search-form" onSubmit={event => { event.preventDefault(); void search(); }}
          onKeyDown={event => {
            if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229)) event.preventDefault();
          }}>
          <TextInput id="message-search-query" label="搜索消息内容" type="search" value={query} size="lg" width="100%"
            hasAutoFocus hasSpellCheck={false} autoComplete="off" enterKeyHint="search" startIcon={<Icon name="search" />}
            placeholder="输入关键词、中文或代码片段" isReadOnly={Boolean(action)} controlClassName="message-search-field"
            status={tooLong ? { type: 'error' } : undefined} aria-describedby={tooLong ? 'message-search-limit' : undefined}
            onChange={value => changeFilters(() => setQuery(value))} />
          {tooLong && <p className="message-search-limit" id="message-search-limit" role="alert">关键词最多 200 个字符。</p>}
          <div className="message-search-filters">
            <label htmlFor="message-search-scope">范围</label>
            <select id="message-search-scope" value={scope} disabled={Boolean(action)}
              onChange={event => changeFilters(() => setScope(event.target.value === 'current' ? 'current' : 'all'))}>
              <option value="all">所有主对话</option>
              {session && <option value="current">当前对话</option>}
            </select>
            <CheckboxInput label="仅书签" value={onlySaved} size="sm" className="message-search-bookmarks-only" isReadOnly={Boolean(action)}
              onChange={value => changeFilters(() => setOnlySaved(value))} />
            <Button label="搜索" type="submit" variant="primary" isDisabled={!canSearch} />
          </div>
        </form>

        <section className="message-search-results" aria-labelledby="message-search-results-title">
          <div className="message-search-results-head">
            <h2 id="message-search-results-title" tabIndex={-1} ref={resultsHeading}>搜索结果</h2>
            <p role="status">{status}</p>
          </div>
          {error && <Banner status="error" role="alert" title={error} />}
          {!loading && !error && !page.items.length && <p className="message-search-empty">
            {searched ? '没有匹配的消息，请更换关键词或搜索范围。' : '输入关键词，或勾选「仅书签」查看收藏。'}
          </p>}
          {page.items.length > 0 && <ul className="message-search-list" aria-busy={loading}>
            {page.items.map(hit => {
              const saved = hit.bookmarked || savedAnchors.has(anchorKey(hit));
              return (
                <li className="message-search-row" key={`${hit.sessionId}:${hit.sequence}`}>
                  <button type="button" className="search-hit" aria-disabled={busy}
                    onClick={() => openResult(hit)}>
                    <strong>{hit.title || '未命名对话'}</strong>
                    <span className="message-search-excerpt">{hit.text}</span>
                    <span className="message-search-meta">{hit.role === 'assistant' ? '助手' : '你'} · 消息 #{hit.sequence}</span>
                  </button>
                  <Button label={saved ? '已收藏' : '收藏所在轮次'} variant="ghost" size="sm"
                    className="message-search-bookmark" aria-disabled={saved || busy}
                    icon={<BookmarkIcon saved={saved} />} onClick={() => { if (!saved) bookmark(hit); }} />
                </li>
              );
            })}
          </ul>}
          {page.hasMore && <Button label="加载更多结果" variant="secondary" aria-disabled={!canSearch}
            className="message-search-more" ref={nextPageButton} onClick={() => { void search(true); }} />}
        </section>
      </div>
      <footer className="message-search-sync">
        <p role={migration.failed ? 'alert' : 'status'}>{syncing ? '正在同步本机旧书签…' : migration.text}</p>
        <Button label="同步旧书签" variant="ghost" size="sm" isDisabled={syncing || busy}
          icon={<Icon name="refresh" />} onClick={() => { void migrate(); }} />
      </footer>
    </Dialog>
  );
}

function BookmarkIcon({ saved }: { saved: boolean }) {
  return <svg className="ui-icon" viewBox="0 0 16 16" fill={saved ? 'currentColor' : 'none'}
    stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 2.5h8v11l-4-3-4 3z" />
  </svg>;
}
