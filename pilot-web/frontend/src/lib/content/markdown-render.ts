import type { MarkdownBlock } from '../../types/content.js';
import DOMPurify from 'dompurify';
/**
 * Markdown 渲染引擎：从旧版原生实现原样搬运（块级流式解析 + 代码块复制按钮 + katex + mermaid）。
 * katex / mermaid 依旧从 /vendor 下的静态脚本加载，行为与旧版一致。
 */

import { writeClipboard as tryWriteClipboard } from '../clipboard.js';
import { tokenizeCodeLine } from '../code-highlight.js';
import { parseCallout, resolveFileReference, languageLabel, outputKind, safeImageSource } from './content-format.js';

const CODE_HIGHLIGHT_MAX_CHARS = 60_000;
const FENCE_HIGHLIGHT_LANG: Record<string, string> = {
  java: 'java', kt: 'java', kotlin: 'java', kts: 'java', cs: 'java', csharp: 'java',
  ts: 'ts', typescript: 'ts', tsx: 'ts', js: 'ts', javascript: 'ts', jsx: 'ts', mjs: 'ts', cjs: 'ts',
  py: 'py', python: 'py',
  go: 'go', golang: 'go',
  rs: 'rust', rust: 'rust',
  c: 'c', h: 'c', cpp: 'c', 'c++': 'c', cc: 'c', hpp: 'c',
  json: 'json', jsonc: 'json',
  yml: 'yaml', yaml: 'yaml',
  xml: 'xml', html: 'xml', htm: 'xml', svg: 'xml',
  css: 'css', scss: 'css', less: 'css',
  sql: 'sql',
  sh: 'sh', bash: 'sh', zsh: 'sh', shell: 'sh', ps1: 'sh', powershell: 'sh', cmd: 'sh', bat: 'sh'
};
const codeBlockTextStore = new WeakMap<Node, string>();

function normalizeHighlightLang(language: string): string | null {
  const key = (language || '').trim().toLowerCase();
  return key ? (FENCE_HIGHLIGHT_LANG[key] ?? null) : null;
}

function clearNode(node: HTMLElement) {
  if (typeof node.replaceChildren === 'function') {
    node.replaceChildren();
  } else {
    node.textContent = '';
  }
}

function populateCodeElement(el: HTMLElement, code: string, language: string) {
  const highlightLang = normalizeHighlightLang(language);
  if (el.childNodes?.length) clearNode(el);
  if (!highlightLang || !code || code.length > CODE_HIGHLIGHT_MAX_CHARS) {
    el.textContent = code;
    return;
  }
  const fragment = document.createDocumentFragment();
  const lines = code.split('\n');
  for (let i = 0; i < lines.length; i += 1) {
    if (i > 0) fragment.append(document.createTextNode('\n'));
    const tokens = tokenizeCodeLine(lines[i], highlightLang);
    for (const token of tokens) {
      if (token.kind === 'plain') {
        fragment.append(document.createTextNode(token.text));
      } else {
        const span = document.createElement('span');
        span.className = `tok-${token.kind}`;
        span.textContent = token.text;
        fragment.append(span);
      }
    }
  }
  el.append(fragment);
}

let notify: (text: string) => void = () => {};
/** React 侧把 showNotice 注入进来，渲染引擎不依赖框架。 */
export function setMarkdownNotifier(fn: (text: string) => void) { notify = fn; }

let getWorkspace = () => '';
let openFile: (path: string, line?: number | null) => void = () => {};
/** 注入工作区与打开文件的回调，避免渲染引擎直接依赖 store。 */
export function setMarkdownContext(ctx: { getWorkspace?: () => string; openFile?: (path: string, line?: number | null) => void }) {
  if (ctx?.getWorkspace) getWorkspace = ctx.getWorkspace;
  if (ctx?.openFile) openFile = ctx.openFile;
}

/**
 * 代码块头部的复制按钮：文案按钮，点击后短暂反馈，失败走 notice。
 * 之前这里调用的是一个从未定义过的 copyControl，带代码块的回复一渲染就抛
 * ReferenceError，整条消息中断——这也是代码块看起来「渲染不出来」的原因。
 */
