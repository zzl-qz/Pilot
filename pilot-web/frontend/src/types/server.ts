/** 对照项目 Java record 核实的 HTTP 数据结构；运行时 JSON 和旧记录仍在各入口处理。 */

/** ArtifactDiff.java / ArtifactDiff */
export interface ArtifactDiff {
  previousCandidate: string;
  currentCandidate: string;
  added: (string)[];
  removed: (string)[];
  modified: (string)[];
  unchanged: (string)[];
}

/** ArtifactFile.java / ArtifactFile */
export interface ArtifactFile {
  path: string;
  baselineHash: string;
  contentHash: string;
  size: number;
}

/** ArtifactManifest.java / ArtifactManifest */
export interface ArtifactManifest {
  manifestId: string;
  sessionId: string;
  sandboxId: string;
  taskId: string;
  baseCommit: string;
  environmentFingerprint: string;
  files: (ArtifactFile)[];
  dependencyManifests: (string)[];
  dependencyHashes: (string)[];
  revision: number;
  candidateHash: string;
  createdAt: string;
}

/** BackupService.java / BackupReport */
export interface BackupServiceBackupReport {
  ok: boolean;
  directory: string;
  files: number;
  bytes: number;
  message: string;
  createdAt: string;
}

/** BackupService.java / Status */
export interface BackupServiceStatus {
  dataDir: string;
  databaseBytes: number;
  backups: number;
  lastBackupAt: string;
  storage: StorageHealthSnapshot;
  includedDirectories: (string)[];
}

/** BranchController.java / BranchResponse */
export interface BranchControllerBranchResponse {
  name: string;
  workspace: string;
}

/** CapabilitiesController.java / CapabilitiesResponse */
export interface CapabilitiesControllerCapabilitiesResponse {
  workspace: string;
  model: string;
  permissionMode: string;
  tools: (CapabilitiesControllerToolPayload)[];
  mcpServers: (CapabilitiesControllerMcpServerPayload)[];
  localCapabilities: (LocalCapabilityServiceLocalCapability)[];
}

/** CapabilitiesController.java / McpServerPayload */
export interface CapabilitiesControllerMcpServerPayload {
  name: string;
  command: string;
  usable: boolean;
  running: boolean;
  failure: string;
  unsupported: string;
  tools: (string)[];
}

/** CapabilitiesController.java / ToolPayload */
export interface CapabilitiesControllerToolPayload {
  name: string;
  description: string;
  readOnly: boolean;
  available: boolean;
  reason: string;
}

/** ChangeService.java / ChangeReport */
export interface ChangeServiceChangeReport {
  workspace: string;
  gitRepository: boolean;
  files: (ChangeServiceFileChange)[];
  diff: string;
  truncated: boolean;
  error: string;
}

/** ChangeService.java / FileChange */
export interface ChangeServiceFileChange {
  status: string;
  path: string;
  added: number;
  removed: number;
}

/** ChangeService.java / FilePatch */
export interface ChangeServiceFilePatch {
  path: string;
  status: string;
  added: number;
  removed: number;
  diff: string;
  truncated: boolean;
}

/** ChangeService.java / RestoreFailure */
export interface ChangeServiceRestoreFailure {
  path: string;
  reason: string;
}

/** ChangeService.java / RestoreReport */
export interface ChangeServiceRestoreReport {
  restored: (string)[];
  deleted: (string)[];
  failed: (ChangeServiceRestoreFailure)[];
  error: string;
}

/** ChangeService.java / RunChangeReport */
export interface ChangeServiceRunChangeReport {
  runId: string;
  workspace: string;
  gitRepository: boolean;
  files: (ChangeServiceRunFileChange)[];
  added: number;
  removed: number;
  truncated: boolean;
  error: string;
}

/** ChangeService.java / RunFileChange */
export interface ChangeServiceRunFileChange {
  path: string;
  status: string;
  added: number;
  removed: number;
  diff: string;
}

