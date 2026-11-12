// 统一的订单标签归一化：去首尾空白、转小写、内部空白折叠成单个空格。
function normalizeKey(input) {
  return String(input).trim().toLowerCase().replace(/\s+/g, ' ');
}

module.exports = { normalizeKey };
