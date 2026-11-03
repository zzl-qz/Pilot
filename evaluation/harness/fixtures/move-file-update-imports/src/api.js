'use strict';

const { slugify } = require('./utils/helpers.js');

function apiPath(title) {
  return `/posts/${slugify(title)}`;
}

module.exports = { apiPath };
