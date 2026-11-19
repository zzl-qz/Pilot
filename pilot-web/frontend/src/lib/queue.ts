import type { QueueEntry, QueueResponse } from '../types/runtime.js';
import type { DraftAttachment } from '../types/input.js';
import { attachmentUrl, splitAttachmentMarkers } from './attachments.js';
import type { QueueEditTarget } from '../types/store.js';

/**
 * 排队消息的内容指纹：正文 + 附件 + 图片位置。
 * 只用下标会在刷新/自动取走队首后把编辑保存到另一条消息上；正文相同的两条也要靠附件区分。
 */
export function queueEntryKey(entry: QueueEntry | null | undefined): string {
  const text = entry?.text ?? entry?.content ?? '';
  const attachments = (Array.isArray(entry?.attachments) ? entry.attachments : [])
    .map(item => `${item?.name || ''}@${item?.url || item?.path || ''}#${item?.size ?? ''}`).join('|');
  const positions = (Array.isArray(entry?.imagePositions) ? entry.imagePositions : []).join(',');
  return `${text}\u0000${attachments}\u0000${positions}`;
}

/**
 * 找到编辑目标当前所在下标。原消息消失、或同一位置已经换成别的消息时返回 -1。
 * 仅当指纹在队列中唯一匹配时才认为「被移动了」，避免两条完全相同消息之间误改。
 */
export function locateQueueTarget(entries: readonly QueueEntry[] | null | undefined, target: QueueEditTarget | null | undefined): number {
  if (!target) return -1;
  const list = Array.isArray(entries) ? entries : [];
  const keys = list.map(queueEntryKey);
  if (keys[target.index] === target.key) return target.index;
  const matches = keys.map((key, index) => (key === target.key ? index : -1)).filter(index => index >= 0);
  return matches.length === 1 ? matches[0] : -1;
}
/**
 * 排队接口的两种字段都认：items 是纯文本，entries 带附件。
 * 旧前端只读 messages，和现在的返回对不上，列表就会一直是空的。
 */
export function normalizeQueue(data: QueueResponse | null | undefined) {
  const entries = Array.isArray(data?.entries) ? data.entries : [];
  const items = Array.isArray(data?.items) ? data.items : [];
  const texts = items.length
    ? items.map((item) => (typeof item === 'string' ? item : (item?.content ?? item?.text ?? '')))
    : entries.map((entry) => entry?.content ?? entry?.text ?? '');
  const rows = entries.length
    ? entries.map((entry, index) => ({
      ...entry,
      text: entry?.content ?? entry?.text ?? texts[index] ?? ''
    }))
    : texts.map((text) => ({ text, content: text, attachments: [], imagePositions: [] }));
  return { texts, rows };
}

/** 会自动发出去的排队标题。0 条不显示，范围外和还不能做的不计入。 */
export function queueHeader({ count = 0, held = false, waitingReason = '' }: { count?: number; held?: boolean; waitingReason?: string } = {}) {
  const n = Number(count) || 0;
  if (!n) {
    return null;
  }
  return {
    title: `${n} 条排队`,
    meta: waitingReason || (held ? '队列已暂停，等待你恢复' : '这轮结束后按顺序发送')
  };
}

export type QueueLane = 'send' | 'parked' | 'blocked' | 'wrapup';

/** 这句话会不会在本轮结束后自动发给工人。 */
export function queueLane(text: string | null | undefined): QueueLane {
  const body = String(text || '').replace(/\s+/g, ' ').trim();
  if (!body) return 'send';
  if (body.startsWith('遗留') || body.includes('不在本次范围')) return 'parked';
  if (isWrapup(body)) return 'wrapup';
  if (isVagueClick(body) || isVagueName(body)) return 'blocked';
  return 'send';
}

/** 还不能做时，写明缺的是按钮还是名字。能发出去的返回空字符串。 */
export function queueBlockReason(text: string | null | undefined): string {
  const body = String(text || '').replace(/\s+/g, ' ').trim();
  if (isVagueClick(body)) return '还不能做：没写点的是哪个按钮，也没写现在怎样、期望怎样。';
  if (isVagueName(body)) return '还不能做：没写是哪一个名字看起来奇怪，以及希望改成什么。';
  return '';
}

