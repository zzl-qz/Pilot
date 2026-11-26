import type { ApiClient } from '../api.js';
import type { ApiResults, BrowserActionEvent, BrowserHandoff, BrowserLane, BrowserStatus, ExecutionMode, GlobalPendingApproval, GlobalPendingQuestion, PendingInteractionsSnapshot, UniversalFileResponse } from './api.js';
import type * as S from './server.js';
import type { ProjectSummary, SessionSummary, ChangeFile } from './core.js';
import type { ReducerState, PlanItem, RunUsage, Notice } from './thread.js';

export type SoundVolumeLevel = 'gentle' | 'standard' | 'crisp';
export type FloatingAlertMode = 'background' | 'always' | 'off';
export type ComposerSendKey = 'enter' | 'mod-enter';
export interface AlertPreferences {
  soundEnabled: boolean;
  soundVolume: SoundVolumeLevel;
  soundBackgroundOnly: boolean;
  soundOnDone: boolean;
  soundOnApproval: boolean;
  soundOnQuestion: boolean;
  soundOnError: boolean;
  floatingMode: FloatingAlertMode;
  taskbarAttention: boolean;
  desktopNotifyOnBackground: boolean;
  dndUntil: number | null;
  keepAwake: boolean;
  minimizeToTray: boolean;
  globalShortcut: string;
  sendKey: ComposerSendKey;
}
import type { CommandLog, QueueEntry, RequestReport, ContextStatus } from './runtime.js';
import type { DraftAttachment, BuiltinCommand, SkillCommand, NoteSeed } from './input.js';
import type { SideChatTab, SideChatsState } from './content.js';
import type { DraftSession, RefreshOptions } from './sync.js';
import type { EventEnvelope } from './events.js';

