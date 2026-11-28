import test from 'node:test';
import assert from 'node:assert/strict';
import { startBrowserControl } from '../browser-control.mjs';
import { readClipboardFiles } from '../clipboard-files.mjs';
import {
  DANGEROUS_DOWNLOAD_EXTENSIONS,
  formatPageLogs,
  isDangerousDownloadExtension,
  isLoopback,
  isRegularFileStat,
  isWindowsUncPath,
  markerPath,
  parseClipboardFilePaths,
  partitionName,
  sanitizeDownloadFilename,
  scrubLogText,
  snapBounds,
  userPageUrl
} from '../protocol.mjs';

test('人的地址栏：本机补 http，外网补 https，拒绝别的协议', () => {
  assert.equal(userPageUrl('localhost:5173'), 'http://localhost:5173/');
  assert.equal(userPageUrl('https://example.com/a'), 'https://example.com/a');
  assert.equal(userPageUrl('example.com'), 'https://example.com/');
  assert.equal(userPageUrl('javascript:alert(1)'), null);
  assert.equal(userPageUrl(''), null);
});

test('isLoopback 统一识别本机回环主机名并拒绝外网主机', () => {
  assert.equal(isLoopback('localhost'), true);
  assert.equal(isLoopback('app.localhost'), true);
  assert.equal(isLoopback('127.0.0.1'), true);
  assert.equal(isLoopback('127.0.1.9'), true);
  assert.equal(isLoopback('[::1]'), true);
  assert.equal(isLoopback('::1'), true);
  assert.equal(isLoopback('example.com'), false);
  assert.equal(isLoopback('10.0.0.1'), false);
});

test('工作区分区稳定且彼此不同', () => {
  assert.equal(partitionName('/work/a'), partitionName('/work/a'));
  assert.notEqual(partitionName('/work/a'), partitionName('/work/b'));
  assert.match(partitionName(''), /^persist:pilot-[0-9a-f]{16}$/);
});

test('视口按像素对齐，盖住或太小时隐藏', () => {
  assert.deepEqual(snapBounds({ x: 10.4, y: 20.6, width: 400.2, height: 300.8, visible: true }), {
    x: 10, y: 21, width: 400, height: 301, visible: true
  });
  assert.equal(snapBounds({ x: 0, y: 0, width: 10, height: 10, visible: true }).visible, false);
  assert.equal(snapBounds({ x: 0, y: 0, width: 800, height: 600, visible: false }).visible, false);
});

test('桌面页的控制台和网络记录按旧面板的句子排', () => {
  assert.equal(formatPageLogs([], 'console'), '没有控制台记录');
  assert.equal(formatPageLogs([], 'network'), '没有网络记录');
  assert.match(formatPageLogs([{ level: 'error', message: 'boom', sourceId: 'https://a.test/app.js', lineNumber: 4 }], 'console'), /\[error\] boom @ https:\/\/a\.test\/app\.js:4/);
  assert.match(formatPageLogs([{ method: 'GET', statusCode: 404, url: 'https://a.test/missing' }], 'network'), /GET 404 https:\/\/a\.test\/missing/);
});

test('标签标记路径只含这个标签的 id', () => {
  assert.equal(markerPath('abc'), '/marker/abc');
  assert.equal(markerPath('a/b'), '/marker/a%2Fb');
});

