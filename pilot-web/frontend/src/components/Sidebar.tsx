import type { SessionSummary } from '../types/core.js';
import { TextInput } from '@astryxdesign/core/TextInput';
import { shortcut } from '../lib/interaction.js';
import { useMemo, useState } from 'react';
import { useStore } from '../store.js';
import { WorkspaceTree } from './files/WorkspaceTree.jsx';
import { groupSessionsByProject } from '../lib/project/sessions.js';
import { Icon } from './Icon.jsx';
import { SessionMenu, SessionActionDialog, type SessionAction } from './SessionMenu.jsx';
import { sideChatsFor } from '../lib/side-chat.js';

type SessionFilter = 'all' | 'running' | 'pending' | 'unread';

/** 左侧导航：项目分组的会话列表 + 工作区文件树 + 连接状态。 */
export function Sidebar() {
  const sessions = useStore((state) => state.sessions);
  const projects = useStore((state) => state.projects);
  const sessionId = useStore((state) => state.sessionId);
  const workspace = useStore((state) => state.workspace);
  const connection = useStore((state) => state.ui.connection);
  const syncError = useStore((state) => state.ui.syncError);
  const sidebarOpen = useStore((state) => state.ui.sidebarOpen);
  const sideChats = useStore((state) => state.sideChats);
  const globalApprovals = useStore((state) => state.globalPendingApprovals);
  const globalQuestions = useStore((state) => state.globalPendingQuestions);
  const [query, setQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<SessionFilter>('all');
  const [action, setAction] = useState<SessionAction | null>(null);
  const [collapsed, setCollapsed] = useState(() => new Set<string>());
  const [searchCollapsed, setSearchCollapsed] = useState(() => new Set<string>());
  const filtering = Boolean(query.trim()) || statusFilter !== 'all';
  const showAction = (next: SessionAction) => setAction(current => current ?? next);

  // 侧边对话属于当前任务：换任务后这里只列出那条任务的，跨项目也不会串台
  const { items: sideItems, activeId: sideActiveId } = sideChatsFor(sideChats, { sessionId, workspace });
  const sideIds = useMemo(() => new Set((sideChats?.items || []).map((item) => item.id)), [sideChats]);

  const pendingSessionIds = useMemo(() => {
    const ids = new Set<string>();
    for (const item of globalApprovals || []) if (item.sessionId) ids.add(item.sessionId);
    for (const item of globalQuestions || []) if (item.sessionId) ids.add(item.sessionId);
    for (const session of sessions || []) {
      const s = String(session.status || '').toLowerCase();
      if (s === 'waiting_approval' || s === 'waiting_question') ids.add(session.id);
    }
    return ids;
  }, [globalApprovals, globalQuestions, sessions]);

  const filterCounts = useMemo(() => {
    const base = (sessions || []).filter((session) => !sideIds.has(session.id) && !session.isBlank && !session.archived);
    return {
      running: base.filter((s) => String(s.status || '').toLowerCase() === 'running').length,
      pending: base.filter((s) => pendingSessionIds.has(s.id)).length,
      unread: base.filter((s) => Boolean(s.unread)).length
    };
  }, [sessions, sideIds, pendingSessionIds]);

  const groups = useMemo(() => {
    const q = query.trim().toLowerCase();
    // 侧边会话即使后端没有过滤掉，也不该在主列表里占一行（旧版本遗留数据）
    const visible = sessions.filter((session) => !sideIds.has(session.id));
    const byStatus = statusFilter === 'all'
      ? visible
      : visible.filter((session) => {
          if (session.isBlank || session.archived) return false;
          if (statusFilter === 'running') return String(session.status || '').toLowerCase() === 'running';
          if (statusFilter === 'pending') return pendingSessionIds.has(session.id);
          if (statusFilter === 'unread') return Boolean(session.unread);
          return true;
        });
    const filtered = q
      ? byStatus.filter((session) => (session.title || '').toLowerCase().includes(q)
        || (session.workspace || '').toLowerCase().includes(q))
      : byStatus;
    // 注意参数顺序：projects 在前、sessions 在后
    const grouped = groupSessionsByProject(projects, filtered);
    return filtering ? grouped.filter(group => group.active.length || group.archived.length) : grouped;
  }, [sessions, projects, query, sideIds, statusFilter, pendingSessionIds, filtering]);

  function toggleProject(key: string) {
    const update = filtering ? setSearchCollapsed : setCollapsed;
    update((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }

  return (
    <aside id="sidebar" className="sidebar rail" aria-label="项目导航">
      <button id="new-session" className="new-task" type="button" onClick={() => useStore.getState().startNewSession()}>
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>
        <span>新任务</span>
        <kbd>{shortcut('N')}</kbd>
      </button>

      <TextInput id="session-search" type="search" label="搜索对话" isLabelHidden placeholder="搜索对话"
        autoComplete="off" hasSpellCheck={false} hasClear value={query} width="100%"
        containerClassName="session-search" controlClassName="session-search-control"
        startIcon={<Icon name="search" />}
        onChange={value => { setQuery(value); setSearchCollapsed(new Set()); }} />

      <div className="session-filter-bar" role="group" aria-label="会话状态筛选">
        {([
          ['all', '全部', 0],
          ['running', '进行中', filterCounts.running],
          ['pending', '待处理', filterCounts.pending],
          ['unread', '未读', filterCounts.unread]
        ] as const).map(([key, label, count]) => (
          <button
            key={key}
            type="button"
            className={`session-filter-pill${statusFilter === key ? ' is-active' : ''}`}
            aria-pressed={statusFilter === key}
            onClick={() => { setStatusFilter(key); setSearchCollapsed(new Set()); }}
          >
            <span>{label}</span>
            {key !== 'all' && count > 0 && <span className="session-filter-count">{count}</span>}
          </button>
        ))}
      </div>

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
                sidebarOpen={sidebarOpen}
                onAction={showAction}
                collapsed={(filtering ? searchCollapsed : collapsed).has(group.project.id || 'ungrouped')}
                filtering={filtering}
                onToggle={() => toggleProject(group.project.id || 'ungrouped')}
              />
            ))}
          </ul>
          {!groups.length && (
            <p id="session-empty" className="nav-empty">
              {query ? `没有标题或路径包含「${query}」的对话。`
                : statusFilter !== 'all' ? '当前筛选条件下没有匹配的对话。'
                  : '还没有项目，点「项目」右侧的 + 新建一个。'}
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
        <div id="connection" className={`conn is-${connection.status}`} role="status" title={syncError || connection.text}>
          <span className="dot" aria-hidden="true" />
          <span id="connection-text">{connection.text}</span>
        </div>
        <button id="workspace-chip" className="workspace-chip mono" type="button" hidden={!workspace} title={workspace || '切换工作区'} onClick={() => useStore.getState().setUi({ picker: { title: '切换工作区' } })}>
          {workspace ? workspace.split(/[\\/]/).filter(Boolean).pop() : ''}
        </button>
      </div>
      {action && <SessionActionDialog action={action} onClose={() => setAction(null)} />}
    </aside>
  );
}

function ProjectGroup({ group, activeId, collapsed, filtering, onToggle, sidebarOpen, onAction }: {
  group: ReturnType<typeof groupSessionsByProject>[number]; activeId: string | null; collapsed: boolean;
  filtering: boolean; onToggle: () => void; sidebarOpen: boolean; onAction: (action: SessionAction) => void;
}) {
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
          <Icon name="folder" />
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
                requestAnimationFrame(() => useStore.getState().composerApi?.focus());
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
        {active.map((session) => <SessionRow key={session.id} session={session} activeId={activeId} visible={sidebarOpen && !collapsed} onAction={onAction} />)}
        {!active.length && !filtering && (
          <li className="project-empty">{project.id ? '还没有对话' : '没有未分组的对话'}</li>
        )}
      </ul>

      {archived.length > 0 && (
        <details className="project-archived" hidden={collapsed} open={filtering || undefined}>
          <summary>{`已归档 ${archived.length}`}</summary>
          <ul className="project-sessions">
            {archived.map((session) => <SessionRow key={session.id} session={session} activeId={activeId} visible={sidebarOpen && !collapsed} onAction={onAction} />)}
          </ul>
        </details>
      )}
    </li>
  );
}

const SESSION_STATUS_LABELS: Record<string, string> = {
  running: '执行中',
  waiting_approval: '等待批准',
  waiting_question: '等待选择',
  paused: '已暂停',
  interrupted: '已中断',
  blocked: '遇到阻塞',
  budget_limited: '已达预算',
  failed: '执行失败',
  error: '执行失败',
  completed: '已完成'
};

function SessionRow({ session, activeId, visible, onAction }: {
  session: SessionSummary; activeId: string | null; visible: boolean; onAction: (action: SessionAction) => void;
}) {
  const hasPendingApproval = useStore((state) => state.globalPendingApprovals.some((item) => item.sessionId === session.id));
  const hasPendingQuestion = useStore((state) => state.globalPendingQuestions.some((item) => item.sessionId === session.id));
  const blank = session.isBlank === true;
  const rawStatus = String(session.status || '').trim().toLowerCase();
  const status = hasPendingApproval ? 'waiting_approval' : hasPendingQuestion ? 'waiting_question' : rawStatus;
  const pendingBadge = hasPendingApproval ? '待批准' : hasPendingQuestion ? '待选择' : '';
  const statusLabel = SESSION_STATUS_LABELS[status] || '';
  const rowTitle = blank
    ? '新对话：还没有消息，发送第一条后自动变成历史对话'
    : [session.title, statusLabel ? `状态：${statusLabel}` : '', session.workspace || ''].filter(Boolean).join('\n');
  return (
    <li className={`session-row${session.unread ? ' is-unread' : ''}`} data-session-id={session.id}>
      <SessionMenu key={visible ? 'visible' : 'collapsed'} session={session} onAction={onAction}>
        <button
          type="button"
          className={`session-item${session.id === activeId ? ' is-active' : ''}${blank ? ' is-blank' : ''}${session.archived ? ' is-archived' : ''}`}
          aria-current={session.id === activeId ? 'page' : undefined}
          title={rowTitle}
          onClick={() => useStore.getState().activate(session.id)}
        >
          {blank ? (
            <span className="session-new-icon" aria-hidden="true">
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 3.5v9M3.5 8h9" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>
            </span>
          ) : (
            <span className={`dot is-${hasPendingApproval || hasPendingQuestion ? 'waiting_approval' : (status || 'idle')}`} title={statusLabel || undefined} aria-hidden="true" />
          )}
          <span className="session-title">{blank ? '新对话' : session.title}</span>
          {!blank && pendingBadge && (
            <span className={`session-pending-badge ${hasPendingApproval ? 'is-approval' : 'is-question'}`}>{pendingBadge}</span>
          )}
          {session.pinned && <span className="session-pin" aria-label="已置顶"><Icon name="pin" /></span>}
          {session.unread && <span className="session-unread" title="未读" aria-label="未读" />}
        </button>
      </SessionMenu>
    </li>
  );
}
