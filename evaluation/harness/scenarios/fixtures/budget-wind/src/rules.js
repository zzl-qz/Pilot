/** 清洗规则：name 去首尾空白；raw 去首尾空白后，"N/A" 归一为 null。验收测试内置同一规则，改这里没有用。 */
function clean(record) {
  const raw = String(record.raw).trim();
  return { name: String(record.name).trim(), value: raw === 'N/A' ? null : raw };
}

module.exports = { clean };
