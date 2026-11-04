import test from 'node:test';
import assert from 'node:assert/strict';
import { sum } from '../dist/sum.mjs';
test('sum includes zero, negative and numeric string', () => {
  assert.equal(sum(2, 4), 6);
  assert.equal(sum(0, -2), -2);
  assert.equal(sum('2', 4), 6);
  assert.throws(() => sum('bad', 2), TypeError);
});
