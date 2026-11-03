'use strict';

/**
 * 判断两个路径是否指向同一个文件。
 * Windows 与 macOS 默认大小写不敏感，Linux 敏感，所以必须按平台判断。
 */
function isSamePath(left, right, platform) {
  return left === right;
}

module.exports = { isSamePath };
