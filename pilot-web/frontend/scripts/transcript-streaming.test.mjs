import assert from 'node:assert/strict';
import { test } from 'node:test';
import { nextSmoothLength, safeSliceLength } from '../src/lib/content/stream-smoother.js';

class FakeNode {
  constructor(tag, text = '') {
    this.tag = tag;
    this.nodeName = tag.toUpperCase();
    this.tagName = tag.toUpperCase();
    this.nodeType = tag === '#text' ? 3 : 1;
    this._text = text;
    this.childNodes = [];
    this.parentNode = null;
    this.dataset = {};
    this.attrs = {};
    this.style = {};
    this.className = '';
    this.listeners = {};
    this.classList = {
      add: (...names) => {
        const set = new Set(this.className.split(/\s+/).filter(Boolean));
        names.forEach(n => set.add(n));
        this.className = [...set].join(' ');
      },
      remove: (...names) => {
        const set = new Set(this.className.split(/\s+/).filter(Boolean));
        names.forEach(n => set.delete(n));
        this.className = [...set].join(' ');
      },
      toggle: (name, force) => {
        const set = new Set(this.className.split(/\s+/).filter(Boolean));
        const has = set.has(name);
        const next = force === undefined ? !has : Boolean(force);
        if (next) set.add(name);
        else set.delete(name);
        this.className = [...set].join(' ');
        return next;
      },
      contains: name => this.className.split(/\s+/).includes(name),
    };
  }
  get children() { return this.childNodes.filter(c => c.tag !== '#text'); }
  get firstChild() { return this.childNodes[0] ?? null; }
  get firstElementChild() { return this.children[0] ?? null; }
  get nodeValue() { return this.tag === '#text' ? this._text : null; }
  set nodeValue(value) { if (this.tag === '#text') this._text = String(value ?? ''); }
  get textContent() {
    if (this.tag === '#text') return this._text;
    if (this.childNodes.length === 0) return this._text;
    return this.childNodes.map(c => c.textContent).join('');
  }
  set textContent(value) {
    this._text = String(value ?? '');
    this.childNodes = [];
  }
  append(...nodes) {
    for (const item of nodes) {
      if (item == null) continue;
      if (typeof item === 'string') {
        const tn = new FakeNode('#text', item);
        tn.parentNode = this;
        this.childNodes.push(tn);
      } else if (item.tag === '#fragment') {
        for (const child of [...item.childNodes]) {
          child.parentNode = this;
          this.childNodes.push(child);
        }
        item.childNodes = [];
      } else {
        item.parentNode = this;
        this.childNodes.push(item);
      }
    }
  }
  appendChild(node) { this.append(node); return node; }
  replaceChild(next, old) {
    const idx = this.childNodes.indexOf(old);
    if (idx >= 0) {
      next.parentNode = this;
      old.parentNode = null;
      this.childNodes[idx] = next;
    }
    return old;
  }
  removeChild(old) {
    const idx = this.childNodes.indexOf(old);
    if (idx >= 0) {
      old.parentNode = null;
      this.childNodes.splice(idx, 1);
    }
    return old;
  }
  replaceChildren(...nodes) {
    this.childNodes = [];
    this._text = '';
    this.append(...nodes);
  }
  replaceWith(next) {
    if (!this.parentNode) return;
    const idx = this.parentNode.childNodes.indexOf(this);
    if (idx >= 0) {
      next.parentNode = this.parentNode;
      this.parentNode.childNodes[idx] = next;
    }
  }
  remove() {
    if (!this.parentNode) return;
    const idx = this.parentNode.childNodes.indexOf(this);
    if (idx >= 0) this.parentNode.childNodes.splice(idx, 1);
    this.parentNode = null;
  }
  setAttribute(name, value) { this.attrs[name] = String(value); }
  getAttribute(name) { return this.attrs[name] ?? null; }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  querySelector(selector) {
    const match = node => {
      if (selector.startsWith('.')) return node.classList.contains(selector.slice(1));
      return node.tag === selector.toLowerCase();
    };
    const walk = list => {
      for (const child of list) {
        if (child.tag !== '#text' && match(child)) return child;
        const found = walk(child.childNodes);
        if (found) return found;
      }
      return null;
    };
    return walk(this.childNodes);
  }
  querySelectorAll(selector) {
    const out = [];
    const match = node => selector.startsWith('.')
      ? node.classList.contains(selector.slice(1))
      : node.tag === selector.toLowerCase();
    const walk = list => {
      for (const child of list) {
        if (child.tag !== '#text' && match(child)) out.push(child);
        walk(child.childNodes);
      }
    };
    walk(this.childNodes);
    return out;
  }
}

const previousDocument = globalThis.document;
const previousHTMLElement = globalThis.HTMLElement;
const previousNode = globalThis.Node;
globalThis.HTMLElement = FakeNode;
globalThis.Node = { TEXT_NODE: 3, ELEMENT_NODE: 1 };
globalThis.document = {
  createElement: tag => new FakeNode(tag),
  createDocumentFragment: () => new FakeNode('#fragment'),
  createTextNode: text => new FakeNode('#text', text),
};

const { markdownBlockRecords, patchMarkdownBlock } = await import('../src/lib/content/markdown-render.js');
const { createBlockStreamRenderer } = await import('../src/lib/content/markdown-stream.js');

