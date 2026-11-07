import type { MarkdownBlock, HistoryRange } from '../types/content.js';
/**
 * 流式 Markdown 的块级增量渲染。
 *
 * <p>朴素的写法是「每一帧把累积全文重新解析 + replaceChildren」，
 * 一次 <code>O(n)</code> 解析再加一次 <code>O(n)</code> 的 DOM 全量拆建，
 * 每帧都做、且 n 一直在长——这就是长回答越到后面越卡的根因。
 *
 * <p>成熟实现（markstream / Streamdown / ChatGPT 的 block reconciler）的做法：
 * 把回复切成「已经稳定的块 + 正在长的最后一块」，每次只重新计算受影响的尾部。
 * 本模块只负责「哪些要重画」，具体块的解析由调用方给的 <code>parse</code> 提供，
 * 因此可以在 node 里直接单测。
 */

/**
 * 第一个不同的位置。
 *
 * @returns {number} -1 表示两行完全一致；否则返回首个不同下标（可能是上一份的长度，
 *                    也就是「新文本只是在尾部多了内容」）
 */
export function firstDifferenceIndex(previous: readonly unknown[] | null | undefined, next: readonly unknown[] | null | undefined) {
  const prev = Array.isArray(previous) ? previous : [];
  const list = Array.isArray(next) ? next : [];
  const shared = Math.min(prev.length, list.length);
  for (let index = 0; index < shared; index += 1) {
    if (prev[index] !== list[index]) {
      return index;
    }
  }
  return prev.length === list.length ? -1 : shared;
}

/**
 * 从第 {@code lineIndex} 行发生变化时，应该从第几行重新开始解析。
 *
 * <p>块的解析只向前看，不回头，所以回到「变化所在块的起点」一定是安全的：
 * 更早的块输入没变，它们的 DOM 可以直接留着复用。
 *
 * @param {Array<{start: number, end: number}>} blocks 已渲染块的绝对行区间
 */
export function resumeLineIndex(blocks: readonly HistoryRange[] | null | undefined, lineIndex: number) {
  if (!Array.isArray(blocks) || !Number.isFinite(lineIndex)) {
    return 0;
  }
  for (const block of blocks) {
    if (!block) {
      continue;
    }
    const start = Number(block.start);
    const end = Number(block.end);
    if (!Number.isFinite(start) || !Number.isFinite(end)) {
      continue;
    }
    if (lineIndex >= start && lineIndex < end) {
      return start;
    }
  }
  return Math.max(0, lineIndex);
}

/** 两个块是否可以复用同一个 DOM 节点：区间与原文都要一致。 */
export function sameBlock(left: Pick<MarkdownBlock, 'start' | 'end' | 'source'> | null | undefined, right: Pick<MarkdownBlock, 'start' | 'end' | 'source'> | null | undefined) {
  if (!left || !right) {
    return false;
  }
  return left.start === right.start
    && left.end === right.end
    && left.source === right.source;
}

/** 新旧块序列从头开始能原样保留多少个。 */
export function sharedBlockPrefix(previous: readonly MarkdownBlock[] | null | undefined, next: readonly MarkdownBlock[] | null | undefined) {
  const prev = Array.isArray(previous) ? previous : [];
  const list = Array.isArray(next) ? next : [];
  const shared = Math.min(prev.length, list.length);
  let index = 0;
  while (index < shared && sameBlock(prev[index], list[index])) {
    index += 1;
  }
  return index;
}

function continuesList(block: MarkdownBlock | undefined, line: string | undefined) {
  if (!block || !line) return false;
  if (block.node.nodeName === 'OL') return /^\s{0,3}(?:\d+[.)]\s+.*|\d+[.)]?)\s*$/.test(line);
  if (block.node.nodeName === 'UL') return /^\s{0,3}(?:[-*+]\s+.*|[-*+])\s*$/.test(line);
  return false;
}

/**
 * 创建一块容器上的流式 Markdown 渲染器。
 *
 * @param {HTMLElement} container 目标容器（助手消息的 .bubble）
 * @param {Object} options
 * @param {(lines: string[], offset: number) => Array<{node: Node, start: number, end: number}>} options.parse
 *        把行切片解析成块节点；start/end 是相对于整段文本的绝对行号。
 */
export function createBlockStreamRenderer(container: HTMLElement, { parse }: { parse?: (lines: string[], offset: number) => MarkdownBlock[] } = {}) {
  if (!container || typeof parse !== 'function') {
    throw new Error('createBlockStreamRenderer: 需要容器与 parse 函数');
  }

  const parseBlocks = parse;
  let lines: string[] = [];
  let blocks: MarkdownBlock[] = [];

  function normalize(text: unknown) {
    return String(text == null ? '' : text).replace(/\r\n/g, '\n').split('\n');
  }

  function reset() {
    lines = [];
    blocks = [];
    container.replaceChildren();
  }

  /**
   * 把当前文本增量落到容器上。
   *
   * @returns {boolean} 是否真的动过 DOM；没动可以用来跳过滚动写入。
   */
  function update(text: unknown) {
    const nextLines = normalize(text);
    const changedAt = firstDifferenceIndex(lines, nextLines);
    if (changedAt < 0) {
      return false;
    }
    let resume = resumeLineIndex(blocks, changedAt);
    const precedingBlock = blocks.find((block) => Number(block.end) === changedAt);
    if (continuesList(precedingBlock, nextLines[changedAt])) {
      resume = Math.min(resume, precedingBlock!.start);
    }
    const lastBlock = blocks.at(-1);
    // An unfinished list/table/paragraph can absorb newly appended lines, even when
    // the new line starts exactly at the previous block's exclusive end.
    if (lastBlock && changedAt >= lastBlock.end) {
      resume = Math.min(resume, lastBlock.start);
    }
    const head = blocks.filter((block) => Number(block.end) <= resume);
    const parsed = parseBlocks(nextLines.slice(resume), resume) || [];
    const next = head.concat(parsed.map((record) => ({
      node: record.node,
      start: Number(record.start),
      end: Number(record.end),
      source: record.source ?? sliceText(nextLines, record.start, record.end)
    })));

    const shared = sharedBlockPrefix(blocks, next);
    // 先砍掉尾部多出来的旧节点，再按下标依次对齐。
    let removed = false;
    while (container.childNodes.length > next.length) {
      container.lastChild!.remove();
      removed = true;
    }
    for (let index = shared; index < next.length; index += 1) {
      const node = next[index].node;
      const existing = container.childNodes[index];
      if (existing === node) {
        continue;
      }
      if (existing) {
        container.replaceChild(node, existing);
      } else {
        container.append(node);
      }
    }
    const touched = removed || shared < next.length;
    lines = nextLines;
    blocks = next;
    return touched;
  }

  function sliceText(source: readonly string[], start: number, end: number) {
    return source.slice(Number(start) || 0, Number(end) || 0).join('\n');
  }

  return {
    update,
    reset,
    get blocks() {
      return blocks;
    },
    get lineCount() {
      return lines.length;
    }
  };
}
