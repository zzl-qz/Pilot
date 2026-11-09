import { useEffect, useId, useMemo, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { TextInput } from '@astryxdesign/core/TextInput';
import type { SessionSummary } from '../types/core.js';
import { useStore } from '../store.js';
import { WorkspaceTree } from './WorkspaceTree.jsx';
import { groupSessionsByProject } from '../lib/projects.js';
import { Icon } from './Icon.jsx';
import { NavigationMenu, NavigationMoreIcon } from './NavigationMenu.jsx';
import { NewTaskButton, NewTaskIcon } from './NavigationRail.jsx';
import { SessionMenu, type SessionMenuAnchor } from './SessionMenu.jsx';
import { sideChatsFor } from '../lib/side-chat.js';
import './navigation.css';

type MenuHandler = (id: string, anchor: SessionMenuAnchor, toggle?: boolean) => void;
type ProjectSessions = ReturnType<typeof groupSessionsByProject>[number];

const statusLabels: Record<string, string> = {
  running: '执行中',
  waiting_approval: '等待批准',
  paused: '已暂停',
  interrupted: '已中断',
  blocked: '已阻塞',
  budget_limited: '已达预算上限',
  failed: '执行失败',
  error: '执行失败',
};

export function Sidebar() {
  const sessions = useStore(state => state.sessions);
  const projects = useStore(state => state.projects);
  const sessionId = useStore(state => state.sessionId);
  const workspace = useStore(state => state.workspace);
  const connection = useStore(state => state.ui.connection);
  const sidebarOpen = useStore(state => state.ui.sidebarOpen);
  const sideChats = useStore(state => state.sideChats);
  const [query, setQuery] = useState('');
  const [menu, setMenu] = useState<(SessionMenuAnchor & { id: string }) | null>(null);
  const [collapsed, setCollapsed] = useState(() => new Set<string>());
  const [searchCollapsed, setSearchCollapsed] = useState(() => new Set<string>());
  const term = query.trim().toLowerCase();
  const menuSession = menu && sessions.find(session => session.id === menu.id);
  useEffect(() => { if (!sidebarOpen) setMenu(null); }, [sidebarOpen]);

  // 侧边对话仅属于当前任务；旧版落在会话列表中的侧边对话也从主列表排除。
  const { items: sideItems, activeId: sideActiveId } = sideChatsFor(sideChats, { sessionId, workspace });
  const sideIds = useMemo(() => new Set((sideChats?.items || []).map(item => item.id)), [sideChats]);
  const groups = useMemo(() => {
    const visible = sessions.filter(session => !sideIds.has(session.id));
    if (!term) return groupSessionsByProject(projects, visible);
    const matches = visible.filter(session => (session.title || '').toLowerCase().includes(term)
      || (session.workspace || '').toLowerCase().includes(term));
    return groupSessionsByProject(projects, matches).filter(group => group.active.length || group.archived.length);
  }, [sessions, projects, term, sideIds]);

  function toggleProject(key: string) {
    const update = term ? setSearchCollapsed : setCollapsed;
    update(previous => {
      const next = new Set(previous);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }
  const openMenu: MenuHandler = (id, anchor, toggle) =>
    setMenu(previous => toggle && previous?.id === id ? null : { id, ...anchor });

  return (
    <aside id="sidebar" className="sidebar rail" aria-label="项目导航">
      <header className="navigation-sidebar-head">
        <Button label="返回对话" icon={<Icon name="chevron" />} variant="ghost" className="navigation-back"
          onClick={() => {
            useStore.getState().setUi({ sidebarOpen: false });
            document.getElementById('sidebar-toggle')?.focus();
          }} />
        <NewTaskButton />
        <TextInput id="session-search" type="search" label="搜索对话" isLabelHidden placeholder="筛选对话"
          size="md" width="100%" value={query} autoComplete="off" hasSpellCheck={false}
          startIcon={<Icon name="search" />} controlClassName="navigation-filter" onChange={value => {
            setQuery(value);
            setSearchCollapsed(new Set());
          }} />
      </header>

      <div className="rail-scroll">
        <section className="navigation-section" aria-label="项目">
          <header className="navigation-section-head">
            <h2>项目</h2>
            <Button id="new-project" label="新建项目" isIconOnly icon={<NewTaskIcon />} variant="ghost" size="sm"
              className="navigation-row-action" onClick={() => useStore.getState().setUi({ projectEditor: { id: null } })} />
          </header>

          <ul id="session-list" className="session-list project-groups">
            {groups.map(group => {
              const key = group.project.id ?? 'ungrouped';
              return <ProjectGroup key={key} group={group} activeId={sessionId}
                collapsed={(term ? searchCollapsed : collapsed).has(key)} filtering={Boolean(term)}
                onToggle={() => toggleProject(key)} onMenu={openMenu} menuId={menu?.id} />;
            })}
          </ul>
          {!groups.length && <p id="session-empty" className="navigation-empty" role="status">
            {term ? `没有标题或路径包含「${query.trim()}」的对话。` : '还没有项目。可以新建项目来整理对话。'}
          </p>}
        </section>

        {sideItems.length > 0 && (
          <section className="navigation-section" aria-label="侧边对话">
            <header className="navigation-section-head"><h2>侧边对话</h2></header>
            <ul className="session-list">
              {sideItems.map(item => (
                <li key={item.id}>
                  <button type="button" className={`session-item${item.id === sideActiveId ? ' is-active' : ''}`}
                    aria-current={item.id === sideActiveId ? 'page' : undefined}
                    title={`${item.title || '侧边对话'}（当前任务的临时对话）`}
                    onClick={() => useStore.getState().selectSideChat(item.id)}>
                    <span className="session-title">{item.title || '侧边对话'}</span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>

      <WorkspaceTree />
      <div className="sidebar-foot rail-foot">
        <div id="connection" className={`conn is-${connection.status}`} role="status">
          <span className="dot" aria-hidden="true" />
          <span id="connection-text">{connection.text}</span>
        </div>
        <button id="workspace-chip" className="workspace-chip" type="button" hidden={!workspace}
          title={workspace || '切换工作区'}
          onClick={() => useStore.getState().setUi({ picker: { title: '切换工作区' } })}>
          {workspace.split(/[\\/]/).filter(Boolean).pop() || workspace}
        </button>
      </div>
      {menu && menuSession && <SessionMenu key={menu.id} session={menuSession} anchor={menu} onClose={() => setMenu(null)} />}
    </aside>
  );
}

function ProjectGroup({ group, activeId, collapsed, filtering, onToggle, onMenu, menuId }: {
  group: ProjectSessions; activeId: string | null; collapsed: boolean; filtering: boolean;
  onToggle: () => void; onMenu: MenuHandler; menuId?: string;
}) {
  const { project, active, archived } = group;
  const listId = useId();
  const hasCurrent = active.some(session => session.id === activeId) || archived.some(session => session.id === activeId);
  const projectId = project.id;
  return (
    <li className="project-group">
      <div className="project-head">
        <button type="button" className="project-disclosure"
          data-current={collapsed && hasCurrent || undefined} aria-expanded={!collapsed}
          aria-controls={archived.length ? `${listId} ${listId}-archived` : listId}
          aria-label={`${collapsed ? '展开' : '收起'}「${project.name}」`}
          title={project.folders?.join('\n') || project.name} onClick={onToggle}>
          <Icon name="chevron" className="project-chevron" />
          <Icon name={collapsed ? 'folder' : 'folderOpen'} />
          <span className="project-label">{project.name}</span>
        </button>
        {projectId && <NavigationMenu
          button={{ label: `项目操作：${project.name}`, isIconOnly: true, icon: <NavigationMoreIcon />,
            variant: 'ghost', size: 'sm', className: 'navigation-row-action' }}
          entries={[
            { label: '在此项目新建对话', icon: <NewTaskIcon />, onClick: () => {
              useStore.getState().startNewSession({ projectId, workspace: project.folders?.[0] });
              requestAnimationFrame(() => useStore.getState().composerApi?.focus());
            } },
            { label: '项目设置', icon: <Icon name="settings" />,
              onClick: () => useStore.getState().setUi({ projectEditor: { id: projectId } }) },
          ]}
        />}
      </div>

      <ul id={listId} className="project-sessions" hidden={collapsed}>
        {active.map(session => <SessionRow key={session.id} session={session} activeId={activeId} onMenu={onMenu} menuId={menuId} />)}
        {!active.length && !filtering && <li className="project-empty">{projectId ? '还没有对话' : '没有未分组的对话'}</li>}
      </ul>
      {archived.length > 0 && (
        <details id={`${listId}-archived`} className="project-archived" hidden={collapsed} open={filtering || undefined}>
          <summary>已归档 {archived.length}</summary>
          <ul className="project-sessions">
            {archived.map(session => <SessionRow key={session.id} session={session} activeId={activeId} onMenu={onMenu} menuId={menuId} />)}
          </ul>
        </details>
      )}
    </li>
  );
}

function SessionRow({ session, activeId, onMenu, menuId }: {
  session: SessionSummary; activeId: string | null; onMenu: MenuHandler; menuId?: string;
}) {
  const active = session.id === activeId;
  const status = String(session.status || '').trim().toLowerCase();
  const statusLabel = Object.hasOwn(statusLabels, status) ? statusLabels[status] : '';
  const title = session.title || '未命名对话';
  return (
    <li className={`session-row${session.unread ? ' is-unread' : ''}`} data-session-id={session.id}
      data-current={active || undefined} onContextMenu={event => {
        event.preventDefault();
        onMenu(session.id, { x: event.clientX, y: event.clientY, trigger: event.currentTarget.querySelector('.session-more') });
      }}>
      <button type="button" className={`session-item${active ? ' is-active' : ''}${session.archived ? ' is-archived' : ''}`}
        aria-current={active ? 'page' : undefined}
        title={[title, statusLabel, session.workspace].filter(Boolean).join('\n')}
        onClick={() => useStore.getState().activate(session.id)}
        onKeyDown={event => {
          if (event.nativeEvent.isComposing || (event.key !== 'ContextMenu' && !(event.shiftKey && event.key === 'F10'))) return;
          event.preventDefault();
          const box = event.currentTarget.getBoundingClientRect();
          onMenu(session.id, { x: box.right, y: box.top, trigger: event.currentTarget });
        }}>
        <span className="session-title">{title}</span>
        {session.pinned && <span className="session-pin"><Icon name="pin" /><span className="navigation-sr-only">已置顶</span></span>}
        {session.unread && <span className="session-unread"><span className="navigation-sr-only">未读</span></span>}
        {statusLabel && <span className={`session-status is-${status}`}><span className="navigation-sr-only">{statusLabel}</span></span>}
      </button>
      <Button className="session-more navigation-row-action" label={`对话操作：${title}`} isIconOnly icon={<NavigationMoreIcon />}
        variant="ghost" size="sm" aria-haspopup="menu" aria-expanded={menuId === session.id} onClick={event => {
          const box = event.currentTarget.getBoundingClientRect();
          onMenu(session.id, { x: box.right + 6, y: box.top, trigger: event.currentTarget }, true);
        }} />
    </li>
  );
}
