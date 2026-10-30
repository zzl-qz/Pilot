import test from 'node:test';
import assert from 'node:assert/strict';

import {
  MAX_ATTACHMENTS,
  MAX_ATTACHMENT_BYTES,
  attachmentUrl,
  checkIncomingFile,
  fileBadge,
  formatBytes,
  isImageAttachment,
  joinAttachmentMarkers,
  splitAttachmentMarkers
} from '../../main/resources/static/lib/attachments.js';

test('文件徽标按扩展名分类', () => {
  assert.deepEqual(fileBadge('App.java'), { label: 'JAVA', tone: 'code' });
  assert.deepEqual(fileBadge('config.yaml'), { label: 'YAML', tone: 'data' });
  assert.deepEqual(fileBadge('README.md'), { label: 'MD', tone: 'doc' });
  assert.deepEqual(fileBadge('shot.PNG'), { label: 'PNG', tone: 'image' });
  assert.deepEqual(fileBadge('backup.tar.gz'), { label: 'GZ', tone: 'archive' });
  assert.deepEqual(fileBadge('Makefile'), { label: 'FILE', tone: 'file' });
  assert.deepEqual(fileBadge('presentation.pptx').label, 'PPTX');
});

test('大小格式化保持短而可读', () => {
  assert.equal(formatBytes(12), '12 B');
  assert.equal(formatBytes(1536), '1.5 KB');
  assert.equal(formatBytes(20 * 1024), '20 KB');
  assert.equal(formatBytes(2 * 1024 * 1024), '2.0 MB');
  assert.equal(formatBytes(-1), '');
});

test('图片判断兼容 MIME 缺失的拖入文件', () => {
  assert.equal(isImageAttachment({ name: 'a.png', mimeType: 'image/png' }), true);
  assert.equal(isImageAttachment({ name: 'a.webp', mimeType: '' }), true);
  assert.equal(isImageAttachment({ name: 'a.txt', mimeType: 'text/plain' }), false);
  assert.equal(isImageAttachment(null), false);
});

test('新文件校验：重复静默忽略，超限与超大给出原因', () => {
  const existing = [{ name: 'a.txt', size: 10 }];
  assert.deepEqual(checkIncomingFile(existing, { name: 'a.txt', size: 10 }), { ok: false, silent: true, reason: '' });
  assert.equal(checkIncomingFile(existing, { name: 'b.txt', size: 10 }).ok, true);
  assert.match(checkIncomingFile(existing, { name: 'big.bin', size: MAX_ATTACHMENT_BYTES + 1 }).reason, /big\.bin.*上限/);
  assert.match(checkIncomingFile(existing, { name: 'empty.txt', size: 0 }).reason, /空文件/);
  const full = Array.from({ length: MAX_ATTACHMENTS }, (_, i) => ({ name: `f${i}`, size: i + 1 }));
  assert.match(checkIncomingFile(full, { name: 'x', size: 5 }).reason, /最多附加 5 个/);
});

const INLINE = '[附件 A.java (text/plain)]\nclass A {}\nclass B {}\n[附件结束 A.java]';
const SAVED = '[附件 report.pdf (application/pdf) 已保存到 /tmp/x/report.pdf，需要时用工具读取]';

test('从正文里拆出内联与落盘文件，用户原话保持不变', () => {
  const parsed = splitAttachmentMarkers(`看一下这个\n\n${INLINE}\n\n${SAVED}`);
  assert.equal(parsed.body, '看一下这个');
  assert.deepEqual(parsed.files.map((file) => [file.name, file.kind, file.mimeType]), [
    ['A.java', 'inline', 'text/plain'],
    ['report.pdf', 'saved', 'application/pdf']
  ]);
  assert.equal(parsed.files[0].lines, 2);
});

test('没有文件块时原样返回', () => {
  assert.deepEqual(splitAttachmentMarkers('普通消息\n第二行'), { body: '普通消息\n第二行', files: [] });
  assert.deepEqual(splitAttachmentMarkers(''), { body: '', files: [] });
});

test('文件内容里出现方括号也不会误拆', () => {
  const text = '[附件 b.md (text/markdown)]\n里面有 [附件 假的 (text/plain)] 字样\n[附件结束 b.md]';
  const parsed = splitAttachmentMarkers(text);
  assert.equal(parsed.files.length, 1);
  assert.equal(parsed.files[0].name, 'b.md');
  assert.equal(parsed.body, '');
});

test('拆开再拼回得到等价正文', () => {
  const original = `改一下\n\n${INLINE}\n\n${SAVED}`;
  const parsed = splitAttachmentMarkers(original);
  assert.equal(joinAttachmentMarkers(parsed.body, parsed.files), original);
  assert.equal(joinAttachmentMarkers('新的话', parsed.files), `新的话\n\n${INLINE}\n\n${SAVED}`);
  assert.equal(joinAttachmentMarkers('', []), '');
});

test('附件地址优先用本地数据，其次服务端落盘文件名', () => {
  assert.equal(attachmentUrl('s1', { dataBase64: 'data:image/png;base64,AA' }), 'data:image/png;base64,AA');
  assert.equal(
    attachmentUrl('ses 1', { path: '/home/u/.pilot/attachments/ses_1/1234-shot.png' }),
    '/api/sessions/ses%201/attachments/1234-shot.png'
  );
  assert.equal(attachmentUrl('s1', { path: 'C:\\data\\a\\shot.png' }), '/api/sessions/s1/attachments/shot.png');
  assert.equal(attachmentUrl('', { path: '/x/y.png' }), '');
  assert.equal(attachmentUrl('s1', null), '');
});
