/** 后端项目和会话列表，以及本地分组视图允许的旧字段。 */
export interface ProjectSummary {
  id: string | null;
  name?: string;
  folders?: string[];
  constraints?: string;
  sessionCount?: number;
}

export interface SessionSummary {
  id: string;
  sessionId?: string;
  title?: string;
  workspace?: string;
  projectId?: string | null;
  createdAt?: string;
  updatedAt?: string;
  status?: string;
  isBlank?: boolean;
  archived?: boolean;
  pinned?: boolean;
  unread?: boolean;
}

export interface ChangeFile {
  path: string;
  status?: string;
  added?: number;
  removed?: number;
  /** null 表示报告只返回统计，补丁可以按需读取。 */
  diff?: string | null;
  note?: string | null;
  /** 行数是否可信；为 false 时 added/removed 的 0 不是「没改」，界面按其显示为未知。 */
  countsKnown?: boolean;
}

export interface ChangeReport {
  gitRepository?: boolean;
  truncated?: boolean;
  files?: ChangeFile[];
}

export interface DiffLine {
  kind: 'hunk' | 'meta' | 'add' | 'del' | 'ctx';
  text: string;
  old: number | '';
  next: number | '';
}

export interface DiffFile extends ChangeFile {
  lines: DiffLine[];
  added: number;
  removed: number;
}
