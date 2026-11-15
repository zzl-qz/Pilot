import assert from 'node:assert/strict';
import test from 'node:test';
import { attachmentBatchError, checkIncomingFile } from '../src/lib/attachments.js';
import { transferDirectoryNames, transferHasDirectory } from '../src/lib/clipboard.js';

const limits = { maxFiles: 2, maxFileBytes: 1000, maxTotalBytes: 1500, maxRequestBytes: 4000 };

test('文件夹不能被当成附件', () => {
  const result = checkIncomingFile([], { name: '资料', size: 0 }, limits, { directory: true });
  assert.equal(result.ok, false);
  assert.match(result.reason, /文件夹/);
  assert.match(result.reason, /压缩/);
});

test('空文件与超限文件给出不同原因', () => {
  const empty = checkIncomingFile([], { name: 'empty.txt', size: 0 }, limits);
  assert.equal(empty.ok, false);
  assert.match(empty.reason, /空文件/);

  const big = checkIncomingFile([], { name: 'big.docx', size: 1001 }, limits);
  assert.equal(big.ok, false);
  assert.match(big.reason, /1000|1\.0 KB/);
  assert.match(big.reason, /工作区|max-file-bytes/);

  const total = checkIncomingFile([{ name: 'a.txt', size: 800 }], { name: 'b.txt', size: 800 }, limits);
  assert.equal(total.ok, false);
  assert.match(total.reason, /总量|预算/);
});

test('数量上限在加入前生效', () => {
  const third = checkIncomingFile(
    [{ name: 'a.txt', size: 10 }, { name: 'b.txt', size: 10 }],
    { name: 'c.txt', size: 10 },
    limits
  );
  assert.equal(third.ok, false);
  assert.match(third.reason, /最多附加 2 个/);
});

test('同名同大小的普通文件静默去重，图片交给内容去重', () => {
  const dup = checkIncomingFile([{ name: 'a.txt', size: 10 }], { name: 'a.txt', size: 10 }, limits);
  assert.deepEqual(dup, { ok: false, silent: true, reason: '' });

  const image = checkIncomingFile(
    [{ name: 'image.png', size: 10 }],
    { name: 'image.png', size: 10, type: 'image/png' },
    limits
  );
  assert.equal(image.ok, true, '同名同大小的图片允许进入，读取后按内容去重');

  const differentSize = checkIncomingFile([{ name: 'a.txt', size: 10 }], { name: 'a.txt', size: 11 }, limits);
  assert.equal(differentSize.ok, true);
});

test('拖放来源能分辨文件夹并给出名字', () => {
  const folder = { kind: 'file', webkitGetAsEntry: () => ({ isDirectory: true, name: '素材' }) };
  const file = { kind: 'file', webkitGetAsEntry: () => ({ isDirectory: false, name: 'a.txt' }) };
  const transfer = { items: [folder, file], types: ['Files'] };
  assert.equal(transferHasDirectory(transfer), true);
  assert.deepEqual(transferDirectoryNames(transfer), ['素材']);
  assert.equal(transferHasDirectory({ items: [file], types: ['Files'] }), false);
  // 没有 entry API 的受限 WebView 不能误判成文件夹
  assert.equal(transferHasDirectory({ items: [{ kind: 'file' }], types: ['Files'] }), false);
});

test('批量文案覆盖空文件与总量', () => {
  assert.match(attachmentBatchError([{ name: 'x', size: 0 }], limits), /空文件/);
  assert.match(attachmentBatchError([{ name: 'x', size: 3 }], { ...limits, maxFileBytes: 2 }), /超过单个/);
  assert.match(attachmentBatchError([{ name: 'x', size: 3 }], { ...limits, maxTotalBytes: 2 }), /总量/);
});
test('SVG 等非栅格格式按普通文件处理，避免图片位置数对不上', async () => {
  const { isImageAttachment, fileBadge } = await import('../src/lib/attachments.js');
  assert.equal(isImageAttachment({ name: 'shot.png', mimeType: 'image/png' }), true);
  assert.equal(isImageAttachment({ name: 'shot.jpg', mimeType: 'image/jpg' }), true);
  assert.equal(isImageAttachment({ name: 'shot.jpg', mimeType: 'application/octet-stream' }), true);
  assert.equal(isImageAttachment({ name: 'logo.svg', mimeType: 'image/svg+xml' }), false);
  assert.equal(isImageAttachment({ name: 'icon.ico', mimeType: 'image/x-icon' }), false);
  assert.equal(isImageAttachment({ name: 'photo.bmp', mimeType: 'image/bmp' }), false);
  assert.equal(isImageAttachment({ name: 'readme.txt', mimeType: 'text/plain' }), false);
  assert.equal(fileBadge('logo.svg', 'image/svg+xml').tone, 'image', '徽标仍按图片上色');
});