test('下载文件名清理路径穿越、Windows 保留名、截断保扩展名，并合并识别全量危险扩展名', () => {
  assert.equal(sanitizeDownloadFilename('../../evil/setup.exe'), 'setup.exe');
  assert.equal(sanitizeDownloadFilename('..'), 'download.bin');
  assert.equal(sanitizeDownloadFilename('CON'), '_CON');
  assert.equal(sanitizeDownloadFilename('nul.txt'), '_nul.txt');
  assert.equal(sanitizeDownloadFilename('COM1.log'), '_COM1.log');
  assert.equal(sanitizeDownloadFilename('LPT9'), '_LPT9');
  assert.equal(sanitizeDownloadFilename('report.pdf.  '), 'report.pdf');
  const longName = sanitizeDownloadFilename('a'.repeat(140) + '.tar.gz');
  assert.equal(longName.length, 120);
  assert.ok(longName.endsWith('.gz'));
  const longDanger = sanitizeDownloadFilename('x'.repeat(140) + '.exe');
  assert.ok(longDanger.endsWith('.exe'));
  assert.equal(isDangerousDownloadExtension(longDanger), true);
  assert.ok(DANGEROUS_DOWNLOAD_EXTENSIONS.size >= 48);
  for (const name of [
    'setup.EXE', 'payload.exe.', 'payload.exe.  ', 'app.desktop', 'script.ps1',
    'lib.dll', 'mobile.apk', 'installer.appimage', 'launch.webloc', 'automator.workflow',
    'shell.bash', 'shell.zsh', 'shortcut.pif', 'script.wsh', 'snap.msc', 'disk.iso'
  ]) {
    assert.equal(isDangerousDownloadExtension(name), true, name);
  }
  assert.equal(isDangerousDownloadExtension('report.pdf'), false);
});

test('剪贴板文件格式跨平台解析多路径并拒绝 UNC 与非普通文件', () => {
  const plist = Buffer.from('<array><string>/tmp/one.txt</string><string>/tmp/a&amp;b&apos;s.txt</string></array>');
  assert.deepEqual(parseClipboardFilePaths('NSFilenamesPboardType', plist, 'darwin'), ['/tmp/one.txt', "/tmp/a&b's.txt"]);
  const dropHeader = Buffer.alloc(20);
  dropHeader.writeUInt32LE(20, 0);
  dropHeader.writeUInt32LE(1, 16);
  const dropBody = Buffer.from('C:\\one.txt\0D:\\two.txt\0\0', 'utf16le');
  assert.deepEqual(parseClipboardFilePaths('CF_HDROP', Buffer.concat([dropHeader, dropBody]), 'win32'), ['C:\\one.txt', 'D:\\two.txt']);
  const wide = Buffer.from('C:\\one.txt\0D:\\two.txt\0\0', 'utf16le');
  assert.deepEqual(parseClipboardFilePaths('FileNameW', wide, 'win32'), ['C:\\one.txt', 'D:\\two.txt']);
  const ansi = Buffer.from('C:\\one.txt\0D:\\two.txt\0', 'latin1');
  assert.deepEqual(parseClipboardFilePaths('FileName', ansi, 'win32'), ['C:\\one.txt', 'D:\\two.txt']);
  assert.deepEqual(parseClipboardFilePaths('text/uri-list', 'file:///tmp/a%20b.txt\r\n# comment', 'linux'), ['/tmp/a b.txt']);
  assert.equal(isWindowsUncPath('\\\\server\\share\\file.txt'), true);
  assert.equal(isWindowsUncPath('//server/share/file.txt'), true);
  assert.equal(isWindowsUncPath('C:\\file.txt'), false);
  assert.equal(isRegularFileStat({ isFile: () => true, isSymbolicLink: () => false }), true);
  assert.equal(isRegularFileStat({ isFile: () => false, isSymbolicLink: () => false }), false);
  assert.equal(isRegularFileStat({ isFile: () => true, isSymbolicLink: () => true }), false);
});

test('readClipboardFiles 读取剪贴板普通文件并拦截空剪贴板与非普通文件', async () => {
  const empty = await readClipboardFiles({}, {
    clipboard: { availableFormats: () => [], readText: () => '' },
    platform: 'linux'
  });
  assert.equal(empty.ok, false);
  const ok = await readClipboardFiles({}, {
    clipboard: {
      availableFormats: () => ['text/uri-list'],
      read: () => 'file:///tmp/demo.png',
      readText: () => ''
    },
    platform: 'linux',
    statFn: () => ({ isFile: () => true, isSymbolicLink: () => false, size: 4 }),
    readFileFn: async () => Buffer.from([1, 2, 3, 4])
  });
  assert.equal(ok.ok, true);
  assert.equal(ok.files.length, 1);
  assert.equal(ok.files[0].name, 'demo.png');
  assert.equal(ok.files[0].mimeType, 'image/png');
  const symlink = await readClipboardFiles({}, {
    clipboard: {
      availableFormats: () => ['text/uri-list'],
      read: () => 'file:///tmp/link.txt',
      readText: () => ''
    },
    platform: 'linux',
    statFn: () => ({ isFile: () => true, isSymbolicLink: () => true, size: 4 })
  });
  assert.equal(symlink.ok, false);
});

