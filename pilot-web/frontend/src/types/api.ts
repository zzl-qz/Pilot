import type * as S from './server.js';
import type * as C from './coordination.js';
import type { ContextStatus, QueueResponse } from './runtime.js';
import type { DraftAttachment } from './input.js';

import type { EventEnvelope } from './events.js';
export type { EventEnvelope } from './events.js';
export interface HistoryPage { events: EventEnvelope[]; hasMore: boolean; total?: number; historyRevision?: number }
export interface ActionResponse { ok?: boolean; message?: string; error?: string }
export interface BrowserLane {
  browserSessionId: string;
  parentSessionId: string;
  agentId: string;
  label: string;
  createdAt?: string;
}
export interface BrowserHandoff {
  handoffId: string;
  sessionId: string;
  browserSessionId: string;
  agentId?: string;
  kind: 'auth' | 'captcha' | 'payment' | 'manual' | string;
  reason: string;
  url?: string;
  requestedAt: string;
  expiresAt?: string | null;
}
export interface BrowserActionEvent {
  event?: string;
  action?: string;
  status?: string;
  summary?: string;
  url?: string;
  title?: string;
  selector?: string;
  ref?: string;
  browserSessionId?: string;
  x?: number;
  y?: number;
  width?: number;
  height?: number;
  viewportWidth?: number;
  viewportHeight?: number;
  box?: { x: number; y: number; width: number; height: number } | null;
  ts?: number;
}
export interface BrowserEvidenceItem {
  id: string;
  sessionId: string;
  browserSessionId: string;
  runId?: string;
  agentId?: string;
  timestamp: string;
  action: string;
  status: string;
  url?: string;
  title?: string;
  summary?: string;
  screenshotPath?: string;
  recordingPath?: string;
  downloadPath?: string;
  downloadName?: string;
  downloadMime?: string;
  downloadBytes?: number;
  blockedOrigins?: string[];
  consoleErrors?: number;
  failedRequests?: number;
  gateKind?: string;
}
export interface BrowserEvidenceSummary {
  total: number;
  screenshots?: number;
  recordings?: number;
  downloads?: number;
  errors?: number;
  handoffs?: number;
  blockedOrigins?: string[];
}
export interface BrowserRecordingFrame {
  ts: number;
  offsetMs: number;
  url?: string;
  w: number;
  h: number;
  data: string;
}
export interface BrowserRecordingAction {
  ts: number;
  offsetMs: number;
  kind: string;
  status?: string;
  url?: string;
  ref?: string;
  x?: number | null;
  y?: number | null;
  box?: { x: number; y: number; width: number; height: number } | null;
}
export interface BrowserRecordingReplay {
  version: number;
  startedAt: number;
  endedAt: number;
  durationMs: number;
  viewport: { width: number; height: number };
  frames: BrowserRecordingFrame[];
  actions: BrowserRecordingAction[];
}
export interface BrowserEvidenceResponse {
  items: BrowserEvidenceItem[];
  summary: BrowserEvidenceSummary;
}
export interface BrowserStatus {
  enabled: boolean;
  capable: boolean;
  available: boolean;
  reason?: string;
  paused?: boolean;
  agentControlled?: boolean;
  controlStatus?: string;
  headless?: boolean;
  activeLane?: string;
  lanes?: BrowserLane[];
  handoffs?: BrowserHandoff[];
  evidenceCount?: number;
}
export interface GlobalPendingApproval {
  callId: string;
  sessionId: string;
  sessionTitle: string;
  toolName: string;
  argsSummary: string;
  structuredArgs?: Record<string, unknown> | null;
  requestedAt: string;
  expiresAt?: string | null;
}
export interface GlobalPendingQuestion {
  callId: string;
  sessionId: string;
  sessionTitle: string;
  question: string;
  options: string[];
  requestedAt: string;
  expiresAt?: string | null;
}
export interface PendingInteractionsSnapshot {
  approvals: GlobalPendingApproval[];
  questions: GlobalPendingQuestion[];
}
export interface RunAttentionPayload {
  sessionId: string;
  runId?: string;
  kind: 'done' | 'error' | 'attention';
  outcome?: string;
  title?: string;
  summary?: string;
  at?: string;
}
export type ExecutionMode = 'act' | 'plan';
export interface InputMessage { content: string; attachments?: Pick<DraftAttachment, 'name' | 'mimeType' | 'dataBase64'>[]; imagePositions?: number[]; skill?: string; mode?: ExecutionMode }
export interface SubmissionResult { state: 'accepted' | 'rejected' | 'unknown'; status?: number; response?: { message?: string; sessionId?: string; runId?: string } }
export type Update<T> = { [K in keyof T]?: T[K] extends readonly unknown[] ? T[K] : T[K] extends object ? Update<T[K]> : T[K] | null };
/** 文件工作台：工作区里的真实文件就是唯一源，下面只是它的索引与审阅元数据。 */
export interface WorkbenchFile {
  id: string; sessionId: string; path: string; filename: string; format: string;
  bytes: number; sha256: string; modifiedAt: string; changedExternally: boolean;
}
export interface WorkbenchPreview { fileId: string; pages: number; notice: string }
export interface WorkbenchVersion { version: number; sha256: string; createdAt: string; label: string }
export interface WorkbenchAnnotation {
  id: string; fileId: string; page: number; anchor: string; text: string; createdAt: string; status: string;
}
export interface WorkbenchOpenResult { ok: boolean; reason: string; alternative: string }
export type UniversalPreviewKind = 'text' | 'image' | 'pdf' | 'audio' | 'video' | 'docx' | 'xlsx' | 'pptx' | 'archive' | 'binary';
export interface UniversalDocBlock { type: 'paragraph' | 'table'; level: number; text: string; rows?: string[][] | null }
export interface UniversalSheetData { name: string; rows: string[][]; totalRows: number; truncated: boolean }
export interface UniversalSlideData { index: number; title: string; paragraphs: string[]; tables?: string[][][] | null; notes?: string | null }
export interface UniversalArchiveItem { path: string; directory: boolean; size: number; compressedSize: number }
export interface UniversalStructuredPreview {
  kind: UniversalPreviewKind;
  truncated?: boolean;
  error?: string | null;
  blocks?: UniversalDocBlock[] | null;
  sheets?: UniversalSheetData[] | null;
  slides?: UniversalSlideData[] | null;
  entries?: UniversalArchiveItem[] | null;
}
export type UniversalFileResponse = S.SessionFilesControllerFileResponse & {
  kind?: UniversalPreviewKind;
  mimeType?: string;
  structured?: UniversalStructuredPreview | null;
};

