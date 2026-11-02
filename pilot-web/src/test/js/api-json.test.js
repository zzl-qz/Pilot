import test from 'node:test';
import assert from 'node:assert/strict';

/*
 * api.js 在模块加载时把 window.fetch 绑死，所以先造一个假的 window 再动态 import。
 * handler 可变，用来按用例换返回值。
 */
let handler = async () => new Response(null, { status: 204 });
const previousWindow = globalThis.window;
globalThis.window = { fetch: (...args) => handler(...args) };

const { apiJson } = await import('../../../frontend/src/api.js');

// 同一个进程里还跑着别的测试文件：import 完立刻还原全局 window，
// 模块内部已经拿到了绑定好的函数引用，不影响这里发请求。
if (previousWindow === undefined) {
  delete globalThis.window;
} else {
  globalThis.window = previousWindow;
}

test('204 响应返回 null，而不是抛 JSON 解析错误', async () => {
  handler = async () => new Response(null, { status: 204 });
  assert.equal(await apiJson('/api/sessions/x', { method: 'DELETE' }), null);
});

test('200 但空正文也返回 null', async () => {
  handler = async () => new Response('', { status: 200 });
  assert.equal(await apiJson('/api/x'), null);
});

test('正常 JSON 正文照常解析', async () => {
  handler = async () => new Response(JSON.stringify({ ok: true }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' }
  });
  assert.deepEqual(await apiJson('/api/x'), { ok: true });
});

test('错误状态把 message 透出来', async () => {
  handler = async () => new Response(JSON.stringify({ message: '路径不在工作区内' }), {
    status: 400,
    headers: { 'Content-Type': 'application/json' }
  });
  await assert.rejects(() => apiJson('/api/x'), /路径不在工作区内/);
});


test('外部取消会中止录音上传，不误报请求超时', async () => {
  handler = (_, { signal }) => new Promise((resolve, reject) => {
    const abort = () => reject(new DOMException('aborted', 'AbortError'));
    if (signal.aborted) abort(); else signal.addEventListener('abort', abort, { once: true });
  });
  const controller = new AbortController();
  const pending = apiJson('/api/voice/transcriptions', { signal: controller.signal });
  controller.abort();
  await assert.rejects(pending, error => error.name === 'AbortError' && !error.message.includes('超时'));
  await assert.rejects(apiJson('/api/voice/transcriptions', { signal: controller.signal }), { name: 'AbortError' });
});

test('请求超时仍给出可读说明', async () => {
  handler = (_, { signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
  });
  await assert.rejects(apiJson('/api/voice/transcriptions', {}, 5), /请求超时/);
});
