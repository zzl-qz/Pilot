import Schedules from './Schedules.jsx';
import React, { useEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { Sidebar } from './Sidebar.jsx';
import { Thread } from './Thread.jsx';
import { Composer } from './Composer.jsx';
import { Inspector } from './Inspector.jsx';
import { TaskContract, Modals } from './Modals.jsx';
import { SelectionQuote } from './SideChat.jsx';
import { ErrorBoundary } from './ErrorBoundary.jsx';
import { resolveFolders, samePath } from '../lib/projects.js';
import { shortcut, runPhase, isShortcutModifier } from '../lib/interaction.js';
import { api } from '../api.js';
import { writeClipboard } from '../lib/clipboard.js';
import { dragWidth, readLayout, writeLayout } from '../lib/layout.js';

/** 应用外壳：左栏 + 主区（topbar / 通知 / 对话 / 输入） + 右侧工作区 + 弹层。 */
export default function App() {
  const init = useStore((state) => state.init);
  const notice = useStore((state) => state.ui.notice);
  const sidebarOpen = useStore((state) => state.ui.sidebarOpen);
  const themeMode = useStore((state) => state.ui.themeMode);
  const running = useStore((state) => state.running);
  const runStartedAt = useStore((state) => state.runStartedAt);
  const sessionTitle = useStore((state) => state.sessionTitle);
  const workspace = useStore((state) => state.workspace);
  const agents = useStore((state) => state.agents);
  const phase = useStore(runPhase);
  const reviewExpanded = useStore(state => state.ui.reviewExpanded && state.ui.inspectorOpen && state.ui.inspectorTab === 'changes');
  const inspectorOpen = useStore((state) => state.ui.inspectorOpen);
  const embedded = useStore((state) => state.embeddedSessionId);
  const sessionId = useStore((state) => state.sessionId);
  const eventCount = useStore((state) => state.timeline.length);
  const lastEventAt = useStore((state) => state.lastEventAt);
  const [, setTick] = useState(0);
  const [folderMenu, setFolderMenu] = useState(false);
  /** 两侧面板宽度：拖过分才写内联变量，没拖过就用 CSS 里的默认/自适应值。 */
  const [layout, setLayout] = useState(readLayout);
  const [resizing, setResizing] = useState(null);
  const appRef = useRef(null);
  // 75 秒没有任何事件就提示可能卡住（与旧版 STALL_SECONDS 一致）
  const idleSeconds = running && lastEventAt ? Math.round((Date.now() - lastEventAt) / 1000) : 0;
  const stalled = idleSeconds >= 75;

  useEffect(() => {
    if (!running) {
      return undefined;
    }
    const timer = setInterval(() => setTick((value) => value + 1), 1000);
    return () => clearInterval(timer);
  }, [running]);

  useEffect(() => {
    useStore.getState().init(embedded);
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => {
      const mode = useStore.getState().ui.themeMode;
      if (mode === 'system') {
        useStore.getState().setTheme('system');
      }
    };
    media.addEventListener?.('change', onChange);
    return () => media.removeEventListener?.('change', onChange);
  }, [embedded]);

  useEffect(() => {
    const narrow = window.matchMedia('(max-width: 960px)');
    const collapse = () => { if (narrow.matches) useStore.getState().setUi({ sidebarOpen: false }); };
    collapse(); narrow.addEventListener('change', collapse);
    return () => narrow.removeEventListener('change', collapse);
  }, []);

  /* Side chat 内嵌页：整页只留对话区，侧栏与审阅面板收起（旧版 body.side-chat-embedded）。 */
  useEffect(() => {
    if (!embedded) {
      return undefined;
    }
    document.body.classList.add('side-chat-embedded');
    document.documentElement.classList.add('side-chat-document');
    return () => {
      document.body.classList.remove('side-chat-embedded');
      document.documentElement.classList.remove('side-chat-document');
    };
  }, [embedded]);

  useEffect(() => {
    const onKey = (event) => {
      // 内嵌页不接管快捷键：Ctrl K / Ctrl N 之类在侧边对话里应当留给浏览器与输入框
      if (embedded || event.defaultPrevented) {
        return;
      }
      const mod = isShortcutModifier(event);
      if (mod && event.altKey && event.key.toLowerCase() === 's') {
        event.preventDefault();
        useStore.getState().toggleSideChatPane();
        return;
      }
      if (mod && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        useStore.getState().setUi({ paletteOpen: !useStore.getState().ui.paletteOpen });
        return;
      }
      if (mod && event.key.toLowerCase() === 'j') {
        event.preventDefault();
        useStore.getState().setUi({ inspectorOpen: !useStore.getState().ui.inspectorOpen });
        return;
      }
      // Ctrl N：旧版是「新任务（先选工作区）」，沿用
      if (mod && event.key.toLowerCase() === 'n') {
        event.preventDefault();
        useStore.getState().startNewSession();
        requestAnimationFrame(() => useStore.getState().composerApi?.focus());
        return;
      }
      if (mod && event.key.toLowerCase() === 'b') {
        event.preventDefault();
        useStore.getState().setUi({ sidebarOpen: !useStore.getState().ui.sidebarOpen });
        return;
      }
      if (mod && event.key === ',') {
        event.preventDefault();
        useStore.getState().setUi({ settingsOpen: !useStore.getState().ui.settingsOpen });
        return;
      }
      /*
       * Esc：旧版是「先关弹层，再取消运行，最后什么也不做」。
       * 弹层各自有 Esc 监听（Modal 组件），这里只负责「别在关弹层的同时把任务也取消了」，
       * 所以有弹层时直接让行，没有弹层且正在跑就中止 Agent。
       */
      if (event.key !== 'Escape' || event.isComposing) {
        return;
      }
      const state = useStore.getState();
      const overlayOpen = Boolean(state.ui.picker || state.ui.projectEditor || state.ui.settingsOpen
        || state.ui.paletteOpen || state.ui.checkpointDialog || state.ui.skillsInstall || state.ui.confirm);
      if (overlayOpen) {
        return;
      }
      if (state.ui.reviewExpanded && state.ui.inspectorOpen) {
        event.preventDefault(); state.setUi({ reviewExpanded: false }); return;
      }
      if (state.running) {
        event.preventDefault();
        state.cancelRun();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [embedded]);

  /*
   * 两侧分栏拖拽：按下时记下起点与当前宽度，之后的 pointermove 在 window 上跟手，
   * 松手才把宽度落盘（拖动中每帧写 localStorage 没有意义）。
   */
  useEffect(() => {
    if (!resizing) {
      return undefined;
    }
    document.body.classList.add('is-resizing');
    const onMove = (event) => {
      const width = appRef.current?.getBoundingClientRect().width || window.innerWidth;
      const key = resizing.kind === 'sidebar' ? 'sidebarW' : 'activityW';
      setLayout((current) => ({
        ...current,
        [key]: dragWidth(resizing.kind, resizing.startWidth, event.clientX - resizing.startX, width)
      }));
    };
    const onUp = () => setResizing(null);
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
    return () => {
      document.body.classList.remove('is-resizing');
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onUp);
    };
  }, [resizing]);

  useEffect(() => {
    if (!resizing && (layout.sidebarW || layout.activityW)) {
      writeLayout(layout);
    }
  }, [resizing, layout]);

  function startResize(kind, event) {
    if (event.button !== undefined && event.button !== 0) {
      return;
    }
    event.preventDefault();
    const app = appRef.current;
    const styles = app ? window.getComputedStyle(app) : null;
    const defaults = { sidebar: 264, activity: 336 };
    const startWidth = parseFloat(styles?.getPropertyValue(`--${kind === 'sidebar' ? 'sidebar-w' : 'activity-w'}`)) || defaults[kind];
    setResizing({ kind, startX: event.clientX, startWidth });
  }

  const appStyle = {};
  if (layout.sidebarW) {
    appStyle['--sidebar-w'] = `${layout.sidebarW}px`;
  }
  if (layout.activityW) {
    appStyle['--activity-w'] = `${layout.activityW}px`;
  }

  return (
    <div
      ref={appRef}
      className={`app${sidebarOpen ? ' sidebar-open' : ' sidebar-collapsed'}${inspectorOpen ? '' : ' inspector-collapsed'}${embedded ? ' side-chat-embedded-app' : ''}${resizing ? ' is-resizing' : ''}${reviewExpanded ? ' review-expanded' : ''}`}
      style={appStyle}
    >
      {/* 各区块单独兜底：任何一块渲染异常都不该把整页卸载成白屏 */}
      <ErrorBoundary name="侧栏"><Sidebar /></ErrorBoundary>
      {sidebarOpen && <button type="button" className="sidebar-scrim" aria-label="关闭导航" onClick={() => useStore.getState().setUi({ sidebarOpen: false })} />}
      <div
        id="gutter-left"
        className="gutter gutter-left"
        role="separator"
        aria-orientation="vertical"
        title="拖动调整会话栏宽度"
        onPointerDown={(event) => startResize('sidebar', event)}
      />
      <main className="main">
        <header className="topbar">
          {/* 内嵌的 side chat 里侧栏恒为隐藏，切换按钮点了没反应，直接不渲染 */}
          {!embedded && (
            <button
              type="button"
              className="icon-btn"
              id="sidebar-toggle"
              title={`切换侧栏 (${shortcut('B')})`}
              aria-label="切换侧栏"
              onClick={() => useStore.getState().setUi((ui) => ({ sidebarOpen: !ui.sidebarOpen }))}
            >
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 4.5h11M2.5 8h11M2.5 11.5h11" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg>
            </button>
          )}
          <div className="crumbs">
            <button
              type="button"
              id="workspace-name"
              className="crumb-root mono"
              title="切换该对话使用的文件夹"
              aria-haspopup="menu"
              aria-expanded={folderMenu}
              // 菜单自己监听 document 的 mousedown 会先把它关掉，这里挡住才能在按钮上切换
              onMouseDown={(event) => event.stopPropagation()}
              onClick={() => setFolderMenu((open) => !open)}
            >
              {workspace ? workspace.split(/[\\/]/).filter(Boolean).pop() : 'workspace'}
            </button>
            <span className="crumb-sep">/</span>
            <h1 id="crumb-title" className="crumb-title" title={sessionTitle}>{sessionTitle || '新任务'}</h1>
          </div>
          {folderMenu && <WorkspaceMenu onClose={() => setFolderMenu(false)} />}
          <div className="topbar-right">
            {Object.keys(agents || {}).length > 0 && (
              <button
                type="button"
                id="agents-chip"
                className="chip agents-chip"
                title="本对话用到了多智能体"
                onClick={() => useStore.getState().setUi({ agentsDockOpen: !useStore.getState().ui.agentsDockOpen })}
              >
                子智能体 · {Object.keys(agents).length}
              </button>
            )}
            {sessionId && (
              <button
                type="button"
                id="session-chip"
                className="chip session-chip mono"
                title={`点击复制会话 ID：${sessionId}`}
                onClick={async () => {
                  try {
                    if (!await writeClipboard(sessionId)) throw new Error('clipboard unavailable');
                    useStore.getState().showNotice(`已复制会话 ID：${sessionId}`);
                  } catch {
                    useStore.getState().showNotice('复制失败');
                  }
                }}
              >{sessionId.slice(0, 12)}</button>
            )}
            <span
              id="run-state"
              className={`run-state state-${running ? 'running' : 'idle'}${running && stalled ? ' is-stalled' : ''}`}
              title={stalled ? `已经 ${idleSeconds} 秒没有新事件，可能卡住了` : undefined}
            >
              {phase}
              {running && runStartedAt ? ` · ${Math.round((Date.now() - runStartedAt) / 1000)}s` : ''}
              {stalled ? ` · ${idleSeconds}s 无新事件` : ''}
            </span>
            <button type="button" id="settings-open" className="icon-btn" aria-label="设置" title={`设置 (${shortcut(',')})`} onClick={() => useStore.getState().setUi({ settingsOpen: true })}>
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 4.5h3.2M9.4 4.5h4.1M2.5 8h4.2M10.1 8h3.4M2.5 11.5h2.2M8 11.5h5.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /><circle cx="7.7" cy="4.5" r="1.5" stroke="currentColor" strokeWidth="1.4" fill="none" /><circle cx="8.4" cy="8" r="1.5" stroke="currentColor" strokeWidth="1.4" fill="none" /><circle cx="6.3" cy="11.5" r="1.5" stroke="currentColor" strokeWidth="1.4" fill="none" /></svg>
            </button>
            <button
              type="button"
              id="theme-toggle"
              className="icon-btn"
              aria-label="切换明暗主题"
              title="切换明暗主题"
              onClick={() => {
                const mode = themeMode === 'dark' ? 'light' : 'dark';
                useStore.getState().setTheme(mode);
              }}
            >
              <svg className="icon-sun" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="3.1" stroke="currentColor" strokeWidth="1.4" fill="none" /><path d="M8 1.6v1.3M8 13.1v1.3M1.6 8h1.3M13.1 8h1.3M3.5 3.5l.9.9M11.6 11.6l.9.9M12.5 3.5l-.9.9M4.4 11.6l-.9.9" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg>
              <svg className="icon-moon" viewBox="0 0 16 16" aria-hidden="true"><path d="M13 9.8A5.4 5.4 0 0 1 6.2 3a5.4 5.4 0 1 0 6.8 6.8Z" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinejoin="round" /></svg>
            </button>
            {/* 内嵌页再展开审阅面板会把窄 iframe 挤成两栏，这里不给入口 */}
            {!embedded && (
              <button
                type="button"
                id="toggle-activity"
                className="icon-btn review-toggle"
                aria-label="切换审阅面板"
                aria-pressed={inspectorOpen}
                title={`审阅面板 (${shortcut('J')})`}
                onClick={() => useStore.getState().setUi((ui) => ({ inspectorOpen: !ui.inspectorOpen }))}
              >
                <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 3.5h11v9h-11zM9.5 3.5v9" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinejoin="round" /></svg>
                <span>审阅</span>
              </button>
            )}
          </div>
        </header>
        {notice && (
          <div id="notice" className="notice" role="status">
            <span id="notice-text">{notice.text}</span>
            <button type="button" id="notice-close" aria-label="关闭通知" onClick={() => useStore.getState().hideNotice()}>×</button>
          </div>
        )}
        <div className="stage">
          <section id="thread-pane" className="thread-pane">
            <ErrorBoundary name="任务契约"><TaskContract /></ErrorBoundary>
            <ErrorBoundary name="消息流"><Thread /></ErrorBoundary>
            <ErrorBoundary name="输入框"><Composer /></ErrorBoundary>
          </section>
          <div
            id="gutter-right"
            className="gutter gutter-right"
            role="separator"
            aria-orientation="vertical"
            title="拖动调整审阅面板宽度"
            onPointerDown={(event) => startResize('activity', event)}
          />
          <ErrorBoundary name="右侧面板"><Inspector /></ErrorBoundary>
        </div>
      </main>
      <SelectionQuote />
      <ErrorBoundary name="定时任务"><Schedules /></ErrorBoundary>
      <ErrorBoundary name="弹层"><Modals /></ErrorBoundary>
    </div>
  );
}

/** 工作区菜单：该对话可用的文件夹 + worktree 的两个动作（旧版 folderMenu）。 */
function WorkspaceMenu({ onClose }) {
  const workspace = useStore((state) => state.workspace);
  const projects = useStore((state) => state.projects);
  const sessions = useStore((state) => state.sessions);
  const sessionId = useStore((state) => state.sessionId);
  const currentProjectId = useStore((state) => state.currentProjectId);
  const projectId = currentProjectId || sessions.find((item) => item.id === sessionId)?.projectId || null;
  const project = projects.find((item) => item.id === projectId) || null;
  const folders = resolveFolders(project, workspace);
  const [worktree, setWorktree] = useState(null);
  const [worktreeBranch, setWorktreeBranch] = useState('');

  // 点外面就收起：菜单是绝对定位的浮层，没有遮罩可以点
  useEffect(() => {
    const onDown = () => onClose();
    const onKey = (event) => {
      if (event.key === 'Escape') { event.preventDefault(); event.stopImmediatePropagation(); onClose(); document.getElementById('workspace-name')?.focus(); }
    };
    document.addEventListener('keydown', onKey, true);
    document.addEventListener('mousedown', onDown);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey, true); };
  }, [onClose]);

  // 当前目录是不是某个 worktree：是的话给出分支与清理入口，否则遗留目录只能手工 rm
  useEffect(() => {
    let alive = true;
    if (!workspace) {
      return undefined;
    }
    api.worktreeInfo(workspace)
      .then((info) => { if (alive) setWorktree(info?.worktree ? info : null); })
      .catch(() => { if (alive) setWorktree(null); });
    return () => { alive = false; };
  }, [workspace]);

  return (
    <div className="folder-menu" role="menu" onMouseDown={(event) => event.stopPropagation()}>
      <p className="model-title">对话使用的文件夹</p>
      {folders.map((folder) => (
        <button
          key={folder}
          type="button"
          role="menuitem"
          className={`perm-item${samePath(folder, workspace) ? ' is-on' : ''}`}
          title={folder}
          onClick={() => {
            onClose();
            useStore.getState().switchWorkspace(folder);
          }}
        >
          <span className="mono">{folder.split(/[\\/]/).filter(Boolean).pop()}</span>
          <small>{folder}</small>
        </button>
      ))}
      {!folders.length && <p className="panel-empty">还没有可用的文件夹。</p>}
      <div className="folder-menu-row">
        <input
          className="branch-input"
          placeholder="分支（留空=当前）"
          aria-label="worktree 分支"
          value={worktreeBranch}
          onChange={(event) => setWorktreeBranch(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              onClose();
              useStore.getState().openWorktree(worktreeBranch.trim());
            }
          }}
        />
        <button
          type="button"
          role="menuitem"
          className="perm-item"
          title="在独立检出里开新对话，改动不会污染当前目录"
          onClick={() => { onClose(); useStore.getState().openWorktree(worktreeBranch.trim()); }}
        >建 worktree</button>
      </div>
      <button
        type="button"
        role="menuitem"
        className="perm-item"
        disabled={!sessionId}
        onClick={() => { onClose(); useStore.getState().handoffWorktree(); }}
      >把 worktree 改动交回本地</button>
      {worktree && (
        <>
          <p className="model-title" title={worktree.path}>当前为 worktree · {worktree.branch} @ {(worktree.commit || '').slice(0, 7)}</p>
          <button
            type="button"
            role="menuitem"
            className="perm-item is-danger"
            onClick={() => {
              onClose();
              useStore.getState().askConfirm({
                title: '删除 worktree',
                message: `会删除 ${worktree.path} 这个检出目录（分支 ${worktree.branch} 保留）。未交回的改动会丢失，确定吗？`,
                confirmText: '删除',
                danger: true,
                onConfirm: async () => {
                  try {
                    await api.worktreeRemove(worktree.path);
                    useStore.getState().showNotice('已删除 worktree');
                    if (worktree.base) {
                      await useStore.getState().switchWorkspace(worktree.base);
                    }
                  } catch (error) {
                    useStore.getState().showNotice('删除 worktree 失败：' + error.message);
                  }
                }
              });
            }}
          >删除这个 worktree</button>
        </>
      )}
    </div>
  );
}
