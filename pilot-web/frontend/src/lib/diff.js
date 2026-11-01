/**
 * unified diff 解析：把 git 补丁拆成「文件 → 行」两级结构。
 *
 * <p>只做纯计算，不碰 DOM，方便直接用 node:test 覆盖边界：
 * 源码里以 {@code --}/{@code ++} 开头的行在补丁里同样以 {@code ---}/{@code +++} 开头，
 * 只有在进入 hunk 之前的 {@code ---}/{@code +++} 才是文件头。
 */

/** 统一路径分隔符，便于和工具返回的相对路径比对。 */
export function normalizePath(path) {
  return String(path || '').replace(/\\/g, '/').replace(/^\.\//, '');
}

const META_PREFIXES = [
  'index ',
  'new file',
  'deleted file',
  'similarity',
  'dissimilarity',
  'rename from',
  'rename to',
  'copy from',
  'copy to',
  'old mode',
  'new mode',
  'Binary files'
];

/**
 * @returns {Array<{path: string, lines: Array<{kind: string, text: string, old: string|number, next: string|number}>, added: number, removed: number}>}
 */
export function parseDiff(text) {
  const files = [];
  let current = null;
  let inHunk = false;
  let oldLine = 0;
  let newLine = 0;

  for (const line of String(text ?? '').split('\n')) {
    if (line.startsWith('diff --git ')) {
      const match = /^diff --git a\/(.+?) b\/(.+)$/.exec(line);
      current = { path: match ? match[2] : line, lines: [], added: 0, removed: 0 };
      files.push(current);
      inHunk = false;
      continue;
    }
    if (!current) {
      continue;
    }
    if (line.startsWith('@@')) {
      const hunk = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
      if (hunk) {
        oldLine = Number(hunk[1]);
        newLine = Number(hunk[2]);
      }
      inHunk = true;
      current.lines.push({ kind: 'hunk', text: line, old: '', next: '' });
      continue;
    }
    // --- / +++ 只在 hunk 之前是文件头；进了 hunk 之后它们是普通的内容行
    if (!inHunk && (line.startsWith('---') || line.startsWith('+++')
        || META_PREFIXES.some((prefix) => line.startsWith(prefix)))) {
      current.lines.push({ kind: 'meta', text: line, old: '', next: '' });
      continue;
    }
    // "\ No newline at end of file"：不是内容行，也不能推进行号
    if (line.startsWith('\\')) {
      current.lines.push({ kind: 'meta', text: line, old: '', next: '' });
      continue;
    }
    if (line === '') {
      // 补丁末尾的换行符，不是内容
      continue;
    }
    if (line.startsWith('+')) {
      current.added++;
      current.lines.push({ kind: 'add', text: line.slice(1), old: '', next: newLine++ });
    } else if (line.startsWith('-')) {
      current.removed++;
      current.lines.push({ kind: 'del', text: line.slice(1), old: oldLine++, next: '' });
    } else if (inHunk) {
      current.lines.push({ kind: 'ctx', text: line.slice(1), old: oldLine++, next: newLine++ });
    }
  }
  return files;
}

/** 合计增删行，用于摘要行（+12 −3）。 */
export function totalDiffLines(files) {
  return (files || []).reduce(
    (sum, file) => ({ added: sum.added + Number(file.added || 0), removed: sum.removed + Number(file.removed || 0) }),
    { added: 0, removed: 0 }
  );
}
