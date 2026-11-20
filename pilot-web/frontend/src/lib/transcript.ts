/**
 * 把一次对话导出成 Markdown，方便归档、贴进 issue 或交给同事。
 * messages: [{ role: 'user' | 'assistant' | 'tools', text, summary }]
 */
export function buildTranscript({ title, workspace, exportedAt, messages }: { title?: string; workspace?: string; exportedAt?: string; messages?: { role: string; text?: string; summary?: string }[] }) {
  const lines = [`# ${String(title || '未命名对话').trim()}`, ''];
  if (workspace) {
    lines.push(`- 工作区：\`${workspace}\``);
  }
  if (exportedAt) {
    lines.push(`- 导出时间：${exportedAt}`);
  }
  if (workspace || exportedAt) {
    lines.push('');
  }
  for (const message of Array.isArray(messages) ? messages : []) {
    const text = String(message.text || '').trim();
    if (message.role === 'tools') {
      if (message.summary) {
        lines.push(`> 工具调用：${message.summary}`, '');
      }
    } else if (text) {
      lines.push(message.role === 'user' ? '## 你' : '## Pilot', '', text, '');
    }
  }
  return lines.join('\n').replace(/\n{3,}/g, '\n\n').trimEnd() + '\n';
}

/** 触发浏览器保存。没有下载能力时返回 false，调用方改走复制。 */
export function saveTextFile(text: string, fileName: string, doc: Document | null | undefined) {
  if (!doc || typeof doc.createElement !== 'function' || !doc.body) return false;
  const link = doc.createElement('a');
  if (typeof link.download !== 'string' || typeof URL.createObjectURL !== 'function') return false;
  const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }));
  try {
    link.href = url;
    link.download = fileName;
    doc.body.append(link);
    link.click();
    return true;
  } catch {
    return false;
  } finally {
    link.remove();
    // 下载由浏览器异步接手，立即释放可能得到空下载。
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }
}

/** 文件名里不能出现的字符替换掉，并限制长度。 */
export function transcriptFileName(title: unknown, date = new Date()) {
  const safe = String(title || '对话').replace(/[\\/:*?"<>|\p{Cc}\s]+/gu, '-').replace(/^-+|-+$/g, '').slice(0, 40) || '对话';
  const stamp = date.toISOString().slice(0, 10);
  return `pilot-${safe}-${stamp}.md`;
}
