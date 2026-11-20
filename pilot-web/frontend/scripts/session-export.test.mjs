import assert from 'node:assert/strict';
import test from 'node:test';

const markdown = '# 测试会话\n\n完整正文\n';
const calls = [];
let response = () => new Response(markdown);
globalThis.window = { fetch(url) { calls.push(url); return response(); } };
const { downloadSessionExport } = await import('../src/lib/session-export.ts');
const run = () => downloadSessionExport('s/1', '项目/会话');

test('在读取全文前打开另存为，写入关闭成功后才报告文件名', async t => {
  calls.length = 0;
  let finish, started;
  const closing = new Promise(resolve => { finish = resolve; });
  const writing = new Promise(resolve => { started = resolve; });
  window.showSaveFilePicker = async options => {
    calls.push('picker');
    assert.match(options.suggestedName, /^pilot-项目-会话-.*\.md$/);
    return { name: '自选.md', createWritable: async () => ({
      write: async text => assert.equal(text, markdown),
      close() { started(); return closing; }
    }) };
  };
  t.after(() => { delete window.showSaveFilePicker; });
  let finished = false;
  const result = run().then(value => { finished = true; return value; });
  assert.deepEqual(calls, ['picker']);
  await writing;
  assert.equal(finished, false);
  finish();
  assert.match((await result).notice, /自选\.md/);
  assert.deepEqual(calls, ['picker', '/api/sessions/s%2F1/export']);
});

test('取消选址时停止导出，不请求会话正文', async t => {
  calls.length = 0;
  window.showSaveFilePicker = async () => { throw new DOMException('', 'AbortError'); };
  t.after(() => { delete window.showSaveFilePicker; });
  assert.equal((await run()).mode, 'cancelled');
  assert.deepEqual(calls, []);
});

test('浏览器写入失败能看到错误', async t => {
  window.showSaveFilePicker = async () => ({ createWritable: async () => ({
    write: async () => {}, close: async () => { throw Error('磁盘已满'); }, abort: async () => {}
  }) });
  t.after(() => { delete window.showSaveFilePicker; });
  await assert.rejects(run, /磁盘已满/);
});

test('不支持另存为时报告下载已发起，保留浏览器读取文件的时间', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const clicked = [];
  let revoked = false;
  t.mock.method(URL, 'revokeObjectURL', () => { revoked = true; });
  globalThis.document = { body: { append() {} }, createElement: () => ({
    download: '', click() { clicked.push(this.download); }, remove() {}
  }) };
  t.after(() => { delete globalThis.document; });
  const result = await run();
  assert.equal(result.mode, 'download');
  assert.match(result.notice, /下载记录/);
  assert.equal(clicked.length, 1);
  assert.equal(revoked, false);
  t.mock.timers.tick(60_000);
  assert.equal(revoked, true);
});

test('服务端错误不写文件', async t => {
  let writes = 0;
  window.showSaveFilePicker = async () => ({ createWritable: async () => { writes++; } });
  response = () => new Response('导出失败', { status: 500 });
  t.after(() => { delete window.showSaveFilePicker; response = () => new Response(markdown); });
  await assert.rejects(run, /导出失败/);
  assert.equal(writes, 0);
});

test('不能下载时提供可手动复制的完整正文', async () => {
  const result = await run();
  assert.equal(result.mode, 'manual');
  assert.equal(result.text, markdown);
});

test('桌面入口把全文交给系统另存为，使用返回的路径或取消状态', async t => {
  let result = { ok: true, path: 'D:\\导出\\复查.md' };
  window.pilotDesktop = { saveSessionMarkdown: async (name, text) => {
    assert.match(name, /^pilot-项目-会话-.*\.md$/);
    assert.equal(text, markdown);
    return result;
  } };
  window.showSaveFilePicker = () => assert.fail('桌面应只弹一次对话框');
  t.after(() => { delete window.pilotDesktop; delete window.showSaveFilePicker; });
  const saved = await run();
  assert.equal(saved.mode, 'saved');
  assert.ok(saved.notice.includes(result.path));
  result = { ok: true, canceled: true };
  assert.equal((await run()).mode, 'cancelled');
  result = { ok: false, error: '工作台已离开' };
  await assert.rejects(run, /工作台已离开/);
});

test('浏览器限制另存为、下载也不可用时退到剪贴板', async t => {
  window.showSaveFilePicker = () => { throw new DOMException('不可用', 'SecurityError'); };
  let copied;
  t.mock.getter(globalThis, 'navigator', () => ({ clipboard: { writeText: async text => { copied = text; } } }));
  t.after(() => { delete window.showSaveFilePicker; });
  assert.equal((await run()).mode, 'clipboard');
  assert.equal(copied, markdown);
});
