import type { DraftAttachment } from '../../types/input.js';
import type { AttachmentLimitsBudget } from '../../types/server.js';
let localId = 0;

/** 仅用于草稿内去重，不是鉴权令牌；普通 HTTP 下 randomUUID 可能不可用。 */
export function attachmentId(cryptoApi: Partial<Pick<Crypto, 'randomUUID' | 'getRandomValues'>> | null | undefined = globalThis.crypto) {
  try {
    if (typeof cryptoApi?.randomUUID === 'function') return cryptoApi.randomUUID();
    if (typeof cryptoApi?.getRandomValues === 'function') {
      const bytes = cryptoApi.getRandomValues(new Uint8Array(16));
      return 'attachment-' + Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
    }
  } catch { /* 受限 WebView 继续使用本地唯一编号 */ }
  return `attachment-${Date.now().toString(36)}-${(++localId).toString(36)}`;
}

/** 服务端消息接口接收 dataBase64；读取阶段不创建会话或上传。 */
export function readAttachmentFile(file: File): Promise<DraftAttachment> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error(`无法读取「${file.name}」`));
    reader.onabort = () => reject(new Error(`已取消读取「${file.name}」`));
    reader.onload = () => resolve({
      id: attachmentId(), name: file.name || 'clipboard.png',
      mimeType: file.type || 'application/octet-stream', size: file.size,
      dataBase64: reader.result as string
    });
    reader.readAsDataURL(file);
  });
}

/**
 * 桌面端系统剪贴板只给文件路径时，转由主进程读取。
 * 浏览器没有这个桥，必须让用户拖入或使用文件选择器。
 */
export async function readDesktopClipboardFiles(limits: AttachmentLimitsBudget): Promise<DraftAttachment[]> {
  const bridge = window.pilotDesktop;
  if (!bridge?.readClipboardFiles) {
    throw new Error('当前环境不支持从系统剪贴板读取文件，请拖入文件或使用「添加文件或图片」。');
  }
  const result = await bridge.readClipboardFiles(limits);
  if (!result?.ok || !Array.isArray(result.files)) {
    throw new Error(result?.error || '系统剪贴板中没有可读取的文件。');
  }
  return result.files.map(file => ({
    id: attachmentId(),
    name: file.name || '剪贴板文件',
    mimeType: file.mimeType || 'application/octet-stream',
    size: file.size,
    dataBase64: file.dataBase64
  }));
}
