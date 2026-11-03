'use strict';

/**
 * 扣款。同一个 key 重复提交只能真正扣一次：
 * 第二次调用返回第一次的结果，账本里也只有一条记录。
 */
function charge(ledger, key, amountCents) {
  const existing = ledger.find(entry => entry.key === key);
  if (existing) return { charged: false, entry: existing };
  const entry = { key, amountCents, at: Date.now() };
  ledger.push(entry);
  return { charged: true, entry };
}

module.exports = { charge };
