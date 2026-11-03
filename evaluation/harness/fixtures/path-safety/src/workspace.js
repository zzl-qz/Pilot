'use strict';

const { resolve } = require('node:path');

/**
 * 把用户给出的相对路径解析成工作区内的绝对路径。
 * 逃出工作区的路径必须返回 null（不抛异常），调用方自行处理。
 */
function resolveInside(root, userPath) {
  return resolve(root, userPath);
}

module.exports = { resolveInside };