function copyControl(label: string, getText: () => string, options: { tip?: boolean } = {}) {
  const { tip = true } = options;
  const node = document.createElement('button');
  node.type = 'button';
  node.className = 'md-code-toggle';
  node.textContent = label;
  if (tip) {
    node.dataset.tip = label;
  }
  node.addEventListener('click', async () => {
    try {
      if (!await tryWriteClipboard(getText())) {
        notify('复制失败');
        return;
      }
      node.textContent = '已复制';
      setTimeout(() => { node.textContent = label; }, 1200);
    } catch {
      notify('复制失败');
    }
  });
  return node;
}


const FENCE_PATTERN = /^\s{0,3}(`{3,}|~{3,})\s*([\w+#.-]*)(?:\s+.*)?$/;
const HEADING_PATTERN = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
const RULE_PATTERN = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/;
const QUOTE_PATTERN = /^\s{0,3}>\s?/;
const LIST_PATTERN = /^\s{0,3}([-*+]|\d+[.)])\s+(.*)$/;
const TABLE_DIVIDER = /^:?-{2,}:?$/;
const MAX_QUOTE_DEPTH = 20;
const MAX_TABLE_ROWS = 200;
const MAX_TABLE_COLUMNS = 30;

/**
 * 把模型返回的 Markdown 渲染成 DOM。只构造节点、不拼 innerHTML，天然免疫 HTML 注入。
 * 支持：围栏代码块、标题、表格、引用、有序/无序列表、分隔线、粗体/斜体/删除线/行内代码/链接。
 * 流式期间未闭合的代码围栏会一直按代码渲染，避免逐字闪烁。
 */
function renderMarkdown(text: unknown) {
  const fragment = document.createDocumentFragment();
  for (const node of markdownBlocks(String(text).replace(/\r\n/g, '\n').split('\n'))) {
    fragment.append(node);
  }
  return fragment;
}

function markdownBlocks(lines: string[]) {
  return markdownBlockRecords(lines).map((record) => record.node);
}

/**
 * 与 markdownBlocks 同样的块划分，但额外带出每个块的绝对行区间。
 *
 * <p>行区间是流式增量渲染的依据：只要一个块的输入没变，它对应 DOM 就可以原样留着。
 * offset 用于解析「从中间某个位置切出来的行片段」时把行号还原成整篇坐标。
 */
function markdownBlockRecords(
  lines: string[],
  offset = 0,
  depthOrOptions: number | { streaming?: boolean; depth?: number } = 0
): MarkdownBlock[] {
  const depth = typeof depthOrOptions === 'number' ? depthOrOptions : (depthOrOptions?.depth ?? 0);
  const streaming = typeof depthOrOptions === 'object' && Boolean(depthOrOptions?.streaming);
  const records: MarkdownBlock[] = [];
  let i = 0;

  /** blocks 的每一次 Push 都带上当前块的起止行；end 是不包含的下标。 */
  const push = (node: Node, start: number, extra?: Partial<Omit<MarkdownBlock, 'node' | 'start' | 'end'>>) => {
    records.push({ node, start: start + offset, end: i + offset, ...extra });
  };

  while (i < lines.length) {
    const line = lines[i];

    if (!line.trim()) {
      i++;
      continue;
    }

    const start = i;

    if (line.trim() === '$$') {
      const math = [];
      i++;
      let closed = false;
      while (i < lines.length && lines[i].trim() !== '$$') {
        math.push(lines[i]);
        i++;
      }
      if (i < lines.length) {
        i++;
        closed = true;
      }
      const tex = math.join('\n');
      if (streaming && !closed) {
        push(codeBlock(tex, 'math', { deferRichPreview: true }), start, {
          settled: false, kind: 'math-streaming', language: 'math', code: tex
        });
      } else {
        push(mathBlock(tex), start, { settled: true, kind: 'math' });
      }
      continue;
    }

    const source = sourceBlockAt(lines, i);
    if (source) {
      i = source.next;
      const code = source.lines.join('\n');
      const deferRichPreview = streaming && !source.closed;
      push(codeBlock(code, source.language, { deferRichPreview }), start, {
        settled: !deferRichPreview, kind: 'code', language: source.language, code
      });
      continue;
    }

    const fence = line.match(FENCE_PATTERN);
    if (fence) {
      const marker = fence[1][0];
      const length = fence[1].length;
      const language = fence[2] || '';
      i++;
      const code = [];
      let closed = false;
      while (i < lines.length) {
        const closing = lines[i].match(/^\s{0,3}(`{3,}|~{3,})\s*$/);
        if (closing && closing[1][0] === marker && closing[1].length >= length) {
          i++;
          closed = true;
          break;
        }
        code.push(lines[i]);
        i++;
      }
      const joined = code.join('\n');
      const kind = outputKind(language, joined);
      const isRich = kind !== 'code' && kind !== 'diff';
      const deferRichPreview = streaming && !closed && isRich;
      push(codeBlock(joined, language, { deferRichPreview }), start, {
        settled: !deferRichPreview, kind: deferRichPreview ? `${kind}-streaming` : kind, language, code: joined
      });
      continue;
    }

    if (RULE_PATTERN.test(line)) {
      push(document.createElement('hr'), start);
      i++;
      continue;
    }

    const heading = line.match(HEADING_PATTERN);
    if (heading) {
      const node = document.createElement('h' + heading[1].length);
      node.append(inlineFragment(heading[2]));
      push(node, start);
      i++;
      continue;
    }

    if (isTableStart(lines, i)) {
      const table = buildTable(lines, i);
      i = table.next;
      push(table.node, start);
      continue;
    }

    if (QUOTE_PATTERN.test(line)) {
      const quoted = [];
      while (i < lines.length && QUOTE_PATTERN.test(lines[i])) {
        quoted.push(lines[i].replace(QUOTE_PATTERN, ''));
        i++;
      }
      const blockquote = document.createElement('blockquote');
      const callout = parseCallout(quoted);
      const content = callout ? callout.lines : quoted;
      if (callout) {
        blockquote.className = `md-callout is-${callout.kind}`;
        const label = document.createElement('strong');
        label.className = 'md-callout-label';
        label.textContent = callout.label;
        blockquote.append(label);
      }
      if (depth >= MAX_QUOTE_DEPTH) blockquote.append(document.createTextNode(content.join('\n')));
      else for (const node of markdownBlockRecords(content, 0, depth + 1).map((record) => record.node)) blockquote.append(node);
      push(blockquote, start);
      continue;
    }

    if (LIST_PATTERN.test(line)) {
      const list = buildList(lines, i);
      i = list.next;
      push(list.node, start);
      continue;
    }

    const paragraph = [];
    while (i < lines.length) {
      const current = lines[i];
      if (!current.trim()) {
        break;
      }
      if (FENCE_PATTERN.test(current) || RULE_PATTERN.test(current)
          || HEADING_PATTERN.test(current) || QUOTE_PATTERN.test(current)
          || LIST_PATTERN.test(current) || isTableStart(lines, i)) {
        break;
      }
      paragraph.push(current.trim());
      i++;
    }
    if (!paragraph.length) {
      i++;
      continue;
    }
    const node = document.createElement('p');
    paragraph.forEach((part, index) => {
      if (index) {
        node.append(document.createElement('br'));
      }
      node.append(inlineFragment(part));
    });
    push(node, start);
  }

  return records;
}

