import type { ApiResults } from '../types/api.js';
import { errorInfo } from '../lib/errors.js';
type ResizeKind = 'sidebar' | 'activity';
import Notifications from './Notifications.jsx';
import React, { lazy, Suspense, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { Dialog } from '@astryxdesign/core/Dialog';
import { TextInput } from '@astryxdesign/core/TextInput';
import { useStore } from '../store.js';
import { NavigationRail } from './NavigationRail.jsx';
import { Icon } from './Icon.jsx';
import { Sidebar } from './Sidebar.jsx';
import { Thread } from './Thread.jsx';
import { Composer } from './Composer.jsx';
import { TaskContract, Modals } from './Modals.jsx';
import { SelectionQuote } from './SideChat.jsx';
import { ErrorBoundary } from './ErrorBoundary.jsx';
import { resolveFolders, samePath } from '../lib/projects.js';
import { shortcut, runPhase, runTone, isShortcutModifier } from '../lib/interaction.js';
import { api } from '../api.js';
import { watchWorkbench } from '../lib/workbench-sync.js';
import { dragWidth, readLayout, writeLayout } from '../lib/layout.js';
import { UiProvider } from './UiProvider.js';
import { useResultUnread } from '../lib/result-unread.js';
import './workbench-shell.css';

const Schedules = lazy(() => import('./Schedules.jsx'));
const RecurringPlans = lazy(() => import('./RecurringPlans.jsx'));
const MessageSearch = lazy(() => import('./MessageSearch.jsx'));
const ResultInbox = lazy(() => import('./ResultInbox.jsx'));
const Inspector = lazy(() => import('./Inspector.jsx').then(module => ({ default: module.Inspector })));

/** 应用外壳：左栏 + 主区（topbar / 通知 / 对话 / 输入） + 右侧工作区 + 弹层。 */
export default function App() {
  return <UiProvider><Workbench /></UiProvider>;
}

function Workbench() {
  const notice = useStore((state) => state.ui.notice);
  useEffect(() => window.pilotDesktop?.onMobileConnection?.(() => useStore.getState().setUi({ mobileConnectionOpen: true })), []);
  useEffect(() => {
    const bridge = window.pilotDesktop;
    return bridge?.onBrowserOpen(async url => {
      const state = useStore.getState();
      try {
        await bridge.ensureTab(state.sessionId || null);
        const result = await bridge.navigate(url);
        if (!result?.ok) throw new Error(result?.error || '链接暂时无法打开');
        state.setUi({ inspectorOpen: true, inspectorTab: 'browser' });
        state.showNotice('已在内置浏览器打开链接');
      } catch (error) { state.showNotice(errorInfo(error).message || '链接暂时无法打开'); }
    });
  }, []);
  useEffect(() => window.pilotDesktop?.onNavigationNotice?.(message => useStore.getState().showNotice(message)), []);
  const ready = useStore(state => state.ready);
  const refreshing = useStore(state => state.ui.refreshing);
  const syncError = useStore(state => state.ui.syncError);
  useEffect(() => window.pilotDesktop?.onWorkbenchCommand?.(command => {
    const state = useStore.getState();
    if (command === 'refresh') state.refreshWorkbench();
    if (command === 'reload') state.reloadWorkbench();
  }), []);
  useEffect(() => ready ? watchWorkbench({ refresh: options => useStore.getState().refreshWorkbench(options) }) : undefined, [ready]);
  const sidebarOpen = useStore((state) => state.ui.sidebarOpen);
  const agentsDockOpen = useStore((state) => state.ui.agentsDockOpen);
  const sessionTitle = useStore((state) => state.sessionTitle);
  const workspace = useStore((state) => state.workspace);
  const agents = useStore((state) => state.agents);
  const reviewExpanded = useStore(state => state.ui.reviewExpanded && state.ui.inspectorOpen && state.ui.inspectorTab === 'changes');
  const inspectorOpen = useStore((state) => state.ui.inspectorOpen);
  const inspectorTab = useStore((state) => state.ui.inspectorTab);
  const embedded = useStore((state) => state.embeddedSessionId);
  const [workspaceOpen, setWorkspaceOpen] = useState(false);
  const [narrowViewport, setNarrowViewport] = useState(() => window.matchMedia('(max-width: 960px)').matches);
  const [viewportWidth, setViewportWidth] = useState(() => window.innerWidth);
  /** 两侧面板宽度：拖过分才写内联变量，没拖过就用 CSS 里的默认/自适应值。 */
  const [layout, setLayout] = useState(readLayout);
  const [resizing, setResizing] = useState<{ kind: ResizeKind; startX: number; startWidth: number } | null>(null);
  const appRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    useStore.getState().init(embedded);
  }, [embedded]);

  useEffect(() => {
    const narrow = window.matchMedia('(max-width: 960px)');
    let focusFrame = 0;
    const collapse = () => {
      setNarrowViewport(narrow.matches);
      if (!narrow.matches) return;
      const focusedInNavigation = document.activeElement?.closest('.navigation-rail, #sidebar');
      useStore.getState().setUi({ sidebarOpen: false });
      if (focusedInNavigation) {
        cancelAnimationFrame(focusFrame);
        focusFrame = requestAnimationFrame(() => document.getElementById('sidebar-toggle')?.focus());
      }
    };
    collapse(); narrow.addEventListener('change', collapse);
    return () => {
      cancelAnimationFrame(focusFrame);
      narrow.removeEventListener('change', collapse);
    };
  }, []);

  useEffect(() => {
    const resize = () => setViewportWidth(window.innerWidth);
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, []);

  // 抽屉打开时键盘留在导航内；折叠后不把焦点遗留在隐藏的项目按钮上。
  useEffect(() => {
    if (embedded) return;
    const sidebar = document.getElementById('sidebar');
    const toggle = document.getElementById('sidebar-toggle');
    const rail = document.querySelector('.navigation-rail');
    const main = appRef.current?.querySelector('.main');
    const narrow = narrowViewport;
    if (!sidebarOpen) {
      if (sidebar?.contains(document.activeElement) || (narrow && rail?.contains(document.activeElement))) toggle?.focus();
      return;
    }
    if (!narrow) return;
    main?.setAttribute('inert', '');
    const frame = requestAnimationFrame(() => document.getElementById('new-session')?.focus());
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Tab' || event.defaultPrevented || document.querySelector('.modal, dialog[open]')) return;
      const items = [toggle, ...document.querySelectorAll<HTMLElement>('.navigation-rail :is(button, summary, [role="menuitem"]), #sidebar :is(button, input, summary, [tabindex="0"])')]
        .filter((el): el is HTMLElement => Boolean(el && el.tabIndex >= 0 && !el.matches(':disabled,[aria-disabled="true"]')
          && el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden'));
      const index = items.findIndex(item => item === document.activeElement);
      event.preventDefault();
      const next = index < 0 ? 0 : (index + (event.shiftKey ? -1 : 1) + items.length) % items.length;
      items[next]?.focus();
    };
    document.addEventListener('keydown', onKey);
    return () => { cancelAnimationFrame(frame); main?.removeAttribute('inert'); document.removeEventListener('keydown', onKey); };
  }, [sidebarOpen, embedded, narrowViewport]);

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
    const onKey = (event: KeyboardEvent) => {
      // 内嵌页不接管快捷键：Ctrl K / Ctrl N 之类在侧边对话里应当留给浏览器与输入框
      if (embedded || event.defaultPrevented) {
        return;
      }
      const mod = isShortcutModifier(event);
      const refreshKey = event.key === 'F5' || (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'r';
      if (!event.altKey && !event.getModifierState?.('AltGraph') && refreshKey) {
        event.preventDefault();
        if (event.isComposing || event.keyCode === 229) return;
        if (!event.repeat) {
          if (event.key === 'F5' && !event.shiftKey) useStore.getState().refreshWorkbench();
          else useStore.getState().reloadWorkbench();
        }
        return;
      }
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
        || state.ui.searchOpen || state.ui.resultsOpen || state.ui.schedulesOpen || state.ui.recurringOpen
        || state.ui.paletteOpen || state.ui.checkpointDialog || state.ui.skillsInstall || state.ui.confirm
        || state.ui.mobileConnectionOpen || state.ui.notificationsOpen || document.querySelector('dialog[open]'));
      if (overlayOpen) {
        return;
      }
      if (state.ui.sidebarOpen && window.matchMedia('(max-width: 960px)').matches) {
        event.preventDefault();
        state.setUi({ sidebarOpen: false });
        document.getElementById('sidebar-toggle')?.focus();
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
    const onMove = (event: PointerEvent) => {
      const appWidth = appRef.current?.getBoundingClientRect().width || window.innerWidth;
      const sidebarWidth = resizing.kind === 'activity' && sidebarOpen
        ? appRef.current?.querySelector('.sidebar')?.getBoundingClientRect().width || 0
        : 0;
      const width = appWidth - 52 - sidebarWidth;
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
  }, [resizing, sidebarOpen]);

  useEffect(() => {
    if (!resizing && (layout.sidebarW || layout.activityW)) {
      writeLayout(layout);
    }
  }, [resizing, layout]);

  function startResize(kind: ResizeKind, event: React.PointerEvent) {
    if (event.button !== undefined && event.button !== 0) {
      return;
    }
    event.preventDefault();
    const app = appRef.current;
    const styles = app ? window.getComputedStyle(app) : null;
    const defaults = { sidebar: 264, activity: 336 };
    const actualActivityWidth = app?.querySelector('.activity.inspector')?.getBoundingClientRect().width;
    const startWidth = kind === 'activity' && actualActivityWidth
      ? actualActivityWidth
      : parseFloat(styles?.getPropertyValue(`--${kind === 'sidebar' ? 'sidebar-w' : 'activity-w'}`) || '') || defaults[kind];
    setResizing({ kind, startX: event.clientX, startWidth });
  }

  const appStyle: React.CSSProperties & { '--sidebar-w'?: string; '--activity-w'?: string } = {};
  if (layout.sidebarW) {
    appStyle['--sidebar-w'] = `${layout.sidebarW}px`;
  }
  if (inspectorTab === 'preview' && inspectorOpen && !embedded) {
    const available = narrowViewport
      ? Math.max(280, viewportWidth - 52)
      : Math.min(760, Math.max(280, viewportWidth - 52 - (sidebarOpen ? 240 : 0) - 420));
    const preferred = narrowViewport ? available : Math.min(560, Math.max(420, viewportWidth * 0.34));
    appStyle['--activity-w'] = `${Math.max(Math.min(420, available), Math.min(available, layout.activityW || preferred))}px`;
  } else if (layout.activityW) {
    appStyle['--activity-w'] = `${layout.activityW}px`;
  }

  return (
    <div
      ref={appRef}
      className={`app${sidebarOpen ? ' sidebar-open' : ' sidebar-collapsed'}${inspectorOpen ? '' : ' inspector-collapsed'}${inspectorTab === 'preview' ? ' inspector-preview' : ''}${embedded ? ' side-chat-embedded-app' : ''}${resizing ? ' is-resizing' : ''}${reviewExpanded ? ' review-expanded' : ''}`}
      style={appStyle}
    >
      {/* 各区块单独兜底：任何一块渲染异常都不该把整页卸载成白屏 */}
      {!embedded && <NavigationRail key={narrowViewport && !sidebarOpen ? 'closed-drawer' : 'navigation'} />}
      <ErrorBoundary name="侧栏"><Sidebar /></ErrorBoundary>
      {sidebarOpen && <button type="button" className="sidebar-scrim" tabIndex={-1} aria-label="关闭导航" onClick={() => useStore.getState().setUi({ sidebarOpen: false })} />}
      <div
        id="gutter-left"
        className="gutter gutter-left"
        role="separator"
        aria-orientation="vertical"
        title="拖动调整会话栏宽度"
        onPointerDown={(event) => startResize('sidebar', event)}
      />
      <header className="topbar">
        {!embedded && <div className="shell-brand"><span className="logo" aria-hidden="true">P</span><strong>Pilot</strong></div> }
          {/* 内嵌的 side chat 里侧栏恒为隐藏，切换按钮点了没反应，直接不渲染 */}
          {!embedded && (
            <Button
              className="workbench-control"
              id="sidebar-toggle"
              label="切换侧栏"
              icon={<Icon name="sidebar" />}
              isIconOnly
              variant="ghost"
              tooltip={`切换侧栏 (${shortcut('B')})`}
              aria-expanded={sidebarOpen}
              aria-controls="sidebar"
              onClick={() => useStore.getState().setUi((ui) => ({ sidebarOpen: !ui.sidebarOpen }))}
            />
          )}
          <div className="crumbs">
            {!embedded && <Button
              id="workspace-name"
              className="crumb-root workbench-control"
              label={workspace ? `切换工作区：${workspace.split(/[\\/]/).filter(Boolean).pop()}` : '选择工作区'}
              icon={<Icon name="folder" />}
              isIconOnly={viewportWidth <= 600}
              variant="ghost"
              tooltip={workspace || '选择工作区'}
              aria-haspopup="dialog"
              aria-expanded={workspaceOpen}
              onClick={() => setWorkspaceOpen(true)}
            >
              {workspace ? workspace.split(/[\\/]/).filter(Boolean).pop() : '选择工作区'}
            </Button>}
            {!embedded && <span className="crumb-sep" aria-hidden="true">/</span>}
            <h1 id="crumb-title" className="crumb-title" title={sessionTitle}>{sessionTitle || '新任务'}</h1>
          </div>
          <div className="topbar-right">
            {Object.keys(agents || {}).length > 0 && (
              <Button
                id="agents-chip"
                className="workbench-agents"
                label={`子智能体 · ${Object.keys(agents).length}`}
                variant="ghost"
                tooltip="查看本对话的子智能体"
                aria-expanded={agentsDockOpen}
                aria-controls="agents-dock"
                onClick={() => useStore.getState().setUi({ agentsDockOpen: !useStore.getState().ui.agentsDockOpen })}
              />
            )}
            {!embedded && <Button id="refresh-workbench" className="workbench-control"
              label="刷新工作台数据" icon={<Icon name="refresh" />} isIconOnly variant="ghost"
              aria-busy={Boolean(refreshing)} isDisabled={Boolean(refreshing)}
              tooltip={syncError ? `同步失败：${syncError}，点击重试` : '刷新工作台数据，保留草稿与附件 (F5)'}
              onClick={() => useStore.getState().refreshWorkbench()} />}
            <RunStatus />
            {/* 内嵌页再展开审阅面板会把窄 iframe 挤成两栏，这里不给入口 */}
            {!embedded && (
              <Button
                id="toggle-activity"
                className="workbench-control workbench-review"
                label="切换审阅面板"
                icon={<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 3.5h11v9h-11zM9.5 3.5v9" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinejoin="round" /></svg>}
                isIconOnly={narrowViewport}
                variant={inspectorOpen ? 'secondary' : 'ghost'}
                aria-pressed={inspectorOpen}
                tooltip={`审阅面板 (${shortcut('J')})`}
                onClick={() => useStore.getState().setUi((ui) => ({ inspectorOpen: !ui.inspectorOpen }))}
              >
                审阅
              </Button>
            )}
          </div>
      </header>
      <main className="main">
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
          {inspectorOpen && <ErrorBoundary name="右侧面板" fallback={<InspectorPlaceholder failed />}>
            <Suspense fallback={<InspectorPlaceholder />}><Inspector /></Suspense>
          </ErrorBoundary>}
        </div>
      </main>
      <SelectionQuote />
      {workspaceOpen && <ErrorBoundary name="工作区"><WorkspaceDialog onClose={() => setWorkspaceOpen(false)} /></ErrorBoundary>}
      <WorkbenchDialogs embedded={Boolean(embedded)} />
      <ErrorBoundary name="结果通知"><Notifications /></ErrorBoundary>
      <ErrorBoundary name="弹层"><Modals /></ErrorBoundary>
    </div>
  );
}

function RunStatus() {
  const running = useStore(state => state.running);
  const runStartedAt = useStore(state => state.runStartedAt);
  const lastEventAt = useStore(state => state.lastEventAt);
  const phase = useStore(runPhase);
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => setTick(value => value + 1), 1000);
    return () => clearInterval(timer);
  }, [running]);

  const now = Date.now();
  const idleSeconds = running && lastEventAt ? Math.round((now - lastEventAt) / 1000) : 0;
  const stalled = idleSeconds >= 75;
  const tone = runTone(phase, running && stalled);
  const label = running && stalled && runTone(phase) === 'running' ? '暂无新进展' : phase;
  const detail = running && stalled ? `${label} · 已经 ${idleSeconds} 秒没有新事件，请检查执行情况`
    : phase === '就绪' ? '就绪，可以发送新消息' : phase;
  return <span id="run-state" className={`run-state state-${tone}`} data-tone={tone} title={detail}>
    <span className="run-state-label" role="status">{label}</span>
    {running && runStartedAt ? <span className="run-state-time" aria-hidden="true">{Math.round((now - runStartedAt) / 1000)}s</span> : null}
  </span>;
}

