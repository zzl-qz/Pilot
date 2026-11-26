import { api, ApiError } from '../api.js';
import type { InputMessage, SubmissionResult } from '../types/api.js';
import { errorInfo } from './errors.js';
import { safeLocalStorage, type SafeStorage } from './safe-storage.js';

export interface PendingSubmission { requestId: string; sessionId: string; createdAt: number; preview: string; tabId?: string; status?: 'in-flight' | 'unknown'; leaseUntil?: number; resolved?: 'accepted' | 'rejected' }
/** 无法解析的旧凭据：保留原文供核对与导出，不参与任何会话的阻止判断。 */
export interface CorruptedSubmission { key: string; raw: string }
type Outcome = { state: SubmissionResult['state'] | 'blocked'; message?: string; response?: SubmissionResult['response'] };
export type SubmissionOperation = (requestId: string) => ReturnType<typeof api.sendMessage>;
const PREFIX = 'pilot.pending-submission:';
const EVENT = 'pilot:submissions-changed';
const unknownMessage = '提交结果尚未确认，任务可能已经执行。请先核对结果，当前草稿已保留；不会自动重发。';
const active = new Set<string>();
const listeners = new Set<() => void>();
const changed = () => { for (const listener of listeners) listener(); };
const LEASE_MS = 15000;
const newTabId = () => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;

