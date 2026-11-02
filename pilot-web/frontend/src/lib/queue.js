/**
 * 排队接口的两种字段都认：items 是纯文本，entries 带附件。
 * 旧前端只读 messages，和现在的返回对不上，列表就会一直是空的。
 */
export function normalizeQueue(data) {
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

/** 排队标题：取消后下一条是纠正，运行中才是按顺序发送。 */
export function queueHeader({ count = 0, held = false } = {}) {
  const n = Number(count) || 0;
  if (!n && !held) {
    return null;
  }
  return {
    title: n ? `${n} 条排队` : '排队已停下',
    meta: held
      ? '已暂停，等待继续执行'
      : '这轮结束后按顺序发送'
  };
}

/** 输入框上方的继续条。需要补充和可续跑共用一个入口。 */
export function resumeBar({ running = false, needInput = false, resumable = false } = {}) {
  if (running || (!needInput && !resumable)) {
    return null;
  }
  return {
    text: needInput ? '需要你补充。写完发送会继续这一轮。' : '这一轮停住了，可以接着跑。',
    label: needInput ? '继续当前任务' : '继续执行'
  };
}
