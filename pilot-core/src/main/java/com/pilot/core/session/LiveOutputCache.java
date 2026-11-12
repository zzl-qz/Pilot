package com.pilot.core.session;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.pilot.core.event.AssistantPreview;
import com.pilot.core.event.AssistantReasoning;
import com.pilot.core.event.EventEnvelope;

/** 调用方持有会话锁；内部短锁仅保护全局缓存预算，不执行 IO。 */
final class LiveOutputCache {
    private final PreviewLimits limits;
    private final Map<String, State> states = new HashMap<>();
    private long bytes;

    private static final class State {
        final String runId;
        final String id = UUID.randomUUID().toString();

        long offset;
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();

        boolean textComplete = true;
        boolean reasoningComplete = true;
        Instant reasoningStartedAt;
        Instant reasoningFinishedAt;

        State(String runId) {
            this.runId = runId;
        }

        long bytes() {
            return 6L * (text.length() + reasoning.length());
        }
    }

    LiveOutputCache(PreviewLimits limits) {
        this.limits = limits;
    }

    synchronized void clear(String sessionId) {
        State old = states.remove(sessionId);
        if (old != null) {
            bytes -= old.bytes();
        }
    }

    synchronized void begin(String sessionId, String runId) {
        clear(sessionId);
        if (states.size() < limits.sessions()) {
            states.put(sessionId, new State(runId));
        }
    }

    /** 正文入库只清除临时文字；同一运行的思考起点不能随分段提交丢失。 */
    synchronized void commit(String sessionId, String runId, Instant timestamp) {
        State previous = states.get(sessionId);
        begin(sessionId, runId);
        State next = states.get(sessionId);
        if (previous != null && next != null && previous.runId.equals(runId)) {
            next.reasoningStartedAt = previous.reasoningStartedAt;
            next.reasoningFinishedAt = previous.reasoningFinishedAt != null ? previous.reasoningFinishedAt
                : previous.reasoningStartedAt == null ? null : timestamp;
        }
    }

    synchronized EventEnvelope update(EventEnvelope event) {
        State state = states.get(event.sessionId());
        if (state == null || !state.runId.equals(event.runId())) {
            // 未保留开头时不从中间建立完整快照；依然投递实时增量。
            return event;
        }
        boolean body = event.payload() instanceof AssistantPreview;
        boolean reset =
            body ? ((AssistantPreview)event.payload()).reset() : ((AssistantReasoning)event.payload()).reset();
        String part = body ? ((AssistantPreview)event.payload()).text() : ((AssistantReasoning)event.payload()).text();
        if (!reset && !part.isEmpty()) {
            if (!body) {
                if (state.reasoningStartedAt == null) {
                    state.reasoningStartedAt = event.timestamp();
                }
                state.reasoningFinishedAt = null;
            } else if (state.reasoningStartedAt != null && state.reasoningFinishedAt == null) {
                state.reasoningFinishedAt = event.timestamp();
            }
        }
        StringBuilder target = body ? state.text : state.reasoning;
        if (reset) {
            bytes -= 6L * target.length();
            if (body) {
                state.text = new StringBuilder();
            } else {
                state.reasoning = new StringBuilder();
            }
            if (body) {
                state.textComplete = true;
            } else {
                state.reasoningComplete = true;
            }
        } else if (body ? state.textComplete : state.reasoningComplete) {
            long available = Math.min(limits.snapshotBytes() - state.bytes(), limits.totalSnapshotBytes() - bytes);
            int length = (int)Math.min(part.length(), Math.max(0, available / 6));
            if (length > 0 && length < part.length() && Character.isHighSurrogate(part.charAt(length - 1))) {
                length--;
            }
            target.append(part, 0, length);
            bytes += length * 6L;
            if (length < part.length()) {
                if (body) {
                    state.textComplete = false;
                } else {
                    state.reasoningComplete = false;
                }
            }
        }
        return new EventEnvelope(event.sequence(), event.timestamp(), event.type(), event.sessionId(), event.runId(),
            event.payload(), event.version(), state.id, ++state.offset);
    }

    synchronized LiveOutput snapshot(String sessionId, String runId, long sequence, long revision, boolean active) {
        State state = states.get(sessionId);
        if (state == null) {
            return new LiveOutput(runId, "", 0, sequence, revision, "", "", !active, null, null);
        }
        return new LiveOutput(state.runId, state.id, state.offset, sequence, revision, state.text.toString(),
            state.reasoning.toString(), state.textComplete && state.reasoningComplete, state.reasoningStartedAt,
            state.reasoningFinishedAt);
    }

    synchronized long bytes() {
        return bytes;
    }

    synchronized int size() {
        return states.size();
    }
}
