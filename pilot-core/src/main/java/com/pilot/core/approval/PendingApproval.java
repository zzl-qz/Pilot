package com.pilot.core.approval;

import java.time.Instant;

/** 等待用户决策的工具调用。用于展示待审批列表与排查超时。 */
public record PendingApproval(String callId, String toolName, String argsSummary, Instant requestedAt) {
}
