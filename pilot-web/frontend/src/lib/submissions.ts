import { api, ApiError } from '../api.js';
import type { InputMessage, SubmissionResult } from '../types/api.js';
import { errorInfo } from './errors.js';

export interface PendingSubmission { requestId: string; sessionId: string; createdAt: number; preview: string; resolved?: 'accepted' | 'rejected' }
type Outcome = { state: SubmissionResult['state'] | 'blocked'; message?: string; response?: SubmissionResult['response'] };
export type SubmissionOperation = (requestId: string) => ReturnType<typeof api.sendMessage>;
const PREFIX = 'pilot.pending-submission:';
const EVENT = 'pilot:submissions-changed';
const unknownMessage = '提交结果尚未确认，任务可能已经执行。请先核对结果，当前草稿已保留；不会自动重发。';
const active = new Set<string>();
const listeners = new Set<() => void>();
const changed = () => { for (const listener of listeners) listener(); };

/** 仅存凭据与短摘要，不把附件再次写入浏览器存储。每个请求独立键，窗口之间不会覆盖凭据。 */
export function createSubmissions(client = api, storage: () => Storage = () => localStorage) {
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
  function pending(sessionId: string): PendingSubmission[] {
    const data = storage();
    const records: PendingSubmission[] = [];
    for (let i = 0; i < data.length; i++) {
      const key = data.key(i);
      if (!key?.startsWith(PREFIX)) continue;
      const record = JSON.parse(data.getItem(key) || 'null') as PendingSubmission | null;
      if (!record || typeof record.requestId !== 'string' || typeof record.sessionId !== 'string') throw Error('待确认提交记录损坏，请先核对任务历史和浏览器存储。');
      if (record.resolved === 'accepted' || record.resolved === 'rejected') acknowledge(record);
      else if (record.sessionId === sessionId) records.push(record);
    }
    return records.sort((a, b) => a.createdAt - b.createdAt);
  }
  function forget(record: PendingSubmission) {
    storage().removeItem(PREFIX + record.requestId);
    changed();
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
    if (active.has(sessionId)) return { state: 'blocked', message: '消息正在提交，请等待本次结果。' };
    active.add(sessionId);
    try { return await perform(sessionId, body, beforeSend, operation); }
    finally { active.delete(sessionId); }
  }
  async function perform(sessionId: string, body: InputMessage, beforeSend: () => boolean, operation?: SubmissionOperation): Promise<Outcome> {
    let record: PendingSubmission | undefined;
    try {
      if (pending(sessionId).length) return { state: 'blocked', message: unknownMessage };
      const token = await client.submissionToken();
      if (!token?.requestId) throw Error('服务器未提供提交凭据，请更新服务端后重试。');
      // 等待令牌期间可能切换会话、窗口或模型；此时尚未提交业务请求。
      if (pending(sessionId).length) return { state: 'blocked', message: unknownMessage };
      record = { requestId: token.requestId, sessionId, createdAt: Date.now(), preview: body.content.slice(0, 100) || '附件消息' };
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
      return await check(record);
    }
  }
  return { pending, submit, check, forget };
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
