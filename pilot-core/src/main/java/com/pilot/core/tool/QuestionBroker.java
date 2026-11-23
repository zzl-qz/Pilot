package com.pilot.core.tool;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 点选问题的等待表。工具线程阻塞，界面提交选项或自行填写的文字后唤醒。 */
public final class QuestionBroker {
    private final Clock clock;
    private final ConcurrentMap<String, Entry> waiting = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public QuestionBroker() {
        this(Clock.systemUTC());
    }

    public QuestionBroker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public CompletableFuture<String> register(String callId) {
        return register(callId, "", "", List.of(), null);
    }

    public CompletableFuture<String> register(String callId, String sessionId, String question, List<String> options,
        Duration timeout) {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        String cleanCallId = callId.strip();
        String cleanSessionId = sessionId == null ? "" : sessionId.strip();
        String cleanQuestion = question == null ? "" : question.strip();
        List<String> cleanOptions = options == null ? List.of()
            : options.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isBlank()).toList();
        Instant requestedAt = clock.instant();
        Instant expiresAt = timeout != null && !timeout.isNegative() && !timeout.isZero()
            ? requestedAt.plus(timeout) : null;
        CompletableFuture<String> future = new CompletableFuture<>();
        Entry entry = new Entry(
            new PendingQuestion(cleanCallId, cleanSessionId, cleanQuestion, cleanOptions, requestedAt, expiresAt),
            future);
        if (waiting.putIfAbsent(cleanCallId, entry) != null) {
            throw new IllegalStateException("问题已经在等待：" + cleanCallId);
        }
        future.whenComplete((ignored, error) -> {
            if (waiting.remove(cleanCallId, entry)) {
                notifyListeners();
            }
        });
        notifyListeners();
        return future;
    }

    public boolean answer(String callId, String choice) {
        return completeAnswer(null, callId, choice, false);
    }

    public boolean answer(String sessionId, String callId, String choice) {
        return completeAnswer(sessionId, callId, choice, true);
    }

    private boolean completeAnswer(String sessionId, String callId, String choice, boolean checkSession) {
        if (callId == null || callId.isBlank() || choice == null || choice.isBlank()) {
            return false;
        }
        String cleanCallId = callId.strip();
        Entry entry = waiting.get(cleanCallId);
        if (entry == null) {
            return false;
        }
        if (checkSession) {
            String owner = entry.question().sessionId();
            String candidate = sessionId == null ? "" : sessionId.strip();
            if (!owner.isEmpty() && !owner.equals(candidate)) {
                return false;
            }
        }
        if (!waiting.remove(cleanCallId, entry)) {
            return false;
        }
        boolean completed = entry.future().complete(choice.strip());
        notifyListeners();
        return completed;
    }

    public boolean cancel(String callId) {
        if (callId == null || callId.isBlank()) {
            return false;
        }
        Entry entry = waiting.remove(callId.strip());
        if (entry == null) {
            return false;
        }
        entry.future().complete(null);
        notifyListeners();
        return true;
    }

    public List<PendingQuestion> pending() {
        return waiting.values().stream().map(Entry::question)
            .sorted(Comparator.comparing(PendingQuestion::requestedAt)).toList();
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Runnable listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // 广播监听异常不能影响提问状态流转
            }
        }
    }

    public record PendingQuestion(String callId, String sessionId, String question, List<String> options,
        Instant requestedAt, Instant expiresAt) {
    }

    private record Entry(PendingQuestion question, CompletableFuture<String> future) {
    }
}
