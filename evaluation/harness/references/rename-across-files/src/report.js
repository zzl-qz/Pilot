'use strict';

const { displayNameOf } = require('./userService.js');

function reportLine(user) {
  return `${displayNameOf(user)} 本月下单 0 次`;
}

module.exports = { reportLine };
