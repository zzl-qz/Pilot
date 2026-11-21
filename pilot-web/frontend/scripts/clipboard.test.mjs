import assert from 'node:assert/strict';
import test from 'node:test';
import { hasFileTransferHint, readTransferFilePaths } from '../src/lib/clipboard.js';
import { readDesktopClipboardFiles } from '../src/lib/attachments/input.js';

test('文件拖拽即使只暴露 items 也能识别', () => {
  assert.equal(hasFileTransferHint({ items: [{ kind: 'file' }] }), true);
  assert.equal(hasFileTransferHint({ types: ['Files'] }), true);
  assert.equal(hasFileTransferHint({ types: ['text/plain'] }), false);
});

test('从 URI 列表提取 file 路径，跳过普通文本和 copy 标记', () => {
  const transfer = {
    types: ['text/uri-list', 'x-special/gnome-copied-files'],
    getData: type => type === 'text/uri-list'
      ? 'file:///home/me/a%20b.txt\r\nfile:///home/me/c.bin\r\n'
      : 'copy\nfile:///home/me/a%20b.txt'
  };
  assert.deepEqual(readTransferFilePaths(transfer), [
    'file:///home/me/a%20b.txt',
    'file:///home/me/c.bin'
  ]);
  assert.deepEqual(readTransferFilePaths({ getData: () => 'copy\nhttps://example.com' }), []);
});

test('桌面剪贴板文件结果转换成草稿附件', async () => {
  const previous = globalThis.window;
  globalThis.window = {
    pilotDesktop: {
      readClipboardFiles: async limits => {
        assert.equal(limits.maxFiles, 64);
        return { ok: true, files: [{ name: 'archive.bin', mimeType: 'application/octet-stream', size: 2, dataBase64: 'data:application/octet-stream;base64,AAE=' }] };
      }
    }
  };
  try {
    const items = await readDesktopClipboardFiles({ maxFiles: 64, maxFileBytes: 1024, maxTotalBytes: 2048, maxRequestBytes: 4096 });
    assert.equal(items.length, 1);
    assert.equal(items[0].name, 'archive.bin');
    assert.equal(items[0].size, 2);
    assert.match(items[0].id, /^attachment-|^[a-f0-9-]{36}$/);
  } finally {
    if (previous === undefined) delete globalThis.window;
    else globalThis.window = previous;
  }
});
