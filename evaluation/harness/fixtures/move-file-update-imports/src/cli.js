'use strict';

const { truncate, slugify } = require('./utils/helpers.js');

function cliLine(title) {
  return `${truncate(title, 12)}  ${slugify(title)}`;
}

module.exports = { cliLine };