function WorkbenchDialogs({ embedded }: { embedded: boolean }) {
  const schedules = useStore(state => state.ui.schedulesOpen);
  const results = useStore(state => state.ui.resultsOpen);
  const recurring = useStore(state => state.ui.recurringOpen);
  const search = useStore(state => state.ui.searchOpen);
  useResultUnread(!embedded && !results);
  const setUi = useStore.getState().setUi;
  return <>
    {schedules && <DeferredDialog name="定时任务" close={() => setUi({ schedulesOpen: false, scheduleDraft: null, scheduleTarget: null, scheduleRepeat: false })}><Schedules /></DeferredDialog>}
    {results && <DeferredDialog name="任务收件箱" close={() => setUi({ resultsOpen: false, resultTarget: null })}><ResultInbox /></DeferredDialog>}
    {recurring && <DeferredDialog name="周期计划" close={() => setUi({ recurringOpen: false })}><RecurringPlans /></DeferredDialog>}
    {search && <DeferredDialog name="消息搜索" close={() => setUi({ searchOpen: false })}><MessageSearch /></DeferredDialog>}
  </>;
}

function DeferredDialog({ name, close, children }: { name: string; close: () => void; children: ReactNode }) {
  return <ErrorBoundary name={name} fallback={<DialogPlaceholder name={name} close={close} failed />}>
    <Suspense fallback={<DialogPlaceholder name={name} close={close} />}>{children}</Suspense>
  </ErrorBoundary>;
}

