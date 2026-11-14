package com.pilot.core.event;

import java.util.Map;

/**
 * 需要用户审批后才能继续执行的高风险工具调用。
 *
 * @param args 结构化参数（仅保留短标量值）；供前端做「批准并信任」这类次级决策，
 *             大对象与超长文本不进事件，展示仍以 {@code argsSummary} 为准
 */
public record ApprovalRequired(String callId, String name, String argsSummary,
    Map<String, Object> args) implements SessionEvent {
    public ApprovalRequired(String callId, String name, String argsSummary) {
        this(callId, name, argsSummary, null);
    }

    @Override
    public EventType type() {
        return EventType.APPROVAL_REQUIRED;
    }
}