export interface ModelProfileView {
  id: string;
  name: string;
  endpoint: string;
  model: string;
  apiKeySet: boolean;
  apiKeyMasked: string;
  contextTokens: number | null;
  maxOutputTokens: number | null;
  childModel: string;
  active: boolean;
  fallback?: boolean;
}
export interface ModelProfilesView {
  activeProfileId: string;
  fallbackProfileId?: string;
  profiles: ModelProfileView[];
}
export interface ModelProfileSaveRequest {
  name?: string;
  endpoint?: string;
  model?: string;
  apiKey?: string;
  clearApiKey?: boolean;
  inheritCurrentKey?: boolean;
  contextTokens?: number | null;
  maxOutputTokens?: number | null;
  childModel?: string;
  activate?: boolean;
}

export interface GitCommitEntry {
  hash: string;
  shortHash: string;
  subject: string;
  author: string;
  date: string;
}
export interface GitCommitLogReport {
  gitRepository: boolean;
  branch: string;
  commits: GitCommitEntry[];
  error: string | null;
}
export interface GitBranchReport { gitRepository: boolean; current: string; branches: string[]; error: string | null }
export interface GitStashEntry { reference: string; message: string }
export interface GitStashReport { gitRepository: boolean; stashes: GitStashEntry[]; error: string | null }
export interface GitOperationResult { ok: boolean; error: string | null }
export interface GitCommitResult {
  ok: boolean;
  hash: string;
  shortHash: string;
  subject: string;
  committedPaths: string[];
  error: string | null;
}
export interface GitDraftMessageResult {
  message: string;
  fileCount: number;
}

