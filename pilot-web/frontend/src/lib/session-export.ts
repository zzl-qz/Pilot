import { apiFetch } from '../api.js';
import { writeClipboard } from './clipboard.js';
import { errorInfo } from './errors.js';
import { saveTextFile, transcriptFileName } from './transcript.js';

/** 保存服务端导出的完整会话。不能下载时退回剪贴板，仍不行就把全文交回调用方。 */
export async function downloadSessionExport(sessionId: string, fallbackTitle: string) {
  const response = await apiFetch(`/api/sessions/${encodeURIComponent(sessionId)}/export`, {}, 120000);
  const text = await response.text();
  if (!response.ok) {
    throw new Error(text.trim() || '导出会话失败');
  }
  const header = response.headers.get('Content-Disposition') || '';
  const quoted = /filename="([^"]+)"/.exec(header);
  const name = quoted?.[1] || transcriptFileName(fallbackTitle);
  if (saveTextFile(text, name, typeof document === 'undefined' ? null : document)) {
    return { mode: 'file' as const, text, name };
  }
  if (await writeClipboard(text)) {
    return { mode: 'clipboard' as const, text, name };
  }
  return { mode: 'manual' as const, text, name };
}

export function exportFailureMessage(error: unknown) {
  return errorInfo(error).message || '导出会话失败';
}
