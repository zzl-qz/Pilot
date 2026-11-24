import type { ActionResponse, ApiResults, BrowserRecordingReplay, InputMessage, ModelProfileSaveRequest, SubmissionResult, Update, ScheduleDraft, ScheduleInput, WebSearchSettingsUpdate, WebSearchTestRequest } from './types/api.js';
import type * as S from './types/server.js';
import { errorInfo } from './lib/errors.js';
import { voiceFilename } from './lib/voice-input.js';
/**
 * 非流式请求入口：超时与取消覆盖响应头和完整正文（包括下载）。
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
    controller.signal.throwIfAborted();
    const response = await NATIVE_FETCH(input, { ...init, signal: controller.signal });
    // Chromium 可能为无正文状态提供空流，Response 构造器仍要求正文为 null。
    if (!response.body || [204, 205, 304].includes(response.status)) return response;
    // fetch 收到响应头就会返回；在清理信号前读取正文，避免慢正文永久挂起。
    // Blob 可供后续 json/text/blob 调用读取；SSE 使用独立的流式连接。
    const body = await response.blob();
    controller.signal.throwIfAborted();
    const buffered = new Response(body, { status: response.status, statusText: response.statusText, headers: response.headers });
    Object.defineProperties(buffered, {
      url: { value: response.url }, redirected: { value: response.redirected }, type: { value: response.type }
    });
    return buffered;
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
    let uncertain = false;
    try {
      const body = await response.json() as { message?: unknown; error?: unknown; uncertain?: boolean } | null;
      uncertain = body?.uncertain === true;
      // Spring 的错误响应体固定带 error（HTTP 状态短语，如「Bad Gateway」），
      // 真正的原因在 message 里。优先 message，否则界面只会显示 "Bad Gateway" 这类英文短语。
      const detail = typeof body?.message === 'string' ? body.message.trim() : '';
      const fallback = typeof body?.error === 'string' ? body.error.trim() : '';
      message = detail && detail !== 'No message available' ? detail : fallback || message;
    } catch { /* 保持 HTTP 状态码文案 */ }
    const error = new ApiError(message, response.status, uncertain);
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

export class ApiError extends Error {
  readonly status: number; readonly uncertain: boolean;
  constructor(message: string, status: number, uncertain = false) { super(message); this.status = status; this.uncertain = uncertain; }
}

