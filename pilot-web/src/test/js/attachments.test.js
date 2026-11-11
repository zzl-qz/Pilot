import test from 'node:test';
import assert from 'node:assert/strict';

import {
  MAX_ATTACHMENTS,
  MAX_ATTACHMENT_BYTES,
  attachmentBatchError,
  requireAttachmentLimits,
  attachmentUrl,
  checkIncomingFile,
  fileBadge,
  formatBytes,
  isImageAttachment,
  joinAttachmentMarkers,
  layoutMessageParts,
  splitAttachmentMarkers
} from '../../../frontend/src/lib/attachments.js';

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
  assert.equal(checkIncomingFile(
    [{ name: 'image.png', size: 10, mimeType: 'image/png' }],
    { name: 'image.png', size: 10, mimeType: 'image/png' }
  ).ok, true, '剪贴板图片即使同名同大小也应允许再次添加');
  assert.equal(checkIncomingFile(existing, { name: 'b.txt', size: 10 }).ok, true);
  assert.match(checkIncomingFile(existing, { name: 'big.bin', size: MAX_ATTACHMENT_BYTES + 1 }).reason, /big\.bin.*上限/);
  assert.match(checkIncomingFile(existing, { name: 'empty.txt', size: 0 }).reason, /空文件/);
  const belowLimit = Array.from({ length: MAX_ATTACHMENTS - 1 }, (_, i) => ({ name: `f${i}`, size: i + 1 }));
  assert.equal(belowLimit.length, 63);
  assert.equal(checkIncomingFile(belowLimit, { name: 'x', size: 65 }).ok, true);
  const full = [...belowLimit, { name: 'f63', size: 64 }];
  assert.equal(full.length, MAX_ATTACHMENTS);
  assert.match(checkIncomingFile(full, { name: 'x', size: 66 }).reason, /最多附加 64 个/);
});

test('附件总量预算：累计超限给出原因', () => {
  const twoMb = 2 * 1024 * 1024;
  const existing = Array.from({ length: 12 }, (_, i) => ({ name: `f${i}.bin`, size: twoMb }));
  const incoming = { name: 'more.bin', size: twoMb };
  const result = checkIncomingFile(existing, incoming);
  assert.equal(result.ok, false);
  assert.match(result.reason, /预算/);
  assert.equal(checkIncomingFile(existing.slice(0, 11), incoming).ok, true, '11×2MB + 2MB 未超 25MB 预算');
});

test('服务端自定义预算同时约束新增文件和恢复的草稿', () => {
  const limits = requireAttachmentLimits({ maxFiles: 2, maxFileBytes: 3, maxTotalBytes: 4, maxRequestBytes: 200 });
  const a = { name: 'a.txt', size: 2 };
  const b = { name: 'b.txt', size: 2 };
  assert.equal(checkIncomingFile([a], b, limits).ok, true);
  assert.match(checkIncomingFile([a], { ...b, size: 3 }, limits).reason, /总量/);
  assert.match(attachmentBatchError([{ ...a, size: 4 }], limits), /单个/);
  assert.match(attachmentBatchError([a, b, a], limits), /最多/);
  assert.match(attachmentBatchError([{ ...a, size: NaN }], limits), /无效/);
  for (const invalid of [null, {}, { ...limits, maxTotalBytes: -1 }, { ...limits, maxFileBytes: 1.5 }]) {
    assert.throws(() => requireAttachmentLimits(invalid), /附件限制/);
  }
});

const INLINE = '[附件 A.java (text/plain)]\nclass A {}\nclass B {}\n[附件结束 A.java]';
const SAVED = '[附件 report.pdf (application/pdf) 已保存到 /tmp/x/12345678-1234-1234-1234-123456789abc-report.pdf，需要时用工具读取]';

test('从正文里拆出内联与落盘文件，用户原话保持不变', () => {
  const parsed = splitAttachmentMarkers(`看一下这个\n\n${INLINE}\n\n${SAVED}`);
  assert.equal(parsed.body, '看一下这个');
  assert.deepEqual(parsed.files.map((file) => [file.name, file.kind, file.mimeType]), [
    ['A.java', 'inline', 'text/plain'],
    ['report.pdf', 'saved', 'application/pdf']
  ]);
  assert.equal(parsed.files[0].lines, 2);
  assert.equal(parsed.files[1].path, '/tmp/x/12345678-1234-1234-1234-123456789abc-report.pdf');
});

test('内联文本原件与内容合并成一个可恢复附件卡', () => {
  const path = '/tmp/x/12345678-1234-1234-1234-123456789abc-A.java';
  const parsed = splitAttachmentMarkers(`看一下\n\n${INLINE}\n\n[附件 A.java (text/plain) 已保存到 ${path}，需要时用工具读取]`);
  assert.equal(parsed.files.length, 1);
  assert.equal(parsed.files[0].kind, 'inline');
  assert.equal(parsed.files[0].path, path);
  assert.equal(joinAttachmentMarkers(parsed.body, parsed.files), `看一下\n\n${INLINE}\n\n[附件 A.java (text/plain) 已保存到 ${path}，需要时用工具读取]`);
});

test('没有文件块时原样返回', () => {
  assert.deepEqual(splitAttachmentMarkers('普通消息\n第二行'), { body: '普通消息\n第二行', files: [] });
  assert.deepEqual(splitAttachmentMarkers(''), { body: '', files: [] });
});

test('新文案（read_attachment）与旧文案的落盘块都能拆出', () => {
  const path = '/tmp/x/12345678-1234-1234-1234-123456789abc-report.pdf';
  const parsed = splitAttachmentMarkers(
    `[附件 report.pdf (application/pdf) 已保存到 ${path}，可用 read_attachment 读取]`
  );
  assert.deepEqual(parsed.files.map((file) => [file.name, file.kind, file.path]), [
    ['report.pdf', 'saved', path]
  ]);
  const legacy = splitAttachmentMarkers(`[附件 report.pdf (application/pdf) 已保存到 ${path}，需要时用工具读取]`);
  assert.equal(legacy.files[0].kind, 'saved', '旧消息仍可解析');
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
  assert.equal(
    attachmentUrl('ses 1', { name: '实习 报告.docx', path: '/home/u/.pilot/attachments/ses_1/1234-report.docx' }),
    '/api/sessions/ses%201/attachments/1234-report.docx?name=%E5%AE%9E%E4%B9%A0%20%E6%8A%A5%E5%91%8A.docx'
  );
  assert.equal(attachmentUrl('s1', { path: 'C:\\data\\a\\shot.png' }), '/api/sessions/s1/attachments/shot.png');
  assert.equal(attachmentUrl('', { path: '/x/y.png' }), '');
  assert.equal(attachmentUrl('s1', null), '');
});

test('图片留在输入时的位置，多张图按顺序插入', () => {
  const shot = { name: 'a.png' };
  const code = { name: 'b.png' };
  const parts = layoutMessageParts('看这一块，帮我改一下', [shot, code], [], [4, 5]);
  assert.deepEqual(parts.map((part) => part.type === 'text' ? part.text : part.image.name), [
    '看这一块',
    'a.png',
    '，',
    'b.png',
    '帮我改一下'
  ]);
});

test('没有位置记录时图片仍跟在正文后面', () => {
  const parts = layoutMessageParts('说明', [{ name: 'a.png' }], [{ name: 'note.txt' }], []);
  assert.deepEqual(parts.map((part) => part.type), ['text', 'image', 'files']);
  assert.equal(parts[2].files[0].name, 'note.txt');
});
