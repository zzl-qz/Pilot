import assert from 'node:assert/strict';
import test from 'node:test';
import { noteInsertion } from '../src/lib/notes.js';

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
