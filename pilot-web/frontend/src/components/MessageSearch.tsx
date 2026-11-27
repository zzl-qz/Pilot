import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
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
import './message-search.css';

const emptyPage: MessageLibraryPage = { items: [], hasMore: false, nextOffset: 0 };
const anchorKey = (hit: MessageLibraryHit) => `${hit.sessionId}:${hit.anchor}`;
const SEARCH_DEBOUNCE_MS = 300;

function HighlightedExcerpt({ text, term }: { text: string; term: string }) {
  if (!term || !text) return <>{text}</>;
  const lowerText = text.toLowerCase();
  const lowerTerm = term.toLowerCase();
  const parts: Array<{ text: string; hit: boolean }> = [];
  let cursor = 0;
  let index = lowerText.indexOf(lowerTerm, cursor);
  while (index >= 0 && parts.length < 40) {
    if (index > cursor) parts.push({ text: text.slice(cursor, index), hit: false });
    parts.push({ text: text.slice(index, index + term.length), hit: true });
    cursor = index + term.length;
    index = lowerText.indexOf(lowerTerm, cursor);
  }
  if (cursor < text.length) parts.push({ text: text.slice(cursor), hit: false });
  return (
    <>
      {parts.map((part, idx) => (
        part.hit ? <mark key={idx} className="search-hit-mark">{part.text}</mark> : <span key={idx}>{part.text}</span>
      ))}
    </>
  );
}

export default function MessageSearch() {
  const open = useStore(state => state.ui.searchOpen);
  return open ? <MessageSearchDialog /> : null;
}

