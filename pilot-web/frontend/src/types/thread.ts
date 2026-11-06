import type { EventEnvelope, EventPayloads } from './events.js';
import type { MessageAttachment, ContextStatus, RequestReport, AgentSummary } from './runtime.js';
import type { ChangeFile } from './core.js';
import type * as S from './server.js';

export interface PlanItem { step: string; status: string }
export interface ToolRow { callId: string; name: string; args: string; status: string; meta: string; body: string; startedAt?: number; finishedAt?: number; agentId: string | null }
export interface AgentView extends AgentSummary { name: string; task: string; status: string; startedAt: number; finishedAt?: number; tools: ToolRow[]; result: string }
export interface RunUsage {
  runId?: string | null;
  startedAt?: number;
  finishedAt?: number | null;
  modelRequests?: number;
  inputTokens?: number;
  outputTokens?: number;
  cachedInputTokens?: number;
  contextTokens?: number;
  contextLimit?: number;
  exact?: boolean;
  reported?: boolean;
  details?: S.RequestTotalsSummary | null;
}
export interface Reply { text: string; preview?: string; live: boolean; needsBreak?: boolean; breakReason?: string; committed?: string }
export interface Thinking { text: string; live: boolean; action: string; startedAt: number; finishedAt?: number | null }
interface RowBase { key: string; start: number; end: number; text?: string; detail?: string | null }
export interface UserRow extends RowBase { type: 'user'; sequence: number | null; text: string; attachments: MessageAttachment[]; imagePositions: number[]; steered?: boolean }
export interface TurnRow extends RowBase { type: 'turn'; runIds: string[]; tools: ToolRow[]; toolIndex: Record<string, ToolRow>; thinking: Thinking | null; reply: Reply; stats: RunUsage | null; agents: Record<string, AgentView>; requests?: RequestReport[] }
export interface ResultRow extends RowBase { type: 'result'; tone: string; title: string; detail: string | null; changedFiles: ChangeFile[]; changedSummary?: string | null; runIds: string[]; resumable?: boolean; errorCode?: string | null; continued?: boolean }
export interface CompactionRow extends RowBase { type: 'compaction'; coveredMessages: number; charactersBefore: number; charactersAfter: number; summary: string }
export interface PlanRow extends RowBase { type: 'plan'; items: PlanItem[] }
export interface SandboxRow extends RowBase { type: 'sandbox'; sandboxId: string; title: string; detail: string }
export interface ArbitrationRow extends RowBase { type: 'arb'; arbitrationId: string; title: string; detail: string; options: string[]; resolved: boolean; resolution?: string | null }
export type ThreadRow = UserRow | TurnRow | ResultRow | CompactionRow | PlanRow | SandboxRow | ArbitrationRow;
export interface ThreadHistory { hasMore: boolean; hasNewer: boolean; loading: boolean; forwardError: boolean; error: boolean; pageSize: number; loadId: number }
export interface ThreadState { rows: ThreadRow[]; windowEvents: EventEnvelope[]; cursor: number; replaying: boolean; history: ThreadHistory; activityCount: number }
export interface TimelineInput { kind: string; tone: string; status: string; title?: string | null; detail?: string | null; foot?: string | null; actions?: boolean; callId?: string | null; dispatchId?: string | null }
export interface TimelineItem extends TimelineInput { key: string; sequence: number; type: string; timestamp?: string | number; _stream?: string }
export interface PendingApproval { callId: string; agent: string | null; name: string; args: string; busy?: boolean }
export interface Notice { text: string; at: number }
export type TaskView = Partial<S.TaskControlServiceTaskContract> & Partial<S.TaskUpdated>;
/** reducer 在历史重放时也使用这组最小状态，不能依赖 Store 的 UI 或网络动作。 */
export interface ReducerState {
  thread: ThreadState;
  ui: { attentionKind?: string; notice: Notice | null; browserDot?: boolean };
  running?: boolean;
  cancelling?: boolean;
  runStartedAt?: number;
  runId?: string | null;
  turnRuns?: string[];
  runUsage?: RunUsage | null;
  requestReports?: RequestReport[];
  latestContextReport?: RequestReport | null;
  liveOutputTokens?: number;
  modelRetryUntil?: number;
  lastOutcome?: string;
  queueHeld?: boolean;
  pendingInputs: string[];
  queue: string[];
  pendingApprovals: Record<string, PendingApproval>;
  pendingArbitrations: Record<string, EventPayloads['arbitration.required']>;
  agents: Record<string, AgentView>;
  task?: TaskView | null;
  planItems?: PlanItem[];
  contextStatus?: ContextStatus | null;
  streamCard?: string | null;
  timeline: TimelineItem[];
  promoteBlank?: { input: string } | null;
  runChangesFetch?: string | null;
}
