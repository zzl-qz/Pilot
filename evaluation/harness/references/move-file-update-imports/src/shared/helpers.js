'use strict';

// 这个模块要挪到 src/shared/ 下，引用方都要跟着改。
function slugify(text) {
  return String(text).trim().toLowerCase().replace(/\s+/g, '-');
}

function truncate(text, max) {
  return text.length <= max ? text : `${text.slice(0, max)}…`;
}

module.exports = { slugify, truncate };
