import type { ApiResults, InputMessage, Update, ScheduleDraft, ScheduleInput } from './types/api.js';
import type * as S from './types/server.js';
import { errorInfo } from './lib/errors.js';
import { voiceFilename } from './lib/voice-input.js';
/**
 * 统一请求入口：带超时与取消（与旧版 apiFetch 行为一致）。
 * 后端卡住或网络断开时抛可读错误，界面据此提示。
 */
const NATIVE_FETCH = window.fetch.bind(window);
const REQUEST_TIMEOUT_MS = 20000;

export async function apiFetch(input: RequestInfo | URL, init: RequestInit = {}, timeoutMs = REQUEST_TIMEOUT_MS) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (init.signal?.aborted) controller.abort();
  init.signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await NATIVE_FETCH(input, { ...init, signal: controller.signal });
  } catch (error) {
    if (init.signal?.aborted) throw new DOMException('请求已取消', 'AbortError');
    if (errorInfo(error).name === 'AbortError') {
      throw new Error(`请求超时（${Math.round(timeoutMs / 1000)} 秒）`);
    }
    throw error;
  } finally {
    clearTimeout(timer);
    init.signal?.removeEventListener('abort', abort);
  }
}

export async function apiJson<T = unknown>(url: RequestInfo | URL, init?: RequestInit, timeoutMs?: number): Promise<T | null> {
  const response = await apiFetch(url, init, timeoutMs);
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      const body = await response.json() as { message?: unknown; error?: unknown } | null;
      // Spring 的错误响应体固定带 error（HTTP 状态短语，如「Bad Gateway」），
      // 真正的原因在 message 里。优先 message，否则界面只会显示 "Bad Gateway" 这类英文短语。
      const detail = typeof body?.message === 'string' ? body.message.trim() : '';
      const fallback = typeof body?.error === 'string' ? body.error.trim() : '';
      message = detail && detail !== 'No message available' ? detail : fallback || message;
    } catch { /* 保持 HTTP 状态码文案 */ }
    const error = new ApiError(message, response.status);
    throw error;
  }
  // 204 / 空响应没有 JSON 正文：直接 return response.json() 会抛「Unexpected end of JSON input」，
  // 让删除会话、删除项目这类「成功但无返回体」的接口看起来全部失败
  if (response.status === 204 || response.headers?.get?.('content-length') === '0') {
    return null;
  }
  if (typeof response.text === 'function') {
    const text = await response.text();
    return text ? JSON.parse(text) as T : null;
  }
  return response.json() as Promise<T>;
}

export class ApiError extends Error { readonly status: number; constructor(message: string, status: number) { super(message); this.status = status; } }

