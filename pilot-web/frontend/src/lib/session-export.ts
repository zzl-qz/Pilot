import { apiFetch } from '../api.js';
import { writeClipboard } from './clipboard.js';
import { errorInfo } from './errors.js';
import { saveTextFile, transcriptFileName } from './transcript.js';

export function downloadSessionExport(sessionId: string, title: string) {
  return saveSessionMarkdown(title, async () => {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(sessionId)}/export`, {}, 120000);
    const text = await response.text();
    if (!response.ok) throw new Error(text.trim() || `HTTP ${response.status}`);
    return text;
  });
}

const cancelled = { mode: 'cancelled', notice: '已取消导出。' } as const;

export async function saveSessionMarkdown(title: string, loadText: () => string | Promise<string>) {
  const name = transcriptFileName(title);
  const desktop = window.pilotDesktop;
  if (desktop?.saveSessionMarkdown) {
    const result = await desktop.saveSessionMarkdown(name, await loadText());
    if (!result.ok) throw new Error(result.error || '桌面保存失败，请重新导出。');
    if (result.canceled) return cancelled;
    if (!result.path) throw new Error('桌面未返回保存位置，请重新导出。');
    return { mode: 'saved' as const, notice: `会话已保存到 ${result.path}` };
  }

  let handle: FileSystemFileHandle | undefined;
  if (typeof window.showSaveFilePicker === 'function') {
    // 文件选择器必须在点击处理期间打开，大会话的网络请求可能耗尽用户激活时间。
    try {
      handle = await window.showSaveFilePicker({
        suggestedName: name, types: [{ description: 'Markdown', accept: { 'text/markdown': ['.md'] } }]
      });
    } catch (error) {
      const reason = errorInfo(error).name;
      if (reason === 'AbortError') return cancelled;
      if (reason !== 'SecurityError' && reason !== 'NotAllowedError') throw error;
    }
  }

  const text = await loadText();
  if (handle) {
    const stream = await handle.createWritable();
    try {
      await stream.write(text);
      await stream.close();
    } catch (error) {
      await stream.abort().catch(() => {});
      throw error;
    }
    return { mode: 'saved' as const, notice: `会话已保存为 ${handle.name}` };
  }
  if (saveTextFile(text, name, typeof document === 'undefined' ? null : document)) {
    return { mode: 'download' as const, notice: `已请求下载 ${name}，请查看浏览器下载记录。可在浏览器下载设置中开启每次询问保存位置。` };
  }
  if (await writeClipboard(text)) return { mode: 'clipboard' as const, notice: '当前环境无法保存文件，已复制会话全文。' };
  return { mode: 'manual' as const, text };
}

export function exportFailureMessage(error: unknown) {
  return `导出会话失败：${errorInfo(error).message || '请重试'}`;
}
