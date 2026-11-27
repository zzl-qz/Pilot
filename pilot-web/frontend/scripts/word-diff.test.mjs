import test from 'node:test';
import assert from 'node:assert/strict';
import { diffLineWords, buildWordDiffMap, buildSplitDiffRows, extractHunkPatch } from '../src/lib/word-diff.ts';
import { parseDiff } from '../src/lib/diff.ts';

test('diffLineWords highlights only changed identifier tokens and keeps common prefix/suffix intact', () => {
  const { oldSegments, newSegments } = diffLineWords(
    'const timeoutMs = 1000;',
    'const timeoutMs = 3500;'
  );
  assert.deepEqual(oldSegments, [
    { text: 'const timeoutMs = ', changed: false },
    { text: '1000', changed: true },
    { text: ';', changed: false }
  ]);
  assert.deepEqual(newSegments, [
    { text: 'const timeoutMs = ', changed: false },
    { text: '3500', changed: true },
    { text: ';', changed: false }
  ]);
});

test('diffLineWords falls back to plain lines on complete replacement or oversized minified lines', () => {
  const replaced = diffLineWords('alpha beta', 'gamma delta');
  assert.equal(replaced.oldSegments.some(s => s.changed), false);
  assert.equal(replaced.newSegments.some(s => s.changed), false);

  const huge = Array.from({ length: 160 }, (_, i) => `v${i}`).join('+');
  const guarded = diffLineWords(huge, huge.replace('v5', 'x5'));
  assert.equal(guarded.oldSegments.some(s => s.changed), false);
  assert.equal(guarded.newSegments.some(s => s.changed), false);
});

test('buildWordDiffMap pairs consecutive del/add blocks 1-to-1 and skips unpaired extras', () => {
  const lines = [
    { kind: 'hunk', text: '@@ -1,3 +1,4 @@', old: '', next: '' },
    { kind: 'del', text: 'return foo(1);', old: 1, next: '' },
    { kind: 'add', text: 'return foo(2);', old: '', next: 1 },
    { kind: 'add', text: 'console.log("extra");', old: '', next: 2 },
    { kind: 'ctx', text: 'end();', old: 2, next: 3 }
  ];
  const map = buildWordDiffMap(lines);
  assert.ok(map.has(1));
  assert.ok(map.has(2));
  assert.equal(map.has(3), false);
  assert.equal(map.has(4), false);
});

test('buildSplitDiffRows aligns del/add blocks side-by-side and pads uneven blocks with null cells', () => {
  const lines = [
    { kind: 'hunk', text: '@@ -1,2 +1,3 @@', old: '', next: '' },
    { kind: 'ctx', text: 'same', old: 1, next: 1 },
    { kind: 'del', text: 'oldA', old: 2, next: '' },
    { kind: 'add', text: 'newA', old: '', next: 2 },
    { kind: 'add', text: 'newB', old: '', next: 3 }
  ];
  const rows = buildSplitDiffRows(lines);
  assert.equal(rows.length, 4);
  assert.equal(rows[0].kind, 'hunk');
  assert.equal(rows[1].kind, 'pair');
  assert.equal(rows[1].leftIndex, 1);
  assert.equal(rows[1].rightIndex, 1);
  assert.equal(rows[2].kind, 'pair');
  assert.equal(rows[2].left?.text, 'oldA');
  assert.equal(rows[2].right?.text, 'newA');
  assert.equal(rows[3].kind, 'pair');
  assert.equal(rows[3].left, null);
  assert.equal(rows[3].right?.text, 'newB');
});

test('extractHunkPatch extracts individual unified hunks and preserves no-newline-at-eof markers', () => {
  const patch = [
    'diff --git a/demo.txt b/demo.txt',
    'index 1111111..2222222 100644',
    '--- a/demo.txt',
    '+++ b/demo.txt',
    '@@ -1,2 +1,2 @@',
    '-first',
    '+FIRST',
    ' middle',
    '@@ -10,2 +10,2 @@',
    ' tail',
    '-last',
    '\\ No newline at end of file',
    '+LAST',
    '\\ No newline at end of file'
  ].join('\n');
  const [parsed] = parseDiff(patch);
  const hunkIndices = parsed.lines
    .map((line, index) => (line.kind === 'hunk' ? index : -1))
    .filter(index => index >= 0);
  assert.equal(hunkIndices.length, 2);

  const first = extractHunkPatch(parsed.lines, hunkIndices[0]);
  assert.equal(first, '@@ -1,2 +1,2 @@\n-first\n+FIRST\n middle\n');

  const second = extractHunkPatch(parsed.lines, hunkIndices[1]);
  assert.equal(
    second,
    '@@ -10,2 +10,2 @@\n tail\n-last\n\\ No newline at end of file\n+LAST\n\\ No newline at end of file\n'
  );
  assert.equal(extractHunkPatch(parsed.lines, 0), null);
});