/** 模型有时会直接输出 XML/SVG 源码而不加 Markdown 围栏。空行不能把一份 SVG 拆开。 */
function sourceBlockAt(lines: string[], start: number) {
  const first = lines[start].trim();
  const svg = /^<svg\b/i.test(first);
  const html = /^<(!doctype\s+html|html)\b/i.test(first);
  if (!svg && !html) {
    return null;
  }
  const closer = svg ? '</svg>' : '</html>';
  let end = start;
  let closed = false;
  while (end < lines.length && end - start < 4000) {
    if (lines[end].toLowerCase().includes(closer)) {
      end++;
      closed = true;
      break;
    }
    end++;
  }
  if (!closed) {
    end = lines.length;
  }
  const taken = lines.slice(start, end);
  const tagLines = taken.filter((line) => /^\s*<\/?[\w:!?-]+(?:\s|\/?>)/.test(line));
  if (tagLines.length < 3) {
    return null;
  }
  return { lines: taken, next: end, language: svg ? 'svg' : 'html', closed };
}

function safeSvg(source: string) {
  const doc = new DOMParser().parseFromString(source, 'image/svg+xml');
  const svg = doc.documentElement;
  if (!svg || svg.nodeName.toLowerCase() !== 'svg' || doc.querySelector('parsererror')) {
    return null;
  }
  // XML 解析保留原有的格式检查，并接受省略 xmlns 的常见模型输出。
  const cleaned = DOMPurify.sanitize(source, {
    USE_PROFILES: { svg: true, svgFilters: true },
    RETURN_DOM_FRAGMENT: true
  });
  cleaned.querySelectorAll('style').forEach((style) => style.remove());
  return cleaned.firstElementChild instanceof SVGSVGElement ? cleaned.firstElementChild : null;
}

