import test from 'node:test';
import assert from 'node:assert/strict';

import { writeClipboard } from '../../../frontend/src/lib/clipboard.js';

test('writeClipboard 优先用异步剪贴板 API', async () => {
  const written = [];
  const ok = await writeClipboard('hello', {
    navigator: { clipboard: { writeText: async (value) => { written.push(value); } } }
  });
  assert.equal(ok, true);
  assert.deepEqual(written, ['hello']);
});

test('异步 API 被拒时退回 execCommand，仍然复制成功', async () => {
  const created = [];
  const document = {
    createElement: () => ({
      style: {},
      setAttribute() {},
      select() {},
      remove() {},
      set value(value) { created.push(value); }
    }),
    body: { append() {} },
    execCommand: (command) => command === 'copy'
  };
  const ok = await writeClipboard('fallback', {
    navigator: { clipboard: { writeText: async () => { throw new Error('denied'); } } },
    document
  });
  assert.equal(ok, true);
  assert.deepEqual(created, ['fallback']);
});

test('两条路都不通时返回 false，界面据此提示', async () => {
  assert.equal(await writeClipboard('x', { navigator: {}, document: null }), false);
  const failing = {
    createElement: () => ({ style: {}, setAttribute() {}, select() {}, remove() {}, value: '' }),
    body: { append() {} },
    execCommand: () => false
  };
  assert.equal(await writeClipboard('x', { navigator: {}, document: failing }), false);
});

test('空值不会抛异常', async () => {
  const written = [];
  await writeClipboard(null, { navigator: { clipboard: { writeText: async (value) => { written.push(value); } } } });
  assert.deepEqual(written, ['']);
});

import { readTransferFiles } from '../../../frontend/src/lib/clipboard.js';
test('粘贴文件优先读取 files，不重复读取 items', () => {
  const file = { name: 'a.txt' };
  assert.deepEqual(readTransferFiles({ files: [file], items: [{ kind: 'file', getAsFile: () => file }] }), [file]);
});

test('损坏的剪贴板条目不阻止其他文件', () => {
  const file = { name: 'ok.txt' };
  assert.deepEqual(readTransferFiles({ items: [
    { kind: 'file', getAsFile() { throw Error('unavailable'); } },
    { kind: 'file' }, { kind: 'file', getAsFile: () => file }
  ] }), [file]);
});

import { hasFileTransferHint } from '../../../frontend/src/lib/clipboard.js';

test('浏览器声明文件传输类型但隐藏内容时给出提示，普通网址与路径文本不被拦截', () => {
  for (const type of ['text/uri-list', 'x-special/gnome-copied-files']) {
    assert.equal(hasFileTransferHint({ types: [type], getData: () => '' }), true);
    assert.equal(hasFileTransferHint({ types: [type], getData: () => '   ' }), true);
    assert.equal(hasFileTransferHint({ types: [type], getData() { throw Error('denied'); } }), true);
    assert.equal(hasFileTransferHint({ types: [type] }), true);
  }
  assert.equal(hasFileTransferHint({ types: ['text/uri-list', 'text/plain'], getData: () => 'https://example.com' }), false);
  assert.equal(hasFileTransferHint({ types: ['text/plain'], getData: () => '' }), false);
});
test('识别各平台文件 URI 和 GNOME 复制格式，普通文字和网页链接不误判', () => {
  for (const uri of ['file:///C:/Users/demo/a.txt', 'file://server/share/a.txt', 'file:///home/demo/a.txt', 'file:///Users/demo/a.txt']) {
    assert.equal(hasFileTransferHint({ getData: type => type === 'text/uri-list' ? uri : '' }), true);
  }
  assert.equal(hasFileTransferHint({ getData: type => type === 'x-special/gnome-copied-files' ? 'copy\nfile:///home/demo/a.txt' : '' }), true);
  assert.equal(hasFileTransferHint({ getData: type => type === 'text/plain' ? 'file:///home/demo/a.txt' : 'https://example.com' }), false);
  assert.equal(hasFileTransferHint({ getData() { throw Error('denied'); } }), false);
  assert.equal(hasFileTransferHint({ types: ['Files'] }), true);
});

test('复制回退留在弹窗内，失败时也清理临时控件并恢复焦点和选区', async () => {
  const events = [];
  const modal = { append() { events.push('append-dialog'); } };
  const range = {};
  const document = {
    activeElement: { closest: () => modal, focus() { events.push('focus'); } },
    getSelection: () => ({ rangeCount: 1, getRangeAt: () => ({ cloneRange: () => range }), removeAllRanges() { events.push('clear-range'); }, addRange(value) { assert.equal(value, range); events.push('restore-range'); } }),
    createElement: () => ({ style: {}, setAttribute() {}, select() { events.push('select'); }, remove() { events.push('remove'); } }),
    body: { append() { throw Error('不能放在弹窗外'); } },
    execCommand() { throw Error('denied'); }
  };
  assert.equal(await writeClipboard('copy', { navigator: {}, document }), false);
  assert.deepEqual(events, ['append-dialog', 'select', 'remove', 'focus', 'clear-range', 'restore-range']);
});
test('兼容只有 items 的剪贴板，忽略文本和无法读取的项目', () => {
  const file = { name: 'a.png' };
  assert.deepEqual(readTransferFiles({ items: [
    { kind: 'string' }, { kind: 'file', getAsFile: () => null }, { kind: 'file', getAsFile: () => file }
  ] }), [file]);
  assert.deepEqual(readTransferFiles(null), []);
});
