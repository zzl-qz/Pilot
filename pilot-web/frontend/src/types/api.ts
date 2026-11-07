import type * as S from './server.js';
import type { ContextStatus, QueueResponse } from './runtime.js';
import type { DraftAttachment } from './input.js';

import type { EventEnvelope } from './events.js';
export type { EventEnvelope } from './events.js';
export interface HistoryPage { events: EventEnvelope[]; hasMore: boolean; total?: number }
export interface ActionResponse { ok?: boolean; message?: string; error?: string }
export interface BrowserStatus { enabled: boolean; capable: boolean; available: boolean; reason?: string; paused?: boolean; headless?: boolean }
export interface InputMessage { content: string; attachments?: Pick<DraftAttachment, 'name' | 'mimeType' | 'dataBase64'>[]; imagePositions?: number[]; skill?: string }
export type Update<T> = { [K in keyof T]?: T[K] extends readonly unknown[] ? T[K] : T[K] extends object ? Update<T[K]> : T[K] | null };
/** 产物工作台：人直接编辑的可编辑源、版本与派生产物，全程不经过模型。 */
export interface StudioDocument {
  id: string; sessionId: string; title: string; kind: string; spec: string; specHash: string;
  version: number; createdBy: string; createdAt: string; updatedAt: string;
}
export interface StudioVersion { version: number; spec: string; specHash: string; createdAt: string; createdBy: string }
export interface StudioArtifact {
  id: string; documentId: string; documentVersion: number; format: string; filename: string;
  bytes: number; sha256: string; verification: string; createdAt: string;
}
export interface StudioRenderResult { document: StudioDocument; artifacts: StudioArtifact[] }

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
  studioDocuments: StudioDocument[];
  studioDocument: StudioDocument;
  studioCreate: StudioDocument;
  studioSave: StudioDocument;
  studioImport: StudioDocument;
  studioVersions: StudioVersion[];
  studioRollback: StudioDocument;
  studioRender: StudioRenderResult;
  studioArtifacts: StudioArtifact[];
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
  history: HistoryPage;
  checkpoints: S.CheckpointControllerPreview;
  restoreCheckpoint: S.CheckpointControllerRestoreResponse;
  forkCheckpoint: S.CheckpointControllerForkResponse;
  task: S.TaskControlServiceTaskContract;
  saveTask: S.TaskControlServiceTaskContract;
  queue: QueueResponse;
  updateQueueItem: QueueResponse;
  deleteQueueItem: QueueResponse;
  moveQueueItem: QueueResponse;
  steerQueueItem: QueueResponse;
  plan: S.SessionControllerPlanResponse;
  context: ContextStatus;
  compactContext: { changed?: boolean; message?: string };
  sandboxAction: S.SandboxStoreApplyReport;
  sandboxManifest: S.MultiAgentControllerSandboxManifestReport;
  resolveArbitration: ActionResponse;
  diff: S.ChangeServiceChangeReport;
  fileDiff: S.ChangeServiceFilePatch;
  restoreChanges: S.ChangeServiceRestoreReport;
  capabilities: S.CapabilitiesControllerCapabilitiesResponse;
  browserStatus: BrowserStatus;
  stopCommand: S.CommandTaskManagerTask;
  runChanges: S.ChangeServiceRunChangeReport & { summary?: string };
  rollbackRun: S.RunSnapshotServiceRollbackReport;
  branch: S.BranchControllerBranchResponse;
  files: S.SessionFilesControllerTreeResponse;
  workspaceTree: S.SessionFilesControllerTreeResponse;
  workspaceFile: S.SessionFilesControllerFileResponse;
  filesFlat: S.SessionFilesControllerFlatResponse;
  workspaceFiles: S.SessionFilesControllerFlatResponse;
  file: S.SessionFilesControllerFileResponse;
  projects: S.ProjectControllerProjectResponse[];
  createProject: S.ProjectControllerProjectResponse;
  renameProject: S.ProjectControllerProjectResponse;
  saveProjectFolders: S.ProjectControllerProjectResponse;
  deleteProject: null;
  worktrees: S.WorktreeControllerOpened;
  worktreeInfo: S.WorktreeControllerInfo;
  worktreeRemove: ActionResponse;
  worktreeHandoff: S.WorktreeControllerHanded;
  workspaceRoots: S.WorkspaceServiceRootEntry[];
  workspaceBrowse: S.WorkspaceServiceBrowseResult & { entries?: S.WorkspaceServiceDirectoryEntry[] };
  settings: S.SettingsControllerSettingsResponse;
  savePermissionMode: S.SettingsControllerSettingsResponse;
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
  backupNow: S.BackupServiceBackupReport;
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
  regenerate: S.SessionControllerSessionResponse;
  deleteSession: null;
}

/** 模板尚未绑定会话/时间；提交任务时这两项必须存在。验收自动化由服务端补齐。 */
export interface ScheduleDraft extends Omit<S.ScheduleServiceCreate, 'sourceSessionId' | 'runAt' | 'skill' | 'requirements'> {
  sourceSessionId: string | null; runAt?: string | null; skill: string | null;
  requirements: Pick<S.TaskControlServiceRequirements, 'constraints' | 'acceptance'>;
}
export type ScheduleInput = ScheduleDraft & { sourceSessionId: string; runAt: string };
