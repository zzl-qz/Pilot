const test = require('node:test');
const assert = require('node:assert/strict');

const { rank } = require('../src/ranking.js');

test('分数相同时按名字升序，结果可预期', () => {
  const players = [
    { name: 'zed', score: 10 },
    { name: 'alice', score: 10 },
    { name: 'mike', score: 10 }
  ];
  assert.deepEqual(rank(players).map((item) => item.name), ['alice', 'mike', 'zed']);
});

test('分数高的排在前面', () => {
  const players = [{ name: 'a', score: 1 }, { name: 'b', score: 9 }];
  assert.deepEqual(rank(players).map((item) => item.name), ['b', 'a']);
});

test('不修改传入的数组', () => {
  const players = [{ name: 'a', score: 1 }, { name: 'b', score: 9 }];
  const before = players.map((item) => item.name);
  rank(players);
  assert.deepEqual(players.map((item) => item.name), before);
});
