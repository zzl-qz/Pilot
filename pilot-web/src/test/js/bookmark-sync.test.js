import test from 'node:test';
import assert from 'node:assert/strict';

const originalWindow = globalThis.window;
globalThis.window = { fetch: async () => new Response('[]') };
const { api } = await import('../../../frontend/src/api.js');
const { syncBookmarks, localBookmarks, normalizeBookmarks, migrateLocalBookmarks } = await import('../../../frontend/src/lib/bookmark-sync.js');
if (originalWindow === undefined) delete globalThis.window; else globalThis.window = originalWindow;
function storageFixture(t) {
  const original = globalThis.localStorage, originalImport = api.importBookmarks;
  const values = new Map();
  globalThis.localStorage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key), get length() { return values.size; }, key: index => [...values.keys()][index] ?? null };
  t.after(() => { globalThis.localStorage = original; api.importBookmarks = originalImport; });
  return values;
}
test('旧书签并发导入合并成一次请求，成功后才备份并移走旧数据', async t => {
  const values = storageFixture(t), local = [{ sequence: 1, title: '中文书签' }];
  values.set('pilot.message-bookmarks:race', JSON.stringify(local));
  let resolve, calls = 0;
  api.importBookmarks = async (session, sequences) => { calls++; assert.equal(session, 'race'); assert.deepEqual(sequences, [1]); return new Promise(done => { resolve = done; }); };
  const first = syncBookmarks('race'), second = syncBookmarks('race');
  assert.equal(first, second); assert.equal(calls, 1);
  assert.ok(values.has('pilot.message-bookmarks:race'));
  resolve({ bookmarks: [{ sequence: 1, title: '中文书签', preview: '' }], skipped: [7] });
  const result = await first;
  assert.equal(result.values[0].id, '1'); assert.equal(result.values[0].key, ''); assert.deepEqual(result.skipped, [7]);
  assert.equal(values.has('pilot.message-bookmarks:race'), false);
  assert.equal(values.get('pilot.message-bookmarks:race:migration-backup'), JSON.stringify(local));
});
test('迁移失败保留旧书签且允许重试，损坏存储和非数组响应明确处理', async t => {
  const values = storageFixture(t);
  values.set('pilot.message-bookmarks:bad', '{'); assert.deepEqual(localBookmarks('bad'), []);
  values.set('pilot.message-bookmarks:failed', JSON.stringify([{ sequence: 2, title: '保留' }]));
  values.set('pilot.message-bookmarks:ok', JSON.stringify([{ sequence: 3, title: '成功' }]));
  api.importBookmarks = async (session, sequences) => { if (session === 'failed') throw Error('offline'); return { bookmarks: sequences.map(sequence => ({ sequence, title: '成功', preview: '' })), skipped: [] }; };
  assert.deepEqual(await migrateLocalBookmarks(), { count: 1, failed: ['failed'] });
  assert.ok(values.has('pilot.message-bookmarks:failed')); assert.equal(values.has('pilot.message-bookmarks:ok'), false);
  api.importBookmarks = async () => ({ bookmarks: [{ sequence: 2, title: '保留', preview: '' }], skipped: [] });
  assert.equal((await syncBookmarks('failed')).values.length, 1);
  assert.throws(() => normalizeBookmarks({}), /格式无效/);
  assert.throws(() => normalizeBookmarks([null]), /格式无效/);
  assert.throws(() => normalizeBookmarks([{ sequence: '2', title: '无效游标' }]), /格式无效/);
});
