package com.pilot.core.coordination;

import java.time.Instant;
import java.util.Objects;

/**
 * 任务交接：把一件还没做完的事交给另一个会话，由对方决定接还是不接。
 *
 * <p>交接只转移责任，不转移写权限：接手方仍在自己工作区的权限边界内操作，
 * 原会话的工作区不会被别人直接改动。
 */
public record CoordinationHandoff(String id, String projectId, String fromSessionId, String toSessionId, String taskId,
    String summary, CoordinationHandoffStatus status, Instant createdAt, Instant updatedAt, Instant expiresAt) {

    public CoordinationHandoff {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (fromSessionId == null || fromSessionId.isBlank() || toSessionId == null || toSessionId.isBlank()) {
            throw new IllegalArgumentException("handoff participants must not be blank");
        }
        taskId = taskId == null ? "" : taskId.strip();
        summary = summary == null ? "" : summary.strip();
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public CoordinationHandoff withStatus(CoordinationHandoffStatus next, Instant at) {
        return new CoordinationHandoff(id, projectId, fromSessionId, toSessionId, taskId, summary, next, createdAt, at,
            expiresAt);
    }

    public boolean expiredAt(Instant now) {
        return status.open() && !expiresAt.isAfter(now);
    }
}
