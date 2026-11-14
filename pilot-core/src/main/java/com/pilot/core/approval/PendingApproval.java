package com.pilot.core.approval;

import java.time.Instant;
import java.util.Map;

/**
 * 等待用户决策的工具调用。用于展示待审批列表与排查超时。
 *
 * @param args 结构化参数（仅短标量值），同 ApprovalRequired 事件的说明
 */
public record PendingApproval(String callId, String toolName, String argsSummary, Map<String, Object> args,
    Instant requestedAt) {
    public PendingApproval(String callId, String toolName, String argsSummary, Instant requestedAt) {
        this(callId, toolName, argsSummary, null, requestedAt);
    }
}
