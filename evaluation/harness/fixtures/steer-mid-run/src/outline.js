'use strict';

// 从 Markdown 提取标题。当前只返回标题文字。
function outline(markdown) {
  return markdown
    .split('\n')
    .filter((line) => /^#{1,6}\s/.test(line))
    .map((line) => line.replace(/^#{1,6}\s+/, '').trim());
}

module.exports = { outline };