function codeLineCountLabel(text: string): string {
  if (!text) return '';
  const count = text.split('\n').length;
  return count >= 2 ? `${count} 行` : '';
}

function codeBlock(code: string, language: string, options: { deferRichPreview?: boolean } = {}) {
  const text = code.replace(/\n+$/, '');
  const kind = outputKind(language, text);
  const defer = Boolean(options.deferRichPreview);
  const figure = document.createElement('figure');
  figure.className = 'md-code' + (!defer && kind !== 'code' && kind !== 'diff' ? ' is-rich' : '') + (defer ? ' is-streaming-rich' : '');
  figure.dataset.codeLang = language;
  figure.dataset.codeKind = defer ? `${kind}-streaming` : kind;
  codeBlockTextStore.set(figure, text);
  const head = document.createElement('figcaption');
  head.className = 'md-code-head';
  const label = document.createElement('span');
  label.className = 'md-code-language';
  const baseLabel = languageLabel(kind === 'code' ? language : kind);
  label.textContent = defer ? `${baseLabel} · 生成中` : baseLabel;
  const lineBadge = document.createElement('span');
  lineBadge.className = 'md-code-lines';
  lineBadge.textContent = codeLineCountLabel(text);
  const source = sourcePre(text, language);
  head.append(label, lineBadge);
  const getText = () => codeBlockTextStore.get(figure) ?? text;
  const preview = defer ? null : buildPreview(kind, text, source);
  if (preview) {
    source.hidden = true;
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'md-code-toggle';
    toggle.textContent = '代码';
    toggle.addEventListener('click', () => {
      const showCode = source.hidden;
      source.hidden = !showCode;
      preview.hidden = showCode;
      toggle.textContent = showCode ? '预览' : '代码';
    });
    head.append(toggle, copyControl('复制代码', getText, { tip: false }));
    figure.append(head, preview, source);
    return figure;
  }
  head.append(copyControl('复制代码', getText, { tip: false }));
  figure.append(head, source);
  return figure;
}

function buildPreview(kind: string, text: string, source: HTMLElement) {
  if (kind === 'svg') {
    const svg = safeSvg(text);
    if (!svg) {
      return null;
    }
    const preview = document.createElement('div');
    preview.className = 'md-preview';
    preview.append(svg);
    return preview;
  }
  if (kind === 'html') {
    const preview = document.createElement('div');
    preview.className = 'md-preview';
    const frame = document.createElement('iframe');
    frame.className = 'md-preview-frame';
    frame.setAttribute('sandbox', '');
    frame.srcdoc = text;
    frame.title = 'HTML 预览';
    preview.append(frame);
    return preview;
  }
  if (kind === 'mermaid') {
    const preview = document.createElement('div');
    preview.className = 'md-preview is-mermaid';
    paintWhenAttached(preview, () => renderMermaid(preview, text, source));
    return preview;
  }
  if (kind === 'math') {
    const preview = document.createElement('div');
    preview.className = 'md-preview is-math';
    paintWhenAttached(preview, () => renderMath(preview, text, true));
    return preview;
  }
  return null;
}

function mathBlock(tex: string) {
  const node = document.createElement('div');
  node.className = 'md-math';
  paintWhenAttached(node, () => renderMath(node, tex, true));
  return node;
}

function paintWhenAttached(node: HTMLElement, paint: () => void) {
  queueMicrotask(() => {
    if (node.isConnected) {
      paint();
    }
  });
}

