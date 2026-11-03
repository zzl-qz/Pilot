'use strict';

// CSV 解析。当前只做了最朴素的逗号切分，引号里的逗号会切错。
function parse(text) {
  return text.trim().split('\n').map((line) => line.split(','));
}

module.exports = { parse };