function MessageSearchDialog() {
  const revision = useRef(0);
  const alive = useRef(false);
  const composing = useRef(false);
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const resultsHeading = useRef<HTMLHeadingElement>(null);
  const nextPageButton = useRef<HTMLButtonElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const [session] = useState(() => useStore.getState().sessionId);
  const [projectId] = useState(() => useStore.getState().currentProjectId);
  const [query, setQuery] = useState('');
  const [scope, setScope] = useState<'all' | 'project' | 'current'>('all');
  const [role, setRole] = useState<'' | 'user' | 'assistant'>('');
  const [onlySaved, setOnlySaved] = useState(false);
  const [savedAnchors, setSavedAnchors] = useState(() => new Set<string>());
  const [page, setPage] = useState(emptyPage);
  const [appliedFilterKey, setAppliedFilterKey] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
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
  const filterKey = `${scope}|${role}|${onlySaved ? '1' : '0'}|${term}`;
  // 旧书签同步改为后台静默执行，不再阻塞搜索输入与提交
  const canSearch = !busy && !tooLong && (Boolean(term) || onlySaved);

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
    return () => {
      alive.current = false;
      revision.current++;
      clearTimeout(debounceTimer.current);
    };
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

  const executeSearch = useCallback(async (params: {
    term: string; scope: 'all' | 'project' | 'current'; role: '' | 'user' | 'assistant'; onlySaved: boolean; more?: boolean; offset?: number;
  }) => {
    clearTimeout(debounceTimer.current);
    if (params.term.length > 200 || (!params.term && !params.onlySaved)) return;
    const token = ++revision.current;
    const more = Boolean(params.more);
    setLoading(true);
    setSearched(true);
    setError('');
    setFeedback('');
    try {
      const data = await api.searchMessages({
        q: params.term,
        sessionId: params.scope === 'current' ? session || '' : '',
        ...(params.scope === 'project' && projectId ? { projectId } : {}),
        ...(params.role ? { role: params.role } : {}),
        saved: params.onlySaved,
        offset: more ? (params.offset ?? 0) : 0,
      });
      if (!data || !Array.isArray(data.items)) throw new Error('搜索返回无效结果，请重试。');
      if (!alive.current || token !== revision.current) return;
      const restore = more && !data.hasMore && document.activeElement === nextPageButton.current;
      setPage(current => ({ ...data, items: more ? [...current.items, ...data.items] : data.items }));
      setAppliedFilterKey(`${params.scope}|${params.role}|${params.onlySaved ? '1' : '0'}|${params.term}`);
      if (!more) setActiveIndex(0);
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
  }, [session, projectId]);

  function search(more = false) {
    if (!canSearch) return;
    void executeSearch({ term, scope, role, onlySaved, more, offset: page.nextOffset });
  }

  function changeFilters(update: () => void) {
    clearTimeout(debounceTimer.current);
    revision.current++;
    setLoading(false);
    setSearched(false);
    setPage(emptyPage);
    setAppliedFilterKey('');
    setActiveIndex(0);
    setError('');
    setFeedback('');
    update();
  }

  // 防抖即时检索：用户停顿 300ms 后自动查询（中文组字中不触发；手动回车/点击立即查询并取消防抖）
  useEffect(() => {
    clearTimeout(debounceTimer.current);
    if (composing.current || action || tooLong || (!term && !onlySaved) || appliedFilterKey === filterKey) {
      return undefined;
    }
    debounceTimer.current = setTimeout(() => {
      if (!alive.current || composing.current) return;
      void executeSearch({ term, scope, role, onlySaved, more: false, offset: 0 });
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(debounceTimer.current);
  }, [term, scope, role, onlySaved, tooLong, action, appliedFilterKey, filterKey, executeSearch]);

  useEffect(() => {
    const activeEl = listRef.current?.querySelector<HTMLElement>(`[data-hit-index="${activeIndex}"]`);
    activeEl?.scrollIntoView({ block: 'nearest' });
  }, [activeIndex]);

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
      useStore.getState().setUi({
        navigationTarget: { sessionId: hit.sessionId, sequence: hit.anchor, around: true },
        searchOpen: false
      });
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
    : feedback || (page.items.length ? `已找到 ${page.items.length}${page.hasMore ? '+' : ''} 条消息` : '');

  return (
    <Dialog isOpen onOpenChange={open => { if (!open) close(); }} width={660}
      maxHeight="calc(100dvh - 32px)" padding={0} purpose="form"
      className="search-dialog pilot-message-search" aria-labelledby="message-search-title"
      onKeyDown={event => {
        if (event.key === 'Tab') return;
        event.stopPropagation();
        if (event.key === 'Escape' && !event.nativeEvent.isComposing && event.nativeEvent.keyCode !== 229) {
          event.preventDefault();
          close();
          return;
        }
        if (!event.nativeEvent.isComposing && event.nativeEvent.keyCode !== 229 && page.items.length > 0) {
          if (event.key === 'ArrowDown') {
            event.preventDefault();
            setActiveIndex(prev => (prev + 1) % page.items.length);
            return;
          }
          if (event.key === 'ArrowUp') {
            event.preventDefault();
            setActiveIndex(prev => (prev - 1 + page.items.length) % page.items.length);
            return;
          }
        }
      }}>
      <header className="message-search-head">
        <div className="message-search-title-group">
          <h2 id="message-search-title">搜索消息与书签</h2>
          <span className="message-search-kbd-hint" aria-hidden="true">↑↓ 选择 · Enter 跳转 · Esc 关闭</span>
        </div>
        <Button label="关闭消息搜索" icon={<Icon name="close" />} isIconOnly variant="ghost" onClick={close} />
      </header>
      <div className="message-search-scroll">
        <form className="message-search-form" onSubmit={event => {
          event.preventDefault();
          if (appliedFilterKey === filterKey && page.items.length > 0 && !busy
              && document.activeElement?.id === 'message-search-query') {
            const picked = page.items[activeIndex] ?? page.items[0];
            if (picked) { openResult(picked); return; }
          }
          search();
        }}
          onCompositionStart={() => { composing.current = true; }}
          onCompositionEnd={() => { composing.current = false; }}
          onKeyDown={event => {
            if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229)) event.preventDefault();
          }}>
          <TextInput id="message-search-query" label="搜索消息内容" type="search" value={query} size="lg" width="100%"
            hasAutoFocus hasSpellCheck={false} autoComplete="off" enterKeyHint="search" startIcon={<Icon name="search" />}
            placeholder="输入关键词、中文或代码片段，即时检索历史记录" isReadOnly={Boolean(action)} controlClassName="message-search-field"
            status={tooLong ? { type: 'error' } : undefined} aria-describedby={tooLong ? 'message-search-limit' : undefined}
            onChange={value => changeFilters(() => setQuery(value))} />
          {tooLong && <p className="message-search-limit" id="message-search-limit" role="alert">关键词最多 200 个字符。</p>}
          <div className="message-search-filters">
            <label htmlFor="message-search-scope">范围</label>
            <select id="message-search-scope" value={scope} disabled={Boolean(action)}
              onChange={event => changeFilters(() => {
                const value = event.target.value;
                setScope(value === 'current' ? 'current' : value === 'project' ? 'project' : 'all');
              })}>
              <option value="all">所有主对话</option>
              {projectId && <option value="project">当前项目</option>}
              {session && <option value="current">当前对话</option>}
            </select>
            <div className="message-search-roles" role="group" aria-label="按发言角色筛选">
              {([['', '全部'], ['user', '提问'], ['assistant', '回复']] as const).map(([value, label]) => (
                <button
                  key={label}
                  type="button"
                  className={`message-search-role-btn${role === value ? ' is-active' : ''}`}
                  aria-pressed={role === value}
                  disabled={Boolean(action)}
                  onClick={() => changeFilters(() => setRole(value))}
                >
                  {label}
                </button>
              ))}
            </div>
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
          {page.items.length > 0 && <ul className="message-search-list" ref={listRef} aria-busy={loading}>
            {page.items.map((hit, index) => {
              const saved = hit.bookmarked || savedAnchors.has(anchorKey(hit));
              const isSelected = index === activeIndex;
              return (
                <li
                  className={`message-search-row${isSelected ? ' is-active' : ''}`}
                  key={`${hit.sessionId}:${hit.sequence}`}
                  data-hit-index={index}
                  onMouseEnter={() => setActiveIndex(index)}
                >
                  <button type="button" className="search-hit" aria-disabled={busy}
                    onClick={() => openResult(hit)}>
                    <span className="message-search-row-top">
                      <strong>{hit.title || '未命名对话'}</strong>
                      <span className={`message-search-role is-${hit.role === 'assistant' ? 'assistant' : 'user'}`}>
                        {hit.role === 'assistant' ? '助手' : '你'}
                      </span>
                      <span className="message-search-seq">#{hit.sequence}</span>
                    </span>
                    <span className="message-search-excerpt">
                      <HighlightedExcerpt text={hit.text} term={term} />
                    </span>
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
            className="message-search-more" ref={nextPageButton} onClick={() => { search(true); }} />}
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