function loadScript(src: string): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${src}"]`);
    if (existing) {
      if (existing.dataset.loaded === '1') {
        resolve();
      } else {
        existing.addEventListener('load', () => resolve(), { once: true });
        existing.addEventListener('error', () => reject(new Error(src)), { once: true });
      }
      return;
    }
    const script = document.createElement('script');
    script.src = src;
    script.onload = () => {
      script.dataset.loaded = '1';
      resolve();
    };
    script.onerror = () => reject(new Error(src));
    document.head.append(script);
  });
}

let mermaidLoading: Promise<Window['mermaid']> | undefined;
function loadMermaid() {
  if (window.mermaid) {
    return Promise.resolve(window.mermaid);
  }
  if (!mermaidLoading) {
    mermaidLoading = loadScript('/vendor/mermaid/mermaid.min.js').then(() => window.mermaid).catch((error) => {
      mermaidLoading = undefined;
      throw error;
    });
  }
  return mermaidLoading;
}

let katexLoading: Promise<Window['katex']> | undefined;
function loadKatex() {
  if (window.katex) {
    return Promise.resolve(window.katex);
  }
  if (!katexLoading) {
    if (!document.querySelector('link[data-katex]')) {
      const link = document.createElement('link');
      link.rel = 'stylesheet';
      link.href = '/vendor/katex/katex.min.css';
      link.dataset.katex = '1';
      document.head.append(link);
    }
    katexLoading = loadScript('/vendor/katex/katex.min.js').then(() => window.katex).catch((error) => {
      katexLoading = undefined;
      throw error;
    });
  }
  return katexLoading;
}

async function renderMermaid(host: HTMLElement, source: string, code: HTMLElement) {
  try {
    const mermaid = await loadMermaid();
    mermaid!.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: document.documentElement.dataset.theme === 'light' ? 'default' : 'dark'
    });
    const id = 'pilot-mermaid-' + Math.random().toString(36).slice(2);
    const drawn = await mermaid!.render(id, source);
    host.replaceChildren();
    const wrap = document.createElement('div');
    wrap.className = 'md-mermaid';
    const safe = DOMPurify.sanitize(drawn.svg, { USE_PROFILES: { svg: true, svgFilters: true }, RETURN_DOM_FRAGMENT: true });
    safe.querySelectorAll('style').forEach((style) => style.remove());
    wrap.append(safe);
    host.append(wrap);
  } catch (error) {
    host.hidden = true;
    code.hidden = false;
  }
}

async function renderMath(host: HTMLElement, tex: string, display: boolean) {
  try {
    const katex = await loadKatex();
    host.replaceChildren();
    katex!.render(tex, host, { throwOnError: false, displayMode: display });
  } catch (error) {
    host.textContent = tex;
  }
}

function populateDiffPre(pre: HTMLElement, code: string) {
  if (pre.childNodes?.length) clearNode(pre);
  pre.className = 'is-diff';
  const fragment = document.createDocumentFragment();
  code.split('\n').forEach((line) => {
    const row = document.createElement('span');
    row.className = 'diff-line';
    if (line.startsWith('@@')) {
      row.classList.add('is-hunk');
    } else if (line.startsWith('+') && !line.startsWith('+++')) {
      row.classList.add('is-add');
    } else if (line.startsWith('-') && !line.startsWith('---')) {
      row.classList.add('is-del');
    }
    row.textContent = line.length ? line : ' ';
    fragment.append(row);
  });
  pre.append(fragment);
}

function sourcePre(code: string, language: string) {
  const pre = document.createElement('pre');
  if (outputKind(language, code) === 'diff') {
    populateDiffPre(pre, code);
    return pre;
  }
  const el = document.createElement('code');
  if (language) {
    el.dataset.lang = language;
  }
  populateCodeElement(el, code, language);
  pre.append(el);
  return pre;
}

function splitRow(line: string) {
  let value = line.trim();
  if (value.startsWith('|')) {
    value = value.slice(1);
  }
  if (value.endsWith('|')) {
    value = value.slice(0, -1);
  }
  return value.split('|').map((cell) => cell.trim());
}

