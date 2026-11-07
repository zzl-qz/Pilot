import type { MarkdownBlock } from '../types/content.js';
/**
 * Markdown 渲染引擎：从旧版原生实现原样搬运（块级流式解析 + 代码块复制按钮 + katex + mermaid）。
 * katex / mermaid 依旧从 /vendor 下的静态脚本加载，行为与旧版一致。
 */

import { writeClipboard as tryWriteClipboard } from './clipboard.js';
import { parseCallout, resolveFileReference, languageLabel, outputKind, safeImageSource } from './content-format.js';

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

const ACTION_ICONS: Record<string, string> = {
  edit: '<path d="M10.8 2.7l2.5 2.5-7.6 7.6-3.2.7.7-3.2z"/><path d="M9.4 4.1l2.5 2.5"/>',
  branch: '<circle cx="4.5" cy="3.6" r="1.7"/><circle cx="4.5" cy="12.4" r="1.7"/><circle cx="11.5" cy="5.6" r="1.7"/><path d="M4.5 5.3v5.4M11.5 7.3c0 2.5-2.3 3.4-6 3.9"/>',
  copy: '<rect x="5.6" y="5.6" width="7.9" height="7.9" rx="2"/><path d="M10.4 3.4A1.4 1.4 0 0 0 9 2H4.2A1.7 1.7 0 0 0 2.5 3.7V9c0 .8.6 1.4 1.4 1.4"/>',
  check: '<path d="M3.4 8.6l3.1 3.1 6.1-7"/>'
};


function actionIconSvg(name: string) {
  return `<svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ACTION_ICONS[name]}</svg>`;
}

/** 图标按钮：不写字，悬停时用自带的小气泡说明（data-tip），读屏和键盘用 aria-label。 */
function actionIcon(label: string, icon: string, className: string, onClick: (event: MouseEvent) => void) {
  const node = document.createElement('button');
  node.type = 'button';
  node.className = 'icon-action ' + className;
  node.setAttribute('aria-label', label);
  node.dataset.tip = label;
  node.innerHTML = actionIconSvg(icon);
  node.addEventListener('click', onClick);
  return node;
}


/** 复用同一复制兼容路径；UI 层仍保留已有的成功/失败反馈。 */
async function writeClipboard(value: unknown) {
  if (!await tryWriteClipboard(value)) throw new Error('当前环境不支持复制，请手动选择文本');
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
      await writeClipboard(getText());
      node.textContent = '已复制';
      setTimeout(() => { node.textContent = label; }, 1200);
    } catch {
      notify('复制失败');
    }
  });
  return node;
}


const FENCE_PATTERN = /^\s{0,3}(`{3,}|~{3,})\s*([\w+#.-]*)\s*$/;
const HEADING_PATTERN = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
const RULE_PATTERN = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/;
const QUOTE_PATTERN = /^\s{0,3}>\s?/;
const LIST_PATTERN = /^\s{0,3}([-*+]|\d+[.)])\s+(.*)$/;
const TABLE_DIVIDER = /^:?-{2,}:?$/;

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
function markdownBlockRecords(lines: string[], offset = 0): MarkdownBlock[] {
  const records: MarkdownBlock[] = [];
  let i = 0;

  /** blocks 的每一次 Push 都带上当前块的起止行；end 是不包含的下标。 */
  const push = (node: Node, start: number) => {
    records.push({ node, start: start + offset, end: i + offset });
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
      while (i < lines.length && lines[i].trim() !== '$$') {
        math.push(lines[i]);
        i++;
      }
      if (i < lines.length) {
        i++;
      }
      push(mathBlock(math.join('\n')), start);
      continue;
    }

    const source = sourceBlockAt(lines, i);
    if (source) {
      i = source.next;
      push(codeBlock(source.lines.join('\n'), source.language), start);
      continue;
    }

    const fence = line.match(FENCE_PATTERN);
    if (fence) {
      const marker = fence[1][0];
      const length = fence[1].length;
      const language = fence[2] || '';
      i++;
      const code = [];
      while (i < lines.length) {
        const closing = lines[i].match(/^\s{0,3}(`{3,}|~{3,})\s*$/);
        if (closing && closing[1][0] === marker && closing[1].length >= length) {
          i++;
          break;
        }
        code.push(lines[i]);
        i++;
      }
      push(codeBlock(code.join('\n'), language), start);
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
      for (const node of markdownBlocks(content)) {
        blockquote.append(node);
      }
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
  return { lines: taken, next: end, language: svg ? 'svg' : 'html' };
}

