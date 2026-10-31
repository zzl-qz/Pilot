/**
 * 把一次对话导出成 Markdown，方便归档、贴进 issue 或交给同事。
 * messages: [{ role: 'user' | 'assistant' | 'tools', text, summary }]
 */
export function buildTranscript({ title, workspace, exportedAt, messages }) {
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

/** 文件名里不能出现的字符替换掉，并限制长度。 */
export function transcriptFileName(title, date = new Date()) {
  const safe = String(title || '对话').replace(/[\\/:*?"<>|\s]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || '对话';
  const stamp = date.toISOString().slice(0, 10);
  return `pilot-${safe}-${stamp}.md`;
}
