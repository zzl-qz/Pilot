import type { QueueEntry, QueueResponse } from '../types/runtime.js';
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

/** 排队标题：运行中按顺序发送，停止后保留队列。 */
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