/** CheckpointController.java / ForkResponse */
export interface CheckpointControllerForkResponse {
  sessionId: string;
  title: string;
  workspace: string;
  input: string;
}

/** CheckpointController.java / Preview */
export interface CheckpointControllerPreview {
  sequence: number;
  revision: number;
  input: string;
  runs: number;
  conversationAvailable: boolean;
  code: RunSnapshotServiceRestorePreview;
}

/** CheckpointController.java / RestoreResponse */
export interface CheckpointControllerRestoreResponse {
  applied: boolean;
  input: string;
  code: RunSnapshotServiceRollbackReport;
}

/** CommandChangeTracker.java / Handle */
export interface CommandChangeTrackerHandle {
  runId: string;
  id: string;
}

/** CommandTaskManager.java / LogChunk */
export interface CommandTaskManagerLogChunk {
  taskId: string;
  offset: number;
  nextOffset: number;
  text: string;
  eof: boolean;
  truncated: boolean;
}

/** CommandTaskManager.java / Task */
export interface CommandTaskManagerTask {
  taskId: string;
  sessionId: string;
  command: string;
  workdir: string;
  status: string;
  pid: number;
  exitCode: number;
  startedAt: string;
  finishedAt: string;
  background: boolean;
  timeoutSeconds: number;
  reason: string;
  changeTracking: CommandChangeTrackerHandle;
}

/** DeliveryService.java / Delivery */
export interface DeliveryServiceDelivery {
  id: string;
  version: number;
  resultId: string;
  revision: string;
  channel: string;
  target: string;
  title: string;
  summary: string;
  state: string;
  attempts: number;
  nextAt: string;
  lease: string;
  error: string;
  createdAt: string;
  outcome: string;
}

/** DeliveryService.java / Settings */
export interface DeliveryServiceSettings {
  version: number;
  desktop: boolean;
  webhookUrl: string;
  desktopSince: string;
  webhookSince: string;
}

/** DiagnosticsController.java / ValidationView */
export interface DiagnosticsControllerValidationView {
  report: ValidationLedgerReport;
  recent: (ValidationEvidence)[];
}

/** GitWorktrees.java / Preview */
export interface GitWorktreesPreview {
  base: string;
  path: string;
  commit: string;
  files: (string)[];
  diff: string;
  truncated: boolean;
  revision: string;
  conflict: string;
}

/** LocalCapabilityService.java / LocalCapability */
export interface LocalCapabilityServiceLocalCapability {
  id: string;
  name: string;
  available: boolean;
  executable: string;
  reason: string;
  purpose: string;
  preparationAdvice: string;
}

/** MaintenanceController.java / Status */
export interface MaintenanceControllerStatus {
  backup: BackupServiceStatus;
  retentionDays: number;
  storage: StorageHealthSnapshot;
  executionIsolation: string;
  isolated: boolean;
  degraded: boolean;
  degradedReason: string;
}

/** MessageLibrary.java / Bookmark */
export interface MessageLibraryBookmark {
  sequence: number;
  title: string;
  preview: string;
  key: string;
}

/** MessageLibrary.java / Hit */
export interface MessageLibraryHit {
  sessionId: string;
  title: string;
  sequence: number;
  anchor: number;
  role: string;
  text: string;
  bookmarked: boolean;
}

/** MessageLibrary.java / ImportResult */
export interface MessageLibraryImportResult {
  bookmarks: (MessageLibraryBookmark)[];
  skipped: (number)[];
}

/** MessageLibrary.java / Page */
export interface MessageLibraryPage {
  items: (MessageLibraryHit)[];
  hasMore: boolean;
  nextOffset: number;
}

/** MetricsController.java / MetricsResponse */
export interface MetricsControllerMetricsResponse {
  metrics: RunMetricsSnapshot;
  retentionDays: number;
}

