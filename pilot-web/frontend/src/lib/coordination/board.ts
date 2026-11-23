import type {
  CoordinationBoard, CoordinationClaim, CoordinationSessionView, CoordinationSnapshot
} from '../../types/coordination.js';
import type * as S from '../../types/server.js';

const STATUS_LABELS: Record<string, string> = {
  CREATED: '待启动', RUNNING: '运行中', WAITING_APPROVAL: '等待批准', COMPLETED: '已完成',
  FAILED: '失败', CANCELLED: '已取消', PAUSED: '已暂停', INTERRUPTED: '已中断'
};

const REQUEST_LABELS: Record<string, string> = {
  PENDING: '待回应', ACCEPTED: '已接受', DECLINED: '已拒绝', WITHDRAWN: '已撤销'
};

const HANDOFF_LABELS: Record<string, string> = {
  PENDING: '待接受', ACCEPTED: '已接受', DECLINED: '已拒绝', EXPIRED: '已过期'
};

const EMPTY_SNAPSHOT: CoordinationSnapshot = { projectId: null, sessions: [], claims: [], requests: [], handoffs: [] };

export function sessionStatusLabel(status: string | undefined | null): string {
  return (status && STATUS_LABELS[status]) || status || '未知';
}

export function requestStatusLabel(status: string): string {
  return REQUEST_LABELS[status] || status;
}

export function handoffStatusLabel(status: string): string {
  return HANDOFF_LABELS[status] || status;
}

/**
 * 看板响应归一：新版返回 { snapshot, overview }，只认旧接口的服务端只返回概览字段，
 * 这时补齐空快照，界面照旧显示会话与冲突，不因升级落后而整块空白。
 */
export function readBoard(payload: unknown): CoordinationBoard | null {
  if (!payload || typeof payload !== 'object') return null;
  const record = payload as {
    snapshot?: Partial<CoordinationSnapshot> | null;
    overview?: S.WorkspaceOverviewServiceOverview | null;
  } & Partial<S.WorkspaceOverviewServiceOverview>;
  const snapshot = record.snapshot;
  if (snapshot && typeof snapshot === 'object') {
    return { snapshot: normalizeSnapshot(snapshot), overview: (record.overview as S.WorkspaceOverviewServiceOverview) ?? null };
  }
  if (Array.isArray(record.sessions)) {
    return { snapshot: EMPTY_SNAPSHOT, overview: record as S.WorkspaceOverviewServiceOverview };
  }
  return null;
}

function normalizeSnapshot(snapshot: Partial<CoordinationSnapshot>): CoordinationSnapshot {
  return {
    projectId: snapshot.projectId ?? null,
    sessions: Array.isArray(snapshot.sessions) ? snapshot.sessions : [],
    claims: Array.isArray(snapshot.claims) ? snapshot.claims : [],
    requests: Array.isArray(snapshot.requests) ? snapshot.requests : [],
    handoffs: Array.isArray(snapshot.handoffs) ? snapshot.handoffs : []
  };
}

/** 协作请求与交接共有的方向字段：据此判断这条是不是等着当前会话处理。 */
interface CoordinationParty {
  fromSessionId: string;
  toSessionId: string;
  status: string;
}

/**
 * 仍待处理、且属于当前会话的协作请求或交接。
 *
 * <p>{@code incoming} 是别人发给我、只能由我接受或拒绝的；{@code outgoing} 是我发出、可以撤销的。
 */
export function openFor<T extends CoordinationParty>(items: T[], sessionId: string,
  side: 'incoming' | 'outgoing'): T[] {
  return items.filter((item) => item.status === 'PENDING'
    && (side === 'incoming' ? item.toSessionId === sessionId : item.fromSessionId === sessionId));
}

/** 自己声明、还能释放的占用；别人的占用只显示占用者，界面不提供释放。 */
export function claimsOwnedBy(claims: CoordinationClaim[], sessionId: string): CoordinationClaim[] {
  return claims.filter((item) => item.sessionId === sessionId);
}

export function otherSessions(snapshot: CoordinationSnapshot, sessionId: string): CoordinationSessionView[] {
  return snapshot.sessions.filter((item) => item.sessionId !== sessionId);
}

/** 看板连接状态的人话口径；顺带说明当前游标，便于排查是否卡在旧事件上。 */
export function connectionLabel(status: string, cursor: number, attempts: number): string {
  if (status === 'live') return `实时 · 游标 ${cursor}`;
  if (status === 'connecting') return '正在连接工作区事件流…';
  if (status === 'reconnecting') return `断线重连中（第 ${attempts} 次）· 游标 ${cursor}`;
  if (status === 'stopped') return `已停止接收 · 游标 ${cursor}`;
  return '尚未连接工作区事件流';
}