test('桌面日志自动脱敏认证令牌、JWT、API 密钥、URL 凭证和敏感查询参数', () => {
  const net = formatPageLogs([{
    method: 'GET',
    statusCode: 200,
    url: 'https://alice:hunter2@a.test/cb?access_token=topsecret&sig=hmac123#code=123456'
  }], 'network');
  assert.doesNotMatch(net, /alice|hunter2|topsecret|hmac123|123456/);
  const jwt = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3OCJ9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';
  const con = formatPageLogs([{
    level: 'warn',
    message: `Authorization: Bearer my-jwt-secret-token jwt=${jwt} sk-proj123456789 ghp_abcdefghij1234 AKIAIOSFODNN7EXAMPLE`
  }], 'console');
  assert.doesNotMatch(con, /my-jwt-secret-token|eyJhbGciOiJIUzI1NiJ9|sk-proj123456789|ghp_abcdefghij1234|AKIAIOSFODNN7EXAMPLE/);
  assert.doesNotMatch(scrubLogText('Basic dXNlcjpwYXNz'), /dXNlcjpwYXNz/);
});

test('桌面控制端口支持 POST /control 令牌校验与控制态更新', async () => {
  let server = null;
  let lastControl = null;
  const port = await startBrowserControl('secret-token', {
    state: () => ({ tabs: [] }),
    open: () => ({}),
    select: () => ({}),
    close: () => ({}),
    emulate: () => ({}),
    control: (body) => {
      if (body.sessionId === 'missing') return null;
      lastControl = body;
      return { ok: true, agentControlled: Boolean(body.agentControlled), controlStatus: body.controlStatus || '' };
    }
  }, { onServer: (s) => { server = s; } });

  try {
    const unauth = await fetch(`http://127.0.0.1:${port}/control`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sessionId: 's1', agentControlled: true })
    });
    assert.equal(unauth.status, 401);

    const missing = await fetch(`http://127.0.0.1:${port}/control`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-pilot-token': 'secret-token' },
      body: JSON.stringify({ sessionId: 'missing', agentControlled: true })
    });
    assert.equal(missing.status, 404);

    const ok = await fetch(`http://127.0.0.1:${port}/control`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-pilot-token': 'secret-token' },
      body: JSON.stringify({ sessionId: 's1', agentControlled: true, controlStatus: '填写 e3' })
    });
    assert.equal(ok.status, 200);
    const body = await ok.json();
    assert.equal(body.agentControlled, true);
    assert.equal(body.controlStatus, '填写 e3');
    assert.equal(lastControl.sessionId, 's1');
    const marker = await fetch(`http://127.0.0.1:${port}/marker/tab_1`);
    assert.equal(marker.status, 200);
    assert.equal(marker.headers.get('content-security-policy'), "default-src 'none'");
    assert.equal(marker.headers.get('x-content-type-options'), 'nosniff');
    assert.equal((await fetch(`http://127.0.0.1:${port}/marker/%25`)).status, 400);
    assert.equal((await fetch(`http://127.0.0.1:${port}/marker/a%2Fb`)).status, 400);
    const oversized = await fetch(`http://127.0.0.1:${port}/control`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-pilot-token': 'secret-token' },
      body: JSON.stringify({ value: 'x'.repeat(1024 * 1024 + 1) })
    });
    assert.equal(oversized.status, 413);
  } finally {
    await new Promise((resolve) => server?.close(resolve));
  }
});
