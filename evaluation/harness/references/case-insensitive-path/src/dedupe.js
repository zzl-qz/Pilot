'use strict';
function isSamePath(left, right, platform) {
  const normalize = value => {
    let path = platform === 'win32' ? value.split(String.fromCharCode(92)).join('/') : value;
    while (path.length > 1 && path.endsWith('/')) path = path.slice(0, -1);
    return platform === 'linux' ? path : path.toLowerCase();
  };
  return normalize(left) === normalize(right);
}
module.exports = { isSamePath };
