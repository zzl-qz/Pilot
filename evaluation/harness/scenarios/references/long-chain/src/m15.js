// 模块 m15：统一复用 common 的 normalizeKey（历史 legacyFormatter 保留不动）。
const { normalizeKey } = require('./common.js');

function key(input) {
  return normalizeKey(input);
}

function legacyFormatter(value) {
  return '[' + String(value) + ']';
}

module.exports = { key, legacyFormatter };
