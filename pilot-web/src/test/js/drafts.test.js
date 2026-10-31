import test from 'node:test';
import assert from 'node:assert/strict';
import { dropDraft, readDraft, writeDraft } from '../../main/resources/static/lib/drafts.js';

function memoryStorage() {
  const map = new Map();
  return {
    map,
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => map.set(key, String(value)),
    removeItem: (key) => map.delete(key)
  };
}

test('drafts are stored per session and restored', () => {
  const storage = memoryStorage();
  writeDraft(storage, 'a', '还没写完的任务');
  writeDraft(storage, 'b', '另一个会话');
  assert.equal(readDraft(storage, 'a'), '还没写完的任务');
  assert.equal(readDraft(storage, 'b'), '另一个会话');
  assert.equal(readDraft(storage, 'c'), '');
});

test('blank drafts remove the key instead of storing empty entries', () => {
  const storage = memoryStorage();
  writeDraft(storage, 'a', 'x');
  writeDraft(storage, 'a', '   \n');
  assert.equal(storage.map.size, 0);
  writeDraft(storage, 'a', 'y');
  dropDraft(storage, 'a');
  assert.equal(storage.map.size, 0);
});

test('drafts without a session id are ignored and storage failures are swallowed', () => {
  const storage = memoryStorage();
  writeDraft(storage, null, 'x');
  assert.equal(storage.map.size, 0);
  const broken = { getItem() { throw new Error('denied'); }, setItem() { throw new Error('full'); }, removeItem() { throw new Error('x'); } };
  assert.doesNotThrow(() => writeDraft(broken, 'a', 'x'));
  assert.equal(readDraft(broken, 'a'), '');
});

test('very long drafts are capped', () => {
  const storage = memoryStorage();
  writeDraft(storage, 'a', 'x'.repeat(50000));
  assert.equal(readDraft(storage, 'a').length, 20000);
});
