'use strict';

/**
 * 游标分页：返回 { items, nextCursor }。
 * 游标必须能抵抗列表变化——翻页期间头部插入新项时，下一页不能重复也不能遗漏。
 */
function page(items, cursor, limit) {
  const start = cursor ? Number(cursor) : 0;
  const slice = items.slice(start, start + limit);
  return { items: slice, nextCursor: String(start + limit) };
}

module.exports = { page };
