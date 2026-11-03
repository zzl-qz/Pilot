'use strict';
function createCounter(initial = 0) {
  let count = initial;
  return {
    bump() { return ++count; },
    value() { return count; },
    reset() { count = initial; }
  };
}
module.exports = { createCounter };
