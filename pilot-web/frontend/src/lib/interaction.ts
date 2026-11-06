import type { RunPhaseInput, PhaseRow } from '../types/input.js';
import type { DiffLine } from '../types/core.js';
import { classifyRunFailure } from './events.js';

/** 平台快捷键文案与界面派生状态，保持事件数据本身不变。 */
export function platformModifier(nav: { platform?: string; userAgentData?: { platform?: string } } | undefined = globalThis.navigator) {
  return /Mac|iPhone|iPad/i.test(nav?.userAgentData?.platform || nav?.platform || '') ? '⌘' : 'Ctrl';
}
export const modifierKey = platformModifier();
export function shortcut(key: string) { return `${modifierKey} ${key}`; }

/** 弹窗期间窗口可能变窄，使原入口折进导航抽屉；此时返回可见的侧栏开关。 */
export function restoreFocus(previous: Element | null | undefined) {
  const visible = (element: Element | null | undefined) => element?.isConnected && element.getClientRects().length
    && getComputedStyle(element).visibility !== 'hidden' && !element.closest('[inert]');
  const target = visible(previous) ? previous : document.getElementById('sidebar-toggle') || document.getElementById('input-flow');
  (target as HTMLElement | null)?.focus();
}

// Windows 的 AltGr 可能同时报告 Ctrl+Alt；输入法候选键也不能触发全局动作。
export function isShortcutModifier(event: Partial<Pick<KeyboardEvent, 'isComposing' | 'keyCode' | 'ctrlKey' | 'metaKey' | 'getModifierState'>> | null | undefined) {
  return Boolean(event && !event.isComposing && event.keyCode !== 229
    && !event.getModifierState?.('AltGraph') && (event.ctrlKey || event.metaKey));
}

export function runPhase({running, cancelling, pausing, queueHeld, pendingApprovals = {}, thread, lastOutcome}: RunPhaseInput) {
  if (cancelling) return '正在停止';
  if (Object.keys(pendingApprovals).length) return '等待批准';
  if (pausing) return '正在暂停';
  if (!running) {
    if (lastOutcome === 'NEED_INPUT') return '等待补充';
    if (queueHeld) return '已暂停';
    if (!lastOutcome || lastOutcome === 'completed') return '就绪';
    const failure = classifyRunFailure(lastOutcome);
    if (failure.cancelled) return '已取消';
    if (failure.paused) return '已暂停';
    if (failure.interrupted) return '已中断';
    if (failure.blocked) return '遇到阻塞';
    if (failure.budgetLimited) return '已达预算';
    return ({ WAITING: '等待外部事件', UNVERIFIED: '结果待核验', NO_PROGRESS: '暂无有效进展',
      REVIEW_UNAVAILABLE: '核验暂不可用' } as Record<string, string>)[failure.outcome] || '运行失败';
  }
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

/** 颜色与文字共同表达状态；暂停和需要人工处理不能伪装成「正在运行」。 */
export function runTone(phase: string, stalled = false) {
  if (phase === '就绪') return 'ready';
  if (phase === '运行失败') return 'error';
  if (['已暂停', '已取消', '正在停止', '正在暂停'].includes(phase)) return 'paused';
  if (phase.startsWith('正在')) return stalled ? 'attention' : 'running';
  return 'attention';
}

export function duplicateResult(rows: readonly PhaseRow[], index: number) {
  const detail = String(rows[index]?.detail || '').trim();
  if (!detail) return false;
  for (let i = index - 1; i >= 0; i--) {
    if (rows[i].type === 'user' || rows[i].type === 'result') break;
    if (rows[i].type === 'turn' && String(rows[i].reply?.text || '').trim() === detail) return true;
  }
  return false;
}

export function reviewQuote(path: unknown, lines: readonly DiffLine[], comment = '') {
  const selected = lines.filter(line => line.kind !== 'hunk' && line.kind !== 'meta');
  if (!selected.length) return '';
  const first = selected[0], last = selected.at(-1)!;
  const location = (line: DiffLine) => `${line.kind === 'del' ? '原文件' : '现文件'}:${line.kind === 'del' ? line.old : line.next}`;
  const code = selected.map(line => `${line.kind === 'del' ? '-' : line.kind === 'add' ? '+' : ' '}${line.text}`).join('\n');
  // 行内容可能含 Markdown 围栏，用长于内容的围栏避免引用提前结束。
  const fence = '`'.repeat(Math.max(3, ...Array.from(code.matchAll(/`+/g), match => match[0].length + 1)));
  return `\n审阅 ${path}（${location(first)}${selected.length > 1 ? ' → ' + location(last) : ''}）\n${fence}diff\n${code}\n${fence}\n${comment.trim() || '请检查这段改动。'}\n`;
}
