import { shortcut } from '../lib/interaction.js';
import React, { useMemo, useState } from 'react';
import { useStore } from '../store.js';
import { WorkspaceTree } from './WorkspaceTree.jsx';
import { groupSessionsByProject } from '../lib/projects.js';
import { sideChatsFor } from '../lib/side-chat.js';

/** 左侧导航：项目分组的会话列表 + 工作区文件树 + 连接状态。 */
export function Sidebar() {
  const sessions = useStore((state) => state.sessions);
  const projects = useStore((state) => state.projects);
  const sessionId = useStore((state) => state.sessionId);
  const workspace = useStore((state) => state.workspace);
  const connection = useStore((state) => state.ui.connection);
  const sideChats = useStore((state) => state.sideChats);
  const [query, setQuery] = useState('');
  const [collapsed, setCollapsed] = useState(() => new Set());

  // 侧边对话属于当前任务：换任务后这里只列出那条任务的，跨项目也不会串台
  const { items: sideItems, activeId: sideActiveId } = sideChatsFor(sideChats, { sessionId, workspace });
  const sideIds = useMemo(() => new Set((sideChats?.items || []).map((item) => item.id)), [sideChats]);

  const groups = useMemo(() => {
    const q = query.trim().toLowerCase();
    // 侧边会话即使后端没有过滤掉，也不该在主列表里占一行（旧版本遗留数据）
    const visible = sessions.filter((session) => !sideIds.has(session.id));
    const filtered = q
      ? visible.filter((session) => (session.title || '').toLowerCase().includes(q)
        || (session.workspace || '').toLowerCase().includes(q))
      : visible;
    // 注意参数顺序：projects 在前、sessions 在后
    return groupSessionsByProject(projects, filtered);
  }, [sessions, projects, query, sideIds]);

  function toggleProject(key) {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }

  return (
    <aside id="sidebar" className="sidebar rail" aria-label="项目导航">
      <div className="sidebar-head rail-head">
        <span className="logo" aria-hidden="true">P</span>
        <span className="logo-text"><strong>Pilot</strong></span>
        <button
          id="rail-toggle"
          className="icon-btn"
          type="button"
          title={`收起侧栏 (${shortcut('B')})`}
          aria-label="收起侧栏"
          onClick={() => useStore.getState().setUi((ui) => ({ sidebarOpen: !ui.sidebarOpen }))}
        >
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M9.5 4 5.5 8l4 4" stroke="currentColor" strokeWidth="1.5" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </button>
      </div>

      <button id="new-session" className="new-task" type="button" onClick={() => useStore.getState().startNewSession()}>
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>
        <span>新任务</span>
        <kbd>{shortcut('N')}</kbd>
      </button>

      <label className="session-search" htmlFor="session-search">
        <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="7" cy="7" r="4.2" stroke="currentColor" strokeWidth="1.4" fill="none" /><path d="m10.2 10.2 3 3" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg>
        <input
          id="session-search"
          type="search"
          placeholder="搜索对话"
          autoComplete="off"
          spellCheck="false"
          aria-label="搜索对话"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
      </label>

      <div className="rail-scroll">
        <section className="nav-block" aria-label="项目">
          <header className="nav-head">
            <span>项目</span>
            <span id="session-count" className="nav-count" title="项目总数">{projects.length}</span>
            <button
              id="new-project"
              className="icon-btn icon-btn-sm"
              type="button"
              title="新建项目"
              aria-label="新建项目"
              onClick={() => useStore.getState().setUi({ projectEditor: { id: null } })}
            >
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>
            </button>
          </header>

          <ul id="session-list" className="session-list project-groups">
            {groups.map((group) => (
              <ProjectGroup
                key={group.project.id || 'ungrouped'}
                group={group}
                activeId={sessionId}
                collapsed={collapsed.has(group.project.id || 'ungrouped')}
                onToggle={() => toggleProject(group.project.id || 'ungrouped')}
              />
            ))}
          </ul>
          {!groups.length && (
            <p id="session-empty" className="nav-empty">
              {query ? `没有标题包含「${query}」的对话。` : '还没有项目，点上面「新任务」新建一个。'}
            </p>
          )}
        </section>

        {sideItems.length > 0 && (
          <section className="nav-block" aria-label="侧边对话">
            <header className="nav-head">
              <span>侧边对话</span>
              <span className="nav-count">{sideItems.length}</span>
            </header>
            <ul className="session-list">
              {sideItems.map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    className={`session-item${item.id === sideActiveId ? ' is-active' : ''}`}
                    title={`${item.title || '侧边对话'}（当前任务的临时对话）`}
                    onClick={() => useStore.getState().selectSideChat(item.id)}
                  >
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
        <button id="workspace-chip" className="workspace-chip mono" type="button" hidden={!workspace} title={workspace || '切换工作区'} onClick={() => useStore.getState().setUi({ picker: { title: '切换工作区' } })}>
          {workspace ? workspace.split(/[\\/]/).filter(Boolean).pop() : ''}
        </button>
      </div>
    </aside>
  );
}

function ProjectGroup({ group, activeId, collapsed, onToggle }) {
  // 空白对话（isBlank）是旧版「先建后聊」的遗留：新对话不再落库，这里也不再给它留位置
  const { project, active, archived } = group;
  const count = active.length;
  return (
    <li className="project-group">
      <div className="project-head">
        <button
          type="button"
          className={`project-toggle${collapsed ? ' is-collapsed' : ''}`}
          title={`${collapsed ? '展开「' : '收起「'}${project.name}」`}
          aria-label={`${collapsed ? '展开' : '收起'}「${project.name}」`}
          aria-expanded={!collapsed}
          onClick={onToggle}
        >
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M6 3.5 10 8l-4 4.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </button>
        <button
          type="button"
          className="project-name"
          title={project.folders && project.folders.length ? project.folders.join('\n') : project.name}
          onClick={onToggle}
        >
          <span className="project-label">{project.name}</span>
          <span className="project-count">{count}</span>
        </button>
        {project.id && (
          <div className="project-actions">
            <button
              type="button"
              className="icon-btn icon-btn-sm"
              title="在此项目新建对话"
              aria-label="在此项目新建对话"
              onClick={(event) => {
                event.stopPropagation();
                // 带上 projectId：否则从项目里新建的对话会掉进「未分组」
                useStore.getState().startNewSession({ projectId: project.id, workspace: project.folders?.[0] });
              }}
            >
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>
            </button>
            <button
              type="button"
              className="icon-btn icon-btn-sm"
              title="项目设置"
              aria-label="项目设置"
              onClick={(event) => {
                event.stopPropagation();
                useStore.getState().setUi({ projectEditor: { id: project.id } });
              }}
            >
              ⋯
            </button>
          </div>
        )}
      </div>

      <ul className="project-sessions" hidden={collapsed}>
        {active.map((session) => <SessionRow key={session.id} session={session} activeId={activeId} />)}
        {!active.length && (
          <li className="project-empty">{project.id ? '还没有对话' : '没有未分组的对话'}</li>
        )}
      </ul>

      {archived.length > 0 && (
        <details className="project-archived">
          <summary>{`已归档 ${archived.length}`}</summary>
          <ul className="project-sessions">
            {archived.map((session) => <SessionRow key={session.id} session={session} activeId={activeId} />)}
          </ul>
        </details>
      )}
    </li>
  );
}

function SessionRow({ session, activeId }) {
  const blank = session.isBlank === true;
  const status = String(session.status || '').trim().toLowerCase();
  return (
    <li className="session-row">
      <button
        type="button"
        className={`session-item${session.id === activeId ? ' is-active' : ''}${blank ? ' is-blank' : ''}${session.archived ? ' is-archived' : ''}`}
        title={blank ? '新对话：还没有消息，发送第一条后自动变成历史对话' : (session.workspace ? `${session.title}\n${session.workspace}` : session.title)}
        onClick={() => useStore.getState().activate(session.id)}
      >
        {blank ? (
          <span className="session-new-icon" aria-hidden="true">
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 3.5v9M3.5 8h9" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>
          </span>
        ) : (
          <span className={`dot is-${status || 'idle'}`} aria-hidden="true" />
        )}
        <span className="session-title">{blank ? '新对话' : session.title}</span>
      </button>
      <button
        type="button"
        className="session-action"
        title={session.archived ? '恢复到列表' : '归档（保留历史，从列表里收起）'}
        aria-label={session.archived ? '恢复到列表' : '归档'}
        onClick={(event) => {
          event.stopPropagation();
          useStore.getState().archiveSession(session.id, !session.archived);
        }}
      >
        {session.archived ? '↩' : '↓'}
      </button>
      <button
        type="button"
        className="session-action is-danger"
        title="彻底删除（事件与检查点一起清掉，不可恢复）"
        aria-label="删除对话"
        onClick={(event) => {
          event.stopPropagation();
          useStore.getState().askConfirm({
            title: '删除对话',
            message: `「${session.title || '新对话'}」及其历史、检查点会被永久删除，无法恢复。确定删除吗？`,
            confirmText: '删除',
            danger: true,
            onConfirm: () => useStore.getState().deleteSession(session.id)
          });
        }}
      >×</button>
    </li>
  );
}
