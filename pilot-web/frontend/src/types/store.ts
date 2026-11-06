import type { ApiClient } from '../api.js';
import type { ApiResults, InputMessage, BrowserStatus } from './api.js';
import type * as S from './server.js';
import type { ProjectSummary, SessionSummary, ChangeFile } from './core.js';
import type { ReducerState, PlanItem, ResultRow, RunUsage } from './thread.js';
import type { CommandLog, QueueEntry, RequestReport, ContextStatus } from './runtime.js';
import type { DraftAttachment, BuiltinCommand, SkillCommand } from './input.js';
import type { SideChatTab, SideChatsState } from './content.js';
import type { DraftSession, RefreshOptions } from './sync.js';
import type { EventEnvelope } from './events.js';

export interface MirrorFrame { url?: string; w?: number; h?: number; jpeg?: string; data?: string }
export interface MirrorState {
  mode: string; frame: MirrorFrame | null; url: string; embed: boolean; frameOptions: string;
  latency: number; paused: boolean; headless: boolean; frameW: number; frameH: number;
  canGoBack: boolean; canGoForward: boolean;
}
export interface ComposerBridge {
  setText(text: string): void; getText(): string; getDraft?(): { text: string; imagePositions: number[] }; attachmentIds?(): string[]; insertAttachments?: ReturnType<typeof import('../lib/composer-input.js').createComposerInput>['insertAttachments']; appendText(text: string): void; focus(): void;
  prepareReload?(): { ok: boolean; reason?: string };
  insertMention?(path: string): void;
  editMessage?(sequence: number, text: string): void;
  cancelEdit?(): void;
}
export interface ConfirmDialog { title: string; message: string; confirmText?: string; danger?: boolean; onConfirm?: () => unknown }
export interface CheckpointDialog { sequence?: number; loading?: boolean; applying?: boolean; error?: string; info?: S.CheckpointControllerPreview | null }
export interface UiState {
  notice: { text: string; at: number } | null; noticeTimer: number | null;
  refreshing: boolean; syncError: string; lastSyncedAt: number; filesRevision: number;
  themeMode: string; accent: string; sidebarOpen: boolean; inspectorOpen: boolean; inspectorTab: string;
  reviewExpanded: boolean; filters: string; settingsSection: string; settingsOpen: boolean; paletteOpen: boolean;
  picker: { title?: string; onPick?: (path: string) => unknown } | null;
  projectEditor: { id: string | null } | null; skillsInstall: object | null;
  checkpointDialog: CheckpointDialog | null; confirm: ConfirmDialog | null;
  agentsDockOpen: boolean; queueCollapsed: boolean; queueEdit: number | null; draftFiles: DraftAttachment[];
  connection: { status: string; text: string }; bottomSignal: number; attentionKind: string; browserDot: boolean;
  mirror: MirrorState; changeView: string | null; previewPath: string; browserStatus: BrowserStatus | null; usageDays: number;
  mobileConnectionOpen?: boolean; notificationsOpen?: boolean; recurringOpen?: boolean; resultsOpen?: boolean;
  schedulesOpen?: boolean; searchOpen?: boolean; composerEnabled?: boolean; resultUnread?: number;
  resultTarget?: string | null; scheduleTarget?: string | null; scheduleDraft?: string | null; scheduleRepeat?: boolean;
  navigationTarget?: { sessionId: string; sequence: number } | null; changeCount?: number;
}
/** 命令面板也接受历史版本的 id 字段。 */
export type CommandTask = Partial<S.CommandTaskManagerTask> & { id?: string };
export interface StoreData extends Omit<ReducerState, 'ui'> {
  sessionId: string | null; sessionEpoch: number; ready: boolean; draftSession: DraftSession | null;
  embeddedSessionId: string | null; sessionTitle: string; workspace: string; sessions: SessionSummary[];
  projects: ProjectSummary[]; currentProjectId: string | null; inputHistory: string[]; inputHistoryIndex: number; inputHistoryDraft: string;
  sideChats: SideChatsState; sideQuote: { id: string; text: string; nonce: number } | null; sideDrafts?: Record<string, string>;
  runId: string | null; lastRunId: string | null; running: boolean; cancelling: boolean; pausing: boolean; steering: boolean;
  queueBusy: boolean; queueRevision: string | null; runStartedAt: number; lastEventAt: number; modelRetryUntil: number;
  runUsage: RunUsage | null; requestReports: RequestReport[]; latestContextReport: RequestReport | null; liveOutputTokens: number;
  lastOutcome: string; queueHeld: boolean; turnRuns: string[]; changedFiles: ChangeFile[];
  contextStatus: ContextStatus | null; compactingContext: boolean; queueEntries: QueueEntry[];
  taskDirty: boolean; taskBusy: boolean; planItems: PlanItem[];
  skills: S.SkillsControllerSkillPayload[]; skillsMeta: { projectRoot: string; userRoot: string; invalid: S.SkillProblem[] };
  quickCommands: (BuiltinCommand | SkillCommand)[]; modelOptions: string[]; remoteModels: string[];
  settingsData: S.SettingsControllerSettingsResponse | null; modelSave: { busy: boolean; message: string; error: boolean };
  notifyEnabled: boolean; permissionMode: string; usageData: S.UsageServiceUsageReport | null;
  fileIndex: { key?: string; items: string[]; sessionId: string | null; loading: boolean };
  commandTasks: CommandTask[]; commandDetail: CommandTask | null; commandLog: CommandLog | null; commandLogTaskId: string | null;
  capabilities: S.CapabilitiesControllerCapabilitiesResponse | null; capabilitiesState: string;
  changeReport: S.ChangeServiceChangeReport | null; changesState: string; runChanges: S.ChangeServiceRunChangeReport | null;
  composerApi: ComposerBridge | null; ui: UiState;
  _previewRequest?: number; _previewData?: S.SessionFilesControllerFileResponse | null; _previewLoading?: boolean;
  _changesRequest?: number; _reconnectAttempt?: number; _editTarget?: number | null;
}
type StrictOptions = { strict?: boolean };
type ForceOptions = { force?: boolean };
export interface StoreActions {
  refreshWorkbench(options?: RefreshOptions): Promise<boolean>;
  reloadWorkbench(): Promise<boolean>;
  showNotice(text: string): void; hideNotice(): void; setTheme(mode: string): void; setAccent(accent: string): void;
  toggleNotify(): Promise<void>; setUi(patch: Partial<UiState> | ((ui: UiState) => Partial<UiState>)): void; setFilters(value: string): void;
  init(embeddedSessionId?: string | null): Promise<void>;
  loadProjects(options?: StrictOptions): Promise<void>; loadSessions(options?: StrictOptions): Promise<SessionSummary[]>;
  startNewSession(options?: Partial<DraftSession>): null; ensureSession(): Promise<string | null>; activate(id: string): Promise<boolean>;
  loadHistory(): Promise<void>; prefetchEarlier(): Promise<boolean>; applyEarlierPage(): boolean; loadEarlier(): Promise<boolean>;
  requestEarlierHistory(options?: ForceOptions): Promise<boolean>; loadLater(): Promise<boolean>; requestLaterHistory(options?: ForceOptions): Promise<boolean>;
  connectSse(id: string, cursor: number): void; scheduleReconnect(id: string): void; handleEvent(event: EventEnvelope): void;
  flushEvents(): void; settleEvent(event: EventEnvelope, after: StoreState): void;
  registerComposer(bridge: ComposerBridge | null): void; modelReady(): boolean;
  submitInput(text: string, attachments?: DraftAttachment[], imagePositions?: number[]): Promise<boolean>;
  steer(text: string, attachments?: DraftAttachment[]): Promise<boolean>; pauseRun(): Promise<void>; cancelRun(): Promise<void>;
  continueRun(): Promise<false | void>; resendMessage(sequence: number, text: string, attachments?: DraftAttachment[]): Promise<boolean>;
  resolveApproval(id: string, approved: boolean): Promise<void>; resolveArbitration(id: string, choice: string): Promise<void>;
  loadPlan(options?: StrictOptions): Promise<void>; loadQueue(options?: StrictOptions): Promise<void>;
  mutateQueue(operation: (id: string, revision: string | null) => Promise<unknown>, failure: string): Promise<boolean>;
  steerQueueItem(index: number): Promise<boolean>; saveQueueItem(index: number, text: string): Promise<boolean>;
  removeQueueItem(index: number): Promise<boolean>; moveQueueItem(from: number, to: number): Promise<boolean>;
  loadTask(options?: StrictOptions): Promise<void>; saveTask(constraints: string[], acceptance: S.TaskControlServiceAcceptance[]): Promise<void>;
  refreshContext(options?: StrictOptions): Promise<void>; ensureMirror(): void; compactContext(): Promise<void>;
  openCheckpoint(sequence: number): Promise<void>; restoreCheckpoint(sequence: number, mode: string, revision: number): Promise<void>;
  forkCheckpoint(sequence: number, revision: number): Promise<void>; currentProject(): ProjectSummary | null;
  createSideChat(options?: ForceOptions): Promise<SideChatTab | null>; selectSideChat(id: string): void; closeSideChat(id: string): Promise<boolean>;
  setSideDraft(id: string, value: string | ((text: string) => string)): void; toggleSideChatPane(): void;
  askSideChat(text: string): Promise<void>; takeSideQuote(id: string): string | null;
  switchWorkspace(workspace: string, options?: { throwOnError?: boolean }): Promise<void>;
  openWorktree(branch?: string): Promise<void>; handoffWorktree(): Promise<void>;
  decideSandbox(id: string, action: string, candidateHash?: string): Promise<boolean>;
  loadChanges(options?: StrictOptions): Promise<void>; restoreChanges(paths: string[] | null, all?: boolean): Promise<ApiResults['restoreChanges'] | null>;
  rollbackRun(id: string): Promise<void>; createBranch(name: string): Promise<{ ok: boolean; error?: string }>; openPreview(path: string): Promise<void>;
  loadCommandTasks(): Promise<void>; loadCommandLog(task: CommandTask): Promise<void>; stopCommand(task: CommandTask): Promise<void>;
  renameSession(title: string, id?: string | null): Promise<boolean>; updateSessionPreferences(id: string, values: { pinned?: boolean; unread?: boolean }): Promise<boolean>;
  archiveSession(id: string, archived?: boolean): Promise<boolean>; deleteSession(id: string): Promise<void>; regenerate(): Promise<void>;
  askConfirm(options: ConfirmDialog): void; loadSettings(): Promise<void>; savePermissionMode(mode: string): Promise<void>;
  clearModelKey(): Promise<boolean>; resetModelFeedback(): void;
  saveModel(body: Parameters<ApiClient['saveModel']>[0], options?: { clearKey?: boolean }): Promise<boolean>;
  probeModels(body: Parameters<ApiClient['probeModels']>[0]): ReturnType<ApiClient['probeModels']>;
  loadSkills(): Promise<void>; loadCapabilities(): Promise<ApiResults['capabilities'] | null>;
  loadBrowserStatus(): Promise<BrowserStatus | null>; loadUsage(days: number): Promise<void>; refreshActiveRun(): Promise<void>;
}
export type StoreState = StoreData & StoreActions;
