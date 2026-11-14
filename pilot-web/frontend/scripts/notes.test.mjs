import assert from 'node:assert/strict';
import test from 'node:test';
import {
  DRAFT_NOTES_KEY, NOTE_COUNT_LIMIT, createDraftNote, noteContentError, noteInsertion,
  readDraftNotes, uploadDraftNotes, writeDraftNotes
} from '../src/lib/notes.js';

function memoryStorage(initial = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: key => (data.has(key) ? data.get(key) : null),
    setItem: (key, value) => { data.set(key, String(value)); },
    removeItem: key => { data.delete(key); },
    dump: () => data
  };
}

function note(id, content) {
  return { id, content, createdAt: '2026-11-14T10:00:00Z', updatedAt: '2026-11-14T10:00:00Z' };
}

test('空草稿直接填入想法原文，保留原文换行与缩进', () => {
  assert.equal(noteInsertion('', '第一行\n第二行'), '第一行\n第二行');
  assert.equal(noteInsertion('   \n ', '想法'), '想法');
  assert.equal(noteInsertion('', '  带缩进的想法  '), '  带缩进的想法  ');
});

test('已有草稿时另起一行追加，不覆盖原草稿', () => {
  assert.equal(noteInsertion('正在写的内容', '想法'), '\n想法');
  assert.equal(noteInsertion('第一行\n第二行', '想法'), '\n想法');
});

test('草稿已经以换行结尾时不再多补空行', () => {
  assert.equal(noteInsertion('已有一行\n', '想法'), '想法');
  assert.equal(noteInsertion('已有一行\r\n', '想法'), '想法');
});

test('内容校验与后端一致：空内容和超长被拒绝，且保留两端空白', () => {
  assert.equal(noteContentError('   '), '想法内容不能为空');
  assert.match(noteContentError('字'.repeat(4001)), /最多 4000/);
  const created = createDraftNote('  保留两端  ');
  assert.equal(created.content, '  保留两端  ');
  assert.equal(createDraftNote('   '), null);
});

test('还没建会话时想法先写本机草稿，空列表会删掉存储键', () => {
  const storage = memoryStorage();
  assert.deepEqual(readDraftNotes(storage), []);
  writeDraftNotes(storage, [note('n2', '新的'), note('n1', '旧的')]);
  assert.deepEqual(readDraftNotes(storage).map(item => item.id), ['n2', 'n1']);
  writeDraftNotes(storage, []);
  assert.equal(storage.getItem(DRAFT_NOTES_KEY), null);
  assert.deepEqual(readDraftNotes(storage), []);
});

test('坏数据与超过上限的草稿被跳过，不会让面板打不开', () => {
  const storage = memoryStorage({ [DRAFT_NOTES_KEY]: 'not json' });
  assert.deepEqual(readDraftNotes(storage), []);
  const many = Array.from({ length: NOTE_COUNT_LIMIT + 5 }, (_, index) => note('n' + index, '想法 ' + index));
  writeDraftNotes(storage, many);
  assert.equal(readDraftNotes(storage).length, NOTE_COUNT_LIMIT);
  const mixed = [
    note('ok', '正常'),
    { id: 'blank', content: '   ' },
    { id: 'noContent' },
    null
  ];
  storage.setItem(DRAFT_NOTES_KEY, JSON.stringify(mixed));
  assert.deepEqual(readDraftNotes(storage).map(item => item.id), ['ok']);
});

test('会话建立时按最早到最新上传草稿想法；中途失败留下未上传的', async () => {
  const notes = [note('n2', '新的'), note('n1', '旧的')];
  const uploaded = [];
  const done = await uploadDraftNotes(notes, async content => { uploaded.push(content); });
  assert.deepEqual(uploaded, ['旧的', '新的']);
  assert.equal(done.uploaded, 2);
  assert.deepEqual(done.remaining, []);

  const calls = [];
  const partial = await uploadDraftNotes(notes, async content => {
    calls.push(content);
    if (calls.length === 2) throw new Error('offline');
  });
  assert.equal(partial.uploaded, 1);
  assert.deepEqual(partial.remaining.map(item => item.id), ['n2']);
});