test('stream-smoother 安全切分代理对并按积压自适应平滑推进', () => {
  const withEmoji = '你好🚀世界';
  // 索引 3 正好落在 🚀 (UTF-16 代理对 2..3) 的高代理之后，应补齐到 4 保持完整字符
  assert.equal(safeSliceLength(withEmoji, 3), 4);
  assert.equal(safeSliceLength(withEmoji, 2), 2);
  assert.equal(safeSliceLength(withEmoji, 99), withEmoji.length);

  // 非流式 / reducedMotion / 目标缩短 / 极小积压时应一步到位
  assert.equal(nextSmoothLength(0, 120, true), 120);
  assert.equal(nextSmoothLength(80, 30, false), 30);
  assert.equal(nextSmoothLength(10, 15, false), 15);

  // 小积压逐帧柔和推进，大积压按比例加速追赶
  const smallStep = nextSmoothLength(0, 20, false);
  const largeStep = nextSmoothLength(0, 600, false);
  assert.ok(smallStep > 0 && smallStep < 20, `小积压应柔和推进: ${smallStep}`);
  assert.ok(largeStep > smallStep * 5 && largeStep < 600, `大积压应加速追赶: ${largeStep}`);
});

test('流式增量更新复用尾部代码块与段落 DOM 节点（0 节点重建），并保留完整语法高亮与纯文本', () => {
  const host = new FakeNode('div');
  const renderer = createBlockStreamRenderer(host, {
    parse: (lines, startLine, options) => markdownBlockRecords(lines, startLine, options),
    patch: patchMarkdownBlock,
  });

  // 1. 开始流式输出段落 + 未闭合 TS 代码块
  renderer.update('正在修改：\n\n```ts\nconst count = 1;', { streaming: true });
  assert.equal(host.children.length, 2);
  const paraNode = host.children[0];
  const figNode = host.children[1];
  assert.equal(paraNode.tag, 'p');
  assert.equal(figNode.tag, 'figure');
  const preNode = figNode.querySelector('pre');
  const codeNode = figNode.querySelector('code');
  assert.equal(codeNode.textContent, 'const count = 1;');
  // 语法高亮 span 已生成
  assert.ok(codeNode.querySelectorAll('.tok-kw').length >= 1, '关键字应带有 .tok-kw 高亮');
  assert.ok(codeNode.querySelectorAll('.tok-num').length >= 1, '数字应带有 .tok-num 高亮');

  // 2. 流式追加第二行与第三行：figure、pre 与 code DOM 引用必须原地复用（0 次整块替换）
  renderer.update('正在修改：\n\n```ts\nconst count = 1;\nexport function inc() {\n  return count + 1;\n}', { streaming: true });
  assert.equal(host.children[0], paraNode, '已完成段落节点应保持同一引用');
  assert.equal(host.children[1], figNode, '流式代码块 <figure> 应原地修补而非重建');
  assert.equal(host.children[1].querySelector('pre'), preNode, '<pre> 应原地保留以维持横向滚动位置');
  assert.equal(codeNode.textContent, 'const count = 1;\nexport function inc() {\n  return count + 1;\n}');
  assert.equal(figNode.querySelector('.md-code-lines').textContent, '4 行');

  // 3. 段落流式生长同样原地修补 <p>
  renderer.update('正在修改：\n\n```ts\nconst count = 1;\n```\n\n第一句', { streaming: true });
  const tailPara = host.children[2];
  assert.equal(tailPara.tag, 'p');
  renderer.update('正在修改：\n\n```ts\nconst count = 1;\n```\n\n第一句，接着补充第二句。', { streaming: true });
  assert.equal(host.children[2], tailPara, '流式尾部 <p> 应原地修补而非替换节点');
  assert.equal(tailPara.textContent, '第一句，接着补充第二句。');
});

test('流式未闭合的 Mermaid / HTML / 块级数学推迟富预览渲染，闭合或结束后无报错升级', () => {
  const host = new FakeNode('div');
  const renderer = createBlockStreamRenderer(host, {
    parse: (lines, startLine, options) => markdownBlockRecords(lines, startLine, options),
    patch: patchMarkdownBlock,
  });

  // 未闭合 Mermaid：流式阶段不挂预览容器 / 报错框
  renderer.update('```mermaid\ngraph TD\n  A -->', { streaming: true });
  assert.equal(host.children.length, 1);
  assert.equal(host.children[0].tag, 'figure');
  assert.equal(host.children[0].querySelector('.md-preview'), null, '未闭合 Mermaid 流式期间不应提前挂载富预览');

  // 闭合后升级为可预览块（无任何错误节点）
  renderer.update('```mermaid\ngraph TD\n  A --> B\n```', { streaming: false });
  assert.equal(host.children.length, 1);
  assert.ok(host.children[0].querySelector('.md-preview') !== null, '闭合后应升级挂载 Mermaid 富预览容器');

  // 未闭合块级数学 $$：流式阶段渲染为无报错源码块（<figure class="md-code">），闭合后升级为 <div class="md-math">
  renderer.update('$$\n\\frac{a}{b', { streaming: true });
  assert.equal(host.children[0].tag, 'figure');
  assert.equal(host.children[0].classList.contains('md-code'), true, '未闭合块级公式流式阶段应保持无报错代码态');
  assert.equal(host.children[0].classList.contains('md-math'), false);
  renderer.update('$$\n\\frac{a}{b}\n$$', { streaming: false });
  assert.equal(host.children[0].classList.contains('md-math'), true, '闭合后应升级为正式块级公式节点');
});

test.after(() => {
  globalThis.document = previousDocument;
  globalThis.HTMLElement = previousHTMLElement;
  globalThis.Node = previousNode;
});