/** ModelCapabilityDiscovery.java / Report */
export interface ModelCapabilityDiscoveryReport {
  state: string;
  protocol: string;
  capabilities: (string)[];
  checkedAt: number;
  expiresAt: number;
  message: string;
  probeRequests: number;
  inputTokens: number;
  outputTokens: number;
}

/** ModelPricing.java / ModelPricing */
export interface ModelPricing {
  currency: string;
  input: number;
  cachedInput: number;
  cacheWrite: number;
  output: number;
  source: string;
}

/** ModelProfile.java / ModelProfile */
export interface ModelProfile {
  provider: string;
  model: string;
  cacheMode: string;
  cacheTtl: string;
  instructionMessages: boolean;
  streamUsage: boolean;
  cacheKey: boolean;
  compactionMode: string;
  pricing: ModelPricing;
}

/** ModelRuntimeController.java / Update */
export interface ModelRuntimeControllerUpdate {
  model: string;
  endpoint: string;
  options: ModelRuntimeSettingsOptions;
}

/** ModelRuntimeSettings.java / Options */
export interface ModelRuntimeSettingsOptions {
  provider: string;
  cacheMode: string;
  cacheTtl: string;
  instructionMessages: boolean;
  streamUsage: boolean;
  compactionMode: string;
  webSearchMode: string;
  pricing: ModelPricing;
}

/** ModelRuntimeSettings.java / View */
export interface ModelRuntimeSettingsView {
  model: string;
  endpoint: string;
  options: ModelRuntimeSettingsOptions;
  effective: ModelProfile;
  nativeCapabilities: (string)[];
  webSearchSource: string;
  capabilityDiscovery: ModelCapabilityDiscoveryReport;
}

/** MultiAgentController.java / SandboxManifestReport */
export interface MultiAgentControllerSandboxManifestReport {
  manifest: ArtifactManifest;
  diff: ArtifactDiff;
  verification: SandboxStoreVerificationState;
  dependencies: (string)[];
  reviewHash: string;
  disposition: string;
  recovery: WorkspaceApplyJournalState;
}

/** PlanItem.java / PlanItem */
export interface PlanItem {
  step: string;
  status: string;
}

/** ProjectController.java / ProjectResponse */
export interface ProjectControllerProjectResponse {
  id: string;
  name: string;
  folders: (string)[];
  baseCommit: string;
  sessionCount: number;
  blankSessionId: string;
  blankCount: number;
  createdAt: string;
  updatedAt: string;
  lastActiveAt: string;
}

/** RecoveryReportService.java / Snapshot */
export interface RecoveryReportServiceSnapshot {
  recoveredAt: string;
  instanceId: string;
  entries: (RunStateTrackerRecoveryEntry)[];
  note: string;
  completed: boolean;
}

/** RecurringScheduleService.java / Create */
export interface RecurringScheduleServiceCreate {
  task: ScheduleServiceCreate;
  cron: string;
  misfire: string;
}

/** RecurringScheduleService.java / Series */
export interface RecurringScheduleServiceSeries {
  id: string;
  name: string;
  task: ScheduleServiceCreate;
  cron: string;
  timezone: string;
  misfire: string;
  state: string;
  nextAt: string;
  activeId: string;
  occurrences: number;
  message: string;
  version: number;
  sourceWorkspace: string;
}

/** RecurringScheduleService.java / Template */
export interface RecurringScheduleServiceTemplate {
  id: string;
  name: string;
  version: number;
  task: ScheduleServiceCreate;
  updatedAt: string;
}

/** RecurringScheduleService.java / Times */
export interface RecurringScheduleServiceTimes {
  runAt: string;
  next: (string)[];
  timezone: string;
}

/** RequestTotals.java / Summary */
export interface RequestTotalsSummary {
  requests: number;
  observedCacheRequests: number;
  observedCacheInputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  pricedRequests: number;
  costs: Record<string, number>;
  exact: boolean;
}

