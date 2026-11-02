/** 平台快捷键文案与界面派生状态，保持事件数据本身不变。 */
export function platformModifier(nav = globalThis.navigator) {
  return /Mac|iPhone|iPad/i.test(nav?.userAgentData?.platform || nav?.platform || '') ? '⌘' : 'Ctrl';
}
export const modifierKey = platformModifier();
export function shortcut(key) { return `${modifierKey} ${key}`; }

// Windows 的 AltGr 可能同时报告 Ctrl+Alt；输入法候选键也不能触发全局动作。
export function isShortcutModifier(event) {
  return Boolean(event && !event.isComposing && event.keyCode !== 229
    && !event.getModifierState?.('AltGraph') && (event.ctrlKey || event.metaKey));
}

export function runPhase({running, cancelling, pausing, queueHeld, pendingApprovals = {}, thread, lastOutcome}) {
  if (cancelling) return '正在停止';
  if (Object.keys(pendingApprovals).length) return '等待批准';
  if (pausing) return '正在暂停';
  if (!running) return lastOutcome === 'NEED_INPUT' ? '等待补充' : queueHeld ? '已暂停' : '就绪';
  const turn = [...(thread?.rows || [])].reverse().find(row => row.type === 'turn');
  const tool = [...(turn?.tools || [])].reverse().find(tool => tool.status === 'run');
  if (tool) {
    const name = tool.name || '';
    if (/read|list|search|glob/.test(name)) return '正在读取';
    if (/edit|create|patch|write/.test(name)) return '正在修改';
    if (name === 'run_command') return /test|pytest|vitest|jest|mvn.*verify/.test(tool.args || '') ? '正在测试' : '正在执行命令';
    return '正在执行';
  }
  return turn?.reply?.live ? '正在回复' : '正在思考';
}

export function duplicateResult(rows, index) {
  const detail = String(rows[index]?.detail || '').trim();
  if (!detail) return false;
  for (let i = index - 1; i >= 0; i--) {
    if (rows[i].type === 'user' || rows[i].type === 'result') break;
    if (rows[i].type === 'turn' && String(rows[i].reply?.text || '').trim() === detail) return true;
  }
  return false;
}

export function reviewQuote(path, lines, comment = '') {
  const selected = lines.filter(line => line.kind !== 'hunk' && line.kind !== 'meta');
  if (!selected.length) return '';
  const first = selected[0], last = selected.at(-1);
  const location = line => `${line.kind === 'del' ? '原文件' : '现文件'}:${line.kind === 'del' ? line.old : line.next}`;
  const code = selected.map(line => `${line.kind === 'del' ? '-' : line.kind === 'add' ? '+' : ' '}${line.text}`).join('\n');
  // 行内容可能含 Markdown 围栏，用长于内容的围栏避免引用提前结束。
  const fence = '`'.repeat(Math.max(3, ...Array.from(code.matchAll(/`+/g), match => match[0].length + 1)));
  return `\n审阅 ${path}（${location(first)}${selected.length > 1 ? ' → ' + location(last) : ''}）\n${fence}diff\n${code}\n${fence}\n${comment.trim() || '请检查这段改动。'}\n`;
}
