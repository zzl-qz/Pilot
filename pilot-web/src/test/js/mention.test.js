import test from 'node:test';
import assert from 'node:assert/strict';
import { isSubsequence, rankFileMatches } from '../../main/resources/static/lib/mention.js';

const FILES = [
  'pilot-core/src/main/java/com/pilot/core/tool/SearchTool.java',
  'pilot-web/src/main/resources/static/app.js',
  'pom.xml',
  'README.md',
  'docs/SearchNotes.md'
];

test('empty query lists shallow paths first', () => {
  assert.deepEqual(rankFileMatches(FILES, '').slice(0, 2), ['pom.xml', 'README.md']);
});

test('file name hits come before directory-only hits', () => {
  const ranked = rankFileMatches(FILES, 'search');

  assert.deepEqual(new Set(ranked.slice(0, 2)), new Set([
    'docs/SearchNotes.md',
    'pilot-core/src/main/java/com/pilot/core/tool/SearchTool.java'
  ]));
  assert.equal(ranked.includes('pilot-web/src/main/resources/static/app.js'), false);
});

test('falls back to subsequence matching for partial input', () => {
  assert.equal(isSubsequence('searchtool', 'srchtl'), true);
  assert.equal(rankFileMatches(FILES, 'srchtl')[0],
    'pilot-core/src/main/java/com/pilot/core/tool/SearchTool.java');
});

test('drops non-matches and respects the limit', () => {
  assert.equal(rankFileMatches(FILES, '不存在的文件').length, 0);
  assert.equal(rankFileMatches(FILES, '', 2).length, 2);
  assert.deepEqual(rankFileMatches(null, 'x'), []);
});