function isTableStart(lines: string[], index: number) {
  const header = lines[index];
  if (!header || !header.includes('|')) {
    return false;
  }
  const divider = lines[index + 1];
  if (!divider || !divider.includes('-')) {
    return false;
  }
  const cells = splitRow(divider);
  return cells.length > 0 && cells.every((cell) => TABLE_DIVIDER.test(cell.replace(/\s/g, '')));
}

function buildTable(lines: string[], start: number) {
  const originalHeader = splitRow(lines[start]);
  const header = originalHeader.slice(0, MAX_TABLE_COLUMNS);
  const aligns = splitRow(lines[start + 1]).map((cell) => {
    const value = cell.replace(/\s/g, '');
    if (value.startsWith(':') && value.endsWith(':')) {
      return 'center';
    }
    if (value.endsWith(':')) {
      return 'right';
    }
    return '';
  });

  const table = document.createElement('table');
  const thead = document.createElement('thead');
  const headRow = document.createElement('tr');
  header.forEach((cell, index) => {
    const th = document.createElement('th');
    if (aligns[index]) {
      th.style.textAlign = aligns[index];
    }
    th.append(inlineFragment(cell));
    headRow.append(th);
  });
  thead.append(headRow);
  table.append(thead);

  const tbody = document.createElement('tbody');
  let i = start + 2;
  while (i < lines.length && lines[i].trim() && lines[i].includes('|')) {
    if (i - start - 2 >= MAX_TABLE_ROWS) break;
    const cells = splitRow(lines[i]).slice(0, MAX_TABLE_COLUMNS);
    const row = document.createElement('tr');
    header.forEach((_ignored, index) => {
      const td = document.createElement('td');
      if (aligns[index]) {
        td.style.textAlign = aligns[index];
      }
      td.append(inlineFragment(cells[index] || ''));
      row.append(td);
    });
    tbody.append(row);
    i++;
  }
  const truncatedRows = i < lines.length && lines[i].trim() && lines[i].includes('|');
  if (truncatedRows) while (i < lines.length && lines[i].trim() && lines[i].includes('|')) i++;
  table.append(tbody);
  const wrap = document.createElement('div');
  wrap.className = 'md-table-wrap';
  wrap.append(table);
  if (originalHeader.length > MAX_TABLE_COLUMNS || truncatedRows) {
    const notice = document.createElement('p');
    notice.className = 'md-table-truncated';
    notice.textContent = `表格内容已截断（最多 ${MAX_TABLE_ROWS} 行、${MAX_TABLE_COLUMNS} 列）`;
    wrap.append(notice);
  }
  return { node: wrap, next: i };
}

function buildList(lines: string[], start: number) {
  const ordered = /^\s{0,3}\d/.test(lines[start]);
  const list = document.createElement(ordered ? 'ol' : 'ul');
  if (ordered) {
    const first = Number(lines[start].match(/^\s*(\d+)/)![1]);
    if (Number.isSafeInteger(first) && first !== 1) list.setAttribute('start', String(first));
  }
  let i = start;
  while (i < lines.length) {
    if (!lines[i].trim()) {
      let next = i;
      while (next < lines.length && !lines[next].trim()) {
        next++;
      }
      if (next >= lines.length || !LIST_PATTERN.test(lines[next])
          || /^\s{0,3}\d/.test(lines[next]) !== ordered) {
        break;
      }
      i = next;
    }
    const item = lines[i].match(LIST_PATTERN);
    if (!item || /^\s{0,3}\d/.test(lines[i]) !== ordered) {
      break;
    }
    const li = document.createElement('li');
    const task = item[2].match(/^\[([ xX])\]\s+(.*)$/);
    if (task) {
      li.className = 'md-task-item';
      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.checked = task[1].toLowerCase() === 'x';
      checkbox.disabled = true;
      checkbox.setAttribute('aria-label', checkbox.checked ? '已完成' : '未完成');
      li.append(checkbox, inlineFragment(task[2]));
    } else {
      li.append(inlineFragment(item[2]));
    }
    list.append(li);
    i++;
  }
  return { node: list, next: i };
}

