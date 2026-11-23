import type * as S from './server.js';
import type * as C from './coordination.js';
import type { ContextStatus, QueueResponse } from './runtime.js';
import type { DraftAttachment } from './input.js';

import type { EventEnvelope } from './events.js';
export type { EventEnvelope } from './events.js';
export interface HistoryPage { events: EventEnvelope[]; hasMore: boolean; total?: number; historyRevision?: number }
export interface ActionResponse { ok?: boolean; message?: string; error?: string }
export interface BrowserStatus { enabled: boolean; capable: boolean; available: boolean; reason?: string; paused?: boolean; headless?: boolean }
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
  capabilities: S.CapabilitiesControllerCapabilitiesResponse;
  mcpServers: S.McpConfigControllerServerPayload[];
  mcpAddServer: S.McpConfigControllerServerPayload;
  mcpImportServers: S.McpConfigControllerImportResponse;
  mcpProbeServer: S.McpConfigControllerProbeResponse;
  mcpDeleteServer: null;
  mcpTrust: S.McpTrustControllerTrustResponse;
  browserStatus: BrowserStatus;
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
  modelRuntime: S.ModelRuntimeSettingsView;
  saveModelRuntime: S.ModelRuntimeSettingsView;
  probeModels: S.SettingsControllerModelsResponse;
  skills: S.SkillsControllerSkillsResponse;
  skillsPreview: S.SkillsControllerPreviewResponse;
  skillsInstall: S.SkillsControllerInstallResponse;
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
