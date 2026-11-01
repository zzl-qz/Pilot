import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createBlockStreamRenderer,
  firstDifferenceIndex,
  resumeLineIndex,
  sameBlock,
  sharedBlockPrefix
} from '../../../frontend/src/lib/markdown-stream.js';

/** 一个行为像 DOM 的最小容器。 */
function fakeContainer() {
  const children = [];
  const attach = (node) => {
    if (!node || typeof node !== 'object') {
      return node;
    }
    node.remove = () => {
      const index = children.indexOf(node);
      if (index >= 0) {
        children.splice(index, 1);
      }
    };
    return node;
  };
  return {
    get childNodes() {
      return children;
    },
    get lastChild() {
      return children.at(-1) ?? null;
    },
    childCount: () => children.length,
    append(node) {
      children.push(attach(node));
    },
    replaceChild(next, old) {
      const index = children.indexOf(old);
      if (index < 0) {
        throw new Error('replaceChild: 目标节点不在容器里');
      }
      children.splice(index, 1, attach(next));
    },
    replaceChildren() {
      children.length = 0;
    }
  };
}

/** 测试用的解析器：一行一个块，空行不算块。 */
function parse(lines, offset = 0) {
  const records = [];
  lines.forEach((line, index) => {
    if (!line.trim()) {
      return;
    }
    records.push({
      node: { line },
      start: index + offset,
      end: index + 1 + offset
    });
  });
  return records;
}

function renderer() {
  const container = fakeContainer();
  return { container, render: createBlockStreamRenderer(container, { parse }) };
}

test('两份行数组完全一致时没有变化点', () => {
  assert.equal(firstDifferenceIndex(['a', 'b'], ['a', 'b']), -1);
  assert.equal(firstDifferenceIndex(['a'], ['a', 'b']), 1);
  assert.equal(firstDifferenceIndex(['a', 'b'], ['a', 'c']), 1);
  assert.equal(firstDifferenceIndex(['a', 'b'], ['a']), 1);
});

test('重解析从变化所在的那个块的起点开始，不越过更早的块', () => {
  const blocks = [{ start: 0, end: 1 }, { start: 1, end: 4 }, { start: 4, end: 6 }];
  assert.equal(resumeLineIndex(blocks, 3), 1);
  assert.equal(resumeLineIndex(blocks, 5), 4);
  // 落在块之间的空行：就从空行自己开始
  assert.equal(resumeLineIndex(blocks, 0), 0);
  assert.equal(resumeLineIndex([], 9), 9);
});

test('块的复用要求行区间与原文都一致', () => {
  const block = { start: 0, end: 2, source: 'a\nb' };
  assert.equal(sameBlock(block, { start: 0, end: 2, source: 'a\nb' }), true);
  assert.equal(sameBlock(block, { start: 0, end: 3, source: 'a\nb' }), false);
  assert.equal(sameBlock(block, { start: 0, end: 2, source: 'a\nB' }), false);
  assert.equal(sharedBlockPrefix([block], [{ start: 0, end: 2, source: 'a\nB' }]), 0);
});

test('逐行追加时只新建最后一个块，前面的节点保持同一个实例', () => {
  const { container, render } = renderer();
  render.update('第一行\n第二行');
  assert.equal(container.childCount(), 2);
  const first = container.childNodes[0];
  const second = container.childNodes[1];

  render.update('第一行\n第二行\n第三行');
  assert.equal(container.childCount(), 3);
  assert.equal(container.childNodes[0], first, '已稳定的块不该被重新创建');
  assert.equal(container.childNodes[1], second);
  assert.equal(container.childNodes[2].line, '第三行');

  render.update('第一行\n第二行\n第三行\n第四行');
  assert.equal(container.childCount(), 4);
  assert.equal(container.childNodes[0], first);
  assert.equal(container.childNodes[3].line, '第四行');
});

test('最后一行变长时只替换最后一个块', () => {
  const { container, render } = renderer();
  render.update('a\nb\nc');
  const first = container.childNodes[0];
  const second = container.childNodes[1];

  render.update('a\nb\ncccc');
  assert.equal(container.childNodes[0], first);
  assert.equal(container.childNodes[1], second);
  assert.equal(container.childNodes[2].line, 'cccc');
});

test('内容回滚变短时删掉多出来的尾块', () => {
  const { container, render } = renderer();
  render.update('a\nb\nc');
  assert.equal(container.childCount(), 3);

  const touched = render.update('a\n');
  assert.equal(touched, true);
  assert.equal(container.childCount(), 1);
  assert.equal(container.childNodes[0].line, 'a');
});

test('文本没有变化时不动 DOM，也不报告脏', () => {
  const { container, render } = renderer();
  render.update('a\nb');
  const first = container.childNodes[0];
  assert.equal(render.update('a\nb'), false);
  assert.equal(container.childCount(), 2);
  assert.equal(container.childNodes[0], first);
});

test('中间位置被改写时从那一块重画，更前面的块仍然复用', () => {
  const { container, render } = renderer();
  render.update('a\nb\nc\nd');
  const first = container.childNodes[0];

  render.update('a\nB\nc\nd');
  assert.equal(container.childCount(), 4);
  assert.equal(container.childNodes[0], first, '变化之前的块仍是同一个节点');
  assert.equal(container.childNodes[1].line, 'B');
});

test('reset 之后重新从头渲染', () => {
  const { container, render } = renderer();
  render.update('a\nb');
  render.reset();
  assert.equal(container.childCount(), 0);
  render.update('x\ny');
  assert.equal(container.childCount(), 2);
  assert.equal(container.childNodes[0].line, 'x');
});
