'use strict';
function page(items, cursor, limit) {
  if (!Number.isInteger(limit) || limit < 1) return { items: [], nextCursor: null };
  const anchor = cursor == null ? -1 : items.findIndex(item => String(item.id) === String(cursor));
  if (cursor != null && anchor < 0) return { items: [], nextCursor: null };
  const start = anchor + 1;
  const slice = items.slice(start, start + limit);
  return { items: slice, nextCursor: start + slice.length < items.length ? String(slice.at(-1).id) : null };
}
module.exports = { page };
