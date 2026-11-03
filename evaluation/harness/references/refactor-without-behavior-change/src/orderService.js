'use strict';
const strategies = require('./pricingStrategies.js');
function priceFor(order) { return strategies.forType(order.type)(order); }
module.exports = { priceFor, registerStrategy: strategies.registerStrategy };
