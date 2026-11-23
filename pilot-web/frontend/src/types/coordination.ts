/** 会话间协调与工作区事件的线上结构；对照 pilot-core coordination 包与 SessionCoordinationController。 */
import type * as S from './server.js';

export type CoordinationClaimKind = 'FILE' | 'DIRECTORY';
export type CoordinationRequestStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'WITHDRAWN' | 'EXPIRED';
export type CoordinationHandoffStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED';

/** CoordinationSessionView.java：协调范围内的一条会话状态；online 只反映当前进程的运行时登记。 */
export interface CoordinationSessionView {
  sessionId: string;
  title: string;
  status: string;
  workspace: string;
  online: boolean;
  archived: boolean;
  lastActivityAt?: string | null;
  stalled?: boolean;
}

/** CoordinationClaim.java：文件或目录占用，路径已规范化，父子目录同样算冲突。 */
export interface CoordinationClaim {
  id: string;
  projectId?: string | null;
  sessionId: string;
  path: string;
  kind: CoordinationClaimKind;
  claimedAt: string;
  expiresAt: string;
}

/** CoordinationRequest.java：协作请求由发起会话创建，只有目标会话能接受或拒绝。 */
export interface CoordinationRequest {
  id: string;
  projectId?: string | null;
  fromSessionId: string;
  toSessionId: string;
  reason: string;
  taskId?: string | null;
  status: CoordinationRequestStatus;
  createdAt: string;
  updatedAt: string;
  expiresAt?: string | null;
}

/** CoordinationHandoff.java：任务交接，目标会话可以接受或拒绝，过期后自动关闭。 */
export interface CoordinationHandoff {
  id: string;
  projectId?: string | null;
  fromSessionId: string;
  toSessionId: string;
  taskId: string;
  summary: string;
  status: CoordinationHandoffStatus;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
}

/** CoordinationAlert.java：看门狗巡检发现的跨会话异常（互等死锁、目标会话停滞或异常中断）。 */
export interface CoordinationAlert {
  kind: 'DEADLOCK' | 'STALLED_DEPENDENCY' | string;
  sessionIds: string[];
  summary: string;
}

/** CoordinationSnapshot.java：协调状态的整体快照，不含沙箱接受、仲裁与事件正文。 */
export interface CoordinationSnapshot {
  projectId?: string | null;
  sessions: CoordinationSessionView[];
  claims: CoordinationClaim[];
  requests: CoordinationRequest[];
  handoffs: CoordinationHandoff[];
  alerts?: CoordinationAlert[];
}

/** SessionCoordinationController.CoordinationBoard：协调快照 + 现有工作区概览（可能为空）。 */
export interface CoordinationBoard {
  snapshot: CoordinationSnapshot;
  overview: S.WorkspaceOverviewServiceOverview | null;
}

/** Refusal：被拒绝时的结构化原因，占用冲突会带上占用方与位置。 */
export interface CoordinationRefusal {
  code: string;
  ownerSessionId?: string | null;
  path?: string | null;
  message: string;
}

/** WorkspaceEventType.java 的线上类型：事件名与单会话 SSE 保持点分风格。 */
export const WORKSPACE_EVENT_TYPES = [
  'session.started', 'session.stopped', 'run.started', 'run.finished', 'sandbox.changed',
  'file.claimed', 'file.released', 'arbitration.created', 'arbitration.resolved',
  'coordination.requested', 'coordination.updated', 'handoff.created', 'handoff.resolved',
  'coordination.alert'
] as const;

export type WorkspaceEventType = typeof WORKSPACE_EVENT_TYPES[number];

/** WorkspaceCoordinationStreamController.WorkspaceEventFrame：只带定位信息与短摘要，不含文件内容。 */
export interface CoordinationEventFrame {
  type: string;
  sequence: number;
  timestamp: string;
  projectId?: string | null;
  sessionId?: string | null;
  directory?: string | null;
  subject?: string | null;
  details?: Record<string, string>;
}

/** 控制帧统一是 { type, payload }；ready 带范围与序号，reset 说明游标已过期。 */
export interface WorkspaceControlFrame<T> {
  type: string;
  payload: T;
}

export interface WorkspaceReadyPayload {
  projectId: string;
  directories: string[];
  earliestSequence: number;
  latestSequence: number;
}

export interface WorkspaceResetPayload {
  earliestSequence?: number;
  latestSequence?: number;
  reason?: string;
}