const jsonBody = (method: string, body: unknown) => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
export const api = {
  /* 产物工作台：与 office 工具同源，但这些调用完全不经过模型。 */
  studioDocuments: (session: string, signal?: AbortSignal) => apiJson<ApiResults['studioDocuments']>(`/api/sessions/${encodeURIComponent(session)}/studio/documents`, { signal }),
  studioDocument: (session: string, id: string, signal?: AbortSignal) => apiJson<ApiResults['studioDocument']>(`/api/sessions/${encodeURIComponent(session)}/studio/documents/${encodeURIComponent(id)}`, { signal }),
  studioCreate: (session: string, body: Record<string, unknown>) => apiJson<ApiResults['studioCreate']>(`/api/sessions/${encodeURIComponent(session)}/studio/documents`, jsonBody('POST', body)),
  studioImport: (session: string, body: Record<string, unknown>) => apiJson<ApiResults['studioImport']>(`/api/sessions/${encodeURIComponent(session)}/studio/documents/import`, jsonBody('POST', body)),
  studioSave: (session: string, id: string, body: Record<string, unknown>) => apiJson<ApiResults['studioSave']>(`/api/sessions/${encodeURIComponent(session)}/studio/documents/${encodeURIComponent(id)}`, jsonBody('PUT', body)),
  studioVersions: (session: string, id: string, signal?: AbortSignal) => apiJson<ApiResults['studioVersions']>(`/api/sessions/${encodeURIComponent(session)}/studio/documents/${encodeURIComponent(id)}/versions`, { signal }),
  studioRollback: (session: string, id: string, body: Record<string, unknown>) => apiJson<ApiResults['studioRollback']>(`/api/sessions/${encodeURIComponent(session)}/studio/documents/${encodeURIComponent(id)}/rollback`, jsonBody('POST', body)),
  studioRender: (session: string, id: string, body: Record<string, unknown>) => apiJson<ApiResults['studioRender']>(`/api/sessions/${encodeURIComponent(session)}/studio/documents/${encodeURIComponent(id)}/render`, jsonBody('POST', body)),
  studioArtifacts: (session: string, id: string, signal?: AbortSignal) => apiJson<ApiResults['studioArtifacts']>(`/api/sessions/${encodeURIComponent(session)}/studio/documents/${encodeURIComponent(id)}/artifacts`, { signal }),
  studioDownload: async (session: string, id: string, artifactId: string, signal?: AbortSignal) => {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(session)}/studio/documents/${encodeURIComponent(id)}/artifacts/${encodeURIComponent(artifactId)}/download`, { signal });
    if (!response.ok) throw new ApiError(`下载失败（HTTP ${response.status}）`, response.status);
    return response.blob();
  },
  workProducts: (session: string, signal?: AbortSignal) => apiJson<ApiResults['workProducts']>(`/api/sessions/${encodeURIComponent(session)}/products`, { signal }),
  productPreview: (session: string, id: string, signal?: AbortSignal) => apiJson<ApiResults['productPreview']>(`/api/sessions/${encodeURIComponent(session)}/products/${encodeURIComponent(id)}/preview`, { signal }),
  skillDrafts: (session: string, signal?: AbortSignal) => apiJson<ApiResults['skillDrafts']>(`/api/sessions/${encodeURIComponent(session)}/skill-drafts`, { signal }),
  decideSkill: (session: string, id: string, action: string, version: number) => apiJson<ApiResults['decideSkill']>(`/api/sessions/${encodeURIComponent(session)}/skill-drafts/${encodeURIComponent(id)}/${action}`, jsonBody('POST', { version })),
  downloadProduct: async (session: string, id: string, signal?: AbortSignal) => {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(session)}/products/${encodeURIComponent(id)}/download`, { signal });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.message || body.error || `下载失败（${response.status}）`);
    }
    return response.blob();
  },
  recurring: () => apiJson<ApiResults['recurring']>('/api/automation/series'),
  createRecurring: (body: { task: ScheduleInput; cron: string; misfire: string }) => apiJson<ApiResults['createRecurring']>('/api/automation/series', jsonBody('POST', body)),
  controlRecurring: (id: string, body: { action: string; version: number }) => apiJson<ApiResults['controlRecurring']>(`/api/automation/series/${encodeURIComponent(id)}`, jsonBody('POST', body)),
  scheduleTimes: (body: { localTime: string; timezone: string; cron?: string }) => apiJson<ApiResults['scheduleTimes']>('/api/automation/times', jsonBody('POST', body)),
  templates: () => apiJson<ApiResults['templates']>('/api/automation/templates'),
  saveTemplate: (body: { id?: string | null; version: number; name: string; task: ScheduleDraft }) => apiJson<ApiResults['saveTemplate']>('/api/automation/templates', jsonBody('POST', body)),
  deleteTemplate: (id: string, version: number) => apiJson<ApiResults['deleteTemplate']>(`/api/automation/templates/${encodeURIComponent(id)}?version=${version}`, { method: 'DELETE' }),
  searchMessages: ({ q = '', sessionId = '', saved = false, offset = 0 }: { q?: string; sessionId?: string; saved?: boolean; offset?: number }) => apiJson<ApiResults['searchMessages']>('/api/library/search?' + new URLSearchParams({ q, sessionId, saved: String(saved), offset: String(offset) })),
  bookmarks: (session: string) => apiJson<ApiResults['bookmarks']>(`/api/library/${encodeURIComponent(session)}/bookmarks`),
  saveBookmark: (session: string, sequence: number) => apiJson<ApiResults['saveBookmark']>(`/api/library/${encodeURIComponent(session)}/bookmarks/${sequence}`, { method: 'PUT' }),
  deleteBookmark: (session: string, sequence: number) => apiJson<ApiResults['deleteBookmark']>(`/api/library/${encodeURIComponent(session)}/bookmarks/${sequence}`, { method: 'DELETE' }),
  importBookmarks: (session: string, sequences: number[]) => apiJson<ApiResults['importBookmarks']>(`/api/library/${encodeURIComponent(session)}/bookmarks/import`, jsonBody('POST', { sequences })),
  deliverySettings: () => apiJson<ApiResults['deliverySettings']>('/api/deliveries/settings'),
  saveDeliverySettings: (body: Pick<S.DeliveryServiceSettings, "version" | "desktop" | "webhookUrl">) => apiJson<ApiResults['saveDeliverySettings']>('/api/deliveries/settings', jsonBody('PUT', body)),
  deliveries: () => apiJson<ApiResults['deliveries']>('/api/deliveries'),
  claimDelivery: () => apiJson<ApiResults['claimDelivery']>('/api/deliveries/claim', { method: 'POST' }),
  deliveryReceipt: (id: string, body: { lease: string; success: boolean; error?: string }) => apiJson<ApiResults['deliveryReceipt']>(`/api/deliveries/${encodeURIComponent(id)}/receipt`, jsonBody('POST', body)),
  retryDelivery: (id: string, version: number) => apiJson<ApiResults['retryDelivery']>(`/api/deliveries/${encodeURIComponent(id)}/retry`, jsonBody('POST', { version })),
  voiceSettings: () => apiJson<ApiResults['voiceSettings']>('/api/voice/settings'),
  saveVoiceSettings: (body: Update<S.VoiceServiceUpdate>) => apiJson<ApiResults['saveVoiceSettings']>('/api/voice/settings', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  transcribeVoice: (blob: Blob, signal?: AbortSignal) => {
    const body = new FormData(); body.append('file', blob, voiceFilename(blob.type));
    return apiJson<ApiResults['transcribeVoice']>('/api/voice/transcriptions', { method: 'POST', body, signal }, 75000);
  },
  health: () => apiFetch('/api/health'),
  openBrowserWindow: () => apiJson<ApiResults['openBrowserWindow']>('/api/desktop/browser-window', { method: 'POST' }, 70000),
  sessions: () => apiJson<ApiResults['sessions']>('/api/sessions'),
  session: (id: string) => apiJson<ApiResults['session']>(`/api/sessions/${encodeURIComponent(id)}`),
  createSession: (body: { title?: string; workspace?: string; projectId?: string; replaceBlank?: boolean }) => apiJson<ApiResults['createSession']>('/api/sessions', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) }),
  /** 从当前对话分叉一条临时侧边对话（对齐 Codex 的 /side）。 */
  createSideChat: (id: string) => apiJson<ApiResults['createSideChat']>(`/api/sessions/${encodeURIComponent(id)}/side`, { method: 'POST' }),
  renameSession: (id: string, title: string) => apiJson<ApiResults['renameSession']>(`/api/sessions/${encodeURIComponent(id)}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title }) }),
  sessionPreferences: (id: string, body: { pinned?: boolean; unread?: boolean }) => apiJson<ApiResults['sessionPreferences']>(`/api/sessions/${encodeURIComponent(id)}/preferences`, jsonBody('PATCH', body)),
  archiveSession: (id: string, archived: boolean = true) => apiJson<ApiResults['archiveSession']>(`/api/sessions/${encodeURIComponent(id)}/archive`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ archived }) }),
  switchWorkspace: (id: string, workspace: string) => apiJson<ApiResults['switchWorkspace']>(`/api/sessions/${encodeURIComponent(id)}/workspace`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ workspace }) }),
  sendMessage: (id: string, body: InputMessage) => apiFetch(`/api/sessions/${encodeURIComponent(id)}/messages`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }, 120000).then((r) => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json() as Promise<S.SessionControllerMessageResponse>; }),
  resend: (id: string, body: { sequence: number; content: string }) => apiJson<ApiResults['resend']>(`/api/sessions/${encodeURIComponent(id)}/resend`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  steer: (id: string, body: { content: string; runId?: string | null }) => apiJson<ApiResults['steer']>(`/api/sessions/${encodeURIComponent(id)}/steer`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  continueRun: (id: string) => apiJson<ApiResults['continueRun']>(`/api/sessions/${encodeURIComponent(id)}/continue`, { method: 'POST' }),
  pauseRun: (runId: string) => apiJson<ApiResults['pauseRun']>(`/api/runs/${encodeURIComponent(runId)}/pause`, { method: 'POST' }),
  cancelRun: (runId: string) => apiJson<ApiResults['cancelRun']>(`/api/runs/${encodeURIComponent(runId)}/cancel`, { method: 'POST' }),
  activeRun: (id: string) => apiJson<ApiResults['activeRun']>(`/api/sessions/${encodeURIComponent(id)}/runs/active`),
  approval: (callId: string, approved: boolean) => apiJson<ApiResults['approval']>(`/api/approvals/${encodeURIComponent(callId)}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ approved }) }),
  history: (id: string, { limit, before, after }: { limit?: number; before?: number; after?: number } = {}) => {
    const params = new URLSearchParams();
    if (limit) params.set('limit', String(limit));
    if (before != null) params.set('before', String(before));
    if (after != null) params.set('after', String(after));
    return apiJson<ApiResults['history']>(`/api/sessions/${encodeURIComponent(id)}/history?${params}`);
  },
  checkpoints: (id: string, sequence: number) => apiJson<ApiResults['checkpoints']>(`/api/sessions/${encodeURIComponent(id)}/checkpoints?sequence=${sequence}`),
  // 回退必须带 mode（code / conversation / both），分支带上 revision 做乐观锁
  restoreCheckpoint: (id: string, body: { sequence: number; mode: string; revision?: number }) => apiJson<ApiResults['restoreCheckpoint']>(`/api/sessions/${encodeURIComponent(id)}/checkpoints/restore`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) }),
  forkCheckpoint: (id: string, body: { sequence: number; revision?: number }) => apiJson<ApiResults['forkCheckpoint']>(`/api/sessions/${encodeURIComponent(id)}/checkpoints/fork`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) }),
  task: (id: string) => apiJson<ApiResults['task']>(`/api/sessions/${encodeURIComponent(id)}/task`),
  saveTask: (id: string, body: Update<S.TaskControlServiceRequirements>) => apiJson<ApiResults['saveTask']>(`/api/sessions/${encodeURIComponent(id)}/task`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  queue: (id: string) => apiJson<ApiResults['queue']>(`/api/sessions/${encodeURIComponent(id)}/queue`),
  updateQueueItem: (id: string, index: number, body: { content: string; revision?: string | null }) => apiJson<ApiResults['updateQueueItem']>(`/api/sessions/${encodeURIComponent(id)}/queue/${index}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  deleteQueueItem: (id: string, index: number, revision?: string | null) => apiJson<ApiResults['deleteQueueItem']>(`/api/sessions/${encodeURIComponent(id)}/queue/${index}?revision=${encodeURIComponent(revision || '')}`, { method: 'DELETE' }),
  moveQueueItem: (id: string, from: number, to: number, revision?: string | null) => apiJson<ApiResults['moveQueueItem']>(`/api/sessions/${encodeURIComponent(id)}/queue/${from}/move?to=${to}&revision=${encodeURIComponent(revision || '')}`, { method: 'POST' }),
  steerQueueItem: (id: string, index: number, body: { runId?: string | null; revision?: string | null }) => apiJson<ApiResults['steerQueueItem']>(`/api/sessions/${encodeURIComponent(id)}/queue/${index}/steer`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  plan: (id: string) => apiJson<ApiResults['plan']>(`/api/sessions/${encodeURIComponent(id)}/plan`),
  context: (id: string) => apiJson<ApiResults['context']>(`/api/sessions/${encodeURIComponent(id)}/context`),
  compactContext: (id: string) => apiJson<ApiResults['compactContext']>(`/api/sessions/${encodeURIComponent(id)}/context/compact`, { method: 'POST' }),
  sandboxAction: (id: string, sandboxId: string, action: string) => apiJson<ApiResults['sandboxAction']>(`/api/sessions/${encodeURIComponent(id)}/multi/sandboxes/${encodeURIComponent(sandboxId)}/${action}`, { method: 'POST' }),
  /** 接受前的产物清单与校验结论：近两版差异 + 依赖，避免盲批。 */
  sandboxManifest: (id: string, sandboxId: string) => apiJson<ApiResults['sandboxManifest']>(`/api/sessions/${encodeURIComponent(id)}/multi/sandboxes/${encodeURIComponent(sandboxId)}/manifest`),
  resolveArbitration: (id: string, arbitrationId: string, body: { choice: string }) => apiJson<ApiResults['resolveArbitration']>(`/api/sessions/${encodeURIComponent(id)}/multi/arbitrations/${encodeURIComponent(arbitrationId)}/resolve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  diff: (id: string) => apiJson<ApiResults['diff']>(`/api/sessions/${encodeURIComponent(id)}/diff`),
  /** 单文件补丁：整份 diff 被截断时按需再取一次。 */
  fileDiff: (id: string, path: string) => apiJson<ApiResults['fileDiff']>(`/api/sessions/${encodeURIComponent(id)}/diff/file?path=${encodeURIComponent(path)}`),
  restoreChanges: (id: string, body: { paths?: string[]; all?: boolean }) => apiJson<ApiResults['restoreChanges']>(`/api/sessions/${encodeURIComponent(id)}/diff/restore`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) }),
  capabilities: (id: string) => apiJson<ApiResults['capabilities']>(`/api/sessions/${encodeURIComponent(id)}/capabilities`),
  browserStatus: (id: string) => apiJson<ApiResults['browserStatus']>(`/api/sessions/${encodeURIComponent(id)}/browser/status`),
  stopCommand: (id: string, taskId: string) => apiJson<ApiResults['stopCommand']>(`/api/sessions/${encodeURIComponent(id)}/commands/${encodeURIComponent(taskId)}/stop`, { method: 'POST' }),
  runChanges: (runId: string) => apiJson<ApiResults['runChanges']>(`/api/runs/${encodeURIComponent(runId)}/changes`),
  rollbackRun: (runId: string) => apiJson<ApiResults['rollbackRun']>(`/api/runs/${encodeURIComponent(runId)}/rollback`, { method: 'POST' }),
  branch: (id: string, name: string) => apiJson<ApiResults['branch']>(`/api/sessions/${encodeURIComponent(id)}/branch`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name }) }),
  files: (id: string, path?: string) => apiJson<ApiResults['files']>(`/api/sessions/${encodeURIComponent(id)}/files?path=${encodeURIComponent(path || '')}`),
  workspaceTree: (root: string, path: string = '') => apiJson<ApiResults['workspaceTree']>(`/api/workspaces/files?root=${encodeURIComponent(root)}&path=${encodeURIComponent(path)}`),
  workspaceFile: (root: string, path: string) => apiJson<ApiResults['workspaceFile']>(`/api/workspaces/file?root=${encodeURIComponent(root)}&path=${encodeURIComponent(path)}`),
  filesFlat: (id: string) => apiJson<ApiResults['filesFlat']>(`/api/sessions/${encodeURIComponent(id)}/files/flat`),
  workspaceFiles: (path: string) => apiJson<ApiResults['workspaceFiles']>(`/api/workspaces/files/flat?path=${encodeURIComponent(path)}`),
  file: (id: string, path: string) => apiJson<ApiResults['file']>(`/api/sessions/${encodeURIComponent(id)}/file?path=${encodeURIComponent(path)}`),
  projects: () => apiJson<ApiResults['projects']>('/api/projects'),
  createProject: (body: { name: string; folders?: string[] }) => apiJson<ApiResults['createProject']>('/api/projects', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  renameProject: (id: string, body: { name: string }) => apiJson<ApiResults['renameProject']>(`/api/projects/${encodeURIComponent(id)}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  saveProjectFolders: (id: string, folders: string[]) => apiJson<ApiResults['saveProjectFolders']>(`/api/projects/${encodeURIComponent(id)}/folders`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ folders }) }),
  deleteProject: (id: string) => apiJson<ApiResults['deleteProject']>(`/api/projects/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  worktrees: (body: { workspace: string; branch?: string; title?: string; projectId?: string | null }) => apiJson<ApiResults['worktrees']>('/api/worktrees', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  worktreeInfo: (path?: string) => apiJson<ApiResults['worktreeInfo']>(`/api/worktrees?path=${encodeURIComponent(path || '')}`),
  worktreeRemove: (path?: string) => apiJson<ApiResults['worktreeRemove']>(`/api/worktrees?path=${encodeURIComponent(path || '')}`, { method: 'DELETE' }),
  worktreeHandoff: (sessionId: string) => apiJson<ApiResults['worktreeHandoff']>(`/api/worktrees/handoff?sessionId=${encodeURIComponent(sessionId)}`, { method: 'POST' }),
  workspaceRoots: () => apiJson<ApiResults['workspaceRoots']>('/api/workspaces/roots'),
  workspaceBrowse: (path?: string) => apiJson<ApiResults['workspaceBrowse']>(`/api/workspaces/browse?path=${encodeURIComponent(path || '')}`),
  settings: () => apiJson<ApiResults['settings']>('/api/settings'),
  // 后端是 PUT：之前发 POST 会 405，权限与模型保存点了没反应
  savePermissionMode: (mode: string) => apiJson<ApiResults['savePermissionMode']>('/api/settings/permission-mode', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mode }) }),
  saveModel: (body: Update<S.SettingsControllerModelRequest>) => apiJson<ApiResults['saveModel']>('/api/settings/model', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  clearModelKey: () => apiJson<ApiResults['clearModelKey']>('/api/settings/model/api-key', { method: 'DELETE' }),
  modelRuntime: () => apiJson<ApiResults['modelRuntime']>('/api/settings/model/runtime'),
  saveModelRuntime: (body: Update<S.ModelRuntimeControllerUpdate>) => apiJson<ApiResults['saveModelRuntime']>('/api/settings/model/runtime', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  probeModels: (body: Update<S.SettingsControllerModelsRequest>) => apiJson<ApiResults['probeModels']>('/api/settings/model/models', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  skills: (sessionId?: string) => apiJson<ApiResults['skills']>(`/api/skills${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''}`),
  skillsPreview: (body: Update<S.SkillsControllerPreviewRequest>) => apiJson<ApiResults['skillsPreview']>('/api/skills/preview', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  skillsInstall: (body: Update<S.SkillsControllerInstallRequest>) => apiJson<ApiResults['skillsInstall']>('/api/skills/install', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  usage: (days: number) => apiJson<ApiResults['usage']>(`/api/usage?days=${days}`),
  diagnostics: (id: string) => apiJson<ApiResults['diagnostics']>(`/api/sessions/${encodeURIComponent(id)}/diagnostics`),
  validation: (id: string) => apiJson<ApiResults['validation']>(`/api/sessions/${encodeURIComponent(id)}/validation`),
  recovery: () => apiJson<ApiResults['recovery']>('/api/recovery'),
  maintenanceStatus: () => apiJson<ApiResults['maintenanceStatus']>('/api/maintenance/status'),
  backupNow: () => apiJson<ApiResults['backupNow']>('/api/maintenance/backup', { method: 'POST' }, 120000),
  purgeNow: () => apiJson<ApiResults['purgeNow']>('/api/maintenance/purge', { method: 'POST' }, 120000),
  results: () => apiJson<ApiResults['results']>('/api/results'),
  result: (id: string) => apiJson<ApiResults['result']>(`/api/results/${encodeURIComponent(id)}`),
  resultReceipt: (id: string, body: { action: string; revision?: string }) => apiJson<ApiResults['resultReceipt']>(`/api/results/${encodeURIComponent(id)}/receipt`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  sessionResearch: (id: string) => apiJson<ApiResults['sessionResearch']>(`/api/results/research/${encodeURIComponent(id)}`),
  scheduleHandoffPreview: (id: string) => apiJson<ApiResults['scheduleHandoffPreview']>(`/api/schedules/${encodeURIComponent(id)}/handoff`),
  scheduleHandoff: (id: string, body: { revision: string }) => apiJson<ApiResults['scheduleHandoff']>(`/api/schedules/${encodeURIComponent(id)}/handoff`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  schedules: () => apiJson<ApiResults['schedules']>('/api/schedules'),
  createSchedule: (body: ScheduleInput) => apiJson<ApiResults['createSchedule']>('/api/schedules', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  controlSchedule: (id: string, body: { action: string; runAt?: string }) => apiJson<ApiResults['controlSchedule']>(`/api/schedules/${encodeURIComponent(id)}/control`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  metrics: () => apiJson<ApiResults['metrics']>('/api/metrics'),
  regenerate: (id: string) => apiJson<ApiResults['regenerate']>(`/api/sessions/${encodeURIComponent(id)}/regenerate`, { method: 'POST' }),
  deleteSession: (id: string) => apiJson<ApiResults['deleteSession']>(`/api/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  browserFocus: (id: string) => apiFetch(`/api/sessions/${encodeURIComponent(id)}/browser/focus`, { method: 'POST' }),
  browserToggle: (id: string, paused: boolean) => apiFetch(`/api/sessions/${encodeURIComponent(id)}/browser/${paused ? 'resume' : 'pause'}`, { method: 'POST' })
};

export type ApiClient = typeof api;
