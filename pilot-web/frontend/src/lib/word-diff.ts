import type { DiffLine } from '../types/core.js';

export interface WordSegment {
  text: string;
  changed: boolean;
}

export type SplitDiffRow =
  | { kind: 'hunk'; text: string; index: number }
  | { kind: 'pair'; left: DiffLine | null; leftIndex: number | null; right: DiffLine | null; rightIndex: number | null };

const MAX_TOKENS_PER_LINE = 140;

function tokenizeForDiff(text: string): string[] {
  return text.match(/[A-Za-z0-9_$]+|\s+|[^\sA-Za-z0-9_$]/g) ?? [];
}

function mergeSegments(parts: WordSegment[]): WordSegment[] {
  const merged: WordSegment[] = [];
  for (const part of parts) {
    if (!part.text) continue;
    const tail = merged[merged.length - 1];
    if (tail && tail.changed === part.changed) {
      tail.text += part.text;
    } else {
      merged.push({ ...part });
    }
  }
  return merged;
}

/**
 * 词级行内差异对比：对相邻删/增行做公共前后缀裁剪 + 有界 LCS，
 * 精准高亮真正发生变化的变量、字面量或标点；超长压缩行自动降级避免主线程阻塞。
 */
export function diffLineWords(oldText: string, newText: string): { oldSegments: WordSegment[]; newSegments: WordSegment[] } {
  const left = tokenizeForDiff(oldText);
  const right = tokenizeForDiff(newText);
  if (left.length > MAX_TOKENS_PER_LINE || right.length > MAX_TOKENS_PER_LINE) {
    return {
      oldSegments: [{ text: oldText, changed: false }],
      newSegments: [{ text: newText, changed: false }],
    };
  }

  let prefix = 0;
  const minLen = Math.min(left.length, right.length);
  while (prefix < minLen && left[prefix] === right[prefix]) prefix++;

  let suffix = 0;
  while (
    suffix < left.length - prefix
    && suffix < right.length - prefix
    && left[left.length - 1 - suffix] === right[right.length - 1 - suffix]
  ) {
    suffix++;
  }

  const midL = left.slice(prefix, left.length - suffix);
  const midR = right.slice(prefix, right.length - suffix);

  const m = midL.length;
  const n = midR.length;
  const dp: Uint16Array[] = Array.from({ length: m + 1 }, () => new Uint16Array(n + 1));
  for (let i = m - 1; i >= 0; i--) {
    for (let j = n - 1; j >= 0; j--) {
      dp[i][j] = midL[i] === midR[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }

  const oldMid: WordSegment[] = [];
  const newMid: WordSegment[] = [];
  let i = 0;
  let j = 0;
  while (i < m && j < n) {
    if (midL[i] === midR[j]) {
      oldMid.push({ text: midL[i], changed: false });
      newMid.push({ text: midR[j], changed: false });
      i++;
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      oldMid.push({ text: midL[i++], changed: true });
    } else {
      newMid.push({ text: midR[j++], changed: true });
    }
  }
  while (i < m) oldMid.push({ text: midL[i++], changed: true });
  while (j < n) newMid.push({ text: midR[j++], changed: true });

  const prefixText = left.slice(0, prefix).join('');
  const suffixText = suffix > 0 ? left.slice(left.length - suffix).join('') : '';

  const oldSegments = mergeSegments([
    ...(prefixText ? [{ text: prefixText, changed: false }] : []),
    ...oldMid,
    ...(suffixText ? [{ text: suffixText, changed: false }] : []),
  ]);
  const newSegments = mergeSegments([
    ...(prefixText ? [{ text: prefixText, changed: false }] : []),
    ...newMid,
    ...(suffixText ? [{ text: suffixText, changed: false }] : []),
  ]);

  // 整行几乎完全替换（无公共非空白词）时取消词级二次底色，避免视觉花哨。
  const hasUnchangedWord = oldSegments.some(seg => !seg.changed && /\S/.test(seg.text));
  if (!hasUnchangedWord) {
    return {
      oldSegments: [{ text: oldText, changed: false }],
      newSegments: [{ text: newText, changed: false }],
    };
  }
  return { oldSegments, newSegments };
}

/** 为连续 del/add 块建立 1-to-1 配对的词级差异映射（键为行在 lines 中的下标）。 */
export function buildWordDiffMap(lines: readonly DiffLine[]): Map<number, WordSegment[]> {
  const map = new Map<number, WordSegment[]>();
  let idx = 0;
  while (idx < lines.length) {
    if (lines[idx].kind !== 'del') {
      idx++;
      continue;
    }
    const delStart = idx;
    while (idx < lines.length && lines[idx].kind === 'del') idx++;
    const addStart = idx;
    while (idx < lines.length && lines[idx].kind === 'add') idx++;
    const pairs = Math.min(addStart - delStart, idx - addStart);
    for (let p = 0; p < pairs; p++) {
      const delIdx = delStart + p;
      const addIdx = addStart + p;
      const { oldSegments, newSegments } = diffLineWords(lines[delIdx].text, lines[addIdx].text);
      if (oldSegments.some(s => s.changed)) map.set(delIdx, oldSegments);
      if (newSegments.some(s => s.changed)) map.set(addIdx, newSegments);
    }
  }
  return map;
}

/** 将统一 diff 行对齐为左右分栏（Split Diff）行序列，保留原始下标以复用选行与审阅批注。 */
export function buildSplitDiffRows(lines: readonly DiffLine[]): SplitDiffRow[] {
  const rows: SplitDiffRow[] = [];
  let idx = 0;
  while (idx < lines.length) {
    const line = lines[idx];
    if (line.kind === 'hunk') {
      rows.push({ kind: 'hunk', text: line.text, index: idx });
      idx++;
      continue;
    }
    if (line.kind === 'del') {
      const delStart = idx;
      while (idx < lines.length && lines[idx].kind === 'del') idx++;
      const addStart = idx;
      while (idx < lines.length && lines[idx].kind === 'add') idx++;
      const delCount = addStart - delStart;
      const addCount = idx - addStart;
      const span = Math.max(delCount, addCount);
      for (let k = 0; k < span; k++) {
        const leftIdx = k < delCount ? delStart + k : null;
        const rightIdx = k < addCount ? addStart + k : null;
        rows.push({
          kind: 'pair',
          left: leftIdx != null ? lines[leftIdx] : null,
          leftIndex: leftIdx,
          right: rightIdx != null ? lines[rightIdx] : null,
          rightIndex: rightIdx,
        });
      }
      continue;
    }
    if (line.kind === 'add') {
      rows.push({ kind: 'pair', left: null, leftIndex: null, right: line, rightIndex: idx });
      idx++;
      continue;
    }
    rows.push({ kind: 'pair', left: line, leftIndex: idx, right: line, rightIndex: idx });
    idx++;
  }
  return rows;
}

/** 从解析后的差异行列表中提取指定 hunk 下标对应的单个统一补丁块，用于按块局部撤销。 */
export function extractHunkPatch(lines: readonly DiffLine[], hunkIndex: number): string | null {
  const header = lines[hunkIndex];
  if (!header || header.kind !== 'hunk' || !header.text.startsWith('@@ ')) {
    return null;
  }
  const out: string[] = [header.text];
  for (let i = hunkIndex + 1; i < lines.length; i++) {
    const line = lines[i];
    if (line.kind === 'hunk') break;
    if (line.kind === 'meta') {
      if (line.text.startsWith('\\')) {
        out.push(line.text);
        continue;
      }
      break;
    }
    if (line.kind === 'add') out.push('+' + line.text);
    else if (line.kind === 'del') out.push('-' + line.text);
    else out.push(' ' + line.text);
  }
  return out.length > 1 ? out.join('\n') + '\n' : null;
}
