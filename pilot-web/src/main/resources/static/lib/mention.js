/**
 * 输入框 @ 引用文件的候选排序。
 *
 * 排序规则：文件名开头命中 > 文件名包含 > 路径包含 > 子序列命中；
 * 同一档里路径越短越靠前，所以输入 "App" 时根目录的 App.java 不会被深层同名文件挤到后面。
 * 没有输入时按路径长度排序，先给出顶层文件。
 */
export function rankFileMatches(paths, query, limit = 40) {
  const needle = String(query || '').trim().toLowerCase();
  const scored = [];
  for (const path of Array.isArray(paths) ? paths : []) {
    if (!path) {
      continue;
    }
    const lower = path.toLowerCase();
    const name = lower.slice(lower.lastIndexOf('/') + 1);
    let score;
    if (!needle) {
      score = 0;
    } else if (name.startsWith(needle)) {
      score = 0;
    } else if (name.includes(needle)) {
      score = 1;
    } else if (lower.includes(needle)) {
      score = 2;
    } else if (isSubsequence(lower, needle)) {
      score = 3;
    } else {
      continue;
    }
    scored.push({ path, score, length: path.length });
  }
  scored.sort((a, b) => a.score - b.score || a.length - b.length || a.path.localeCompare(b.path));
  const max = Math.max(1, Number(limit) || 40);
  return scored.slice(0, max).map((entry) => entry.path);
}

/** needle 的字符是否按顺序出现在 haystack 里（typo 容忍用）。 */
export function isSubsequence(haystack, needle) {
  if (!needle) {
    return true;
  }
  let index = 0;
  for (let i = 0; i < haystack.length && index < needle.length; i += 1) {
    if (haystack[i] === needle[index]) {
      index += 1;
    }
  }
  return index === needle.length;
}
