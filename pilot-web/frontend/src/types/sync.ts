import type { ApiClient } from '../api.js';
export interface DraftSession { projectId: string | null; workspace: string | null }
export interface WorkbenchView { sessionId: string | null; draftSession: DraftSession | null; workspace: string; currentProjectId: string | null }
export interface RefreshOptions { silent?: boolean; full?: boolean; reconnect?: boolean }
export interface WorkbenchSyncState extends WorkbenchView {
  sessionEpoch: number;
  sessionTitle: string;
  runId: string | null;
  running: boolean;
  cancelling: boolean;
  pausing: boolean;
  taskDirty: boolean;
  taskBusy: boolean;
  thread: { cursor: number };
  fileIndex: { items: string[]; sessionId: string | null; loading: boolean };
  ui: { refreshing: boolean; syncError: string; lastSyncedAt: number; filesRevision: number; connection: { status: string }; inspectorOpen: boolean; inspectorTab: string };
  loadProjects(options: { strict: boolean }): Promise<unknown>;
  loadSessions(options: { strict: boolean }): Promise<unknown>;
  loadQueue(options: { strict: boolean }): Promise<unknown>;
  refreshContext(options: { strict: boolean }): Promise<unknown>;
  loadPlan(options: { strict: boolean }): Promise<unknown>;
  loadTask(options: { strict: boolean }): Promise<unknown>;
  loadChanges(options: { strict: boolean }): Promise<unknown>;
  flushEvents(): void;
  connectSse(sessionId: string, cursor: number): void;
  showNotice(text: string): void;
}
export interface WorkbenchRefresherOptions {
  get: () => WorkbenchSyncState;
  set: (change: (state: WorkbenchSyncState) => void) => void;
  api: Pick<ApiClient, 'session' | 'activeRun'>;
}
