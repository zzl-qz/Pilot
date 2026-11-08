import type { MessageAttachment } from '../types/runtime.js';
import type { AttachmentMarker, MessagePart } from '../types/input.js';
/**
 * 输入框附件相关的纯函数：限制校验、文件类型徽标、大小格式化，
 * 以及把服务端内联进正文的文件块还原成「正文 + 文件卡片」。
 *
 * 服务端对文本文件的处理是把内容直接拼进消息正文（见 AttachmentService），
 * 这样任何模型都能读，但界面不能把几百行文件内容当成用户说的话渲染。
 * 这里按同一套标记把它们拆出来，队列和对话气泡都复用。
 */

// 单条消息允许批量附加图片或文件，避免截图类任务被过早截断。
export const MAX_ATTACHMENTS = 64;
export const MAX_ATTACHMENT_BYTES = 2 * 1024 * 1024;

const IMAGE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'webp', 'gif', 'svg', 'bmp', 'ico']);
const CODE_EXTENSIONS = new Set([
  'java', 'kt', 'kts', 'js', 'mjs', 'ts', 'tsx', 'jsx', 'css', 'html', 'vue', 'svelte', 'py', 'go', 'rs',
  'c', 'h', 'cpp', 'hpp', 'cs', 'rb', 'php', 'sh', 'bat', 'ps1', 'sql', 'gradle'
]);
const DATA_EXTENSIONS = new Set(['json', 'xml', 'yml', 'yaml', 'toml', 'ini', 'cfg', 'conf', 'properties', 'csv', 'log', 'diff', 'patch', 'pom']);
const DOC_EXTENSIONS = new Set(['md', 'txt', 'pdf', 'doc', 'docx', 'rtf', 'ppt', 'pptx', 'xls', 'xlsx']);
const ARCHIVE_EXTENSIONS = new Set(['zip', 'tar', 'gz', 'tgz', '7z', 'rar', 'jar']);

function extensionOf(name: unknown) {
  const text = String(name || '');
  const dot = text.lastIndexOf('.');
  return dot > 0 && dot < text.length - 1 ? text.slice(dot + 1).toLowerCase() : '';
}

export function isImageAttachment(item: MessageAttachment | null | undefined) {
  if (!item) {
    return false;
  }
  const mime = String(item.mimeType || '').toLowerCase();
  if (mime.startsWith('image/')) {
    return true;
  }
  return (!mime || mime === 'application/octet-stream') && IMAGE_EXTENSIONS.has(extensionOf(item.name));
}

/** 文件卡片左侧的小徽标：扩展名大写（最多 4 个字符）+ 一个用来上色的类别。 */
export function fileBadge(name: unknown, mimeType = '') {
  const ext = extensionOf(name);
  let tone = 'file';
  if (IMAGE_EXTENSIONS.has(ext) || String(mimeType).startsWith('image/')) {
    tone = 'image';
  } else if (CODE_EXTENSIONS.has(ext)) {
    tone = 'code';
  } else if (DATA_EXTENSIONS.has(ext)) {
    tone = 'data';
  } else if (DOC_EXTENSIONS.has(ext)) {
    tone = 'doc';
  } else if (ARCHIVE_EXTENSIONS.has(ext)) {
    tone = 'archive';
  }
  return { label: (ext || 'file').slice(0, 4).toUpperCase(), tone };
}

