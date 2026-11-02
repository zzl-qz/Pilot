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
test('兼容只有 items 的剪贴板，忽略文本和无法读取的项目', () => {
  const file = { name: 'a.png' };
  assert.deepEqual(readTransferFiles({ items: [
    { kind: 'string' }, { kind: 'file', getAsFile: () => null }, { kind: 'file', getAsFile: () => file }
  ] }), [file]);
  assert.deepEqual(readTransferFiles(null), []);
});
