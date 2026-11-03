'use strict';
const { resolve, relative, isAbsolute, sep } = require('node:path');
function resolveInside(root, userPath) {
  try {
    const target = resolve(root, userPath);
    const fromRoot = relative(resolve(root), target);
    return fromRoot === '..' || fromRoot.startsWith(`..${sep}`) || isAbsolute(fromRoot) ? null : target;
  } catch { return null; }
}
module.exports = { resolveInside };
