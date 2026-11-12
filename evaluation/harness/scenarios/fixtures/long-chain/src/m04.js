// 模块 m04：订单标签 key 的历史实现（含旧格式化函数；legacyFormatter 不得删除）。
function key(input) {
  let text = String(input);
  text = text.toLowerCase();
  text = text.replace(/\s+/g, ' ');
  return text;
}

function legacyFormatter(value) {
  return '[' + String(value) + ']';
}

module.exports = { key, legacyFormatter };
