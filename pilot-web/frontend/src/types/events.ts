import type * as S from './server.js';
import type { MessageAttachment } from './runtime.js';

/** 与 EventType 的线上名称、SessionEvent 的 record 载荷一一对应；额外字段兼容旧记录。 */
export interface EventPayloads {
  'approval.required': S.ApprovalRequired & { agentId?: string };
  'approval.resolved': S.ApprovalResolved;
  'arbitration.required': S.ArbitrationRequired & { title?: string; summary?: string };
  'arbitration.resolved': S.ArbitrationResolved & { decision?: string };
  'assistant.delta': S.AssistantDelta;
  'assistant.preview': S.AssistantPreview;
  'assistant.reasoning': S.AssistantReasoning;
  'context.compacted': S.ContextCompacted;
  'execution.updated': S.ExecutionUpdated;
  'model.request': S.ModelRequestCompleted;
  'multi.budget': S.MultiAgentBudgetUpdated;
  'plan.updated': S.PlanUpdated;
  'run.cancelled': S.RunCancelled;
  'run.completed': S.RunCompleted;
  'run.continued': S.RunContinued;
  'run.failed': S.RunFailed & { error?: string; reason?: string };
  'run.retrying': S.RunRetrying & { until?: number };
  'run.started': S.RunStarted;
  'run.steered': S.RunSteered & { message?: string };
  'run.usage': S.RunUsageUpdated;
  'sandbox.diff': S.SandboxDiffReady & { title?: string; summary?: string; diff?: string };
  'subagent.finished': S.SubagentFinished & { id?: string; result?: string };
  'subagent.started': S.SubagentStarted & { id?: string };
  'task.updated': S.TaskUpdated;
  'tool.completed': S.ToolCompleted;
  'tool.started': S.ToolStarted;
  'user.message': { input?: string; text?: string; attachments?: MessageAttachment[]; imagePositions?: number[] };
}
export interface LiveOutput { runId: string; previewId: string; previewOffset: number; baseSequence: number; historyRevision: number; text: string; reasoning: string; complete: boolean; reasoningStartedAt?: string | null; reasoningFinishedAt?: string | null }
export interface EventMetadata { previewId?: string | null; previewOffset?: number | null; sequence: number; timestamp?: string | number; sessionId?: string; runId?: string | null }
export type EventEnvelope = { [K in keyof EventPayloads]: EventMetadata & { type: K; payload: EventPayloads[K] } }[keyof EventPayloads];
