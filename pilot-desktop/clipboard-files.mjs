import { lstatSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isRegularFileStat, isWindowsUncPath, parseClipboardFilePaths } from './protocol.mjs';

// 系统文件管理器复制文件后，浏览器剪贴板事件通常只有路径；按用户主动 Ctrl+V 读取一次。
const CLIPBOARD_IMAGE_MIME = new Map([
  ['.png', 'image/png'], ['.jpg', 'image/jpeg'], ['.jpeg', 'image/jpeg'],
  ['.webp', 'image/webp'], ['.gif', 'image/gif']
]);

function clipboardLimits(raw) {
  const value = raw && typeof raw === 'object' ? raw : {};
  const limit = (item, fallback, max) => {
    const number = Number(item);
    return Number.isSafeInteger(number) && number > 0 ? Math.min(number, max) : fallback;
  };
  return {
    maxFiles: limit(value.maxFiles, 64, 64),
    maxFileBytes: limit(value.maxFileBytes, 2 * 1024 * 1024, 64 * 1024 * 1024),
    maxTotalBytes: limit(value.maxTotalBytes, 25 * 1024 * 1024, 128 * 1024 * 1024)
  };
}

function clipboardPath(value, platform) {
  let text = String(value || '').replace(/^\uFEFF/, '').trim();
  if (!text || text === 'copy' || text.startsWith('#')) return '';
  if ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith("'") && text.endsWith("'"))) {
    text = text.slice(1, -1);
  }
  if (/^file:/i.test(text)) {
    try { return fileURLToPath(new URL(text)); } catch { return ''; }
  }
  if (platform === 'win32') {
    if (isWindowsUncPath(text)) return '';
    return /^[A-Za-z]:[\\/]/.test(text) ? text : '';
  }
  return text.startsWith('/') ? text : '';
}

function clipboardFilePaths(limit, clipboard, platform) {
  // Windows 不区分大小写：同一个文件可能以不同大小写出现两次，去重但不能改写真实大小写
  const found = new Map();
  const key = (filePath) => (platform === 'win32' ? filePath.toLowerCase() : filePath);
  const addPaths = (values) => {
    for (const line of values) {
      if (found.size >= limit) return;
      const filePath = clipboardPath(line, platform);
      if (filePath && !found.has(key(filePath))) found.set(key(filePath), filePath);
    }
  };
  let formats = [];
  try { formats = clipboard.availableFormats() || []; } catch { /* 无系统剪贴板 */ }
  for (const format of formats) {
    try {
      const data = /FileNameW|CF_HDROP/i.test(format) ? clipboard.readBuffer(format) : clipboard.read(format);
      addPaths(parseClipboardFilePaths(format, data, platform));
    } catch { /* 该格式不可读，继续看其他格式 */ }
  }
  if (!found.size) {
    try { addPaths(clipboard.readText().split(/\r?\n/)); } catch { /* 忽略 */ }
  }
  return [...found.values()];
}

export async function readClipboardFiles(rawLimits, {
  clipboard,
  platform = process.platform,
  statFn = lstatSync,
  readFileFn = readFile
} = {}) {
  const limits = clipboardLimits(rawLimits);
  const human = (bytes) => (bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.round(bytes / 1024)} KB`);
  const paths = clipboardFilePaths(limits.maxFiles, clipboard, platform);
  if (!paths.length) {
    return { ok: false, error: '系统剪贴板中没有文件。复制文件后再按 Ctrl+V，或直接拖入文件。' };
  }
  if (paths.length > limits.maxFiles) {
    return { ok: false, error: `一次最多附加 ${limits.maxFiles} 个文件。` };
  }
  const files = [];
  let total = 0;
  for (const filePath of paths) {
    const name = basename(filePath) || '附件';
    let info;
    try { info = statFn(filePath); } catch { return { ok: false, error: `无法读取「${name}」，文件可能已移动或没有权限。` }; }
    if (!isRegularFileStat(info)) {
      return { ok: false, error: `「${name}」不是普通文件；文件夹请压缩后再拖入，或拖入其中的文件。` };
    }
    if (info.size <= 0) {
      return { ok: false, error: `「${name}」是空文件，无法添加。` };
    }
    if (info.size > limits.maxFileBytes) {
      return { ok: false, error: `「${name}」超过单文件上限（${human(limits.maxFileBytes)}）。` };
    }
    total += info.size;
    if (total > limits.maxTotalBytes) {
      return { ok: false, error: `附件总量超过单条消息上限（${human(limits.maxTotalBytes)}）。` };
    }
    let bytes;
    try { bytes = await readFileFn(filePath); } catch { return { ok: false, error: `无法读取「${name}」的内容。` }; }
    const mimeType = CLIPBOARD_IMAGE_MIME.get(name.slice(name.lastIndexOf('.')).toLowerCase()) || 'application/octet-stream';
    files.push({
      name, mimeType, size: bytes.length,
      dataBase64: `data:${mimeType};base64,${bytes.toString('base64')}`
    });
  }
  return { ok: true, files };
}
