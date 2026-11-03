'use strict';

const { truncate, slugify } = require('./shared/helpers.js');

function cliLine(title) {
  return `${truncate(title, 12)}  ${slugify(title)}`;
}

module.exports = { cliLine };
