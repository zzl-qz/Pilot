import test from 'node:test';
import assert from 'node:assert/strict';
import { localDirectoryPickerAvailable, selectProjectDirectories } from './directory-picker.mjs';

test('本机工作台支持 IPv4、IPv6 和 localhost，远程与相似主机不打开本机目录', async () => {
  for (const url of ['http://127.0.0.1:9999', 'https://localhost:443', 'http://[::1]:9999']) assert.equal(localDirectoryPickerAvailable(url), true);
  for (const url of ['http://192.168.1.10:9999', 'https://pilot.example', 'http://localhost.evil.test', 'http://127.0.0.1.evil.test', 'file:///tmp', 'http://user:password@localhost', 'bad']) {
    let opened = false;
    const result = await selectProjectDirectories({ origin: url, dialog: { showOpenDialog() { opened = true; } } });
    assert.equal(result.code, 'REMOTE_WORKBENCH', url);
    assert.equal(opened, false);
  }
});

for (const [platform, paths] of Object.entries({
  win32: ['C:\\项目 空间', '\\\\server\\share'],
  darwin: ['/Users/example/项目 空间', '/Volumes/资料'],
  linux: ['/home/example/项目 空间', '/mnt/资料'],
})) {
  test(`${platform} 模拟：仅选择目录，保留多个原始路径与父窗口`, async () => {
    const parent = { isDestroyed: () => false };
    const result = await selectProjectDirectories({ origin: 'http://localhost:9999', parent, dialog: {
      async showOpenDialog(window, options) {
        assert.equal(window, parent);
        assert.deepEqual(options.properties, ['openDirectory', 'multiSelections']);
        return { canceled: false, filePaths: paths };
      },
    } });
    assert.deepEqual(result, { ok: true, canceled: false, paths });
  });
}

test('取消不暴露旧选择，系统错误可见，已销毁窗口不弹框', async () => {
  const input = { origin: 'http://localhost:9999', parent: { isDestroyed: () => false } };
  assert.deepEqual(await selectProjectDirectories({ ...input, dialog: { showOpenDialog: async () => ({ canceled: true, filePaths: ['/old'] }) } }),
    { ok: true, canceled: true, paths: [] });
  assert.equal((await selectProjectDirectories({ ...input, dialog: { showOpenDialog: async () => { throw Error('系统目录选择失败'); } } })).message, '系统目录选择失败');
  assert.equal((await selectProjectDirectories({ ...input, parent: { isDestroyed: () => true }, dialog: {} })).code, 'WINDOW_UNAVAILABLE');
});
