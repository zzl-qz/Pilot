import { voiceFilename } from './lib/voice-input.js';
/**
 * 统一请求入口：带超时与取消（与旧版 apiFetch 行为一致）。
 * 后端卡住或网络断开时抛可读错误，界面据此提示。
 */
const NATIVE_FETCH = window.fetch.bind(window);
const REQUEST_TIMEOUT_MS = 20000;

export async function apiFetch(input, init = {}, timeoutMs = REQUEST_TIMEOUT_MS) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (init.signal?.aborted) controller.abort();
  init.signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await NATIVE_FETCH(input, { ...init, signal: controller.signal });
  } catch (error) {
    if (init.signal?.aborted) throw new DOMException('请求已取消', 'AbortError');
    if (error && error.name === 'AbortError') {
      throw new Error(`请求超时（${Math.round(timeoutMs / 1000)} 秒）`);
    }
    throw error;
  } finally {
    clearTimeout(timer);
    init.signal?.removeEventListener('abort', abort);
  }
}

export async function apiJson(url, init, timeoutMs) {
  const response = await apiFetch(url, init, timeoutMs);
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      const body = await response.json();
      // Spring 的错误响应体固定带 error（HTTP 状态短语，如「Bad Gateway」），
      // 真正的原因在 message 里。优先 message，否则界面只会显示 "Bad Gateway" 这类英文短语。
      const detail = typeof body?.message === 'string' ? body.message.trim() : '';
      const fallback = typeof body?.error === 'string' ? body.error.trim() : '';
      message = detail && detail !== 'No message available' ? detail : fallback || message;
    } catch { /* 保持 HTTP 状态码文案 */ }
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  // 204 / 空响应没有 JSON 正文：直接 return response.json() 会抛「Unexpected end of JSON input」，
  // 让删除会话、删除项目这类「成功但无返回体」的接口看起来全部失败
  if (response.status === 204 || response.headers?.get?.('content-length') === '0') {
    return null;
  }
  if (typeof response.text === 'function') {
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  }
  return response.json();
}

