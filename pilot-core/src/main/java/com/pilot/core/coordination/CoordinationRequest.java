package com.pilot.core.coordination;

import java.time.Instant;
import java.util.Objects;

/**
 * 协作请求：一个会话请另一个会话帮忙做事。
 *
 * <p>请求只表达意图，不携带文件内容；状态变化由目标会话推进，发起方只能撤销。
 *
 * @param projectId 双方共同的项目；个人会话为 {@code null}
 * @param taskId    关联任务标识，可为空串
 */
public record CoordinationRequest(String id, String projectId, String fromSessionId, String toSessionId, String reason,
    String taskId, CoordinationRequestStatus status, Instant createdAt, Instant updatedAt, Instant expiresAt) {

    public CoordinationRequest(String id, String projectId, String fromSessionId, String toSessionId, String reason,
        String taskId, CoordinationRequestStatus status, Instant createdAt, Instant updatedAt) {
        this(id, projectId, fromSessionId, toSessionId, reason, taskId, status, createdAt, updatedAt, null);
    }

    public CoordinationRequest {
        id = requireText(id, "id");
        fromSessionId = requireText(fromSessionId, "fromSessionId");
        toSessionId = requireText(toSessionId, "toSessionId");
        reason = reason == null ? "" : reason.strip();
        taskId = taskId == null ? "" : taskId.strip();
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        expiresAt = expiresAt == null ? createdAt.plus(SessionCoordinationService.DEFAULT_REQUEST_TTL) : expiresAt;
    }

    public CoordinationRequest withStatus(CoordinationRequestStatus next, Instant at) {
        return new CoordinationRequest(id, projectId, fromSessionId, toSessionId, reason, taskId, next, createdAt, at,
            expiresAt);
    }

    public boolean expiredAt(Instant now) {
        return status.open() && !expiresAt.isAfter(now);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