/** 仅存凭据与短摘要，不把附件再次写入浏览器存储。每个请求独立键，窗口之间不会覆盖凭据。 */
export function createSubmissions(client = api, storage: () => SafeStorage = safeLocalStorage, tabId = newTabId()) {
  const acknowledging = new Set<string>();
  function acknowledge(record: PendingSubmission) {
    if (acknowledging.has(record.requestId)) return;
    acknowledging.add(record.requestId);
    void (async () => {
      try { await client.acknowledgeSubmission(record.requestId); forget(record); }
      catch { /* 已确定结果仍保留确认凭据；下次访问、联网或聚焦时只重试确认，不重发消息。 */ }
      finally { acknowledging.delete(record.requestId); }
    })();
  }
  /**
   * 扫描全部凭据。单条损坏不能影响其他会话：
   * 解析失败的记录单独返回，交给界面导出或删除，而不是抛出异常阻断发送。
   */
  function scan(sessionId: string | null): { records: PendingSubmission[]; corrupted: CorruptedSubmission[] } {
    const data = storage();
    const records: PendingSubmission[] = [];
    const corrupted: CorruptedSubmission[] = [];
    for (let i = 0; i < data.length; i++) {
      const key = data.key(i);
      if (!key?.startsWith(PREFIX)) continue;
      const raw = data.getItem(key) ?? '';
      let record: PendingSubmission | null = null;
      try {
        record = JSON.parse(raw) as PendingSubmission | null;
      } catch {
        corrupted.push({ key, raw });
        continue;
      }
      if (!record || typeof record.requestId !== 'string' || typeof record.sessionId !== 'string') {
        corrupted.push({ key, raw });
        continue;
      }
      if (record.resolved === 'accepted' || record.resolved === 'rejected') acknowledge(record);
      else if (record.sessionId === (sessionId ?? record.sessionId)) records.push(record);
    }
    records.sort((a, b) => a.createdAt - b.createdAt);
    return { records, corrupted };
  }
  function pending(sessionId: string): PendingSubmission[] {
    return scan(sessionId).records;
  }
  function corrupted(): CorruptedSubmission[] {
    return scan(null).corrupted;
  }
  function forget(record: PendingSubmission) {
    storage().removeItem(PREFIX + record.requestId);
    changed();
  }
  /** 删除一条损坏凭据：只在用户核对任务历史并明确确认后调用。 */
  function forgetCorrupted(key: string) {
    if (key.startsWith(PREFIX)) {
      storage().removeItem(key);
      changed();
    }
  }
  /** 导出损坏凭据的原文，方便保留证据后再删除。 */
  function exportCorrupted(): string {
    return corrupted().map(item => `### ${item.key}\n${item.raw}`).join('\n\n');
  }
  function finish(record: PendingSubmission, state: 'accepted' | 'rejected') {
    const resolved = { ...record, resolved: state };
    storage().setItem(PREFIX + record.requestId, JSON.stringify(resolved));
    acknowledge(resolved);
    changed();
  }
  async function check(record: PendingSubmission): Promise<Outcome> {
    try {
      const result = await client.submissionResult(record.requestId);
      if (result?.state === 'accepted' || result?.state === 'rejected') {
        finish(record, result.state);
        return { state: result.state, message: result.response?.message, ...(result.response ? { response: result.response } : {}) };
      }
    } catch { /* 查询失败保留凭据，不发送原请求。 */ }
    return { state: 'unknown', message: unknownMessage };
  }
  async function submit(sessionId: string, body: InputMessage, beforeSend: () => boolean, operation?: SubmissionOperation): Promise<Outcome> {
    const lock = `${tabId}:${sessionId}`;
    if (active.has(lock)) return { state: 'blocked', message: '消息正在提交，请等待本次结果。' };
    active.add(lock);
    try { return await perform(sessionId, body, beforeSend, operation); }
    finally { active.delete(lock); }
  }
  const blockedMessage = (existing: PendingSubmission[]) => {
    const inFlight = existing.some((item) => item.status === 'in-flight' && (item.leaseUntil ?? 0) > Date.now() && item.tabId !== tabId);
    return inFlight ? '另一标签页正在提交，请等待本次结果。' : unknownMessage;
  };
  async function perform(sessionId: string, body: InputMessage, beforeSend: () => boolean, operation?: SubmissionOperation): Promise<Outcome> {
    let record: PendingSubmission | undefined;
    try {
      const existing = pending(sessionId);
      if (existing.length) return { state: 'blocked', message: blockedMessage(existing) };
      const token = await client.submissionToken();
      if (!token?.requestId) throw Error('服务器未提供提交凭据，请更新服务端后重试。');
      // 等待令牌期间可能切换会话、窗口或模型；此时尚未提交业务请求。
      const concurrent = pending(sessionId);
      if (concurrent.length) return { state: 'blocked', message: blockedMessage(concurrent) };
      record = { requestId: token.requestId, sessionId, createdAt: Date.now(), preview: body.content.slice(0, 100) || '附件消息', tabId, status: 'in-flight', leaseUntil: Date.now() + LEASE_MS };
      const key = PREFIX + record.requestId;
      const value = JSON.stringify(record);
      storage().setItem(key, value);
      if (storage().getItem(key) !== value) throw Error('浏览器无法保存提交凭据，请允许站点存储后重试。');
      if (!beforeSend()) { forget(record); return { state: 'blocked' }; }
      changed();
    } catch (error) {
      return { state: 'rejected', message: '尚未发送：' + errorInfo(error).message };
    }
    if (!record) return { state: 'blocked' };
    try {
      const response = await (operation ? operation(record.requestId) : client.sendMessage(sessionId, body, record.requestId));
      if (!response || response.sessionId !== sessionId) throw Error('提交响应不完整');
      finish(record, 'accepted');
      return { state: 'accepted', response };
    } catch (error) {
      if (error instanceof ApiError && error.status >= 400 && error.status < 500 && !error.uncertain) {
        try { finish(record, 'rejected'); } catch { return { state: 'unknown', message: unknownMessage }; }
        return { state: 'rejected', message: error.message };
      }
      // 只读核对一次；5xx、超时、取消及正文读取失败都不是明确拒绝。
      record.status = 'unknown';
      record.leaseUntil = undefined;
      storage().setItem(PREFIX + record.requestId, JSON.stringify(record));
      return await check(record);
    }
  }
  return { pending, corrupted, forget, forgetCorrupted, exportCorrupted, submit, check };
}
export const submissions = createSubmissions();
export function watchSubmissions(listener: () => void) {
  listeners.add(listener);
  const storage = (event: StorageEvent) => { if (!event.key || event.key.startsWith(PREFIX)) listener(); };
  window.addEventListener('storage', storage);
  window.addEventListener(EVENT, listener);
  window.addEventListener('focus', listener);
  window.addEventListener('online', listener);
  return () => {
    listeners.delete(listener); window.removeEventListener('storage', storage); window.removeEventListener(EVENT, listener);
    window.removeEventListener('focus', listener); window.removeEventListener('online', listener);
  };
}
