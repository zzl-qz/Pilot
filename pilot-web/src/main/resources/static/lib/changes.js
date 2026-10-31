/**
 * 变更面板的纯逻辑：多轮运行报告的合并、文件排序、git 状态归类。
 *
 * <p>这些函数原来内联在 app.js 里，和 DOM 混在一起没法单测；
 * 抽出来之后可以直接用 node:test 覆盖「自动继续换了 run」「同一文件被两轮都改」这类边界。
 */

import { normalizePath } from './diff.js';

/**
 * 把同一轮里多个 run 的改动报告合并成一份。
 *
 * <p>自动继续会换 runId，用户在界面上看到的应该是「这一轮总共改了什么」，
 * 同一个文件以后出现的报告为准（后面的 run 基于前一次的结果）。
 *
 * @param {Array<{gitRepository?: boolean, truncated?: boolean, files?: Array<{path: string, added?: number, removed?: number}>}>} reports
 */
export function mergeRunReports(reports) {
  const files = new Map();
  let gitRepository = false;
  let truncated = false;
  for (const report of reports || []) {
    gitRepository = gitRepository || report.gitRepository === true;
    truncated = truncated || report.truncated === true;
    for (const file of report.files || []) {
      files.set(normalizePath(file.path), file);
    }
  }
  const list = [...files.values()];
  return {
    gitRepository,
    truncated,
    files: list,
    added: list.reduce((sum, file) => sum + Number(file.added || 0), 0),
    removed: list.reduce((sum, file) => sum + Number(file.removed || 0), 0)
  };
}

/**
 * 本次运行改过的文件排前面，其余按路径排序。
 *
 * @param {Array<{path: string}>} files
 * @param {string[]} runOrderPaths 本轮改过的路径，按出现顺序
 */
export function sortChangeFiles(files, runOrderPaths = []) {
  const runOrder = new Map((runOrderPaths || []).map((path, index) => [normalizePath(path), index]));
  return (files || []).slice().sort((a, b) => {
    const left = runOrder.has(normalizePath(a.path)) ? runOrder.get(normalizePath(a.path)) : Number.MAX_SAFE_INTEGER;
    const right = runOrder.has(normalizePath(b.path)) ? runOrder.get(normalizePath(b.path)) : Number.MAX_SAFE_INTEGER;
    if (left !== right) {
      return left - right;
    }
    return String(a.path).localeCompare(String(b.path));
  });
}

/**
 * 回合结束卡片的编辑摘要：总数、增删行、默认露出的文件与需要折叠的其余文件。
 *
 * <p>界面照着 Codex 的做法：默认只露三条，其余收在「展开其余 N 个文件」里。
 * 摘要行必须同时给「几个文件」和「+x −y」，否则用户只能点开才知道改了多少。
 *
 * @param {Array<{path: string, added?: number, removed?: number}>} files
 * @param {number} limit 默认展示的文件数
 */
export function editSummary(files, limit = 3) {
  const list = (files || []).map((file) => ({ ...file, path: normalizePath(file.path) }));
  const size = Number.isFinite(limit) && limit >= 0 ? Math.floor(limit) : 3;
  return {
    total: list.length,
    added: list.reduce((sum, file) => sum + Number(file.added || 0), 0),
    removed: list.reduce((sum, file) => sum + Number(file.removed || 0), 0),
    visible: list.slice(0, size),
    hidden: list.slice(size),
    label: list.length ? `已编辑 ${list.length} 个文件` : '本轮没有改动文件'
  };
}

/** 路径拆成「目录 + 文件名」，界面上目录用弱化色，长路径省略时仍能看到文件名。 */
export function splitPath(path) {
  const normalized = normalizePath(path);
  const index = normalized.lastIndexOf('/');
  return index < 0
    ? { dir: '', name: normalized }
    : { dir: normalized.slice(0, index + 1), name: normalized.slice(index + 1) };
}

/** git 的两字母状态码归类：未跟踪/新增算 added，删除算 deleted，其余都算 modified。 */
export function statusKind(status) {
  if (status === '??' || status === 'A') {
    return 'added';
  }
  if (status === 'D') {
    return 'deleted';
  }
  return 'modified';
}