const jsonBody = (method: string, body: unknown, headers?: Record<string, string>) => ({ method, headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
/** 文件工作台的根路径；会话 id 必须转义，避免路径注入。 */
const workbenchBase = (session: string) => `/api/sessions/${encodeURIComponent(session)}/workbench/files`;

export const api = {
  /* 文件工作台：所有端点都指向会话工作区里的真实文件，没有第二套可编辑源。 */
  workbenchFiles: (session: string, signal?: AbortSignal) => apiJson<ApiResults['workbenchFiles']>(`${workbenchBase(session)}`, { signal }),
  attachmentPreviewText: (session: string, file: string, signal?: AbortSignal) =>
    apiJson<{ text: string }>(`/api/sessions/${encodeURIComponent(session)}/attachments/${encodeURIComponent(file)}/preview-text`, { signal }),
  workbenchFile: (session: string, id: string, signal?: AbortSignal) => apiJson<ApiResults['workbenchFile']>(`${workbenchBase(session)}/${encodeURIComponent(id)}`, { signal }),
  workbenchText: (session: string, id: string, signal?: AbortSignal) => apiJson<{ text: string }>(`${workbenchBase(session)}/${encodeURIComponent(id)}/text`, { signal }),
  workbenchPreview: (session: string, id: string, signal?: AbortSignal) => apiJson<ApiResults['workbenchPreview']>(`${workbenchBase(session)}/${encodeURIComponent(id)}/preview`, { signal }, 60000),
  /** 预览图需要走带鉴权的 fetch，转成 object URL 供 img 使用，调用方负责 revoke。 */
  workbenchPreviewImage: async (session: string, id: string, page: number, signal?: AbortSignal) => {
    const response = await apiFetch(`${workbenchBase(session)}/${encodeURIComponent(id)}/preview/${page}`, { signal }, 60000);
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.error || `预览失败（${response.status}）`);
    }
    return URL.createObjectURL(await response.blob());
  },
  workbenchDownload: async (session: string, id: string, signal?: AbortSignal) => {
    const response = await apiFetch(`${workbenchBase(session)}/${encodeURIComponent(id)}/download`, { signal }, 60000);
    if (!response.ok) throw new Error(`下载失败（${response.status}）`);
    return response.blob();
  },
  workbenchOpen: (session: string, id: string) => apiJson<ApiResults['workbenchOpen']>(`${workbenchBase(session)}/${encodeURIComponent(id)}/open`, { method: 'POST' }, 60000),
  workbenchPublish: (session: string, id: string, body: { title?: string; summary?: string }) =>
    apiJson<Record<string, unknown>>(`${workbenchBase(session)}/${encodeURIComponent(id)}/publish`, jsonBody('POST', body)),
  workbenchVersions: (session: string, id: string, signal?: AbortSignal) => apiJson<ApiResults['workbenchVersions']>(`${workbenchBase(session)}/${encodeURIComponent(id)}/versions`, { signal }),
  workbenchSnapshot: (session: string, id: string, label: string) => apiJson<ApiResults['workbenchVersion']>(`${workbenchBase(session)}/${encodeURIComponent(id)}/versions`, jsonBody('POST', { label })),
  workbenchRevert: (session: string, id: string, version: number) => apiJson<ApiResults['workbenchVersion']>(`${workbenchBase(session)}/${encodeURIComponent(id)}/revert`, jsonBody('POST', { version })),
  workbenchAnnotations: (session: string, id: string, signal?: AbortSignal) => apiJson<ApiResults['workbenchAnnotations']>(`${workbenchBase(session)}/${encodeURIComponent(id)}/annotations`, { signal }),
  workbenchAnnotate: (session: string, id: string, body: { page: number; anchor?: string; text: string }) =>
    apiJson<ApiResults['workbenchAnnotation']>(`${workbenchBase(session)}/${encodeURIComponent(id)}/annotations`, jsonBody('POST', body)),
  workbenchChangeRequest: (session: string, id: string, annotationId: string) =>
    apiJson<ApiResults['workbenchChangeRequest']>(`${workbenchBase(session)}/${encodeURIComponent(id)}/annotations/${encodeURIComponent(annotationId)}/request`, { method: 'POST' }),
  workbenchPdfOperations: (session: string, id: string, operations: Record<string, unknown>) =>
    apiJson<ApiResults['workbenchFile']>(`${workbenchBase(session)}/${encodeURIComponent(id)}/pdf-operations`, jsonBody('POST', operations), 60000),
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
  /** 会话想法：只是用户自己的记事，保存与修改都不会发送消息或进入执行队列。 */
  notes: (session: string) => apiJson<ApiResults['notes']>(`/api/sessions/${encodeURIComponent(session)}/notes`),
  searchNotes: (projectId: string, q: string) => apiJson<ApiResults['searchNotes']>('/api/library/notes/search?' + new URLSearchParams({ projectId, q })),
  createNote: (session: string, content: string, source?: { sequence?: number | null; excerpt?: string; role?: string | null }) => apiJson<ApiResults['createNote']>(`/api/sessions/${encodeURIComponent(session)}/notes`, jsonBody('POST', source ? { content, source } : { content })),
  noteStatus: (session: string, id: string, body: { status: string; link?: { kind: string; id: string } | null }) => apiJson<ApiResults['noteStatus']>(`/api/sessions/${encodeURIComponent(session)}/notes/${encodeURIComponent(id)}/status`, jsonBody('POST', body)),
  promoteNote: (session: string, id: string, kind: string) => apiJson<ApiResults['promoteNote']>(`/api/sessions/${encodeURIComponent(session)}/notes/${encodeURIComponent(id)}/promote`, jsonBody('POST', { kind })),
  updateNote: (session: string, noteId: string, content: string) => apiJson<ApiResults['updateNote']>(`/api/sessions/${encodeURIComponent(session)}/notes/${encodeURIComponent(noteId)}`, jsonBody('PUT', { content })),
  deleteNote: (session: string, noteId: string) => apiJson<ApiResults['deleteNote']>(`/api/sessions/${encodeURIComponent(session)}/notes/${encodeURIComponent(noteId)}`, { method: 'DELETE' }),
  deliverySettings: () => apiJson<ApiResults['deliverySettings']>('/api/deliveries/settings'),
  saveDeliverySettings: (body: Pick<S.DeliveryServiceSettings, "version" | "desktop" | "webhookUrl">) => apiJson<ApiResults['saveDeliverySettings']>('/api/deliveries/settings', jsonBody('PUT', body)),
  deliveries: () => apiJson<ApiResults['deliveries']>('/api/deliveries'),
  claimDelivery: () => apiJson<ApiResults['claimDelivery']>('/api/deliveries/claim', { method: 'POST' }),
  deliveryReceipt: (id: string, body: { lease: string; success: boolean; error?: string }) => apiJson<ApiResults['deliveryReceipt']>(`/api/deliveries/${encodeURIComponent(id)}/receipt`, jsonBody('POST', body)),
  retryDelivery: (id: string, version: number) => apiJson<ApiResults['retryDelivery']>(`/api/deliveries/${encodeURIComponent(id)}/retry`, jsonBody('POST', { version })),
  voiceSettings: () => apiJson<ApiResults['voiceSettings']>('/api/voice/settings'),
  saveVoiceSettings: (body: Update<S.VoiceServiceUpdate>) => apiJson<ApiResults['saveVoiceSettings']>('/api/voice/settings', jsonBody('PUT', body)),
  transcribeVoice: (blob: Blob, signal?: AbortSignal) => {
    const body = new FormData(); body.append('file', blob, voiceFilename(blob.type));
    return apiJson<ApiResults['transcribeVoice']>('/api/voice/transcriptions', { method: 'POST', body, signal }, 75000);
  },
  health: () => apiFetch('/api/health'),
  openBrowserWindow: () => apiJson<ApiResults['openBrowserWindow']>('/api/desktop/browser-window', { method: 'POST' }, 70000),
  sessions: () => apiJson<ApiResults['sessions']>('/api/sessions'),
  session: (id: string) => apiJson<ApiResults['session']>(`/api/sessions/${encodeURIComponent(id)}`),
  createSession: (body: { title?: string; workspace?: string; projectId?: string; replaceBlank?: boolean }) => apiJson<ApiResults['createSession']>('/api/sessions', jsonBody('POST', body || {})),
  /** 从当前对话分叉一条临时侧边对话（对齐 Codex 的 /side）。 */
  createSideChat: (id: string) => apiJson<ApiResults['createSideChat']>(`/api/sessions/${encodeURIComponent(id)}/side`, { method: 'POST' }),
  renameSession: (id: string, title: string) => apiJson<ApiResults['renameSession']>(`/api/sessions/${encodeURIComponent(id)}`, jsonBody('PATCH', { title })),
  sessionPreferences: (id: string, body: { pinned?: boolean; unread?: boolean }) => apiJson<ApiResults['sessionPreferences']>(`/api/sessions/${encodeURIComponent(id)}/preferences`, jsonBody('PATCH', body)),
  archiveSession: (id: string, archived: boolean = true) => apiJson<ApiResults['archiveSession']>(`/api/sessions/${encodeURIComponent(id)}/archive`, jsonBody('PATCH', { archived })),
  switchWorkspace: (id: string, workspace: string) => apiJson<ApiResults['switchWorkspace']>(`/api/sessions/${encodeURIComponent(id)}/workspace`, jsonBody('PATCH', { workspace })),
  submissionToken: () => apiJson<{ requestId: string }>('/api/submissions/token', { cache: 'no-store' }),
  submissionResult: (requestId: string) => apiJson<SubmissionResult>('/api/submissions/result', { headers: { 'X-Pilot-Request-Id': requestId }, cache: 'no-store' }),
  acknowledgeSubmission: (requestId: string) => apiJson('/api/submissions/ack', jsonBody('POST', { requestId })),
  sendMessage: (id: string, body: InputMessage, requestId: string) => apiJson<S.SessionControllerMessageResponse>(`/api/sessions/${encodeURIComponent(id)}/messages`, jsonBody('POST', body, { 'X-Pilot-Request-Id': requestId }), 120000),
  resend: (id: string, body: { sequence: number; content: string }, requestId: string) => apiJson<ApiResults['resend']>(`/api/sessions/${encodeURIComponent(id)}/resend`, jsonBody('POST', body, { 'X-Pilot-Request-Id': requestId })),
  steer: (id: string, body: { content: string; runId?: string | null }, requestId: string) => apiJson<ApiResults['steer']>(`/api/sessions/${encodeURIComponent(id)}/steer`, jsonBody('POST', body, { 'X-Pilot-Request-Id': requestId })),
  continueRun: (id: string, requestId: string) => apiJson<ApiResults['continueRun']>(`/api/sessions/${encodeURIComponent(id)}/continue`, { method: 'POST', headers: { 'X-Pilot-Request-Id': requestId } }),
  pauseRun: (runId: string) => apiJson<ApiResults['pauseRun']>(`/api/runs/${encodeURIComponent(runId)}/pause`, { method: 'POST' }),
  cancelRun: (runId: string) => apiJson<ApiResults['cancelRun']>(`/api/runs/${encodeURIComponent(runId)}/cancel`, { method: 'POST' }),
  activeRun: (id: string) => apiJson<ApiResults['activeRun']>(`/api/sessions/${encodeURIComponent(id)}/runs/active`),
  approval: (callId: string, approved: boolean, reason?: string) => apiJson<ApiResults['approval']>(
    `/api/approvals/${encodeURIComponent(callId)}`,
    jsonBody('POST', reason && reason.trim() ? { approved, reason: reason.trim() } : { approved })
  ),
  answerQuestion: (id: string, callId: string, choice: string) => apiJson<ApiResults['answerQuestion']>(`/api/sessions/${encodeURIComponent(id)}/questions/${encodeURIComponent(callId)}`, jsonBody('POST', { choice })),
  pendingInteractions: () => apiJson<ApiResults['pendingInteractions']>('/api/interactions/pending'),
  history: (id: string, { limit, before, after, compactTools }: { limit?: number; before?: number; after?: number; compactTools?: boolean } = {}) => {
    const params = new URLSearchParams();
    if (limit) params.set('limit', String(limit));
    if (before != null) params.set('before', String(before));
    if (after != null) params.set('after', String(after));
    if (compactTools) params.set('compactTools', 'true');
    return apiJson<ApiResults['history']>(`/api/sessions/${encodeURIComponent(id)}/history?${params}`);
  },
  event: (id: string, sequence: number, revision: number) => apiJson<{ event: import('./types/events.js').EventEnvelope; historyRevision: number }>(
    `/api/sessions/${encodeURIComponent(id)}/events/${sequence}?revision=${revision}`),
  checkpoints: (id: string, sequence: number) => apiJson<ApiResults['checkpoints']>(`/api/sessions/${encodeURIComponent(id)}/checkpoints?sequence=${sequence}`),
  // 回退必须带 mode（code / conversation / both），分支带上 revision 做乐观锁
  restoreCheckpoint: (id: string, body: { sequence: number; mode: string; revision?: number; operationId?: string }) => apiJson<ApiResults['restoreCheckpoint']>(`/api/sessions/${encodeURIComponent(id)}/checkpoints/restore`, jsonBody('POST', body || {})),
  forkCheckpoint: (id: string, body: { sequence: number; revision?: number }) => apiJson<ApiResults['forkCheckpoint']>(`/api/sessions/${encodeURIComponent(id)}/checkpoints/fork`, jsonBody('POST', body || {})),
  task: (id: string) => apiJson<ApiResults['task']>(`/api/sessions/${encodeURIComponent(id)}/task`),
  saveTask: (id: string, body: Update<S.TaskControlServiceRequirements>) => apiJson<ApiResults['saveTask']>(`/api/sessions/${encodeURIComponent(id)}/task`, jsonBody('PUT', body)),
  queue: (id: string) => apiJson<ApiResults['queue']>(`/api/sessions/${encodeURIComponent(id)}/queue`),
  holdQueue: (id: string, held: boolean) => apiJson<ApiResults['holdQueue']>(`/api/sessions/${encodeURIComponent(id)}/queue/hold`, jsonBody('POST', { held })),
  updateQueueItem: (id: string, index: number, body: { content: string; revision?: string | null; constraints?: string[]; acceptance?: string[] }) => apiJson<ApiResults['updateQueueItem']>(`/api/sessions/${encodeURIComponent(id)}/queue/${index}`, jsonBody('PUT', body)),
  deleteQueueItem: (id: string, index: number, revision?: string | null) => apiJson<ApiResults['deleteQueueItem']>(`/api/sessions/${encodeURIComponent(id)}/queue/${index}?revision=${encodeURIComponent(revision || '')}`, { method: 'DELETE' }),
  moveQueueItem: (id: string, from: number, to: number, revision?: string | null) => apiJson<ApiResults['moveQueueItem']>(`/api/sessions/${encodeURIComponent(id)}/queue/${from}/move?to=${to}&revision=${encodeURIComponent(revision || '')}`, { method: 'POST' }),
  steerQueueItem: (id: string, index: number, body: { runId?: string | null; revision?: string | null }) => apiJson<ApiResults['steerQueueItem']>(`/api/sessions/${encodeURIComponent(id)}/queue/${index}/steer`, jsonBody('POST', body)),
  plan: (id: string) => apiJson<ApiResults['plan']>(`/api/sessions/${encodeURIComponent(id)}/plan`),
  context: (id: string) => apiJson<ApiResults['context']>(`/api/sessions/${encodeURIComponent(id)}/context`),
  compactContext: (id: string, focus?: string) => apiJson<ApiResults['compactContext']>(`/api/sessions/${encodeURIComponent(id)}/context/compact`, focus?.trim() ? jsonBody('POST', { focus: focus.trim() }) : { method: 'POST' }),
  sandboxAction: (id: string, sandboxId: string, action: string) => apiJson<ApiResults['sandboxAction']>(`/api/sessions/${encodeURIComponent(id)}/multi/sandboxes/${encodeURIComponent(sandboxId)}/${action}`, { method: 'POST' }),
  /** 接受前的产物清单与校验结论：近两版差异 + 依赖，避免盲批。 */
  sandboxManifest: (id: string, sandboxId: string) => apiJson<ApiResults['sandboxManifest']>(`/api/sessions/${encodeURIComponent(id)}/multi/sandboxes/${encodeURIComponent(sandboxId)}/manifest`),
  resolveArbitration: (id: string, arbitrationId: string, body: { choice: string }) => apiJson<ApiResults['resolveArbitration']>(`/api/sessions/${encodeURIComponent(id)}/multi/arbitrations/${encodeURIComponent(arbitrationId)}/resolve`, jsonBody('POST', body)),
  diff: (id: string) => apiJson<ApiResults['diff']>(`/api/sessions/${encodeURIComponent(id)}/diff`),
  /** 单文件补丁：整份 diff 被截断时按需再取一次。 */
  fileDiff: (id: string, path: string) => apiJson<ApiResults['fileDiff']>(`/api/sessions/${encodeURIComponent(id)}/diff/file?path=${encodeURIComponent(path)}`),
  restoreChanges: (id: string, body: { paths?: string[]; all?: boolean }) => apiJson<ApiResults['restoreChanges']>(`/api/sessions/${encodeURIComponent(id)}/diff/restore`, jsonBody('POST', body || {})),
  gitLog: (id: string, limit = 15, signal?: AbortSignal) =>
    apiJson<ApiResults['gitLog']>(`/api/sessions/${encodeURIComponent(id)}/git/log?limit=${limit}`, { signal }),
  gitDraftMessage: (id: string, body: { paths: string[]; all?: boolean }) =>
    apiJson<ApiResults['gitDraftMessage']>(`/api/sessions/${encodeURIComponent(id)}/git/commit-message`, jsonBody('POST', body)),
  gitCommit: (id: string, body: { paths: string[]; all?: boolean; message: string }) =>
    apiJson<ApiResults['gitCommit']>(`/api/sessions/${encodeURIComponent(id)}/git/commit`, jsonBody('POST', body)),
  capabilities: (id: string) => apiJson<ApiResults['capabilities']>(`/api/sessions/${encodeURIComponent(id)}/capabilities`),
  /** MCP：合并视图只读、写用户级配置、信任管理。 */
  mcpServers: (id: string) => apiJson<ApiResults['mcpServers']>(`/api/sessions/${encodeURIComponent(id)}/mcp/servers`),
  mcpAddServer: (id: string, body: Update<S.McpConfigControllerAddRequest>) => apiJson<ApiResults['mcpAddServer']>(`/api/sessions/${encodeURIComponent(id)}/mcp/servers`, jsonBody('POST', body)),
  mcpImportServers: (id: string, body: Update<S.McpConfigControllerImportRequest>) => apiJson<ApiResults['mcpImportServers']>(`/api/sessions/${encodeURIComponent(id)}/mcp/servers/import`, jsonBody('POST', body)),
  /** 测试连接：真正启动服务器并读工具清单；npx 首次拉包可能较慢，放宽超时。 */
  mcpProbeServer: (id: string, name: string) => apiJson<ApiResults['mcpProbeServer']>(`/api/sessions/${encodeURIComponent(id)}/mcp/servers/${encodeURIComponent(name)}/probe`, jsonBody('POST', {}), 120000),
  mcpDeleteServer: (id: string, name: string) => apiJson<ApiResults['mcpDeleteServer']>(`/api/sessions/${encodeURIComponent(id)}/mcp/servers/${encodeURIComponent(name)}`, { method: 'DELETE' }),
  mcpTrust: (id: string, body: Update<S.McpTrustControllerTrustRequest>) => apiJson<ApiResults['mcpTrust']>(`/api/sessions/${encodeURIComponent(id)}/mcp/trust`, jsonBody('POST', body)),
  browserStatus: (id: string) => apiJson<ApiResults['browserStatus']>(`/api/sessions/${encodeURIComponent(id)}/browser/status`),
  resumeBrowserHandoff: (id: string, body: { browserSessionId?: string; note?: string } = {}) =>
    apiJson<ApiResults['browserHandoffResume']>(`/api/sessions/${encodeURIComponent(id)}/browser/handoff/resume`, jsonBody('POST', body)),
  cancelBrowserHandoff: (id: string, body: { browserSessionId?: string; reason?: string } = {}) =>
    apiJson<ApiResults['browserHandoffCancel']>(`/api/sessions/${encodeURIComponent(id)}/browser/handoff/cancel`, jsonBody('POST', body)),
  selectBrowserLane: (id: string, browserSessionId: string) =>
    apiJson<ApiResults['browserLane']>(`/api/sessions/${encodeURIComponent(id)}/browser/lane`, jsonBody('POST', { browserSessionId })),
  stopBrowserControl: (id: string, browserSessionId?: string) =>
    apiJson<ApiResults['browserStopControl']>(`/api/sessions/${encodeURIComponent(id)}/browser/stop-control`, jsonBody('POST', browserSessionId ? { browserSessionId } : {})),
  browserEvidence: (id: string, runId = '', limit = 80) => {
    const params = new URLSearchParams();
    if (runId) params.set('runId', runId);
    params.set('limit', String(limit));
    return apiJson<ApiResults['browserEvidence']>(`/api/sessions/${encodeURIComponent(id)}/browser/evidence?${params}`);
  },
  browserEvidenceImageUrl: (id: string, evidenceId: string) =>
    `/api/sessions/${encodeURIComponent(id)}/browser/evidence/${encodeURIComponent(evidenceId)}/image`,
  browserEvidenceRecording: (id: string, evidenceId: string) =>
    apiJson<BrowserRecordingReplay>(`/api/sessions/${encodeURIComponent(id)}/browser/evidence/${encodeURIComponent(evidenceId)}/recording`),
  browserNavigate: (id: string, url: string, browserSessionId?: string) =>
    apiJson<ActionResponse & { text?: string }>(`/api/sessions/${encodeURIComponent(id)}/browser/navigate`, jsonBody('POST', browserSessionId ? { url, browserSessionId } : { url })),
  browserBack: (id: string, browserSessionId?: string) =>
    apiJson<ActionResponse & { text?: string }>(`/api/sessions/${encodeURIComponent(id)}/browser/back`, jsonBody('POST', browserSessionId ? { browserSessionId } : {})),
  browserForward: (id: string, browserSessionId?: string) =>
    apiJson<ActionResponse & { text?: string }>(`/api/sessions/${encodeURIComponent(id)}/browser/forward`, jsonBody('POST', browserSessionId ? { browserSessionId } : {})),
  browserReload: (id: string, browserSessionId?: string) =>
    apiJson<ActionResponse & { text?: string }>(`/api/sessions/${encodeURIComponent(id)}/browser/reload`, jsonBody('POST', browserSessionId ? { browserSessionId } : {})),
  browserStop: (id: string, browserSessionId?: string) =>
    apiJson<ActionResponse & { text?: string }>(`/api/sessions/${encodeURIComponent(id)}/browser/stop`, jsonBody('POST', browserSessionId ? { browserSessionId } : {})),
  browserPointer: (id: string, body: { action: string; x: number; y: number; deltaX?: number; deltaY?: number; browserSessionId?: string }) =>
    apiJson<ActionResponse & { text?: string }>(`/api/sessions/${encodeURIComponent(id)}/browser/pointer`, jsonBody('POST', body)),
  browserPick: (id: string, body: { x: number; y: number; browserSessionId?: string }) =>
    apiJson<ActionResponse & { text?: string }>(`/api/sessions/${encodeURIComponent(id)}/browser/pick`, jsonBody('POST', body)),
  browserKeys: (id: string, body: { key?: string; text?: string; browserSessionId?: string }) =>
    apiJson<ActionResponse & { text?: string }>(`/api/sessions/${encodeURIComponent(id)}/browser/keys`, jsonBody('POST', body)),
  browserResize: (id: string, body: { width: number; height: number; browserSessionId?: string }) =>
    apiJson<ActionResponse & { text?: string }>(`/api/sessions/${encodeURIComponent(id)}/browser/resize`, jsonBody('POST', body)),
  browserLogs: (id: string, body: { network?: boolean; clear?: boolean; level?: string; browserSessionId?: string }) =>
    apiJson<ActionResponse & { text?: string }>(`/api/sessions/${encodeURIComponent(id)}/browser/logs`, jsonBody('POST', body)),
  stopCommand: (id: string, taskId: string) => apiJson<ApiResults['stopCommand']>(`/api/sessions/${encodeURIComponent(id)}/commands/${encodeURIComponent(taskId)}/stop`, { method: 'POST' }),
  runChanges: (runId: string, summary = false) => apiJson<ApiResults['runChanges']>(`/api/runs/${encodeURIComponent(runId)}/changes${summary ? '?summary=true' : ''}`),
  rollbackRun: (runId: string) => apiJson<ApiResults['rollbackRun']>(`/api/runs/${encodeURIComponent(runId)}/rollback`, { method: 'POST' }),
  branch: (id: string, name: string) => apiJson<ApiResults['branch']>(`/api/sessions/${encodeURIComponent(id)}/branch`, jsonBody('POST', { name })),
  files: (id: string, path?: string) => apiJson<ApiResults['files']>(`/api/sessions/${encodeURIComponent(id)}/files?path=${encodeURIComponent(path || '')}`),
  workspaceTree: (root: string, path: string = '') => apiJson<ApiResults['workspaceTree']>(`/api/workspaces/files?root=${encodeURIComponent(root)}&path=${encodeURIComponent(path)}`),
  workspaceFile: (root: string, path: string) => apiJson<ApiResults['workspaceFile']>(`/api/workspaces/file?root=${encodeURIComponent(root)}&path=${encodeURIComponent(path)}`),
  filesFlat: (id: string) => apiJson<ApiResults['filesFlat']>(`/api/sessions/${encodeURIComponent(id)}/files/flat`),
  workspaceFiles: (path: string) => apiJson<ApiResults['workspaceFiles']>(`/api/workspaces/files/flat?path=${encodeURIComponent(path)}`),
  file: (id: string, path: string) => apiJson<ApiResults['file']>(`/api/sessions/${encodeURIComponent(id)}/file?path=${encodeURIComponent(path)}`),
  fileRawUrl: (sessionId: string | null, workspace: string, path: string, download = false) => {
    const dl = download ? '&download=true' : '';
    return sessionId
      ? `/api/sessions/${encodeURIComponent(sessionId)}/file/raw?path=${encodeURIComponent(path)}${dl}`
      : `/api/workspaces/file/raw?root=${encodeURIComponent(workspace)}&path=${encodeURIComponent(path)}${dl}`;
  },
  openFileNative: (sessionId: string | null, workspace: string, path: string, mode: 'open' | 'reveal' = 'open') => (
    sessionId
      ? apiJson<{ ok: boolean; message: string; alternative?: string }>(`/api/sessions/${encodeURIComponent(sessionId)}/file/open`, jsonBody('POST', { path, mode }))
      : apiJson<{ ok: boolean; message: string; alternative?: string }>('/api/workspaces/file/open', jsonBody('POST', { root: workspace, path, mode }))
  ),
  projects: () => apiJson<ApiResults['projects']>('/api/projects'),
  createProject: (body: { name: string; folders?: string[]; constraints?: string }) => apiJson<ApiResults['createProject']>('/api/projects', jsonBody('POST', body)),
  renameProject: (id: string, body: { name: string }) => apiJson<ApiResults['renameProject']>(`/api/projects/${encodeURIComponent(id)}`, jsonBody('PATCH', body)),
  saveProjectFolders: (id: string, folders: string[]) => apiJson<ApiResults['saveProjectFolders']>(`/api/projects/${encodeURIComponent(id)}/folders`, jsonBody('PUT', { folders })),
  saveProjectConstraints: (id: string, text: string) => apiJson<ApiResults['saveProjectConstraints']>(`/api/projects/${encodeURIComponent(id)}/constraints`, jsonBody('PUT', { text })),
  readProjectAgents: (id: string) => apiJson<ApiResults['readProjectAgents']>(`/api/projects/${encodeURIComponent(id)}/agents`),
  deleteProject: (id: string) => apiJson<ApiResults['deleteProject']>(`/api/projects/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  worktrees: (body: { workspace: string; branch?: string; title?: string; projectId?: string | null }) => apiJson<ApiResults['worktrees']>('/api/worktrees', jsonBody('POST', body)),
  worktreeInfo: (path?: string) => apiJson<ApiResults['worktreeInfo']>(`/api/worktrees?path=${encodeURIComponent(path || '')}`),
  worktreeRemove: (path?: string) => apiJson<ApiResults['worktreeRemove']>(`/api/worktrees?path=${encodeURIComponent(path || '')}`, { method: 'DELETE' }),
  worktreeHandoff: (sessionId: string) => apiJson<ApiResults['worktreeHandoff']>(`/api/worktrees/handoff?sessionId=${encodeURIComponent(sessionId)}`, { method: 'POST' }),
  workspaceRoots: () => apiJson<ApiResults['workspaceRoots']>('/api/workspaces/roots'),
  /** 工作区看板：一次拿到该目录下所有会话在做什么；只读，改动仍走各会话自己的接受入口。 */
  workspaceOverview: (path: string) => apiJson<ApiResults['workspaceOverview']>(`/api/workspaces/overview?path=${encodeURIComponent(path)}`),
  workspaceBrowse: (path?: string) => apiJson<ApiResults['workspaceBrowse']>(`/api/workspaces/browse?path=${encodeURIComponent(path || '')}`),
  // 会话间协调：所有动作都以 sessionId 作为发起方，越权与占用冲突由服务端拒绝并带原因
  coordinationBoard: (sessionId: string) => apiJson<ApiResults['coordinationBoard']>(`/api/sessions/${encodeURIComponent(sessionId)}/coordination`),
  coordinationRequest: (sessionId: string, body: { toSessionId: string; reason: string; taskId?: string }) =>
    apiJson<ApiResults['coordinationRequest']>(`/api/sessions/${encodeURIComponent(sessionId)}/coordination/requests`, jsonBody('POST', body)),
  coordinationResolveRequest: (sessionId: string, requestId: string, action: 'accept' | 'decline' | 'withdraw') =>
    apiJson<ApiResults['coordinationRequest']>(`/api/sessions/${encodeURIComponent(sessionId)}/coordination/requests/${encodeURIComponent(requestId)}/${action}`, { method: 'POST' }),
  coordinationClaim: (sessionId: string, body: { path: string; kind?: 'FILE' | 'DIRECTORY'; ttlSeconds?: number }) =>
    apiJson<ApiResults['coordinationClaim']>(`/api/sessions/${encodeURIComponent(sessionId)}/coordination/claims`, jsonBody('POST', body)),
  coordinationReleaseClaim: (sessionId: string, claimId: string) =>
    apiJson<ApiResults['coordinationClaim']>(`/api/sessions/${encodeURIComponent(sessionId)}/coordination/claims/${encodeURIComponent(claimId)}`, { method: 'DELETE' }),
  coordinationHandoff: (sessionId: string, body: { toSessionId: string; taskId: string; summary: string }) =>
    apiJson<ApiResults['coordinationHandoff']>(`/api/sessions/${encodeURIComponent(sessionId)}/coordination/handoffs`, jsonBody('POST', body)),
  coordinationResolveHandoff: (sessionId: string, handoffId: string, action: 'accept' | 'decline') =>
    apiJson<ApiResults['coordinationHandoff']>(`/api/sessions/${encodeURIComponent(sessionId)}/coordination/handoffs/${encodeURIComponent(handoffId)}/${action}`, { method: 'POST' }),
  settings: (signal?: AbortSignal) => apiJson<ApiResults['settings']>('/api/settings', { signal }),
  // 后端是 PUT：之前发 POST 会 405，权限与模型保存点了没反应
  savePermissionMode: (mode: string) => apiJson<ApiResults['savePermissionMode']>('/api/settings/permission-mode', jsonBody('PUT', { mode })),
  /** 工作区 .pilot/settings.json 的 allow/deny/ask 规则；每次请求都重新读盘，改完立即生效。 */
  permissionRules: (workspace: string, signal?: AbortSignal) =>
    apiJson<ApiResults['permissionRules']>(`/api/permission-rules?workspace=${encodeURIComponent(workspace)}`, { signal }),
  savePermissionRules: (body: { workspace: string; allow: string[]; deny: string[]; ask: string[] }) =>
    apiJson<ApiResults['savePermissionRules']>('/api/permission-rules', jsonBody('PUT', body)),
  saveModel: (body: Update<S.SettingsControllerModelRequest>) => apiJson<ApiResults['saveModel']>('/api/settings/model', jsonBody('PUT', body)),
  clearModelKey: () => apiJson<ApiResults['clearModelKey']>('/api/settings/model/api-key', { method: 'DELETE' }),
  modelProfiles: (signal?: AbortSignal) => apiJson<ApiResults['modelProfiles']>('/api/settings/model/profiles', { signal }),
  createModelProfile: (body: ModelProfileSaveRequest) => apiJson<ApiResults['createModelProfile']>('/api/settings/model/profiles', jsonBody('POST', body)),
  updateModelProfile: (id: string, body: ModelProfileSaveRequest) =>
    apiJson<ApiResults['updateModelProfile']>(`/api/settings/model/profiles/${encodeURIComponent(id)}`, jsonBody('PUT', body)),
  deleteModelProfile: (id: string) =>
    apiJson<ApiResults['deleteModelProfile']>(`/api/settings/model/profiles/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  activateModelProfile: (id: string) =>
    apiJson<ApiResults['activateModelProfile']>(`/api/settings/model/profiles/${encodeURIComponent(id)}/activate`, { method: 'POST' }),
  setFallbackModelProfile: (profileId: string) =>
    apiJson<ApiResults['setFallbackModelProfile']>('/api/settings/model/profiles/fallback', jsonBody('PUT', { profileId })),
  modelRuntime: () => apiJson<ApiResults['modelRuntime']>('/api/settings/model/runtime'),
  saveModelRuntime: (body: Update<S.ModelRuntimeControllerUpdate>) => apiJson<ApiResults['saveModelRuntime']>('/api/settings/model/runtime', jsonBody('PUT', body)),
  probeModels: (body: Update<S.SettingsControllerModelsRequest>) => apiJson<ApiResults['probeModels']>('/api/settings/model/models', jsonBody('POST', body)),
  webSearchSettings: (signal?: AbortSignal) => apiJson<ApiResults['webSearchSettings']>('/api/settings/web-search', { signal }),
  saveWebSearchSettings: (body: WebSearchSettingsUpdate) => apiJson<ApiResults['saveWebSearchSettings']>('/api/settings/web-search', jsonBody('PUT', body)),
  clearWebSearchApiKey: (provider?: string) =>
    apiJson<ApiResults['clearWebSearchApiKey']>(`/api/settings/web-search/api-key${provider ? `?provider=${encodeURIComponent(provider)}` : ''}`, { method: 'DELETE' }),
  testWebSearch: (body: WebSearchTestRequest) => apiJson<ApiResults['testWebSearch']>('/api/settings/web-search/test', jsonBody('POST', body), 35000),
  restartLocalWebSearch: () => apiJson<ApiResults['restartLocalWebSearch']>('/api/settings/web-search/restart-local', { method: 'POST' }, 70000),
  skills: (sessionId?: string) => apiJson<ApiResults['skills']>(`/api/skills${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''}`),
  agentProfiles: (sessionId?: string) => apiJson<ApiResults['agentProfiles']>(`/api/agents${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''}`),
  saveAgentProfile: (name: string, content: string) => apiJson<S.AgentProfilesControllerProfilePayload>(`/api/agents/${encodeURIComponent(name)}`, jsonBody('PUT', { content })),
  deleteAgentProfile: (name: string) => apiJson<null>(`/api/agents/${encodeURIComponent(name)}`, { method: 'DELETE' }),
  skillsPreview: (body: Update<S.SkillsControllerPreviewRequest>) => apiJson<ApiResults['skillsPreview']>('/api/skills/preview', jsonBody('POST', body), 150000),
  skillsInstall: (body: Update<S.SkillsControllerInstallRequest>) => apiJson<ApiResults['skillsInstall']>('/api/skills/install', jsonBody('POST', body)),
  memoryOverview: (workspace?: string) => apiJson<ApiResults['memoryOverview']>(`/api/memory${workspace ? `?workspace=${encodeURIComponent(workspace)}` : ''}`),
  memoryDraftCount: (workspace?: string) => apiJson<ApiResults['memoryDraftCount']>(`/api/memory/drafts/count${workspace ? `?workspace=${encodeURIComponent(workspace)}` : ''}`),
  memoryImportLegacy: (workspace?: string) => apiJson<ApiResults['memoryImportLegacy']>('/api/memory/import-legacy', jsonBody('POST', workspace ? { workspace } : {})),
  memoryApproveDraft: (id: string, body: { workspace?: string; scope: 'project' | 'user'; type?: string; title?: string; note?: string; why?: string }) =>
    apiJson<ApiResults['memoryApproveDraft']>(`/api/memory/drafts/${encodeURIComponent(id)}/approve`, jsonBody('POST', body)),
  memoryDiscardDraft: (id: string, workspace?: string) =>
    apiJson<ApiResults['memoryDiscardDraft']>(`/api/memory/drafts/${encodeURIComponent(id)}${workspace ? `?workspace=${encodeURIComponent(workspace)}` : ''}`, { method: 'DELETE' }),
  memoryCreateEntry: (body: { workspace?: string; scope: 'project' | 'user'; type: string; title?: string; note: string; why?: string }) =>
    apiJson<ApiResults['memoryCreateEntry']>('/api/memory/entries', jsonBody('POST', body)),
  memoryUpdateEntry: (slug: string, body: { workspace?: string; scope: 'project' | 'user'; type: string; title?: string; note: string; why?: string }) =>
    apiJson<ApiResults['memoryUpdateEntry']>(`/api/memory/entries/${encodeURIComponent(slug)}`, jsonBody('PUT', body)),
  memoryDeleteEntry: (slug: string, scope: 'project' | 'user', workspace?: string) =>
    apiJson<ApiResults['memoryDeleteEntry']>(`/api/memory/entries/${encodeURIComponent(slug)}?scope=${encodeURIComponent(scope)}${workspace ? `&workspace=${encodeURIComponent(workspace)}` : ''}`, { method: 'DELETE' }),
  usage: (days: number) => apiJson<ApiResults['usage']>(`/api/usage?days=${days}`),
  diagnostics: (id: string) => apiJson<ApiResults['diagnostics']>(`/api/sessions/${encodeURIComponent(id)}/diagnostics`),
  exportDiagnostics: async (sessionId?: string | null, signal?: AbortSignal) => {
    const url = `/api/diagnostics/export${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''}`;
    const response = await apiFetch(url, { signal }, 60000);
    if (!response.ok) {
      if (response.status === 404 || response.status === 405 || response.status === 501) {
        throw new ApiError('当前服务端暂不支持导出脱敏诊断包，请升级服务端后重试。', response.status);
      }
      let message = `导出诊断包失败（HTTP ${response.status}）`;
      try {
        const body = await response.json() as { message?: unknown; error?: unknown } | null;
        const detail = typeof body?.message === 'string' ? body.message.trim() : '';
        const fallback = typeof body?.error === 'string' ? body.error.trim() : '';
        if (detail && detail !== 'No message available') message = detail;
        else if (fallback) message = fallback;
      } catch { /* 保持默认提示 */ }
      throw new ApiError(message, response.status);
    }
    const disposition = response.headers?.get?.('content-disposition') || '';
    const match = disposition.match(/filename\*?=(?:UTF-8'')?["']?([^"';]+)["']?/i);
    let rawName = '';
    if (match?.[1]) {
      try { rawName = decodeURIComponent(match[1].trim()); } catch { rawName = match[1].trim(); }
    }
    const filename = rawName.replace(/[\\/]/g, '_') || `pilot-diagnostics-${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
    return { blob: await response.blob(), filename };
  },
  validation: (id: string) => apiJson<ApiResults['validation']>(`/api/sessions/${encodeURIComponent(id)}/validation`),
  recovery: () => apiJson<ApiResults['recovery']>('/api/recovery'),
  maintenanceStatus: () => apiJson<ApiResults['maintenanceStatus']>('/api/maintenance/status'),
  pluginCompat: (sessionId?: string) => apiJson<ApiResults['pluginCompat']>(`/api/maintenance/plugin-compat${sessionId ? `?session=${encodeURIComponent(sessionId)}` : ''}`),
  backupNow: () => apiJson<ApiResults['backupNow']>('/api/maintenance/backup', { method: 'POST' }, 120000),
  verifyBackup: () => apiJson<ApiResults['verifyBackup']>('/api/maintenance/backup/verify', { method: 'POST' }, 120000),
  purgeNow: () => apiJson<ApiResults['purgeNow']>('/api/maintenance/purge', { method: 'POST' }, 120000),
  results: () => apiJson<ApiResults['results']>('/api/results'),
  result: (id: string) => apiJson<ApiResults['result']>(`/api/results/${encodeURIComponent(id)}`),
  resultReceipt: (id: string, body: { action: string; revision?: string }) => apiJson<ApiResults['resultReceipt']>(`/api/results/${encodeURIComponent(id)}/receipt`, jsonBody('POST', body)),
  sessionResearch: (id: string) => apiJson<ApiResults['sessionResearch']>(`/api/results/research/${encodeURIComponent(id)}`),
  scheduleHandoffPreview: (id: string) => apiJson<ApiResults['scheduleHandoffPreview']>(`/api/schedules/${encodeURIComponent(id)}/handoff`),
  scheduleHandoff: (id: string, body: { revision: string }) => apiJson<ApiResults['scheduleHandoff']>(`/api/schedules/${encodeURIComponent(id)}/handoff`, jsonBody('POST', body)),
  schedules: () => apiJson<ApiResults['schedules']>('/api/schedules'),
  createSchedule: (body: ScheduleInput) => apiJson<ApiResults['createSchedule']>('/api/schedules', jsonBody('POST', body)),
  controlSchedule: (id: string, body: { action: string; runAt?: string }) => apiJson<ApiResults['controlSchedule']>(`/api/schedules/${encodeURIComponent(id)}/control`, jsonBody('POST', body)),
  metrics: () => apiJson<ApiResults['metrics']>('/api/metrics'),
  regenerate: (id: string, requestId: string) => apiJson<ApiResults['regenerate']>(`/api/sessions/${encodeURIComponent(id)}/regenerate`, { method: 'POST', headers: { 'X-Pilot-Request-Id': requestId } }),
  deleteSession: (id: string) => apiJson<ApiResults['deleteSession']>(`/api/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  browserFocus: (id: string) => apiFetch(`/api/sessions/${encodeURIComponent(id)}/browser/focus`, { method: 'POST' }),
  browserToggle: (id: string, paused: boolean) => apiFetch(`/api/sessions/${encodeURIComponent(id)}/browser/${paused ? 'resume' : 'pause'}`, { method: 'POST' })
};

export type ApiClient = typeof api;
