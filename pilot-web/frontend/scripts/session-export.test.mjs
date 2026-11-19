import assert from 'node:assert/strict';
import test from 'node:test';
import { saveTextFile } from '../src/lib/transcript.ts';

test('有下载能力时保存文件', () => {
  const clicks = [];
  const doc = {
    body: { append() {}, },
    createElement() {
      return { download: '', click() { clicks.push(this.download); }, remove() {} };
    }
  };
  globalThis.URL.createObjectURL = () => 'blob:session';
  globalThis.URL.revokeObjectURL = () => {};
  globalThis.Blob = class Blob { constructor(parts) { this.parts = parts; } };
  assert.equal(saveTextFile('# 会话', 'pilot-会话.md', doc), true);
  assert.deepEqual(clicks, ['pilot-会话.md']);
});

test('不能下载时返回 false，方便改成复制全文', () => {
  const doc = { body: {}, createElement: () => ({}) };
  assert.equal(saveTextFile('全文', 'a.md', doc), false);
  assert.equal(saveTextFile('全文', 'a.md', null), false);
});