/** 去掉「不在本次范围」这句开头，剩下的才能重新进入自动发送。 */
export function textWithoutParkedMark(text: string): string {
  return text.replace(/^遗留（不在本次范围）\s*[|｜]?\s*/, '').replace(/^遗留\s*[|｜]?\s*/, '').trim();
}

function isWrapup(body: string): boolean {
  const ships = /提交|commit/i.test(body) && /推送|push/i.test(body);
  const splits = /按功能|拆分多个|多个\s*commit/i.test(body);
  return ships && (/做完|记得|收尾/.test(body) || splits);
}

function namesAThing(body: string): boolean {
  if (/[「『"“'][^」』"”']{1,40}[」』"”']/.test(body)) return true;
  return /[\u4e00-\u9fa5A-Za-z0-9]{2,16}(按钮|图标|链接|菜单|输入框|弹窗)/.test(body)
    && !/(这个|那个|这里的?)(按钮|图标|链接|菜单|输入框|弹窗)/.test(body);
}

function isVagueClick(body: string): boolean {
  if (!/点击|点了|点一下|点之后|点这里|点这儿/.test(body)) return false;
  if (!/为啥|为什么|不太行|不对|修复|修一下|怎样|这样/.test(body)) return false;
  return !namesAThing(body);
}

function isVagueName(body: string): boolean {
  if (!/命名|名字|叫法/.test(body)) return false;
  if (!/奇怪|不对|不好|别扭|改一下|改改/.test(body)) return false;
  return !namesAThing(body);
}

/** 输入框上方的继续条。需要补充和可续跑共用一个入口。 */
export function resumeBar({ running = false, needInput = false, resumable = false }: { running?: boolean; needInput?: boolean; resumable?: boolean } = {}) {
  if (running || (!needInput && !resumable)) {
    return null;
  }
  return {
    text: needInput ? '需要你补充。写完发送会继续这一轮。' : '这一轮停住了，可以接着跑。',
    label: needInput ? '继续当前任务' : '继续执行'
  };
}

/**
 * 把排队消息还原成可放进输入框的草稿：去掉内联文件标记，图片与文件都按附件芯片带回。
 * 内容通过会话附件接口读取；任何一条读取失败都抛错，调用方保留原队列。
 */
export async function fetchQueuedDraft(
  sessionId: string,
  entry: QueueEntry,
  fetcher: typeof fetch = fetch
): Promise<{ text: string; files: DraftAttachment[]; imagePositions: number[] }> {
  const spoken = splitAttachmentMarkers(entry.text ?? entry.content ?? '');
  const images = (Array.isArray(entry.attachments) ? entry.attachments : [])
    .map(item => ({ name: item.name || '图片', mimeType: item.mimeType, url: attachmentUrl(sessionId, item) }));
  const saved = spoken.files.map(file => ({
    name: file.name,
    mimeType: file.mimeType,
    url: attachmentUrl(sessionId, { name: file.name, mimeType: file.mimeType, path: file.path || '' })
  }));
  const files: DraftAttachment[] = [];
  for (const source of [...images, ...saved]) {
    if (!source.url) continue;
    const response = await fetcher(source.url);
    if (!response.ok) {
      throw new Error('读取附件「' + source.name + '」失败（' + response.status + '）');
    }
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (!bytes.length) {
      throw new Error('附件「' + source.name + '」内容为空');
    }
    const mimeType = source.mimeType || 'application/octet-stream';
    files.push({
      id: 'queued-' + files.length + '-' + source.name,
      name: source.name,
      mimeType,
      size: bytes.length,
      dataBase64: 'data:' + mimeType + ';base64,' + base64Of(bytes)
    });
  }
  return {
    text: spoken.body,
    files,
    imagePositions: images.map(() => Number.POSITIVE_INFINITY)
  };
}

function base64Of(bytes: Uint8Array): string {
  let binary = '';
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  }
  return btoa(binary);
}
