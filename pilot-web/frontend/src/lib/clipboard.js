/**
 * 写剪贴板：优先异步 API，失败退回 execCommand。
 *
 * <p>内嵌 webview、非安全上下文（http + 非 localhost）里 `navigator.clipboard`
 * 可能直接缺失或拒绝，只走一条路意味着「点了没反应」。
 *
 * @returns {Promise<boolean>} 是否真的写进去了
 */
export async function writeClipboard(text, env = {}) {
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
  try {
    const area = doc.createElement('textarea');
    area.value = value;
    area.setAttribute('readonly', '');
    area.style.cssText = 'position:fixed;top:-1000px;opacity:0';
    doc.body.append(area);
    area.select();
    const ok = doc.execCommand('copy');
    area.remove();
    return ok === true;
  } catch {
    return false;
  }
}

/** 两种浏览器剪贴板表示择一读取，避免同一文件重复加入。 */
export function readTransferFiles(data) {
  if (!data) return [];
  const files = Array.from(data.files || []);
  if (files.length) return files;
  return Array.from(data.items || [])
    .filter((item) => item.kind === 'file')
    .map((item) => item.getAsFile()).filter(Boolean);
}