/** ResultInboxService.java / Detail */
export interface ResultInboxServiceDetail {
  item: ResultInboxServiceItem;
  research: (ResultInboxServiceResearch)[];
  verification: string;
  acceptance: (TaskControlServiceAcceptance)[];
  verificationDetail: string;
}

/** ResultInboxService.java / Item */
export interface ResultInboxServiceItem {
  id: string;
  revision: string;
  sessionId: string;
  title: string;
  source: string;
  state: string;
  summary: string;
  at: string;
  runId: string;
  scheduleId: string;
  read: boolean;
  handled: boolean;
  changedFiles: (string)[];
  checks: (string)[];
  openSteps: (string)[];
  workspace: ScheduledTaskWorkspace;
}

/** ResultInboxService.java / Page */
export interface ResultInboxServicePage {
  items: (ResultInboxServiceItem)[];
  truncated: boolean;
}

/** ResultInboxService.java / Research */
export interface ResultInboxServiceResearch {
  id: string;
  sources: (string)[];
  decision: string;
  license: string;
  sourceEvidence: (string)[];
  observedAt: number;
  structured: boolean;
  raw: string;
}

/** RetentionService.java / PurgeReport */
export interface RetentionServicePurgeReport {
  events: number;
  snapshotRuns: number;
  traces: number;
  retentionDays: number;
  backups: number;
  validationFiles: number;
  backupDays: number;
  validationDays: number;
}

/** RunMetrics.java / Snapshot */
export interface RunMetricsSnapshot {
  counters: Record<string, number>;
  runs: number;
  totalRunMs: number;
  averageRunMs: number;
}

/** RunSnapshotService.java / RestorePreview */
export interface RunSnapshotServiceRestorePreview {
  files: (string)[];
  conflicts: (string)[];
  unavailable: (string)[];
  ready: boolean;
}

/** RunSnapshotService.java / RollbackReport */
export interface RunSnapshotServiceRollbackReport {
  runId: string;
  workspace: string;
  restored: (string)[];
  deleted: (string)[];
  failed: (string)[];
  conflicts: (string)[];
}

/** RunStateTracker.java / RecoveryEntry */
export interface RunStateTrackerRecoveryEntry {
  runId: string;
  sessionId: string;
  step: string;
  lastSuccessfulStep: string;
  nextAction: string;
  workspace: string;
  leaseState: string;
  updatedAt: string;
}

/** SandboxStore.java / ApplyReport */
export interface SandboxStoreApplyReport {
  ok: boolean;
  written: (string)[];
  skipped: (string)[];
  failed: (string)[];
  message: string;
}

/** SandboxStore.java / VerificationState */
export interface SandboxStoreVerificationState {
  status: string;
  current: boolean;
  candidateHash: string;
  reason: string;
}

/** ScheduleService.java / Create */
export interface ScheduleServiceCreate {
  name: string;
  sourceSessionId: string;
  content: string;
  runAt: string;
  timezone: string;
  requirements: TaskControlServiceRequirements;
  skill: string;
  fullAccess: boolean;
  isolated: boolean;
}

/** ScheduledTask.java / Invocation */
export interface ScheduledTaskInvocation {
  runId: string;
  startedAt: string;
  status: string;
}

/** ScheduledTask.java / ScheduledTask */
export interface ScheduledTask {
  id: string;
  name: string;
  sessionId: string;
  taskId: string;
  content: string;
  requirements: TaskControlServiceRequirements;
  skill: string;
  runAt: string;
  timezone: string;
  state: string;
  runId: string;
  attempts: number;
  recoveries: number;
  deadline: string;
  message: string;
  history: (ScheduledTaskInvocation)[];
  version: number;
  workspace: ScheduledTaskWorkspace;
  endedAt: string;
}

/** ScheduledTask.java / Workspace */
export interface ScheduledTaskWorkspace {
  mode: string;
  path: string;
  base: string;
  branch: string;
  commit: string;
  handedOffAt: string;
}

/** SessionController.java / ActiveRunResponse */
export interface SessionControllerActiveRunResponse {
  sessionId: string;
  runId: string;
}