function DialogPlaceholder({ name, close, failed = false }: { name: string; close: () => void; failed?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  useLayoutEffect(() => {
    const dialog = ref.current;
    // 先关闭等待弹窗，让浏览器将焦点还给触发入口，再由正式弹窗捕获。
    return () => { if (dialog?.open) dialog.close(); };
  }, []);
  return <Dialog ref={ref} isOpen onOpenChange={open => { if (!open) close(); }} aria-label={`打开${name}`}
    className="workbench-deferred-dialog" onKeyDown={event => event.stopPropagation()}>
    <p role={failed ? 'alert' : 'status'}>{failed ? `${name}加载失败，请重新载入工作台。` : `正在打开${name}…`}</p>
    <div className="workbench-worktree-actions">
      <Button label="关闭" onClick={close} />
      {failed && <Button label="重新载入工作台" variant="primary" onClick={() => {
        close();
        void useStore.getState().reloadWorkbench();
      }} />}
    </div>
  </Dialog>;
}

function InspectorPlaceholder({ failed = false }: { failed?: boolean }) {
  return <aside className="activity inspector is-rail is-open workbench-inspector-placeholder" aria-label="工作区面板">
    <p role={failed ? 'alert' : 'status'}>{failed ? '审阅面板加载失败。' : '正在打开审阅面板…'}</p>
    <div className="workbench-worktree-actions">
      <Button label="关闭工作区面板" onClick={() => useStore.getState().setUi({ inspectorOpen: false, reviewExpanded: false })} />
      {failed && <Button label="重新载入工作台" variant="primary" onClick={() => { void useStore.getState().reloadWorkbench(); }} />}
    </div>
  </aside>;
}

function WorkspaceDialog({ onClose }: { onClose: () => void }) {
  const workspace = useStore((state) => state.workspace);
  const projects = useStore((state) => state.projects);
  const sessions = useStore((state) => state.sessions);
  const sessionId = useStore((state) => state.sessionId);
  const currentProjectId = useStore((state) => state.currentProjectId);
  const projectId = currentProjectId || sessions.find((item) => item.id === sessionId)?.projectId || null;
  const project = projects.find((item) => item.id === projectId) || null;
  const folders = resolveFolders(project, workspace);
  const [worktree, setWorktree] = useState<ApiResults['worktreeInfo'] | null>(null);
  const [worktreeBranch, setWorktreeBranch] = useState('');

  const [inspectionError, setInspectionError] = useState('');

  // 当前目录是不是某个 worktree：是的话给出分支与清理入口，否则遗留目录只能手工 rm
  useEffect(() => {
    let alive = true;
    setWorktree(null);
    setInspectionError('');
    if (!workspace) {
      return undefined;
    }
    api.worktreeInfo(workspace)
      .then((info) => { if (alive) setWorktree(info?.worktree ? info : null); })
      .catch(error => {
        if (!alive) return;
        setWorktree(null);
        setInspectionError(errorInfo(error).message || '无法读取工作区状态');
      });
    return () => { alive = false; };
  }, [workspace]);

  return (
    <Dialog isOpen onOpenChange={open => { if (!open) onClose(); }} purpose="form" width={560}
      maxHeight="calc(100dvh - 32px)" className="workbench-workspace-dialog"
      aria-labelledby="workbench-workspace-title" onKeyDown={event => event.stopPropagation()}>
      <header className="workbench-workspace-heading">
        <div>
          <h2 id="workbench-workspace-title">工作区</h2>
          <p>为当前对话选择文件夹，或创建独立检出。</p>
        </div>
        <Button label="关闭工作区" icon={<Icon name="close" />} isIconOnly variant="ghost" onClick={onClose} />
      </header>
      {folders.length > 0 && <div className="workbench-workspace-folders">
        {folders.map(folder => {
          const current = samePath(folder, workspace);
          return <Button key={folder} label={folder} tooltip={folder}
            variant={current ? 'secondary' : 'ghost'} className="workbench-workspace-folder"
            aria-current={current ? 'true' : undefined}
            onClick={() => {
              onClose();
              if (!current) void useStore.getState().switchWorkspace(folder);
            }} />;
        })}
      </div>}
      <Button label={folders.length ? '选择其他文件夹' : '选择文件夹'} icon={<Icon name="folder" />}
        onClick={() => { onClose(); useStore.getState().setUi({ picker: { title: '切换工作区' } }); }} />
      <section className="workbench-workspace-section" aria-labelledby="workbench-worktree-title">
        <h3 id="workbench-worktree-title">独立检出</h3>
        {inspectionError && <p role="alert">读取检出状态失败：{inspectionError}</p>}
        <form className="workbench-worktree-form" onSubmit={event => {
          event.preventDefault();
          if (!workspace) return;
          onClose();
          void useStore.getState().openWorktree(worktreeBranch.trim());
        }}>
          <TextInput label="分支" placeholder="留空使用当前分支" value={worktreeBranch}
            isDisabled={!workspace} onChange={setWorktreeBranch} onKeyDown={event => {
              if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) {
                event.preventDefault();
              }
            }} />
          <Button type="submit" label="创建 worktree" variant="primary" isDisabled={!workspace} />
        </form>
        {worktree && (
          <>
            <p title={worktree.path}>当前检出：{worktree.branch} · {(worktree.commit || '').slice(0, 7)}</p>
            <div className="workbench-worktree-actions">
              <Button label="把改动交回本地" isDisabled={!sessionId}
                onClick={() => { onClose(); void useStore.getState().handoffWorktree(); }} />
              <Button label="删除这个 worktree" variant="destructive"
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
                        if (worktree.base) await useStore.getState().switchWorkspace(worktree.base);
                      } catch (error) {
                        useStore.getState().showNotice('删除 worktree 失败：' + errorInfo(error).message);
                      }
                    }
                  });
                }}
              />
            </div>
          </>
        )}
      </section>
    </Dialog>
  );
}
