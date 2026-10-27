package com.pilot.core.event;

/**
 * 会话事件负载。实现类只描述负载字段，事件类型由 {@link #type()} 声明，
 * 信封上的 {@code type} 字段由该枚举的线上名称决定。
 */
public sealed interface SessionEvent
        permits RunStarted, AssistantDelta, AssistantPreview, ContextCompacted, PlanUpdated, RunSteered, RunContinued,
                RunRetrying,
                SubagentStarted, SubagentFinished, ToolStarted, ApprovalRequired, ApprovalResolved,
                ToolCompleted, RunFailed, RunCompleted, RunCancelled,
                TaskUpdated, SandboxDiffReady, ArbitrationRequired, ArbitrationResolved {

    EventType type();
}
