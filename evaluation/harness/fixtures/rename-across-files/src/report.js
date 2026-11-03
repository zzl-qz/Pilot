'use strict';

const { getUserName } = require('./userService.js');

function reportLine(user) {
  return `${getUserName(user)} 本月下单 0 次`;
}

module.exports = { reportLine };
