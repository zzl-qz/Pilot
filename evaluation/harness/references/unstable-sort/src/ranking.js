'use strict';

/**
 * 排名：分数高的在前；分数相同时按名字升序（稳定、可预期）。
 * 不允许修改传入的数组。
 */
function rank(players) {
  return [...players].sort((left, right) => right.score - left.score || left.name.localeCompare(right.name));
}

module.exports = { rank };