function safeSvg(source: string) {
  const doc = new DOMParser().parseFromString(source, 'image/svg+xml');
  const svg = doc.documentElement;
  if (!svg || svg.nodeName.toLowerCase() !== 'svg' || doc.querySelector('parsererror')) {
    return null;
  }
  svg.querySelectorAll('script, foreignObject, iframe, object, embed, audio, video').forEach((node) => node.remove());
  svg.querySelectorAll('*').forEach((el) => {
    [...el.attributes].forEach((attr) => {
      const name = attr.name.toLowerCase();
      const value = attr.value.trim().toLowerCase();
      if (name.startsWith('on') || value.startsWith('javascript:') || (name === 'href' && value.startsWith('javascript:'))) {
        el.removeAttribute(attr.name);
      }
    });
  });
  return document.importNode(svg, true);
}

function codeBlock(code: string, language: string) {
  const text = code.replace(/\n+$/, '');
  const kind = outputKind(language, text);
  const figure = document.createElement('figure');
  figure.className = 'md-code' + (kind === 'code' || kind === 'diff' ? '' : ' is-rich');
  const head = document.createElement('figcaption');
  head.className = 'md-code-head';
  const label = document.createElement('span');
  label.className = 'md-code-language';
  label.textContent = languageLabel(kind === 'code' ? language : kind);
  const source = sourcePre(text, language);
  head.append(label);
  const preview = buildPreview(kind, text, source);
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
    head.append(toggle, copyControl('复制代码', () => text, { tip: false }));
    figure.append(head, preview, source);
    return figure;
  }
  head.append(copyControl('复制代码', () => text, { tip: false }));
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
    mermaidLoading = loadScript('/vendor/mermaid/mermaid.min.js').then(() => window.mermaid);
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
    katexLoading = loadScript('/vendor/katex/katex.min.js').then(() => window.katex);
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
    wrap.innerHTML = drawn.svg;
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

function sourcePre(code: string, language: string) {
  const pre = document.createElement('pre');
  if (outputKind(language, code) === 'diff') {
    pre.className = 'is-diff';
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
      pre.append(row);
    });
    return pre;
  }
  const el = document.createElement('code');
  if (language) {
    el.dataset.lang = language;
  }
  el.textContent = code;
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
  const header = splitRow(lines[start]);
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
    const cells = splitRow(lines[i]);
    const row = document.createElement('tr');
    header.forEach((ignored, index) => {
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
  table.append(tbody);
  const wrap = document.createElement('div');
  wrap.className = 'md-table-wrap';
  wrap.append(table);
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
  const pattern = /(!\[[^\]\n]*\]\([^)\s]+\)|(?<!\$)\$(?!\$)(?:\\.|[^$\n])+\$(?!\$)|`[^`\n]+`|\*\*[^*\n]+\*\*|~~[^~\n]+~~|\*[^*\n]+\*|\[[^\]\n]+\]\([^)\s]+\))/g;
  let last = 0;
  let match;
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > last) {
      fragment.append(document.createTextNode(text.slice(last, match.index)));
    }
    fragment.append(inlineToken(match[0]));
    last = match.index + match[0].length;
  }
  if (last < text.length) {
    fragment.append(document.createTextNode(text.slice(last)));
  }
  return fragment;
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

export { renderMarkdown, markdownBlocks, markdownBlockRecords, actionIconSvg, actionIcon };
