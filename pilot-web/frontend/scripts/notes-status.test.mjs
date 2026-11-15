import test from 'node:test';
import assert from 'node:assert/strict';
import { createDraftNote, readDraftNotes, DRAFT_NOTES_KEY } from '../src/lib/notes.js';

function storage(initial = {}) {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => { map.set(key, value); },
    removeItem: (key) => { map.delete(key); }
  };
}

test('草稿想法一律在收件箱，方便和不带状态的老数据区分', () => {
  const note = createDraftNote('随手记的想法');
  assert.equal(note.status, 'inbox');
});

test('老的本机草稿没有 status 字段时按收件箱读回来，不会被丢弃', () => {
  // 升级前写下的草稿不带 status；把这些想法当成坏数据丢掉等于让用户凭空少几条记录。
  const legacy = [{
    id: 'draft_note_old',
    content: '升级前记的想法',
    createdAt: '2026-11-01T10:00:00.000Z',
    updatedAt: '2026-11-01T10:00:00.000Z'
  }];
  const notes = readDraftNotes(storage({ [DRAFT_NOTES_KEY]: JSON.stringify(legacy) }));
  assert.equal(notes.length, 1);
  assert.equal(notes[0].status, 'inbox');
  assert.equal(notes[0].content, '升级前记的想法');
});

test('草稿里保留了 status 时按原样读回', () => {
  const planned = [{ id: 'n1', content: '已安排的事', createdAt: 'now', updatedAt: 'now', status: 'planned' }];
  const notes = readDraftNotes(storage({ [DRAFT_NOTES_KEY]: JSON.stringify(planned) }));
  assert.equal(notes[0].status, 'planned');
});

test('坏数据不影响打开面板', () => {
  assert.deepEqual(readDraftNotes(storage({ [DRAFT_NOTES_KEY]: 'not json' })), []);
  assert.deepEqual(readDraftNotes(storage({ [DRAFT_NOTES_KEY]: JSON.stringify([{ content: '   ' }]) })), []);
});