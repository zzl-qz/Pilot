/** 后端项目和会话列表，以及本地分组视图允许的旧字段。 */
export interface ProjectSummary {
  id: string | null;
  name?: string;
  folders?: string[];
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
  diff?: string;
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
