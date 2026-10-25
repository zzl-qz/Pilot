package com.pilot.core.event;

import java.time.Instant;
import java.util.Objects;

/**
 * 事件信封：所有 SSE 推送和事件存储的单元。
 *
 * @param sequence  会话内单调递增序号，从 1 开始；客户端按序号排序，不依赖到达顺序
 * @param timestamp 事件产生时间（UTC）
 * @param type      事件类型，取值为 {@link EventType#wireName()}，例如 {@code run.started}
 * @param sessionId 会话标识
 * @param runId     本次运行标识
 * @param payload   事件负载
 * @param version   事件信封协议版本，当前为 1
 */
public record EventEnvelope(
        long sequence,
        Instant timestamp,
        String type,
        String sessionId,
        String runId,
        SessionEvent payload,
        int version) {

    public EventEnvelope(long sequence, Instant timestamp, String type, String sessionId, String runId,
                         SessionEvent payload) {
        this(sequence, timestamp, type, sessionId, runId, payload, 1);
    }

    public EventEnvelope {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must start at 1, but was " + sequence);
        }
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(payload, "payload must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive, but was " + version);
        }
        if (!type.equals(payload.type().wireName())) {
            throw new IllegalArgumentException("type must match payload event type");
        }
    }

    public static EventEnvelope of(long sequence, Instant timestamp, String sessionId, String runId, SessionEvent payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return new EventEnvelope(sequence, timestamp, payload.type().wireName(), sessionId, runId, payload, 1);
    }
}
