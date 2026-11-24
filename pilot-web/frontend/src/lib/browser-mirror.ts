import type { BrowserActionEvent, BrowserHandoff, BrowserLane } from '../types/api.js';
import type { MirrorState } from '../types/store.js';

/** 人在地址栏输入的地址。本机没写协议时用 http，其它主机用 https。 */
export function userPageUrl(input: unknown) {
  const text = String(input || '').trim();
  if (!text) return null;
  const hasScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(text);
  let url;
  try {
    url = new URL(hasScheme ? text : `http://${text}`);
  } catch {
    return null;
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
  if (!hasScheme) {
    const host = url.hostname.replace(/^\[|\]$/g, '');
    const local = host === 'localhost' || host.endsWith('.localhost')
      || host === '127.0.0.1' || host === '::1' || /^127\.\d+\.\d+\.\d+$/.test(host);
    if (!local) url.protocol = 'https:';
  }
  return url.href;
}

/** 浏览器 SSE 状态更新：覆盖导航、实时动作、子代理泳道、接管请求与证据计数。 */
export function browserStateUpdate(
  kind: string,
  payload: unknown,
  current?: Partial<MirrorState>
): Partial<MirrorState> {
  if (kind === 'closed') {
    return { mode: 'closed', paused: false, agentControlled: false, controlStatus: '' };
  }
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    return {};
  }
  const data = payload as Record<string, unknown>;
  if (kind === 'action') {
    return { lastAction: data as unknown as BrowserActionEvent };
  }
  if (kind === 'control-state') {
    const active = Boolean(data.agentControlled);
    return {
      agentControlled: active,
      controlStatus: active && typeof data.controlStatus === 'string' ? data.controlStatus : ''
    };
  }
  if (kind === 'control-stopped') {
    return { agentControlled: false, controlStatus: '', paused: true };
  }
  if (kind === 'evidence') {
    return {
      evidenceCount: (current?.evidenceCount ?? 0) + 1,
      evidenceVersion: (current?.evidenceVersion ?? 0) + 1
    };
  }
  if (kind === 'handoff') {
    if (Array.isArray(data.pending)) {
      return { handoffs: data.pending as BrowserHandoff[] };
    }
    if (data.handoff && typeof data.handoff === 'object') {
      return { handoffs: [data.handoff as BrowserHandoff] };
    }
    return {};
  }
  if (!['nav', 'state', 'status'].includes(kind)) {
    return {};
  }
  const update: Partial<MirrorState> = {};
  if (typeof data.url === 'string' && data.url) {
    update.url = data.url;
  }
  if (kind === 'nav' && update.url && update.url !== 'about:blank') {
    update.mode = 'live';
  }
  if (kind === 'state' || kind === 'status') {
    if (typeof data.headless === 'boolean') update.headless = data.headless;
    if (typeof data.paused === 'boolean') update.paused = data.paused;
    if (typeof data.agentControlled === 'boolean') update.agentControlled = data.agentControlled;
    if (typeof data.controlStatus === 'string') update.controlStatus = data.controlStatus;
    if (typeof data.activeLane === 'string') update.activeLane = data.activeLane;
    if (Array.isArray(data.lanes)) update.lanes = data.lanes as BrowserLane[];
    if (Array.isArray(data.handoffs)) update.handoffs = data.handoffs as BrowserHandoff[];
    if (typeof data.evidenceCount === 'number') update.evidenceCount = data.evidenceCount;
  }
  return update;
}

export interface ScreenshotFeedbackInput {
  url?: string;
  title?: string;
  action?: string;
  x: number;
  y: number;
  width?: number;
  height?: number;
  naturalWidth?: number;
  naturalHeight?: number;
  note?: string;
}

/** 把用户在截图上的点选/框选坐标整理成可插入输入框的结构化定位引用。 */
export function formatScreenshotFeedback(input: ScreenshotFeedbackInput): string {
  const x = Math.max(0, Math.round(input.x));
  const y = Math.max(0, Math.round(input.y));
  const w = input.width ? Math.max(0, Math.round(input.width)) : 0;
  const h = input.height ? Math.max(0, Math.round(input.height)) : 0;
  const nw = input.naturalWidth && input.naturalWidth > 0 ? input.naturalWidth : 0;
  const nh = input.naturalHeight && input.naturalHeight > 0 ? input.naturalHeight : 0;
  const area = w > 4 && h > 4 ? `框选区域 (${x}, ${y}) 尺寸 ${w}×${h}px` : `点击位置 (${x}, ${y})px`;
  const ratio = nw && nh
    ? `（约画面 ${Math.round((x / nw) * 100)}%, ${Math.round((y / nh) * 100)}%`
      + (w > 4 && h > 4 ? `，宽高 ${Math.round((w / nw) * 100)}%×${Math.round((h / nh) * 100)}%` : '')
      + '）'
    : '';
  const target = [input.title, input.url].filter(Boolean).join(' · ') || input.action || '浏览器截图';
  const noteLine = input.note && input.note.trim() ? `\n说明：${input.note.trim()}` : '';
  return `\n> 截图定位反馈（${target}）\n> ${area}${ratio}${noteLine}\n\n`;
}
