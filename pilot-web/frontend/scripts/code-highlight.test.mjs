import test from 'node:test';
import assert from 'node:assert/strict';
import { detectCodeLanguage, tokenizeCodeLine } from '../src/lib/code-highlight.ts';

test('detectCodeLanguage maps common source extensions and returns null for plain text', () => {
  assert.equal(detectCodeLanguage('src/App.tsx'), 'ts');
  assert.equal(detectCodeLanguage('Service.java'), 'java');
  assert.equal(detectCodeLanguage('script.py'), 'py');
  assert.equal(detectCodeLanguage('query.sql'), 'sql');
  assert.equal(detectCodeLanguage('notes.txt'), null);
});

test('tokenizeCodeLine highlights keywords, types, strings, numbers, functions and comments', () => {
  const tokens = tokenizeCodeLine('public final int count = parse("ok", 42); // note', 'java');
  assert.ok(tokens.some(t => t.kind === 'kw' && t.text === 'public'));
  assert.ok(tokens.some(t => t.kind === 'fn' && t.text === 'parse'));
  assert.ok(tokens.some(t => t.kind === 'str' && t.text === '"ok"'));
  assert.ok(tokens.some(t => t.kind === 'num' && t.text === '42'));
  assert.ok(tokens.some(t => t.kind === 'com' && t.text === '// note'));
  assert.equal(tokens.map(t => t.text).join(''), 'public final int count = parse("ok", 42); // note');
});