/** SessionController.java / MessageResponse */
export interface SessionControllerMessageResponse {
  sessionId: string;
  runId: string;
}

/** SessionController.java / PlanResponse */
export interface SessionControllerPlanResponse {
  plan: (PlanItem)[];
}

/** SessionController.java / SessionResponse */
export interface SessionControllerSessionResponse {
  id: string;
  sessionId: string;
  title: string;
  createdAt: string;
  status: string;
  workspace: string;
  projectId: string;
  isBlank: boolean;
  archived: boolean;
  eventCount: number;
  parentSessionId: string;
  updatedAt: string;
  pinned: boolean;
  unread: boolean;
  titleManual: boolean;
}

/** SessionFilesController.java / Entry */
export interface SessionFilesControllerEntry {
  name: string;
  path: string;
  directory: boolean;
  size: number;
}

/** SessionFilesController.java / FileResponse */
export interface SessionFilesControllerFileResponse {
  path: string;
  size: number;
  binary: boolean;
  truncated: boolean;
  content: string;
}

/** SessionFilesController.java / FlatResponse */
export interface SessionFilesControllerFlatResponse {
  files: (string)[];
  truncated: boolean;
}

/** SessionFilesController.java / TreeResponse */
export interface SessionFilesControllerTreeResponse {
  path: string;
  parent: string;
  entries: (SessionFilesControllerEntry)[];
  truncated: boolean;
}

/** SettingsController.java / ModelRequest */
export interface SettingsControllerModelRequest {
  endpoint: string;
  model: string;
  apiKey: string;
  clearApiKey: boolean;
}

/** SettingsController.java / ModelsRequest */
export interface SettingsControllerModelsRequest {
  endpoint: string;
  apiKey: string;
}

/** SettingsController.java / ModelsResponse */
export interface SettingsControllerModelsResponse {
  models: (string)[];
}

/** SettingsController.java / SettingsResponse */
export interface SettingsControllerSettingsResponse {
  permissionMode: string;
  permissionModes: (string)[];
  model: string;
  modelEndpoint: string;
  modelApiKeySet: boolean;
  modelApiKeyMasked: string;
  modelOptions: (string)[];
}

