import assert from 'node:assert/strict';
import test from 'node:test';
import { fetchQueuedDraft } from '../src/lib/queue.js';
import { attachmentUrl } from '../src/lib/attachments/attachments.js';

const bytes = (text) => new TextEncoder().encode(text);
function fetcher(files, calls = []) {
  return async (url) => {
    calls.push(url);
    const body = files[url];
    if (body === undefined) return { ok: false, status: 404, arrayBuffer: async () => new ArrayBuffer(0) };
    return { ok: true, status: 200, arrayBuffer: async () => body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength) };
  };
}

test('纯文本排队消息取回后去掉内联标记', async () => {
  const result = await fetchQueuedDraft('s1', { content: '普通排队消息', text: '普通排队消息' }, fetcher({}));
  assert.equal(result.text, '普通排队消息');
  assert.deepEqual(result.files, []);
});

test('图片与文件都带回为草稿附件并保留正文', async () => {
  const imageUrl = '/api/sessions/s1/attachments/a.png';
  const fileUrl = attachmentUrl('s1', { name: 'b.txt', mimeType: 'text/plain', path: '/tmp/b.txt' });
  const calls = [];
  const result = await fetchQueuedDraft('s1', {
    content: '看图并检查 [附件 b.txt (text/plain) 已保存到 /tmp/b.txt，可用 read_attachment 读取]',
    text: '看图并检查 [附件 b.txt (text/plain) 已保存到 /tmp/b.txt，可用 read_attachment 读取]',
    attachments: [{ name: 'a.png', mimeType: 'image/png', url: imageUrl }],
    imagePositions: [0]
  }, fetcher({ [imageUrl]: bytes('png-bytes'), [fileUrl]: bytes('file-bytes') }, calls));

  assert.equal(result.text, '看图并检查');
  assert.deepEqual(result.files.map(item => item.name), ['a.png', 'b.txt']);
  assert.equal(result.files[0].mimeType, 'image/png');
  assert.equal(result.files[0].size, 9);
  assert.match(result.files[0].dataBase64, /^data:image\/png;base64,/);
  assert.match(result.files[1].dataBase64, /^data:text\/plain;base64,/);
  assert.equal(result.imagePositions.length, 1);
  assert.deepEqual(calls, [imageUrl, fileUrl]);
});

test('附件读取失败时抛错，调用方可以保留队列', async () => {
  await assert.rejects(
    () => fetchQueuedDraft('s1', {
      content: '看图', text: '看图',
      attachments: [{ name: 'broken.png', mimeType: 'image/png', url: '/api/sessions/s1/attachments/x.png' }],
      imagePositions: [0]
    }, fetcher({})),
    /读取附件「broken.png」失败/
  );
});
