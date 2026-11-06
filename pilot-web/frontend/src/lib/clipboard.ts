import type { TransferData } from '../types/input.js';
/**
 * 写剪贴板：优先异步 API，失败退回 execCommand。
 *
 * <p>内嵌 webview、非安全上下文（http + 非 localhost）里 `navigator.clipboard`
 * 可能直接缺失或拒绝，只走一条路意味着「点了没反应」。
 *
 * @returns {Promise<boolean>} 是否真的写进去了
 */
export async function writeClipboard(text: unknown, env: { navigator?: { clipboard?: { writeText?: (text: string) => Promise<void> } } | null; document?: Document | null } = {}) {
  const value = text == null ? '' : String(text);
  const nav = env.navigator || (typeof navigator === 'undefined' ? null : navigator);
  const doc = env.document || (typeof document === 'undefined' ? null : document);
  if (nav?.clipboard?.writeText) {
    try {
      await nav.clipboard.writeText(value);
      return true;
    } catch {
      // 权限被拒：继续走下面的兼容路径
    }
  }
  if (!doc || typeof doc.createElement !== 'function' || typeof doc.execCommand !== 'function') {
    return false;
  }
  const previous = doc.activeElement as HTMLElement | null;
  const selection = doc.getSelection?.();
  const ranges = selection ? Array.from({ length: selection.rangeCount }, (_, index) => selection.getRangeAt(index).cloneRange()) : [];
  let area: HTMLTextAreaElement | undefined;
  try {
    area = doc.createElement('textarea');
    area.value = value;
    area.setAttribute('readonly', '');
    area.style.cssText = 'position:fixed;top:-1000px;opacity:0';
    // 放在当前弹窗内，避免焦点约束把临时文本框的选区移走。
    (previous?.closest?.('[role="dialog"]') || doc.body).append(area);
    area.focus?.({ preventScroll: true });
    area.select();
    area.setSelectionRange?.(0, value.length);
    const ok = doc.execCommand('copy');
    return ok === true;
  } catch {
    return false;
  } finally {
    area?.remove();
    try {
      if (previous?.isConnected !== false) previous?.focus?.({ preventScroll: true });
      if (selection && ranges.length) {
        selection.removeAllRanges();
        for (const range of ranges) selection.addRange(range);
      }
    } catch { /* 复制期间原控件可能已卸载，不能把复制成功改成失败 */ }
  }
}

/** 两种浏览器剪贴板表示择一读取，避免同一文件重复加入。 */
export function readTransferFiles(data: TransferData | null | undefined): File[] {
  if (!data) return [];
  const files = Array.from(data.files || []);
  if (files.length) return files;
  return Array.from(data.items || [])
    .filter((item) => item.kind === 'file')
    .map((item) => { try { return item.getAsFile?.(); } catch { return null; } }).filter((file): file is File => Boolean(file));
}

/** File 不可读时识别传输标记和 URI；部分 Linux 文件管理器只提供这些。普通路径文本仍可粘贴。 */
export function hasFileTransferHint(data: TransferData | null | undefined) {
  if (Array.from(data?.types || []).includes('Files')) return true;
  if (!data?.getData) return false;
  try {
    return ['text/uri-list', 'x-special/gnome-copied-files'].some(type =>
      /(?:^|[\r\n])file:\/\//i.test(data.getData!(type) || ''));
  } catch { return false; }
}