/** SkillDraftService.java / Draft */
export interface SkillDraftServiceDraft {
  id: string;
  sessionId: string;
  runId: string;
  workspace: string;
  name: string;
  description: string;
  body: string;
  evidence: string;
  previousContent: string;
  baseHash: string;
  installedHash: string;
  status: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** SkillProblem.java / SkillProblem */
export interface SkillProblem {
  name: string;
  path: string;
  reason: string;
}

/** SkillsController.java / CandidatePayload */
export interface SkillsControllerCandidatePayload {
  name: string;
  description: string;
  command: string;
  valid: boolean;
  reason: string;
}

/** SkillsController.java / InstallRequest */
export interface SkillsControllerInstallRequest {
  token: string;
  skills: (string)[];
  target: string;
  sessionId: string;
  overwrite: boolean;
}

/** SkillsController.java / InstallResponse */
export interface SkillsControllerInstallResponse {
  results: (SkillsControllerOutcomePayload)[];
}

/** SkillsController.java / OutcomePayload */
export interface SkillsControllerOutcomePayload {
  name: string;
  status: string;
  reason: string;
}

/** SkillsController.java / PreviewRequest */
export interface SkillsControllerPreviewRequest {
  source: string;
  sessionId: string;
}

/** SkillsController.java / PreviewResponse */
export interface SkillsControllerPreviewResponse {
  token: string;
  kind: string;
  message: string;
  candidates: (SkillsControllerCandidatePayload)[];
}

/** SkillsController.java / SkillPayload */
export interface SkillsControllerSkillPayload {
  name: string;
  description: string;
  command: string;
  argumentHint: string;
  allowedTools: (string)[];
  source: string;
  directory: string;
}

/** SkillsController.java / SkillsResponse */
export interface SkillsControllerSkillsResponse {
  available: (SkillsControllerSkillPayload)[];
  invalid: (SkillProblem)[];
  projectRoot: string;
  userRoot: string;
}

/** StorageHealth.java / Issue */
export interface StorageHealthIssue {
  area: string;
  reason: string;
  at: string;
}

/** StorageHealth.java / Snapshot */
export interface StorageHealthSnapshot {
  healthy: boolean;
  issues: (StorageHealthIssue)[];
  checkedAt: string;
}

/** TaskControlService.java / Acceptance */
export interface TaskControlServiceAcceptance {
  text: string;
  checked: boolean;
  evidence: string;
}

/** TaskControlService.java / Automation */
export interface TaskControlServiceAutomation {
  scheduleId: string;
  fullAccess: boolean;
  requireResearch: boolean;
}

/** TaskControlService.java / Criterion */
export interface TaskControlServiceCriterion {
  outcome: string;
  verification: string;
  required: boolean;
}

/** TaskControlService.java / Evidence */
export interface TaskControlServiceEvidence {
  id: string;
  tool: string;
  target: string;
  result: string;
  sourceVersion: string;
  check: string;
  status: string;
  startVersion: string;
  endVersion: string;
  observedAtEpochMillis: number;
  operationKey: string;
}

/** TaskControlService.java / Execution */
export interface TaskControlServiceExecution {
  mode: string;
  reason: string;
  criteria: (TaskControlServiceCriterion)[];
  unknowns: (string)[];
  requests: number;
  activeMillis: number;
  evidence: (TaskControlServiceEvidence)[];
  phase: string;
  gap: string;
  idleReviews: number;
  progress: string;
  requestAllowance: number;
  timeAllowanceMillis: number;
  assessmentVersion: number;
  usage: TaskControlServiceUsage;
  changedPaths: (string)[];
  nextAction: string;
  expectedObservation: string;
  budgetSegments: number;
}

/** TaskControlService.java / Requirements */
export interface TaskControlServiceRequirements {
  constraints: (string)[];
  acceptance: (TaskControlServiceAcceptance)[];
  automation: TaskControlServiceAutomation;
}

/** TaskControlService.java / TaskContract */
export interface TaskControlServiceTaskContract {
  taskId: string;
  originalGoal: string;
  constraints: (string)[];
  acceptance: (TaskControlServiceAcceptance)[];
  corrections: (string)[];
  status: string;
  execution: TaskControlServiceExecution;
  automation: TaskControlServiceAutomation;
}

/** TaskControlService.java / TaskSnapshot */
export interface TaskControlServiceTaskSnapshot {
  task: TaskControlServiceTaskContract;
}

/** TaskControlService.java / Usage */
export interface TaskControlServiceUsage {
  inputTokens: number;
  outputTokens: number;
  reasoningTokens: number;
  inputAllowance: number;
  outputAllowance: number;
  continuationRounds: number;
}

/** TaskDiagnosticsService.java / CommandView */
export interface TaskDiagnosticsServiceCommandView {
  taskId: string;
  command: string;
  status: string;
  exitCode: number;
  runningSeconds: number;
}

/** TaskDiagnosticsService.java / Diagnostics */
export interface TaskDiagnosticsServiceDiagnostics {
  sessionId: string;
  sessionStatus: string;
  activeRunId: string;
  instanceId: string;
  phase: string;
  step: number;
  lastSuccessfulStep: string;
  nextAction: string;
  leaseState: string;
  retryAttempt: number;
  retryDelayMs: number;
  retryReason: string;
  elapsedSeconds: number;
  modelRequests: number;
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
  usageExact: boolean;
  lastToolSummary: string;
  commands: (TaskDiagnosticsServiceCommandView)[];
  validation: TaskDiagnosticsServiceValidationView;
  recovery: TaskDiagnosticsServiceRecoveryView;
  storageHealth: string;
  usageDetails: RequestTotalsSummary;
}

/** TaskDiagnosticsService.java / RecoveryView */
export interface TaskDiagnosticsServiceRecoveryView {
  recoveredAt: string;
  note: string;
  count: number;
  thisRunStartupState: string;
}

/** TaskDiagnosticsService.java / ValidationView */
export interface TaskDiagnosticsServiceValidationView {
  covered: boolean;
  reason: string;
  candidateHash: string;
  status: string;
  checks: (string)[];
  recordedAt: string;
  runId: string;
}

/** UsageService.java / DayTotal */
export interface UsageServiceDayTotal {
  date: string;
  requests: number;
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
}

/** UsageService.java / SessionTotal */
export interface UsageServiceSessionTotal {
  sessionId: string;
  title: string;
  requests: number;
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
}

/** UsageService.java / UsageReport */
export interface UsageServiceUsageReport {
  days: number;
  daily: (UsageServiceDayTotal)[];
  sessions: (UsageServiceSessionTotal)[];
  totalRequests: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCachedInputTokens: number;
  details: RequestTotalsSummary;
}

/** ValidationEvidence.java / ValidationEvidence */
export interface ValidationEvidence {
  id: string;
  sessionId: string;
  runId: string;
  candidateHash: string;
  files: (string)[];
  checks: (string)[];
  status: string;
  environmentFingerprint: string;
  source: string;
  recordedAt: string;
}

/** ValidationLedger.java / Report */
export interface ValidationLedgerReport {
  current: boolean;
  reason: string;
  currentCandidateHash: string;
  latest: ValidationEvidence;
  recent: (ValidationEvidence)[];
}

/** VoiceService.java / Settings */
export interface VoiceServiceSettings {
  endpoint: string;
  model: string;
  apiKeySet: boolean;
  effectiveEndpoint: string;
}

/** VoiceService.java / Update */
export interface VoiceServiceUpdate {
  endpoint: string;
  model: string;
  apiKey: string;
  clearApiKey: boolean;
}

/** WorkProductService.java / Preview */
export interface WorkProductServicePreview {
  format: string;
  text: string;
  truncated: boolean;
  notice: string;
}

/** WorkProductService.java / Product */
export interface WorkProductServiceProduct {
  id: string;
  sessionId: string;
  runId: string;
  title: string;
  summary: string;
  filename: string;
  sourcePath: string;
  mediaType: string;
  bytes: number;
  sha256: string;
  sources: (WorkProductServiceSource)[];
  createdAt: string;
}

/** WorkProductService.java / Source */
export interface WorkProductServiceSource {
  url: string;
  observedRead: boolean;
  callId: string;
}

/** WorkspaceApplyJournal.java / State */
export interface WorkspaceApplyJournalState {
  operationId: string;
  phase: string;
  sessionId: string;
  sandboxId: string;
  paths: (string)[];
}

/** WorkspaceService.java / BrowseResult */
export interface WorkspaceServiceBrowseResult {
  path: string;
  name: string;
  parent: string;
  gitRepository: boolean;
  directories: (WorkspaceServiceDirectoryEntry)[];
  truncated: boolean;
}

/** WorkspaceService.java / DirectoryEntry */
export interface WorkspaceServiceDirectoryEntry {
  name: string;
  path: string;
}

/** WorkspaceService.java / RootEntry */
export interface WorkspaceServiceRootEntry {
  path: string;
  label: string;
}

/** WorktreeController.java / Handed */
export interface WorktreeControllerHanded {
  sessionId: string;
  workspace: string;
  files: number;
  note: string;
}

/** WorktreeController.java / Info */
export interface WorktreeControllerInfo {
  worktree: boolean;
  id: string;
  path: string;
  base: string;
  branch: string;
  commit: string;
}

/** WorktreeController.java / Opened */
export interface WorktreeControllerOpened {
  sessionId: string;
  title: string;
  path: string;
  base: string;
  branch: string;
  commit: string;
}