export interface MirrorState {
  mode: 'idle' | 'live' | 'closed';
  url: string;
  headless: boolean;
  paused: boolean;
  agentControlled: boolean;
  controlStatus: string;
  activeLane: string;
  lanes: BrowserLane[];
  handoffs: BrowserHandoff[];
  lastAction: BrowserActionEvent | null;
  evidenceCount: number;
  evidenceVersion: number;
}
/** 正在编辑的排队消息：用内容指纹而不是下标定位，刷新/重排后不会改到别的消息。 */
export interface QueueEditTarget { index: number; key: string }
export interface ComposerBridge {
  setText(text: string): void; replaceText?(text: string): void; getText(): string; getDraft?(): { text: string; imagePositions: number[] }; attachmentIds?(): string[]; insertAttachments?: ReturnType<typeof import('../lib/composer-input.js').createComposerInput>['insertAttachments']; appendText(text: string): void; focus(): void;
  prepareReload?(): { ok: boolean; reason?: string };
  insertMention?(path: string): void;
  editMessage?(sequence: number, text: string): void;
  cancelEdit?(): void;
}
export interface ConfirmDialog { title: string; message: string; confirmText?: string; danger?: boolean; onConfirm?: () => unknown }
export interface CheckpointDialog { sequence?: number; loading?: boolean; applying?: boolean; error?: string; info?: S.CheckpointControllerPreview | null }
export interface UiState {
  exportCopy?: string | null;
  notice: Notice | null; noticeTimer: number | null;
  refreshing: boolean; syncError: string; lastSyncedAt: number; filesRevision: number;
  themeMode: string; accent: string; sidebarOpen: boolean; inspectorOpen: boolean; inspectorTab: string;
  ideasOpen?: boolean;
  ideaSeed?: { text: string } | null;
  reviewExpanded: boolean; filters: string; settingsSection: string; settingsOpen: boolean; paletteOpen: boolean;
  picker: { title?: string; onPick?: (path: string) => unknown } | null;
  projectEditor: { id: string | null; name?: string; folders?: string[] } | null; skillsInstall: object | null;
  checkpointDialog: CheckpointDialog | null; confirm: ConfirmDialog | null;
  agentsDockOpen: boolean; queueCollapsed: boolean; queueEdit: QueueEditTarget | null; draftFiles: DraftAttachment[];
  connection: { status: string; text: string }; bottomSignal: number; attentionKind: string; browserDot: boolean;
  mirror: MirrorState; changeView: string | null; reviewRunId: string | null; previewPath: string; browserStatus: BrowserStatus | null; usageDays: number;
  mobileConnectionOpen?: boolean; notificationsOpen?: boolean; recurringOpen?: boolean; resultsOpen?: boolean;
  schedulesOpen?: boolean; searchOpen?: boolean; shortcutsOpen?: boolean; composerEnabled?: boolean; resultUnread?: number;
  resultTarget?: string | null; scheduleTarget?: string | null; scheduleDraft?: string | null; scheduleRepeat?: boolean;
  navigationTarget?: { sessionId: string; sequence: number } | null; changeCount?: number;
  /** 执行模式：act 允许修改文件与运行命令，plan 为只读分析/规划模式。 */
  executionMode: ExecutionMode;
  /** 文件工作台要聚焦的工作区文件 id；不改文件本身，只影响面板选中项。 */
  workbenchFileId?: string | null;
}
/** 命令面板也接受历史版本的 id 字段。 */
export type CommandTask = Partial<S.CommandTaskManagerTask> & { id?: string };
export interface StoreData extends Omit<ReducerState, 'ui'> {
  sessionId: string | null; sessionEpoch: number; ready: boolean; draftSession: DraftSession | null;
  historyRevision: number | null;
  embeddedSessionId: string | null; sessionTitle: string; workspace: string; sessions: SessionSummary[];
  projects: ProjectSummary[]; currentProjectId: string | null; inputHistory: string[]; inputHistoryIndex: number; inputHistoryDraft: string;
  /** 当前会话的想法：只属于这个会话，保存与回填都不发送消息。 */
  notes: S.SessionNotesNote[]; noteSeed: NoteSeed | null; noteFocus: string | null; notesState: 'idle' | 'loading' | 'ready' | 'error'; notesError: string; notesSaving: string;
  sideChats: SideChatsState; sideQuote: { id: string; text: string; nonce: number; quote?: boolean } | null; sideDrafts?: Record<string, string>;
  runId: string | null; lastRunId: string | null; running: boolean; cancelling: boolean; pausing: boolean; steering: boolean;
  queueBusy: boolean; queueRevision: string | null; runStartedAt: number; lastEventAt: number; modelRetryUntil: number;
  runUsage: RunUsage | null; requestReports: RequestReport[]; latestContextReport: RequestReport | null; liveOutputTokens: number;
  lastOutcome: string; queueHeld: boolean; queueWaitingReason: string; queueHolding: boolean; queueTakingBack: boolean; turnRuns: string[]; changedFiles: ChangeFile[];
  contextStatus: ContextStatus | null; compactingContext: boolean; queueEntries: QueueEntry[];
  taskDirty: boolean; taskBusy: boolean; taskDraftRevision: number; planItems: PlanItem[];
  skills: (S.SkillsControllerSkillPayload & { id?: string })[]; skillsMeta: { projectRoot: string; userRoot: string; invalid: S.SkillProblem[] };
  agentProfiles: S.AgentProfilesControllerProfilePayload[];
  agentProfilesMeta: { projectRoot: string; userRoot: string; invalid: S.AgentProfilesControllerProblemPayload[] };
  pendingMemoryDrafts: number;
  quickCommands: (BuiltinCommand | SkillCommand)[]; modelOptions: string[]; remoteModels: string[];
  settingsData: S.SettingsControllerSettingsResponse | null; modelSave: { busy: boolean; message: string; error: boolean };
  notifyEnabled: boolean; alertPrefs: AlertPreferences; desktopFocused: boolean | null;
  globalPendingApprovals: GlobalPendingApproval[]; globalPendingQuestions: GlobalPendingQuestion[];
  permissionMode: string; usageData: S.UsageServiceUsageReport | null;
  fileIndex: { key?: string; items: string[]; sessionId: string | null; loading: boolean };
  commandTasks: CommandTask[]; commandTasksError: string; commandDetail: CommandTask | null; commandLog: CommandLog | null; commandLogError: string; commandLogTaskId: string | null;
  capabilities: S.CapabilitiesControllerCapabilitiesResponse | null; capabilitiesState: string;
  /** 当前会话的验证结论；输入框上方的结论条读它。null 表示还没加载。 */
  validation: S.DiagnosticsControllerValidationView | null;
  /** 本会话是否已经跑过至少一轮：没跑过时不显示「未验证」，避免把空状态说成没有证据。 */
  hasRun: boolean;
  changeReport: S.ChangeServiceChangeReport | null; changesState: string; runChanges: S.ChangeServiceRunChangeReport | null;
  composerApi: ComposerBridge | null; ui: UiState;
  _previewRequest?: number; _previewData?: UniversalFileResponse | null; _previewLoading?: boolean;
  _previewLine?: number | null;
  _changesRequest?: number; _reconnectAttempt?: number; _editTarget?: number | null;
}
type StrictOptions = { strict?: boolean };
type ForceOptions = { force?: boolean };
export interface StoreActions {
  refreshWorkbench(options?: RefreshOptions): Promise<boolean>;
  reloadWorkbench(): Promise<boolean>;
  showNotice(text: string, action?: { label: string; onAction: () => unknown; durationMs?: number }): void;
  hideNotice(): void; runNoticeAction(): void; setTheme(mode: string): void; setAccent(accent: string): void;
  toggleNotify(): Promise<void>; setUi(patch: Partial<UiState> | ((ui: UiState) => Partial<UiState>)): void; setFilters(value: string): void;
  init(embeddedSessionId?: string | null): Promise<void>;
  loadProjects(options?: StrictOptions): Promise<void>; loadSessions(options?: StrictOptions): Promise<SessionSummary[]>;
  loadNotes(): Promise<void>; addNote(content: string, source?: NoteSeed['source']): Promise<boolean>; openNoteFromSelection(selection: NoteSeed | null): void; focusNote(noteId: string | null): void;
  editNote(id: string, content: string): Promise<boolean>; removeNote(id: string): Promise<boolean>;
  setNoteStatus(id: string, status: string): Promise<boolean>; promoteNote(id: string): Promise<string | null>;
  startNewSession(options?: Partial<DraftSession>): null; ensureSession(): Promise<string | null>; activate(id: string): Promise<boolean>;
  loadHistory(): Promise<boolean>; prefetchEarlier(): Promise<boolean>; applyEarlierPage(): boolean; loadEarlier(): Promise<boolean>;
  requestEarlierHistory(options?: ForceOptions): Promise<boolean>; loadLater(): Promise<boolean>; requestLaterHistory(options?: ForceOptions): Promise<boolean>;
  connectSse(id: string, cursor: number): void; scheduleReconnect(id: string): void; handleEvent(event: EventEnvelope): void;
  flushEvents(): void; settleEvent(event: EventEnvelope, after: StoreState): void;
  registerComposer(bridge: ComposerBridge | null): void; modelReady(): boolean;
  submitInput(text: string, attachments?: DraftAttachment[], imagePositions?: number[]): Promise<boolean>;
  steer(text: string, attachments?: DraftAttachment[]): Promise<boolean>; pauseRun(): Promise<void>; cancelRun(): Promise<void>;
  continueRun(): Promise<false | void>; resendMessage(sequence: number, text: string, attachments?: DraftAttachment[]): Promise<boolean>;
  resolveApproval(id: string, approved: boolean, sessionId?: string, reason?: string): Promise<void>;
  resolveApprovalWithTrust(id: string, approved: boolean, trust: { action: string; server: string; tool?: string; configHash?: string; toolHash?: string }): Promise<void>;
  resolveApprovalWithRule(callId: string, rule: string): Promise<void>;
  answerQuestion(callId: string, choice: string, sessionId?: string): Promise<boolean>;
  resolveArbitration(id: string, choice: string): Promise<void>;
  updateAlertPrefs(patch: Partial<AlertPreferences>): void;
  setDesktopFocused(focused: boolean): void;
  syncPendingInteractions(snapshot?: PendingInteractionsSnapshot | null): void;
  connectGlobalInteractions(): void;
  loadPlan(options?: StrictOptions): Promise<void>; loadQueue(options?: StrictOptions): Promise<void>;
  mutateQueue(operation: (id: string, revision: string | null) => Promise<unknown>, failure: string): Promise<boolean>;
  steerQueueItem(index: number): Promise<boolean>; saveQueueItem(target: QueueEditTarget, text: string, requirements?: { constraints: string[]; acceptance: string[] }): Promise<boolean>;
  removeQueueItem(index: number): Promise<boolean>; moveQueueItem(from: number, to: number): Promise<boolean>; setQueueHeld(held: boolean): Promise<boolean>; takeBackQueueItem(index: number): Promise<boolean>;
  loadTask(options?: StrictOptions): Promise<void>; saveTask(constraints: string[], acceptance: S.TaskControlServiceAcceptance[]): Promise<void>;
  refreshContext(options?: StrictOptions): Promise<void>; ensureMirror(): void; compactContext(focus?: string): Promise<boolean>;
  openCheckpoint(sequence: number): Promise<void>; restoreCheckpoint(sequence: number, mode: string, revision: number): Promise<void>;
  forkCheckpoint(sequence: number, revision: number): Promise<void>; currentProject(): ProjectSummary | null;
  createSideChat(options?: ForceOptions): Promise<SideChatTab | null>; selectSideChat(id: string): void; closeSideChat(id: string): Promise<boolean>;
  setSideDraft(id: string, value: string | ((text: string) => string)): void; toggleSideChatPane(): void;
  askSideChat(text: string, quote?: boolean): Promise<void>; takeSideQuote(id: string): string | null;
  switchWorkspace(workspace: string, options?: { throwOnError?: boolean }): Promise<void>;
  openWorktree(branch?: string): Promise<void>; handoffWorktree(): Promise<void>;
  decideSandbox(id: string, action: string, candidateHash?: string): Promise<boolean>;
  loadChanges(options?: StrictOptions): Promise<void>; restoreChanges(paths: string[] | null, all?: boolean): Promise<ApiResults['restoreChanges'] | null>;
  rollbackRun(id: string): Promise<void>; createBranch(name: string): Promise<{ ok: boolean; error?: string }>; openPreview(path: string, line?: number | null): Promise<void>;
  loadCommandTasks(): Promise<void>; loadCommandLog(task: CommandTask): Promise<void>; stopCommand(task: CommandTask): Promise<void>;
  renameSession(title: string, id?: string | null): Promise<boolean>; updateSessionPreferences(id: string, values: { pinned?: boolean; unread?: boolean }): Promise<boolean>;
  archiveSession(id: string, archived?: boolean): Promise<boolean>; deleteSession(id: string): Promise<boolean>; regenerate(): Promise<void>;
  askConfirm(options: ConfirmDialog): void; loadSettings(): Promise<void>; savePermissionMode(mode: string): Promise<void>;
  clearModelKey(): Promise<boolean>; resetModelFeedback(): void;
  saveModel(body: Parameters<ApiClient['saveModel']>[0], options?: { clearKey?: boolean }): Promise<boolean>;
  probeModels(body: Parameters<ApiClient['probeModels']>[0]): ReturnType<ApiClient['probeModels']>;
  loadSkills(): Promise<void>; refreshMemoryDraftCount(workspace?: string): Promise<void>; loadCapabilities(): Promise<ApiResults['capabilities'] | null>; loadAgentProfiles(): Promise<void>;
  loadValidation(): Promise<void>;
  loadBrowserStatus(): Promise<BrowserStatus | null>;
  selectBrowserLane(browserSessionId: string): Promise<boolean>;
  resumeBrowserHandoff(browserSessionId?: string, note?: string): Promise<boolean>;
  cancelBrowserHandoff(browserSessionId?: string, reason?: string): Promise<boolean>;
  stopBrowserControl(browserSessionId?: string): Promise<boolean>;
  loadUsage(days: number): Promise<void>; refreshActiveRun(): Promise<void>;
}
export type StoreState = StoreData & StoreActions;