export interface ApiResults {
  workProducts: S.WorkProductServiceProduct[];
  workbenchFiles: WorkbenchFile[];
  workbenchFile: WorkbenchFile;
  workbenchPreview: WorkbenchPreview;
  workbenchVersions: WorkbenchVersion[];
  workbenchVersion: WorkbenchVersion;
  workbenchAnnotations: WorkbenchAnnotation[];
  workbenchAnnotation: WorkbenchAnnotation;
  workbenchOpen: WorkbenchOpenResult;
  workbenchChangeRequest: { prompt: string };
  productPreview: S.WorkProductServicePreview;
  skillDrafts: S.SkillDraftServiceDraft[];
  decideSkill: S.SkillDraftServiceDraft;
  recurring: S.RecurringScheduleServiceSeries[];
  createRecurring: S.RecurringScheduleServiceSeries;
  controlRecurring: S.RecurringScheduleServiceSeries;
  scheduleTimes: S.RecurringScheduleServiceTimes;
  templates: S.RecurringScheduleServiceTemplate[];
  saveTemplate: S.RecurringScheduleServiceTemplate;
  deleteTemplate: null;
  searchMessages: S.MessageLibraryPage;
  bookmarks: S.MessageLibraryBookmark[];
  saveBookmark: S.MessageLibraryBookmark;
  deleteBookmark: null;
  importBookmarks: S.MessageLibraryImportResult;
  notes: S.SessionNotesNote[];
  searchNotes: S.ProjectNotesControllerResult;
  createNote: S.SessionNotesNote;
  noteStatus: S.SessionNotesNote;
  promoteNote: S.SessionNotesPromotion;
  updateNote: S.SessionNotesNote;
  deleteNote: null;
  deliverySettings: S.DeliveryServiceSettings;
  saveDeliverySettings: S.DeliveryServiceSettings;
  deliveries: S.DeliveryServiceDelivery[];
  claimDelivery: S.DeliveryServiceDelivery;
  deliveryReceipt: ActionResponse;
  retryDelivery: S.DeliveryServiceDelivery;
  voiceSettings: S.VoiceServiceSettings;
  saveVoiceSettings: S.VoiceServiceSettings;
  transcribeVoice: { text: string };
  openBrowserWindow: ActionResponse & { url?: string; opened?: boolean; hint?: string };
  sessions: S.SessionControllerSessionResponse[];
  session: S.SessionControllerSessionResponse;
  createSession: S.SessionControllerSessionResponse;
  createSideChat: S.SessionControllerSessionResponse;
  renameSession: S.SessionControllerSessionResponse;
  sessionPreferences: S.SessionControllerSessionResponse;
  archiveSession: S.SessionControllerSessionResponse;
  switchWorkspace: S.SessionControllerSessionResponse;
  resend: S.SessionControllerMessageResponse;
  steer: ActionResponse;
  continueRun: S.SessionControllerMessageResponse;
  pauseRun: ActionResponse;
  cancelRun: ActionResponse;
  activeRun: S.SessionControllerActiveRunResponse;
  approval: ActionResponse;
  answerQuestion: { choice: string };
  pendingInteractions: PendingInteractionsSnapshot;
  history: HistoryPage;
  checkpoints: S.CheckpointControllerPreview;
  restoreCheckpoint: S.CheckpointControllerRestoreResponse;
  forkCheckpoint: S.CheckpointControllerForkResponse;
  task: S.TaskControlServiceTaskContract;
  saveTask: S.TaskControlServiceTaskContract;
  queue: QueueResponse;
  holdQueue: QueueResponse;
  updateQueueItem: QueueResponse;
  deleteQueueItem: QueueResponse;
  moveQueueItem: QueueResponse;
  steerQueueItem: QueueResponse;
  plan: S.SessionControllerPlanResponse;
  context: ContextStatus;
  compactContext: { changed?: boolean; message?: string };
  sandboxAction: S.SandboxStoreApplyReport;
  sandboxManifest: S.MultiAgentControllerSandboxManifestReport;
  /** 工作区看板：该目录下所有会话的当前状态（只读）。 */
  workspaceOverview: S.WorkspaceOverviewServiceOverview;
  /** 会话间协调：看板一次拉齐协调快照与工作区概览。 */
  coordinationBoard: C.CoordinationBoard;
  coordinationRequest: C.CoordinationRequest;
  coordinationClaim: C.CoordinationClaim;
  coordinationHandoff: C.CoordinationHandoff;
  resolveArbitration: ActionResponse;
  diff: S.ChangeServiceChangeReport;
  fileDiff: S.ChangeServiceFilePatch;
  restoreChanges: S.ChangeServiceRestoreReport;
  gitLog: GitCommitLogReport;
  gitBranches: GitBranchReport;
  gitStashes: GitStashReport;
  gitOperation: GitOperationResult;
  gitDraftMessage: GitDraftMessageResult;
  gitCommit: GitCommitResult;
  capabilities: S.CapabilitiesControllerCapabilitiesResponse;
  agentProfiles: S.AgentProfilesControllerProfilesResponse;
  mcpServers: S.McpConfigControllerServerPayload[];
  mcpAddServer: S.McpConfigControllerServerPayload;
  mcpImportServers: S.McpConfigControllerImportResponse;
  mcpProbeServer: S.McpConfigControllerProbeResponse;
  mcpDeleteServer: null;
  mcpTrust: S.McpTrustControllerTrustResponse;
  browserStatus: BrowserStatus;
  browserHandoffResume: { ok: boolean; resolved?: boolean; handoffs?: BrowserHandoff[]; error?: string };
  browserHandoffCancel: { ok: boolean; resolved?: boolean; handoffs?: BrowserHandoff[]; error?: string };
  browserLane: { ok: boolean; activeLane?: string; error?: string };
  browserStopControl: { ok: boolean; browserSessionId?: string; paused?: boolean; agentControlled?: boolean; error?: string };
  browserEvidence: BrowserEvidenceResponse;
  stopCommand: S.CommandTaskManagerTask;
  runChanges: S.ChangeServiceRunChangeReport & { summary?: string };
  rollbackRun: S.RunSnapshotServiceRollbackReport;
  branch: S.BranchControllerBranchResponse;
  files: S.SessionFilesControllerTreeResponse;
  workspaceTree: S.SessionFilesControllerTreeResponse;
  workspaceFile: UniversalFileResponse;
  filesFlat: S.SessionFilesControllerFlatResponse;
  workspaceFiles: S.SessionFilesControllerFlatResponse;
  file: UniversalFileResponse;
  projects: S.ProjectControllerProjectResponse[];
  createProject: S.ProjectControllerProjectResponse;
  renameProject: S.ProjectControllerProjectResponse;
  saveProjectFolders: S.ProjectControllerProjectResponse;
  saveProjectConstraints: S.ProjectControllerProjectResponse;
  readProjectAgents: S.ProjectControllerAgentsFile;
  deleteProject: null;
  worktrees: S.WorktreeControllerOpened;
  worktreeInfo: S.WorktreeControllerInfo;
  worktreeRemove: ActionResponse;
  worktreeHandoff: S.WorktreeControllerHanded;
  workspaceRoots: S.WorkspaceServiceRootEntry[];
  workspaceBrowse: S.WorkspaceServiceBrowseResult & { entries?: S.WorkspaceServiceDirectoryEntry[] };
  settings: S.SettingsControllerSettingsResponse;
  savePermissionMode: S.SettingsControllerSettingsResponse;
  permissionRules: S.PermissionRulesControllerRulesResponse;
  savePermissionRules: S.PermissionRulesControllerRulesResponse;
  saveModel: S.SettingsControllerSettingsResponse;
  clearModelKey: S.SettingsControllerSettingsResponse;
  modelProfiles: ModelProfilesView;
  createModelProfile: ModelProfilesView;
  updateModelProfile: ModelProfilesView;
  deleteModelProfile: ModelProfilesView;
  activateModelProfile: ModelProfilesView;
  setFallbackModelProfile: ModelProfilesView;
  modelRuntime: S.ModelRuntimeSettingsView;
  saveModelRuntime: S.ModelRuntimeSettingsView;
  probeModels: S.SettingsControllerModelsResponse;
  webSearchSettings: WebSearchSettingsView;
  saveWebSearchSettings: WebSearchSettingsView;
  clearWebSearchApiKey: WebSearchSettingsView;
  testWebSearch: WebSearchTestResponse;
  restartLocalWebSearch: WebSearchSettingsView;
  skills: S.SkillsControllerSkillsResponse;
  skillsPreview: S.SkillsControllerPreviewResponse;
  skillsInstall: S.SkillsControllerInstallResponse;
  memoryOverview: MemoryOverview;
  memoryDraftCount: MemoryDraftCount;
  memoryImportLegacy: MemoryImportResult;
  memoryApproveDraft: MemoryEntry;
  memoryDiscardDraft: null;
  memoryCreateEntry: MemoryEntry;
  memoryUpdateEntry: MemoryEntry;
  memoryDeleteEntry: null;
  usage: S.UsageServiceUsageReport;
  diagnostics: S.TaskDiagnosticsServiceDiagnostics;
  validation: S.DiagnosticsControllerValidationView;
  recovery: S.RecoveryReportServiceSnapshot;
  maintenanceStatus: S.MaintenanceControllerStatus;
  pluginCompat: S.PluginCompatServiceReport;
  backupNow: S.BackupServiceBackupReport;
  verifyBackup: S.BackupServiceVerificationReport;
  purgeNow: S.RetentionServicePurgeReport;
  results: S.ResultInboxServicePage;
  result: S.ResultInboxServiceDetail;
  resultReceipt: S.ResultInboxServiceItem;
  sessionResearch: S.ResultInboxServiceResearch[];
  scheduleHandoffPreview: S.GitWorktreesPreview;
  scheduleHandoff: S.ScheduledTask;
  schedules: S.ScheduledTask[];
  createSchedule: S.ScheduledTask;
  controlSchedule: S.ScheduledTask;
  metrics: S.MetricsControllerMetricsResponse;
  regenerate: S.SessionControllerMessageResponse;
  deleteSession: null;
}

