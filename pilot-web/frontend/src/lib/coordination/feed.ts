import type { CoordinationEventFrame } from '../../types/coordination.js';
import { WORKSPACE_EVENT_TYPES } from '../../types/coordination.js';

/** 控制帧只用到 payload：解析失败一律当作无效帧，不抛给连接流程。 */
function payloadOf(data: string): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(data) as { payload?: unknown };
    const payload = parsed?.payload;
    return payload && typeof payload === 'object' ? payload as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

/** 事件帧必须带序号，否则无法去重与续传。 */
function frameOf(data: string): CoordinationEventFrame | null {
  try {
    const parsed = JSON.parse(data) as CoordinationEventFrame;
    return parsed && typeof parsed === 'object' && typeof parsed.sequence === 'number' ? parsed : null;
  } catch {
    return null;
  }
}

/** 事件源的最小接口：只用到订阅、关闭与错误回调，便于在无浏览器环境下测试。 */
export interface CoordinationEventSource {
  addEventListener(type: string, listener: (message: { data: string }) => void): void;
  close(): void;
  onerror: ((event: Event) => void) | null;
}

export type CoordinationFeedStatus = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'stopped';

export interface CoordinationFeedState {
  status: CoordinationFeedStatus;
  /** 最后一条已交付事件的序号；重连时作为 after 游标，保证不重复、不丢事件。 */
  cursor: number;
  attempts: number;
  lastError: string;
  projectId: string;
  directories: string[];
}

export interface CoordinationFeedOptions {
  /** 按游标拼事件流地址；游标 0 表示从头（或从缓存起点）回放。 */
  url: (cursor: number) => string;
  createSource: (url: string) => CoordinationEventSource;
  onEvent: (frame: CoordinationEventFrame) => void;
  onState?: (state: CoordinationFeedState) => void;
  /** 游标已滚出缓存：调用方应按完整快照重建看板。 */
  onReset?: (state: CoordinationFeedState) => void;
  schedule?: (run: () => void, delayMs: number) => unknown;
  cancel?: (handle: unknown) => void;
}

const BASE_DELAY_MS = 1000;
const MAX_DELAY_MS = 30_000;
const MAX_BACKOFF_STEPS = 5;

/**
 * 工作区事件流的连接管理：游标去重、断线退避重连、缓存滚动后重建。
 *
 * <p>不直接读 window：定时器与事件源都可由调用方注入，Node 测试用假事件源即可覆盖连接生命周期。
 */
export function createCoordinationFeed(options: CoordinationFeedOptions) {
  const schedule = options.schedule ?? ((run: () => void, delayMs: number) => setTimeout(run, delayMs));
  const cancel = options.cancel ?? ((handle: unknown) => clearTimeout(handle as ReturnType<typeof setTimeout>));
  let state: CoordinationFeedState = {
    status: 'idle', cursor: 0, attempts: 0, lastError: '', projectId: '', directories: []
  };
  let source: CoordinationEventSource | null = null;
  let timer: unknown = null;
  let active = false;

  const publish = (patch: Partial<CoordinationFeedState>) => {
    state = { ...state, ...patch };
    options.onState?.(state);
  };

  const closeSource = () => {
    if (!source) return;
    const current = source;
    source = null;
    current.close();
  };

  const scheduleReconnect = () => {
    if (!active) return;
    const attempts = state.attempts + 1;
    const delayMs = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** Math.min(attempts - 1, MAX_BACKOFF_STEPS));
    publish({ status: 'reconnecting', attempts });
    timer = schedule(() => {
      timer = null;
      connect();
    }, delayMs);
  };

  const connect = () => {
    timer = null;
    if (!active) return;
    closeSource();
    const current = options.createSource(options.url(state.cursor));
    source = current;
    const isCurrent = () => active && source === current;
    publish({ status: state.attempts === 0 ? 'connecting' : 'reconnecting' });
    const accept = (message: { data: string }) => {
      if (!isCurrent()) return;
      const frame = frameOf(message.data);
      if (!frame) return;
      // 序号不大于游标说明是回放重叠或重复投递，直接丢弃。
      if (frame.sequence <= state.cursor) return;
      publish({ cursor: frame.sequence, lastError: '' });
      options.onEvent(frame);
    };
    for (const type of WORKSPACE_EVENT_TYPES) current.addEventListener(type, accept);
    current.addEventListener('workspace.ready', (message) => {
      if (!isCurrent()) return;
      const payload = payloadOf(message.data);
      const projectId = typeof payload?.projectId === 'string' ? payload.projectId : state.projectId;
      const directories = Array.isArray(payload?.directories) ? payload.directories as string[] : state.directories;
      publish({
        status: 'live', attempts: 0, lastError: '', projectId, directories
      });
    });
    current.addEventListener('workspace.reset', (message) => {
      if (!isCurrent()) return;
      const payload = payloadOf(message.data);
      closeSource();
      publish({
        status: 'reconnecting', cursor: 0,
        lastError: payload?.reason === 'cursor_expired' ? '事件缓存已滚动，按最新快照重建看板' : '工作区事件流已重置'
      });
      options.onReset?.(state);
      timer = schedule(() => {
        timer = null;
        connect();
      }, 0);
    });
    current.addEventListener('workspace.error', (message) => {
      if (!isCurrent()) return;
      const payload = payloadOf(message.data);
      publish({ lastError: typeof payload?.message === 'string' && payload.message ? payload.message : '工作区事件流不可用' });
    });
    current.onerror = () => {
      if (!isCurrent()) return;
      closeSource();
      scheduleReconnect();
    };
  };

  return {
    start() {
      if (active) return;
      active = true;
      connect();
    },
    stop() {
      if (!active) return;
      active = false;
      if (timer !== null) {
        cancel(timer);
        timer = null;
      }
      closeSource();
      publish({ status: 'stopped' });
    },
    /** 手动重试：断线期间用户点「重连」时立即重试，不等退避时间。 */
    retry() {
      if (!active) return;
      if (timer !== null) {
        cancel(timer);
        timer = null;
      }
      closeSource();
      publish({ attempts: 0 });
      connect();
    },
    state(): CoordinationFeedState {
      return state;
    }
  };
}

export type CoordinationFeed = ReturnType<typeof createCoordinationFeed>;
