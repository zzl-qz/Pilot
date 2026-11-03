'use strict';
const { posix, win32 } = require('node:path');
function joinPath(root, relative) {
  const backslash = String.fromCharCode(92);
  const windows = root.includes(backslash) || /^[a-z]:/i.test(root);
  const separator = windows ? backslash : '/';
  const normalize = value => value.split(backslash).join(separator).split('/').join(separator);
  return (windows ? win32 : posix).join(normalize(root), normalize(relative));
}
module.exports = { joinPath };