function inlineFragment(text: string) {
  const fragment = document.createDocumentFragment();
  let last = 0;
  let index = 0;
  while (index < text.length) {
    const match = findInlineToken(text, index);
    if (!match) break;
    if (match.index > last) fragment.append(document.createTextNode(text.slice(last, match.index)));
    fragment.append(inlineToken(match.token));
    last = match.index + match.token.length;
    index = last;
  }
  if (last < text.length) fragment.append(document.createTextNode(text.slice(last)));
  return fragment;
}

const INLINE_PATTERNS = [
  /!\[[^\]\n]*\]\([^)\s]+\)/y,
  /`[^`\n]+`/y,
  /\*\*[^*\n]+\*\*/y,
  /~~[^~\n]+~~/y,
  /\*[^*\n]+\*/y,
  /\[[^\]\n]+\]\([^)\s]+\)/y
];

function matchInlineMath(text: string, index: number): { token: string } | { noCloseUntil: number } {
  let end = index + 1;
  while (end < text.length && text[end] !== '\n') {
    if (text[end] === '$' && text[end + 1] !== '$') {
      return end > index + 1 ? { token: text.slice(index, end + 1) } : { noCloseUntil: index };
    }
    end += text[end] === '\\' && end + 1 < text.length ? 2 : 1;
  }
  return { noCloseUntil: end };
}

function findInlineToken(text: string, from: number): { index: number; token: string } | null {
  let noDollarUntil = -1;
  for (let index = from; index < text.length; index++) {
    const marker = text[index];
    if (marker === '$' && index >= noDollarUntil && text[index - 1] !== '$' && text[index + 1] !== '$') {
      const math = matchInlineMath(text, index);
      if ('token' in math) return { index, token: math.token };
      noDollarUntil = Math.max(noDollarUntil, math.noCloseUntil);
      continue;
    }
    if (!'!`*~['.includes(marker)) continue;
    for (const pattern of INLINE_PATTERNS) {
      pattern.lastIndex = index;
      const match = pattern.exec(text);
      if (match) return { index, token: match[0] };
    }
  }
  return null;
}

function inlineToken(token: string) {
  if (token.startsWith('$') && !token.startsWith('$$')) {
    const tex = token.slice(1, -1);
    if (/[\\^_]/.test(tex)) {
      const node = document.createElement('span');
      node.className = 'md-math';
      paintWhenAttached(node, () => renderMath(node, tex, false));
      return node;
    }
    return document.createTextNode(token);
  }
  if (token.startsWith('![')) {
    const image = token.match(/^!\[([^\]]*)\]\(([^)\s]+)\)$/);
    const src = image ? safeImageSource(image[2]) : null;
    if (src) {
      const node = document.createElement('img');
      node.className = 'md-image';
      node.alt = image![1];
      node.src = src;
      return node;
    }
    return document.createTextNode(token);
  }
  if (token.startsWith('`')) {
    const code = document.createElement('code');
    code.textContent = token.slice(1, -1);
    return code;
  }
  if (token.startsWith('**')) {
    const strong = document.createElement('strong');
    strong.append(inlineFragment(token.slice(2, -2)));
    return strong;
  }
  if (token.startsWith('~~')) {
    const del = document.createElement('del');
    del.textContent = token.slice(2, -2);
    return del;
  }
  if (token.startsWith('*')) {
    const em = document.createElement('em');
    em.textContent = token.slice(1, -1);
    return em;
  }
  const link = token.match(/^\[([^\]]+)\]\(([^)\s]+)\)$/);
  const file = link ? resolveFileReference(link[2], getWorkspace()) : null;
  if (link && file) {
    const control = document.createElement('button');
    control.type = 'button';
    control.className = 'file-ref';
    const name = document.createElement('span');
    name.className = 'file-ref-name';
    name.textContent = link[1];
    control.append(name);
    if (file.line) {
      const line = document.createElement('span');
      line.className = 'file-ref-line';
      line.textContent = `L${file.line}`;
      control.append(line);
    }
    control.title = file.path + (file.line ? `:${file.line}` : '');
    control.setAttribute('aria-label', `打开文件 ${control.title}`);
    control.addEventListener('click', () => openFile(file.path, file.line));
    return control;
  }
  if (link && /^https?:\/\//i.test(link[2])) {
    const anchor = document.createElement('a');
    anchor.href = link[2];
    anchor.textContent = link[1];
    anchor.className = 'external-ref';
    anchor.target = '_blank';
    anchor.rel = 'noreferrer noopener';
    return anchor;
  }
  return document.createTextNode(token);
}

