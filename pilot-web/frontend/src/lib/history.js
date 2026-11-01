const HISTORY_PREFETCH_VIEWPORTS = 2;
const HISTORY_PREFETCH_MIN_DISTANCE = 64;
export const HISTORY_WINDOW_LIMIT = 240;
const THREAD_FOLLOW_SLACK = 140;

export function historyPrefetchDistance(viewportHeight) {
  const height = Number(viewportHeight);
  return Number.isFinite(height) && height > 0
    ? Math.max(HISTORY_PREFETCH_MIN_DISTANCE, height * HISTORY_PREFETCH_VIEWPORTS)
    : HISTORY_PREFETCH_MIN_DISTANCE;
}

export function mergeHistoryEvents(older, current) {
  const sequenced = new Map();
  const unsequenced = [];
  for (const event of [...older, ...current]) {
    if (typeof event?.sequence !== 'number') {
      unsequenced.push(event);
    } else if (!sequenced.has(event.sequence)) {
      sequenced.set(event.sequence, event);
    }
  }
  return [...sequenced.values(), ...unsequenced]
    .sort((left, right) => (left.sequence ?? Infinity) - (right.sequence ?? Infinity));
}

export function isValidEarlierHistoryPage(events, before) {
  return Array.isArray(events)
    && events.length > 0
    && Number.isFinite(before)
    && events.every((event) => Number.isFinite(event?.sequence) && event.sequence < before);
}

export function isValidLaterHistoryPage(events, after) {
  return Array.isArray(events)
    && events.length > 0
    && Number.isFinite(after)
    && events.every((event) => Number.isFinite(event?.sequence) && event.sequence > after);
}

export function trimHistoryWindow(events, direction, limit = HISTORY_WINDOW_LIMIT) {
  const size = Math.max(1, Number(limit) || HISTORY_WINDOW_LIMIT);
  if (events.length <= size) {
    return { events, evictedBefore: false, evictedAfter: false };
  }
  return direction === 'older'
    ? { events: events.slice(0, size), evictedBefore: false, evictedAfter: true }
    : { events: events.slice(-size), evictedBefore: true, evictedAfter: false };
}

export function trimHistoryWindowAtRows(events, rows, direction, limit = HISTORY_WINDOW_LIMIT) {
  const bounded = Math.max(1, Number(limit) || HISTORY_WINDOW_LIMIT);
  if (events.length <= bounded) {
    return { events, evictedBefore: false, evictedAfter: false, removeStartCount: 0, removeEndCount: 0 };
  }
  if (direction === 'older') {
    const cutoff = events[bounded]?.sequence;
    const index = rows.findIndex((row) => row.end >= cutoff);
    if (index < 0) return { events, evictedBefore: false, evictedAfter: false, removeStartCount: 0, removeEndCount: 0 };
    const boundary = Number(rows[index].start) || cutoff;
    const kept = events.filter((event) => event.sequence < boundary);
    return { events: kept, evictedBefore: false, evictedAfter: kept.length < events.length,
      removeStartCount: 0, removeEndCount: rows.length - index };
  }
  const cutoff = events[events.length - bounded]?.sequence;
  let index = -1;
  rows.forEach((row, rowIndex) => { if (row.start < cutoff) index = rowIndex; });
  if (index < 0) return { events, evictedBefore: false, evictedAfter: false, removeStartCount: 0, removeEndCount: 0 };
  const boundary = Number(rows[index].end) || (cutoff - 1);
  const kept = events.filter((event) => event.sequence > boundary);
  return { events: kept, evictedBefore: kept.length < events.length, evictedAfter: false,
    removeStartCount: index + 1, removeEndCount: 0 };
}

export function canLoadEarlier({ hasMore, loading, eventCount }) {
  return hasMore === true && loading !== true && Number(eventCount || 0) > 0;
}

export function shouldAutoLoadEarlier({ scrollTop, hasMore, loading, eventCount, viewportHeight }) {
  return canLoadEarlier({ hasMore, loading, eventCount })
    && Number(scrollTop || 0) <= historyPrefetchDistance(viewportHeight);
}

export function shouldAutoLoadLater({ distanceFromBottom, hasMore, loading, eventCount, viewportHeight }) {
  return canLoadEarlier({ hasMore, loading, eventCount })
    && Number(distanceFromBottom || 0) <= historyPrefetchDistance(viewportHeight);
}

function threadDistanceFromBottom({ scrollHeight, scrollTop, clientHeight }) {
  return Math.max(0,
    Number(scrollHeight || 0) - Number(scrollTop || 0) - Number(clientHeight || 0));
}

export function isNearThreadBottom(metrics, slack = THREAD_FOLLOW_SLACK) {
  return threadDistanceFromBottom(metrics) < Number(slack || 0);
}

export function shouldShowThreadBottom({ empty, following, jumping, ...metrics }) {
  return empty !== true
    && following !== true
    && jumping !== true
    && !isNearThreadBottom(metrics);
}