/** 模板尚未绑定会话/时间；提交任务时这两项必须存在。验收自动化由服务端补齐。 */
export interface ScheduleDraft extends Omit<S.ScheduleServiceCreate, 'sourceSessionId' | 'runAt' | 'skill' | 'requirements'> {
  sourceSessionId: string | null; runAt?: string | null; skill: string | null;
  requirements: Pick<S.TaskControlServiceRequirements, 'constraints' | 'acceptance'>;
}
export type ScheduleInput = ScheduleDraft & { sourceSessionId: string; runAt: string };

export type MemoryTypeName = 'user' | 'feedback' | 'project' | 'reference';
export type MemoryScope = 'project' | 'user';

export interface MemoryDraft {
  id: string;
  type: MemoryTypeName;
  title: string;
  note: string;
  why: string;
  date: string;
  createdAt: string;
}

export interface MemoryEntry {
  slug: string;
  scope: MemoryScope;
  type: MemoryTypeName;
  title: string;
  description: string;
  body: string;
  updated: string;
}

export interface MemoryOverview {
  workspace: string;
  projectKey: string;
  projectRoot: string;
  storagePath: string;
  drafts: MemoryDraft[];
  projectEntries: MemoryEntry[];
  userEntries: MemoryEntry[];
  hasLegacyDrafts?: boolean;
}