/**
 * 流式尾块就地更新：避免每帧 replaceChild 销毁重建节点导致划选打断、横向滚动条归零与滚动锚点抖动。
 */
function patchMarkdownBlock(existing: Node, next: MarkdownBlock, _previous?: MarkdownBlock): boolean {
  if (!(existing instanceof HTMLElement) || !(next.node instanceof HTMLElement)) {
    return false;
  }
  // 1. 普通代码块 / 流式未闭合代码块：保持 figure + figcaption + pre 原位（保留横向滚动位置），只更新代码体与行数
  if (existing.tagName === 'FIGURE' && next.node.tagName === 'FIGURE'
      && existing.classList.contains('md-code') && next.node.classList.contains('md-code')
      && !existing.classList.contains('is-rich') && !next.node.classList.contains('is-rich')
      && existing.dataset.codeKind === next.node.dataset.codeKind
      && existing.dataset.codeLang === next.node.dataset.codeLang) {
    const nextCode = codeBlockTextStore.get(next.node);
    const existingPre = existing.querySelector('pre');
    if (nextCode == null || !existingPre) return false;
    const prevScrollLeft = existingPre.scrollLeft;
    const prevScrollTop = existingPre.scrollTop;
    codeBlockTextStore.set(existing, nextCode);
    const linesEl = existing.querySelector('.md-code-lines');
    if (linesEl) linesEl.textContent = codeLineCountLabel(nextCode);
    const language = existing.dataset.codeLang || '';
    if (outputKind(language, nextCode) === 'diff') {
      populateDiffPre(existingPre, nextCode);
      if (prevScrollLeft > 0) existingPre.scrollLeft = prevScrollLeft;
      if (prevScrollTop > 0) existingPre.scrollTop = prevScrollTop;
      return true;
    }
    const existingCodeEl = existingPre.querySelector('code');
    if (!existingCodeEl) return false;
    populateCodeElement(existingCodeEl, nextCode, language);
    if (prevScrollLeft > 0) existingPre.scrollLeft = prevScrollLeft;
    if (prevScrollTop > 0) existingPre.scrollTop = prevScrollTop;
    return true;
  }
  // 2. 普通段落：纯文本增长直接改 Text.data（用户划选不会断开），含行内格式则保留 <p> 节点本身替换子节点
  if (existing.tagName === 'P' && next.node.tagName === 'P') {
    if (existing.childNodes.length === 1 && existing.firstChild?.nodeType === Node.TEXT_NODE
        && next.node.childNodes.length === 1 && next.node.firstChild?.nodeType === Node.TEXT_NODE) {
      const nextText = next.node.firstChild.nodeValue ?? '';
      if (existing.firstChild.nodeValue !== nextText) {
        existing.firstChild.nodeValue = nextText;
      }
      return true;
    }
    existing.replaceChildren(...Array.from(next.node.childNodes));
    return true;
  }
  // 3. 有序/无序列表：保留前缀未变的 <li> 节点，只更新变更项与新增项
  if ((existing.tagName === 'UL' || existing.tagName === 'OL') && existing.tagName === next.node.tagName
      && existing.getAttribute('start') === next.node.getAttribute('start')) {
    const nextItems = Array.from(next.node.children);
    while (existing.children.length > nextItems.length) {
      existing.lastElementChild?.remove();
    }
    for (let index = 0; index < nextItems.length; index += 1) {
      const nextItem = nextItems[index];
      const currentItem = existing.children[index];
      if (!currentItem) {
        existing.append(nextItem);
      } else if (currentItem.innerHTML !== nextItem.innerHTML || currentItem.className !== nextItem.className) {
        currentItem.className = nextItem.className;
        currentItem.replaceChildren(...Array.from(nextItem.childNodes));
      }
    }
    return true;
  }
  return false;
}

export { renderMarkdown, markdownBlocks, markdownBlockRecords, patchMarkdownBlock };
