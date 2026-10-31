export const HISTORY_AUTO_LOAD_THRESHOLD = 180;
export const THREAD_FOLLOW_SLACK = 140;

export function canLoadEarlier({ hasMore, loading, eventCount }) {
  return hasMore === true && loading !== true && Number(eventCount || 0) > 0;
}

export function shouldAutoLoadEarlier({ scrollTop, previousScrollTop, hasMore, loading, eventCount }) {
  return canLoadEarlier({ hasMore, loading, eventCount })
    && Number(scrollTop || 0) < Number(previousScrollTop || 0)
    && Number(scrollTop || 0) <= HISTORY_AUTO_LOAD_THRESHOLD;
}

export function threadDistanceFromBottom({ scrollHeight, scrollTop, clientHeight }) {
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
