import assert from 'node:assert/strict';
import test from 'node:test';

const previousWindow = globalThis.window;
const fetchQueue = [];
globalThis.window = { fetch: async () => fetchQueue.shift() };
const { apiJson, ApiError } = await import('../src/api.js');

test('200 HTML gateway response becomes a semantic ApiError', async () => {
  fetchQueue.push(new Response('<html>gateway unavailable</html>', { status: 200, headers: { 'content-type': 'text/html' } }));
  await assert.rejects(apiJson('/test'), error => error instanceof ApiError && /非预期.*非 JSON/.test(error.message));
});

test('non-2xx null and primitive JSON use status fallback without TypeError', async () => {
  for (const body of ['null', '"bad"', '42', '']) {
    fetchQueue.push(new Response(body, { status: 502 }));
    await assert.rejects(apiJson('/test'), error => error instanceof ApiError && error.message === 'HTTP 502');
  }
});

test.after(() => { globalThis.window = previousWindow; });
