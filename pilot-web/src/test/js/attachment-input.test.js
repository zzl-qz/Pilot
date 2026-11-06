import test from 'node:test';
import assert from 'node:assert/strict';
import { readAttachmentFile } from '../../../frontend/src/lib/attachment-input.js';

test('读取附件保留原名、类型、大小和完整 data URL', async t => {
  const original = globalThis.FileReader;
  t.after(() => { globalThis.FileReader = original; });
  globalThis.FileReader = class {
    readAsDataURL(file) { this.result = 'data:text/plain;base64,5Lit5paH'; queueMicrotask(() => this.onload()); this.file = file; }
  };
  const result = await readAttachmentFile({ name: '中文 空格.txt', type: 'text/plain', size: 6 });
  assert.equal(result.name, '中文 空格.txt');
  assert.equal(result.mimeType, 'text/plain');
  assert.equal(result.size, 6);
  assert.equal(result.dataBase64, 'data:text/plain;base64,5Lit5paH');
  assert.ok(result.id);
});

test('读取失败和取消明确拒绝，不把文件路径或空数据当作成功附件', async t => {
  const original = globalThis.FileReader;
  t.after(() => { globalThis.FileReader = original; });
  for (const [event, message] of [['onerror', '无法读取'], ['onabort', '已取消读取']]) {
    globalThis.FileReader = class { readAsDataURL() { queueMicrotask(() => this[event]()); } };
    await assert.rejects(readAttachmentFile({ name: '文件.txt', type: '', size: 10 }), new RegExp(message + '.*文件.txt'));
  }
});
