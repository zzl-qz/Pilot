import test from 'node:test';
import assert from 'node:assert/strict';

import { normalizePath, parseDiff, totalDiffLines } from '../../main/resources/static/lib/diff.js';

test('把补丁拆成文件与行，并统计增删', () => {
  const diff = [
    'diff --git a/a.txt b/a.txt',
    'index 111..222 100644',
    '--- a/a.txt',
    '+++ b/a.txt',
    '@@ -1,2 +1,2 @@',
    ' one',
    '-two',
    '+TWO',
    ' end',
    ''
  ].join('\n');

  const files = parseDiff(diff);

  assert.equal(files.length, 1);
  assert.equal(files[0].path, 'a.txt');
  assert.equal(files[0].added, 1);
  assert.equal(files[0].removed, 1);

  const del = files[0].lines.find((line) => line.kind === 'del');
  const add = files[0].lines.find((line) => line.kind === 'add');
  assert.equal(del.text, 'two');
  assert.equal(del.old, 2, '上下文行 one 已占掉第 1 行');
  assert.equal(add.text, 'TWO');
  assert.equal(add.next, 2);
});

test('hunk 里以 --- / +++ 开头的内容仍按代码行处理', () => {
  const diff = [
    'diff --git a/notes.txt b/notes.txt',
    '--- a/notes.txt',
    '+++ b/notes.txt',
    '@@ -1,3 +1,3 @@',
    ' a',
    '--- dash',
    '+++ plus',
    ' b',
    ''
  ].join('\n');

  const file = parseDiff(diff)[0];

  assert.equal(file.added, 1, '整行以 --- 开头的删除行不能被当成文件头');
  assert.equal(file.removed, 1);
  assert.equal(file.lines.filter((line) => line.kind === 'del').length, 1);
  assert.equal(file.lines.filter((line) => line.kind === 'add').length, 1);
  assert.equal(file.lines.find((line) => line.kind === 'del').text, '-- dash');
  assert.equal(file.lines.find((line) => line.kind === 'add').text, '++ plus');
});

test('\\ No newline 是元信息，不占用行号', () => {
  const diff = [
    'diff --git a/a.txt b/a.txt',
    '--- a/a.txt',
    '+++ b/a.txt',
    '@@ -1 +1 @@',
    '-old',
    '\\ No newline at end of file',
    '+new',
    '\\ No newline at end of file',
    ''
  ].join('\n');

  const file = parseDiff(diff)[0];

  assert.equal(file.added, 1);
  assert.equal(file.removed, 1);
  assert.equal(file.lines.find((line) => line.kind === 'del').old, 1);
  assert.equal(file.lines.find((line) => line.kind === 'add').next, 1);
  assert.equal(file.lines.filter((line) => line.kind === 'ctx').length, 0);
});

test('多个文件各自计数，末尾换行不会多出一行上下文', () => {
  const diff = [
    'diff --git a/a.txt b/a.txt',
    '--- a/a.txt',
    '+++ b/a.txt',
    '@@ -1 +1 @@',
    '-old',
    '+new',
    'diff --git a/b.txt b/b.txt',
    '--- a/b.txt',
    '+++ b/b.txt',
    '@@ -1 +1 @@',
    '-x',
    '+y',
    ''
  ].join('\n');

  const files = parseDiff(diff);

  assert.deepEqual(files.map((file) => file.path), ['a.txt', 'b.txt']);
  assert.deepEqual(totalDiffLines(files), { added: 2, removed: 2 });
});

test('重命名与二进制的文件头都能取到新路径', () => {
  const diff = [
    'diff --git a/old.txt b/new.txt',
    'similarity index 80%',
    'rename from old.txt',
    'rename to new.txt',
    '--- a/old.txt',
    '+++ b/new.txt',
    '@@ -1 +1 @@',
    '-a',
    '+b',
    'diff --git a/image.bin b/image.bin',
    'Binary files a/image.bin and b/image.bin differ',
    ''
  ].join('\n');

  const files = parseDiff(diff);

  assert.equal(files[0].path, 'new.txt');
  assert.equal(files[0].added, 1);
  assert.equal(files[1].path, 'image.bin');
  assert.equal(files[1].added, 0);
  assert.equal(files[1].lines[0].kind, 'meta');
});

test('normalizePath 统一分隔符并去掉 ./', () => {
  assert.equal(normalizePath('src\\main\\App.java'), 'src/main/App.java');
  assert.equal(normalizePath('./a.txt'), 'a.txt');
  assert.equal(normalizePath(null), '');
});