export function formatBytes(bytes: unknown) {
  const size = Number(bytes);
  if (!Number.isFinite(size) || size < 0) {
    return '';
  }
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(size < 10 * 1024 ? 1 : 0)} KB`;
  }
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * 判断一个新文件能不能加入当前草稿。
 * 重复（同名同大小）直接静默忽略，其余情况返回一句能直接展示给用户的原因。
 */
export function checkIncomingFile(existing: readonly MessageAttachment[] | null | undefined, file: { name?: string; size: number; mimeType?: string; type?: string }): { ok: boolean; silent?: boolean; reason: string } {
  const name = (file && file.name) || '未命名文件';
  const list = Array.isArray(existing) ? existing : [];
  const mime = String((file && (file.mimeType || file.type)) || '').toLowerCase();
  const image = mime.startsWith('image/') || IMAGE_EXTENSIONS.has(extensionOf(name));
  if (!image && list.some((item) => item.name === name && item.size === file.size)) {
    return { ok: false, silent: true, reason: '' };
  }
  if (list.length >= MAX_ATTACHMENTS) {
    return { ok: false, reason: `一次最多附加 ${MAX_ATTACHMENTS} 个文件，「${name}」没有加入。` };
  }
  if (!file.size) {
    return { ok: false, reason: `「${name}」是空文件，已跳过。` };
  }
  if (file.size > MAX_ATTACHMENT_BYTES) {
    return {
      ok: false,
      reason: `「${name}」${formatBytes(file.size)}，超过单个 ${formatBytes(MAX_ATTACHMENT_BYTES)} 的上限，已跳过。`
    };
  }
  return { ok: true, reason: '' };
}

const INLINE_BLOCK = /\[附件 (.+?) \(([^()\]\s]+)\)\]\n([\s\S]*?)\n\[附件结束 \1\]/g;
// 后缀兼容两种文案：旧消息「需要时用工具读取」与现行「可用 read_attachment 读取」
const SAVED_BLOCK = /\[附件 (.+?) \(([^()\]\s]+)\) 已保存到 (.+?)，(?:需要时用工具读取|可用 read_attachment 读取)\]/g;

/**
 * 把正文里服务端拼进去的文件块拆出来。
 *
 * @returns {{body: string, files: Array<{name: string, mimeType: string, kind: 'inline'|'saved',
 *   lines: number, raw: string}>}} body 是去掉文件块后的用户原话，raw 保留原文便于编辑后原样拼回
 */
export function splitAttachmentMarkers(text: unknown): { body: string; files: AttachmentMarker[] } {
  const source = String(text || '');
  const found: { index: number; end: number; file: AttachmentMarker }[] = [];
  for (const match of source.matchAll(INLINE_BLOCK)) {
    found.push({
      index: match.index,
      end: match.index + match[0].length,
      file: {
        name: match[1],
        mimeType: match[2],
        kind: 'inline',
        lines: match[3] ? match[3].split('\n').length : 0,
        raw: match[0]
      }
    });
  }
  for (const match of source.matchAll(SAVED_BLOCK)) {
    found.push({
      index: match.index,
      end: match.index + match[0].length,
      file: { name: match[1], mimeType: match[2], kind: 'saved', lines: 0, raw: match[0], path: match[3] }
    });
  }
  if (!found.length) {
    return { body: source, files: [] };
  }
  found.sort((a, b) => a.index - b.index);
  const files = [];
  let body = '';
  let cursor = 0;
  for (const item of found) {
    if (item.index < cursor) {
      continue;
    }
    body += source.slice(cursor, item.index);
    cursor = item.end;
    const previousInline = item.file.kind === 'saved'
      ? [...files].reverse().find(file => file.kind === 'inline' && file.name === item.file.name && file.mimeType === item.file.mimeType && !file.path)
      : undefined;
    if (previousInline) {
      previousInline.path = item.file.path;
      previousInline.raw = `${previousInline.raw}\n\n${item.file.raw}`;
    } else {
      files.push(item.file);
    }
  }
  body += source.slice(cursor);
  return { body: body.replace(/\n{3,}/g, '\n\n').trim(), files };
}

/**
 * 按输入时光标记下的字符位置，把图片插回正文。
 * 位置和图片一一对应；没有位置的旧消息仍把图片放在正文后面。
 * 非图片附件没有插入位置，跟在最后。
 *
 * @returns {Array<{type:'text', text:string}|{type:'image', image: object}|{type:'files', files: object[]}>}
 */
export function layoutMessageParts(body: unknown, images: MessageAttachment[] | null | undefined, files: MessageAttachment[] | null | undefined, positions: readonly number[] = []): MessagePart[] {
  const text = String(body || '');
  const pics = Array.isArray(images) ? images : [];
  const spots = Array.isArray(positions) && positions.length === pics.length
    ? positions.map((value) => {
      const index = Number(value);
      if (!Number.isFinite(index)) {
        return 0;
      }
      return Math.max(0, Math.min(text.length, index));
    })
    : null;
  const parts: MessagePart[] = [];
  if (!spots) {
    if (text) {
      parts.push({ type: 'text', text });
    }
    pics.forEach((image) => parts.push({ type: 'image', image }));
  } else {
    let cursor = 0;
    pics.forEach((image, index) => {
      const at = Math.max(cursor, spots[index]);
      if (at > cursor) {
        parts.push({ type: 'text', text: text.slice(cursor, at) });
      }
      parts.push({ type: 'image', image });
      cursor = at;
    });
    if (cursor < text.length) {
      parts.push({ type: 'text', text: text.slice(cursor) });
    }
  }
  if (files && files.length) {
    parts.push({ type: 'files', files });
  }
  return parts;
}

/** {@link splitAttachmentMarkers} 的逆操作：编辑排队消息后，把原来的文件块接回正文末尾。 */
export function joinAttachmentMarkers(body: unknown, files: readonly Pick<AttachmentMarker, 'raw'>[] | null | undefined) {
  return [String(body || '').trim(), ...(files || []).map((file) => file.raw)]
    .filter((part) => part)
    .join('\n\n');
}

/**
 * 附件缩略图地址。本地刚选的文件有 dataBase64，历史/队列里的图片只有服务端落盘路径，
 * 按文件名走附件接口取回。
 */
export function attachmentUrl(sessionId: string | null | undefined, item: MessageAttachment | null | undefined) {
  if (!item) {
    return '';
  }
  if (item.dataBase64) {
    return item.dataBase64;
  }
  if (item.url) {
    return item.url;
  }
  const path = String(item.path || '');
  const file = path.slice(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
  if (!sessionId || !file) {
    return '';
  }
  const name = item.name ? `?name=${encodeURIComponent(item.name)}` : '';
  return `/api/sessions/${encodeURIComponent(sessionId)}/attachments/${encodeURIComponent(file)}${name}`;
}
