'use strict';

/**
 * 拼接路径。输入可能是 Windows 风格（反斜杠）也可能是 POSIX 风格（斜杠），
 * 输出必须与输入风格一致，且不能出现混合分隔符。
 */
function joinPath(root, relative) {
  return `${root}/${relative}`;
}

module.exports = { joinPath };
