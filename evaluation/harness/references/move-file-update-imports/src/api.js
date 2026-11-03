'use strict';

const { slugify } = require('./shared/helpers.js');

function apiPath(title) {
  return `/posts/${slugify(title)}`;
}

module.exports = { apiPath };
