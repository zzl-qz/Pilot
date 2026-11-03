'use strict';

/**
 * 取第 page 页（从 1 开始），每页 pageSize 条。
 * 越界时返回空数组，不要抛异常。
 */
function paginate(items, page, pageSize) {
  if (page < 1 || pageSize < 1) {
    return [];
  }
  const start = (page - 1) * pageSize;
  const end = start + pageSize;
  return items.slice(start, end);
}

module.exports = { paginate };
