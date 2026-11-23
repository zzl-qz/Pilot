package com.pilot.server.interaction;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.approval.InMemoryApprovalBroker;
import com.pilot.core.approval.PendingApproval;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.QuestionBroker;
import com.pilot.core.tool.QuestionBroker.PendingQuestion;

import tools.jackson.databind.ObjectMapper;

/**
 * 全局交互中心：实时汇集所有会话的待审批、待选择与运行注意力信号（完成/失败/需介入），
 * 通过全局 SSE 推向工作台与桌面独立提醒，解决切换会话或切出窗口后看不到等待事项的问题。
 */
@Service
public class InteractionHub {
    private static final long HEARTBEAT_SECONDS = 25;

    private final ApprovalBroker approvals;
    private final QuestionBroker questions;
    private final SessionService sessions;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<SseEmitter, Object> subscribers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat;
    private final Runnable brokerListener = this::broadcastSnapshot;

    public InteractionHub(ApprovalBroker approvals, QuestionBroker questions, SessionService sessions,
        ObjectMapper objectMapper) {
        this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
        this.questions = Objects.requireNonNull(questions, "questions must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        if (approvals instanceof InMemoryApprovalBroker memory) {
            memory.addListener(brokerListener);
        }
        questions.addListener(brokerListener);
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pilot-interactions-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeat.scheduleAtFixedRate(this::sendHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    public PendingSnapshot snapshot() {
        List<PendingApproval> rawApprovals = approvals.pending();
        List<PendingQuestion> rawQuestions = questions.pending();
        Map<String, String> titles = new HashMap<>();
        List<PendingApprovalView> approvalViews = rawApprovals.stream().map(item -> {
            String sid = clean(item.sessionId());
            return new PendingApprovalView(item.callId(), sid, sessionTitle(sid, titles), item.toolName(),
                item.argsSummary(), item.args(), item.requestedAt(), item.expiresAt());
        }).toList();
        List<PendingQuestionView> questionViews = rawQuestions.stream().map(item -> {
            String sid = clean(item.sessionId());
            return new PendingQuestionView(item.callId(), sid, sessionTitle(sid, titles), item.question(),
                item.options(), item.requestedAt(), item.expiresAt());
        }).toList();
        return new PendingSnapshot(approvalViews, questionViews);
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        Object lock = new Object();
        subscribers.put(emitter, lock);
        emitter.onCompletion(() -> subscribers.remove(emitter, lock));
        emitter.onTimeout(() -> drop(emitter, lock));
        emitter.onError(ignored -> drop(emitter, lock));
        sendTo(emitter, lock, "interactions.snapshot", toJson(snapshot()));
        return emitter;
    }

    public void broadcastSnapshot() {
        if (subscribers.isEmpty()) {
            return;
        }
        sendAll("interactions.snapshot", toJson(snapshot()));
    }

    public void publishRunAttention(String sessionId, String runId, String kind, String outcome, String title,
        String summary) {
        if (subscribers.isEmpty()) {
            return;
        }
        String sid = clean(sessionId);
        String resolvedTitle = title != null && !title.isBlank() ? title.strip()
            : sid.isEmpty() ? "当前对话" : sessions.find(sid).map(Session::title).orElse("当前对话");
        RunAttentionEvent payload = new RunAttentionEvent(sid, clean(runId), clean(kind), clean(outcome),
            resolvedTitle, summary == null ? "" : summary.strip(), Instant.now());
        sendAll("run.attention", toJson(payload));
    }

    String sessionTitle(String sessionId) {
        return sessionTitle(sessionId, new HashMap<>());
    }

    private String sessionTitle(String sessionId, Map<String, String> cache) {
        if (sessionId.isEmpty()) {
            return "";
        }
        return cache.computeIfAbsent(sessionId, id -> sessions.find(id).map(Session::title).orElse("当前对话"));
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private String toJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private void sendAll(String name, String data) {
        for (Map.Entry<SseEmitter, Object> entry : subscribers.entrySet()) {
            sendTo(entry.getKey(), entry.getValue(), name, data);
        }
    }

    private void sendTo(SseEmitter emitter, Object lock, String name, String data) {
        synchronized (lock) {
            if (!subscribers.containsKey(emitter)) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
            } catch (IOException | IllegalStateException error) {
                subscribers.remove(emitter, lock);
            }
        }
    }

    private void sendHeartbeat() {
        for (Map.Entry<SseEmitter, Object> entry : subscribers.entrySet()) {
            SseEmitter emitter = entry.getKey();
            Object lock = entry.getValue();
            synchronized (lock) {
                if (!subscribers.containsKey(emitter)) {
                    continue;
                }
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException error) {
                    subscribers.remove(emitter, lock);
                }
            }
        }
    }

    private void drop(SseEmitter emitter, Object lock) {
        subscribers.remove(emitter, lock);
    }

    @EventListener(ContextClosedEvent.class)
    @PreDestroy
    public void shutdown() {
        if (approvals instanceof InMemoryApprovalBroker memory) {
            memory.removeListener(brokerListener);
        }
        questions.removeListener(brokerListener);
        heartbeat.shutdownNow();
        for (Map.Entry<SseEmitter, Object> entry : subscribers.entrySet()) {
            SseEmitter emitter = entry.getKey();
            subscribers.remove(emitter, entry.getValue());
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // 已断开的连接无需重复结束
            }
        }
    }

    public record PendingApprovalView(String callId, String sessionId, String sessionTitle, String toolName,
        String argsSummary, Map<String, Object> structuredArgs, Instant requestedAt, Instant expiresAt) {
    }

    public record PendingQuestionView(String callId, String sessionId, String sessionTitle, String question,
        List<String> options, Instant requestedAt, Instant expiresAt) {
    }

    public record PendingSnapshot(List<PendingApprovalView> approvals, List<PendingQuestionView> questions) {
    }

    public record RunAttentionEvent(String sessionId, String runId, String kind, String outcome, String title,
        String summary, Instant at) {
    }
}
