import type { ProjectSummary, SessionSummary } from '../types/core.js';
/**
 * 项目制的纯逻辑：路径比较、可用文件夹、侧栏分组。
 *
 * <p>这几件事都有「看起来差不多、坏了很难发现」的边界：
 * Windows 下反斜杠与大小写、对话用的目录被从项目里删掉、
 * 历史会话没有 project_id、空白对话与归档对话的归类。
 */

/** 以服务端路径格式判断：Windows 盘符/UNC 忽略大小写，POSIX 保留大小写。 */
export function samePath(left: unknown, right: unknown) {
  const normalize = (value: unknown) => {
    const path = String(value || '').replace(/\\/g, '/');
    const normalized = path.replace(/\/+$/, '') || (path.startsWith('/') ? '/' : '');
    return /^(?:[a-z]:\/|\/\/)/i.test(path) ? normalized.toLowerCase() : normalized;
  };
  const a = normalize(left);
  return a !== '' && a === normalize(right);
}

/**
 * 当前对话可用的文件夹：项目绑定的目录，前面补上「正在用的那个」。
 *
 * <p>如果对话的当前目录已经从项目里移除，也要留在列表最前面：
 * 否则切换菜单里没有任何一项是选中的，用户不知道自己正在哪个目录里跑。
 */
export function resolveFolders(project: Pick<ProjectSummary, 'folders'> | null | undefined, workspace: string | null | undefined) {
  const folders = project && Array.isArray(project.folders) ? project.folders.slice() : [];
  if (workspace && !folders.some((folder) => samePath(folder, workspace))) {
    folders.unshift(workspace);
  }
  return folders;
}

/**
 * 把一个项目的对话分成三档：空白（最多一条）、活跃历史、已归档。
 *
 * <p>空白对话单独拎出来是因为它在侧栏里的位置和样式都不一样：
 * 永远置顶、浅色、发送第一条消息后自动变成普通历史。
 * 归档的不删，收进折叠区，随时可以恢复。
 */
export function sortSessions<T extends SessionSummary>(sessions: readonly T[] | null | undefined): T[] {
  const time = (session: SessionSummary) => Date.parse(session.updatedAt || session.createdAt || '') || 0;
  return [...(Array.isArray(sessions) ? sessions : [])].sort((a, b) =>
    Number(Boolean(b.pinned)) - Number(Boolean(a.pinned)) || time(b) - time(a));
}

export function partitionSessions<T extends SessionSummary>(sessions: readonly T[] | null | undefined) {
  const list = sortSessions(sessions);
  const blank = list.find((session) => session.isBlank && !session.archived) || null;
  const active = list.filter((session) => !session.isBlank && !session.archived);
  const archived = list.filter((session) => session.archived);
  return { blank, active, archived };
}

/**
 * 侧栏分组：每个项目一组，没有归属的历史会话归到「未分组」。
 *
 * @param {Array<{id: string, name: string, folders?: string[]}>} projects
 * @param {Array<{id: string, projectId?: string|null, isBlank?: boolean, archived?: boolean}>} sessions
 *        按置顶和最近活动时间排序
 * @returns {Array<{project: object, blank: object|null, active: Array, archived: Array}>}
 */
export function groupSessionsByProject(projects: readonly ProjectSummary[] | null | undefined, sessions: readonly SessionSummary[] | null | undefined) {
  // 空白对话（旧版「先建后聊」的遗留）不再进侧栏，连分组都不该被它撑出来
  const list = (Array.isArray(sessions) ? sessions : []).filter((session) => !session.isBlank);
  const groups = (Array.isArray(projects) ? projects : []).map((project) => ({
    project,
    ...partitionSessions(list.filter((session) => session.projectId === project.id))
  }));
  const orphans = list.filter((session) => !session.projectId);
  if (orphans.length) {
    groups.push({
      project: { id: null, name: '未分组', folders: [], sessionCount: orphans.length },
      ...partitionSessions(orphans)
    });
  }
  return groups;
}
