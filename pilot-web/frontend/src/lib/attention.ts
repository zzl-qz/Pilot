/**
 * 标签页上的「注意力信号」：运行中、需要你处理、完成、失败。
 * 切到别的窗口干活时，只看标签页标题和图标就知道该不该回来。
 */
const PREFIX: Record<string, string> = { idle: '', running: '● ', attention: '! ', done: '✓ ', error: '✕ ' };
const BADGE: Record<string, string> = { running: '#4fa3ea', attention: '#f2b544', done: '#3ecf8e', error: '#ff6f6f' };

export function tabTitle(base: unknown, kind: string) {
  const name = String(base || 'Pilot').trim() || 'Pilot';
  return (PREFIX[kind] ?? '') + name;
}

/** 圆环 + 箭头的品牌图标；有状态时右下角加一枚状态色圆点。 */
export function faviconHref(kind: string) {
  const badge = BADGE[kind]
    ? `%3Ccircle cx='25' cy='25' r='6.5' fill='${encodeURIComponent(BADGE[kind])}' stroke='%23171717' stroke-width='2'/%3E`
    : '';
  return "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E"
    + "%3Crect width='32' height='32' rx='9' fill='%23171717'/%3E"
    + "%3Ccircle cx='16' cy='16' r='9.5' fill='none' stroke='%238c8c8c' stroke-width='1.8'/%3E"
    + "%3Cpath d='M16 8.6l4.6 13.6L16 19.4l-4.6 2.8z' fill='%236cb4f0'/%3E"
    + badge
    + '%3C/svg%3E';
}

/** 只有页面在后台、用户开了通知、浏览器也授权了、且事件值得打扰时才弹系统通知。 */
export function shouldNotify({ kind, hidden, enabled, permission }: { kind: string; hidden: boolean; enabled: boolean; permission: string }) {
  return Boolean(hidden && enabled && permission === 'granted'
    && (kind === 'attention' || kind === 'done' || kind === 'error'));
}