const jsonBody = (method, body) => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
export const api = {
  recurring: () => apiJson('/api/automation/series'),
  createRecurring: body => apiJson('/api/automation/series', jsonBody('POST', body)),
  controlRecurring: (id, body) => apiJson(`/api/automation/series/${encodeURIComponent(id)}`, jsonBody('POST', body)),
  scheduleTimes: body => apiJson('/api/automation/times', jsonBody('POST', body)),
  templates: () => apiJson('/api/automation/templates'),
  saveTemplate: body => apiJson('/api/automation/templates', jsonBody('POST', body)),
  deleteTemplate: (id, version) => apiJson(`/api/automation/templates/${encodeURIComponent(id)}?version=${version}`, { method: 'DELETE' }),
  searchMessages: ({ q = '', sessionId = '', saved = false, offset = 0 }) => apiJson('/api/library/search?' + new URLSearchParams({ q, sessionId, saved, offset })),
  bookmarks: session => apiJson(`/api/library/${encodeURIComponent(session)}/bookmarks`),
  saveBookmark: (session, sequence) => apiJson(`/api/library/${encodeURIComponent(session)}/bookmarks/${sequence}`, { method: 'PUT' }),
  deleteBookmark: (session, sequence) => apiJson(`/api/library/${encodeURIComponent(session)}/bookmarks/${sequence}`, { method: 'DELETE' }),
  importBookmarks: (session, sequences) => apiJson(`/api/library/${encodeURIComponent(session)}/bookmarks/import`, jsonBody('POST', { sequences })),
  deliverySettings: () => apiJson('/api/deliveries/settings'),
  saveDeliverySettings: body => apiJson('/api/deliveries/settings', jsonBody('PUT', body)),
  deliveries: () => apiJson('/api/deliveries'),
  claimDelivery: () => apiJson('/api/deliveries/claim', { method: 'POST' }),
  deliveryReceipt: (id, body) => apiJson(`/api/deliveries/${encodeURIComponent(id)}/receipt`, jsonBody('POST', body)),
  retryDelivery: (id, version) => apiJson(`/api/deliveries/${encodeURIComponent(id)}/retry`, jsonBody('POST', { version })),
  voiceSettings: () => apiJson('/api/voice/settings'),
  saveVoiceSettings: body => apiJson('/api/voice/settings', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  transcribeVoice: (blob, signal) => {
    const body = new FormData(); body.append('file', blob, voiceFilename(blob.type));
    return apiJson('/api/voice/transcriptions', { method: 'POST', body, signal }, 75000);
  },
  health: () => apiFetch('/api/health'),
  openBrowserWindow: () => apiJson('/api/desktop/browser-window', { method: 'POST' }, 70000),
  sessions: () => apiJson('/api/sessions'),
  session: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}`),
  createSession: (body) => apiJson('/api/sessions', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) }),
  /** 从当前对话分叉一条临时侧边对话（对齐 Codex 的 /side）。 */
  createSideChat: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/side`, { method: 'POST' }),
  renameSession: (id, title) => apiJson(`/api/sessions/${encodeURIComponent(id)}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title }) }),
  archiveSession: (id, archived = true) => apiJson(`/api/sessions/${encodeURIComponent(id)}/archive`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ archived }) }),
  switchWorkspace: (id, workspace) => apiJson(`/api/sessions/${encodeURIComponent(id)}/workspace`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ workspace }) }),
  sendMessage: (id, body) => apiFetch(`/api/sessions/${encodeURIComponent(id)}/messages`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }, 120000).then((r) => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); }),
  resend: (id, body) => apiJson(`/api/sessions/${encodeURIComponent(id)}/resend`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  steer: (id, body) => apiJson(`/api/sessions/${encodeURIComponent(id)}/steer`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  continueRun: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/continue`, { method: 'POST' }),
  pauseRun: (runId) => apiJson(`/api/runs/${encodeURIComponent(runId)}/pause`, { method: 'POST' }),
  cancelRun: (runId) => apiJson(`/api/runs/${encodeURIComponent(runId)}/cancel`, { method: 'POST' }),
  activeRun: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/runs/active`),
  approval: (callId, approved) => apiJson(`/api/approvals/${encodeURIComponent(callId)}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ approved }) }),
  history: (id, { limit, before, after } = {}) => {
    const params = new URLSearchParams();
    if (limit) params.set('limit', limit);
    if (before != null) params.set('before', before);
    if (after != null) params.set('after', after);
    return apiJson(`/api/sessions/${encodeURIComponent(id)}/history?${params}`);
  },
  checkpoints: (id, sequence) => apiJson(`/api/sessions/${encodeURIComponent(id)}/checkpoints?sequence=${sequence}`),
  // 回退必须带 mode（code / conversation / both），分支带上 revision 做乐观锁
  restoreCheckpoint: (id, body) => apiJson(`/api/sessions/${encodeURIComponent(id)}/checkpoints/restore`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) }),
  forkCheckpoint: (id, body) => apiJson(`/api/sessions/${encodeURIComponent(id)}/checkpoints/fork`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) }),
  task: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/task`),
  saveTask: (id, body) => apiJson(`/api/sessions/${encodeURIComponent(id)}/task`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  queue: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/queue`),
  updateQueueItem: (id, index, body) => apiJson(`/api/sessions/${encodeURIComponent(id)}/queue/${index}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  deleteQueueItem: (id, index, revision) => apiJson(`/api/sessions/${encodeURIComponent(id)}/queue/${index}?revision=${encodeURIComponent(revision || '')}`, { method: 'DELETE' }),
  moveQueueItem: (id, from, to, revision) => apiJson(`/api/sessions/${encodeURIComponent(id)}/queue/${from}/move?to=${to}&revision=${encodeURIComponent(revision || '')}`, { method: 'POST' }),
  steerQueueItem: (id, index, body) => apiJson(`/api/sessions/${encodeURIComponent(id)}/queue/${index}/steer`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  plan: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/plan`),
  context: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/context`),
  compactContext: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/context/compact`, { method: 'POST' }),
  sandboxAction: (id, sandboxId, action) => apiJson(`/api/sessions/${encodeURIComponent(id)}/multi/sandboxes/${encodeURIComponent(sandboxId)}/${action}`, { method: 'POST' }),
  /** 接受前的产物清单与校验结论：近两版差异 + 依赖，避免盲批。 */
  sandboxManifest: (id, sandboxId) => apiJson(`/api/sessions/${encodeURIComponent(id)}/multi/sandboxes/${encodeURIComponent(sandboxId)}/manifest`),
  resolveArbitration: (id, arbitrationId, body) => apiJson(`/api/sessions/${encodeURIComponent(id)}/multi/arbitrations/${encodeURIComponent(arbitrationId)}/resolve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  diff: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/diff`),
  /** 单文件补丁：整份 diff 被截断时按需再取一次。 */
  fileDiff: (id, path) => apiJson(`/api/sessions/${encodeURIComponent(id)}/diff/file?path=${encodeURIComponent(path)}`),
  restoreChanges: (id, body) => apiJson(`/api/sessions/${encodeURIComponent(id)}/diff/restore`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) }),
  capabilities: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/capabilities`),
  browserStatus: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/browser/status`),
  stopCommand: (id, taskId) => apiJson(`/api/sessions/${encodeURIComponent(id)}/commands/${encodeURIComponent(taskId)}/stop`, { method: 'POST' }),
  runChanges: (runId) => apiJson(`/api/runs/${encodeURIComponent(runId)}/changes`),
  rollbackRun: (runId) => apiJson(`/api/runs/${encodeURIComponent(runId)}/rollback`, { method: 'POST' }),
  branch: (id, name) => apiJson(`/api/sessions/${encodeURIComponent(id)}/branch`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name }) }),
  files: (id, path) => apiJson(`/api/sessions/${encodeURIComponent(id)}/files?path=${encodeURIComponent(path || '')}`),
  workspaceTree: (root, path = '') => apiJson(`/api/workspaces/files?root=${encodeURIComponent(root)}&path=${encodeURIComponent(path)}`),
  workspaceFile: (root, path) => apiJson(`/api/workspaces/file?root=${encodeURIComponent(root)}&path=${encodeURIComponent(path)}`),
  filesFlat: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/files/flat`),
  workspaceFiles: (path) => apiJson(`/api/workspaces/files/flat?path=${encodeURIComponent(path)}`),
  file: (id, path) => apiJson(`/api/sessions/${encodeURIComponent(id)}/file?path=${encodeURIComponent(path)}`),
  projects: () => apiJson('/api/projects'),
  createProject: (body) => apiJson('/api/projects', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  renameProject: (id, body) => apiJson(`/api/projects/${encodeURIComponent(id)}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  saveProjectFolders: (id, folders) => apiJson(`/api/projects/${encodeURIComponent(id)}/folders`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ folders }) }),
  deleteProject: (id) => apiJson(`/api/projects/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  worktrees: (body) => apiJson('/api/worktrees', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  worktreeInfo: (path) => apiJson(`/api/worktrees?path=${encodeURIComponent(path || '')}`),
  worktreeRemove: (path) => apiJson(`/api/worktrees?path=${encodeURIComponent(path || '')}`, { method: 'DELETE' }),
  worktreeHandoff: (sessionId) => apiJson(`/api/worktrees/handoff?sessionId=${encodeURIComponent(sessionId)}`, { method: 'POST' }),
  workspaceRoots: () => apiJson('/api/workspaces/roots'),
  workspaceBrowse: (path) => apiJson(`/api/workspaces/browse?path=${encodeURIComponent(path || '')}`),
  settings: () => apiJson('/api/settings'),
  // 后端是 PUT：之前发 POST 会 405，权限与模型保存点了没反应
  savePermissionMode: (mode) => apiJson('/api/settings/permission-mode', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mode }) }),
  saveModel: (body) => apiJson('/api/settings/model', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  clearModelKey: () => apiJson('/api/settings/model/api-key', { method: 'DELETE' }),
  modelRuntime: () => apiJson('/api/settings/model/runtime'),
  saveModelRuntime: (body) => apiJson('/api/settings/model/runtime', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  probeModels: (body) => apiJson('/api/settings/model/models', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  skills: (sessionId) => apiJson(`/api/skills${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''}`),
  skillsPreview: (body) => apiJson('/api/skills/preview', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  skillsInstall: (body) => apiJson('/api/skills/install', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  usage: (days) => apiJson(`/api/usage?days=${days}`),
  diagnostics: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/diagnostics`),
  validation: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/validation`),
  recovery: () => apiJson('/api/recovery'),
  maintenanceStatus: () => apiJson('/api/maintenance/status'),
  backupNow: () => apiJson('/api/maintenance/backup', { method: 'POST' }, 120000),
  purgeNow: () => apiJson('/api/maintenance/purge', { method: 'POST' }, 120000),
  results: () => apiJson('/api/results'),
  result: (id) => apiJson(`/api/results/${encodeURIComponent(id)}`),
  resultReceipt: (id, body) => apiJson(`/api/results/${encodeURIComponent(id)}/receipt`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  sessionResearch: (id) => apiJson(`/api/results/research/${encodeURIComponent(id)}`),
  scheduleHandoffPreview: (id) => apiJson(`/api/schedules/${encodeURIComponent(id)}/handoff`),
  scheduleHandoff: (id, body) => apiJson(`/api/schedules/${encodeURIComponent(id)}/handoff`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  schedules: () => apiJson('/api/schedules'),
  createSchedule: (body) => apiJson('/api/schedules', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  controlSchedule: (id, body) => apiJson(`/api/schedules/${encodeURIComponent(id)}/control`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  metrics: () => apiJson('/api/metrics'),
  regenerate: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}/regenerate`, { method: 'POST' }),
  deleteSession: (id) => apiJson(`/api/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  browserFocus: (id) => apiFetch(`/api/sessions/${encodeURIComponent(id)}/browser/focus`, { method: 'POST' }),
  browserToggle: (id, paused) => apiFetch(`/api/sessions/${encodeURIComponent(id)}/browser/${paused ? 'resume' : 'pause'}`, { method: 'POST' })
};
