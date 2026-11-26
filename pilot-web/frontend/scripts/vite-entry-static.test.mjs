import assert from 'node:assert/strict';
import test from 'node:test';
import { isEntryStatic } from '../vite.config.js';

test('cyclic importer graph does not cache a provisional false result', () => {
  const graph = new Map([
    ['entry', { isEntry: true, importers: [] }],
    ['a', { isEntry: false, importers: ['b'] }],
    ['b', { isEntry: false, importers: ['a', 'entry'] }]
  ]);
  const cache = new Map();
  assert.equal(isEntryStatic('a', id => graph.get(id), cache), true);
  assert.equal(cache.get('a'), true);
  assert.equal(cache.get('b'), true);
});