export interface MemoryDraftCount {
  count: number;
}

export interface MemoryImportResult {
  imported: number;
  skipped: number;
  sourceFound: boolean;
}

export interface WebSearchProviderView {
  id: string;
  label: string;
  description: string;
  requiresApiKey: boolean;
  defaultEndpoint: string;
  apiEndpoint?: string;
  apiKeySet: boolean;
  apiKeyMasked: string;
}

export interface WebSearchSettingsView {
  provider: string;
  effectiveProvider: string;
  apiEndpoint: string;
  apiKeySet: boolean;
  apiKeyMasked: string;
  searxngEndpoint: string;
  fallbackEnabled: boolean;
  preferPilotSearch?: boolean;
  proxy: string;
  proxyNotice?: string;
  requestTimeoutSeconds: number;
  localEnabled: boolean;
  localManaged: boolean;
  localRunning: boolean;
  localPortOpen: boolean;
  localSettingsUpToDate?: boolean;
  localSettingsPath: string;
  localLogPath: string;
  localMessage: string;
  nativeWebSearchActive?: boolean;
  nativeWebSearchSummary?: string;
  providers: WebSearchProviderView[];
}

export interface WebSearchSettingsUpdate {
  provider?: string;
  apiKey?: string;
  clearApiKey?: boolean;
  apiEndpoint?: string;
  searxngEndpoint?: string;
  fallbackEnabled?: boolean;
  preferPilotSearch?: boolean;
  proxy?: string;
}

export interface WebSearchHit {
  title: string;
  url: string;
  snippet: string;
  publishedDate: string;
  engines: string[];
  score: number;
}

export interface WebSearchTestRequest extends WebSearchSettingsUpdate {
  query?: string;
  count?: number;
  category?: string;
}

export interface WebSearchTestResponse {
  ok: boolean;
  sourceId: string;
  sourceLabel: string;
  triedSources: string[];
  hits: WebSearchHit[];
  answers: string[];
  warnings: string[];
  error?: string | null;
  elapsedMs: number;
}
