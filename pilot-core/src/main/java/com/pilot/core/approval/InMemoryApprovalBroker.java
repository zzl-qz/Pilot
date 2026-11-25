package com.pilot.core.approval;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 内存版审批注册表：callId → Future。
 * 超时通过 {@link CompletableFuture#completeOnTimeout} 实现，不需要轮询线程。
 */
public final class InMemoryApprovalBroker implements ApprovalBroker {
    private static final int MAX_REASON_LENGTH = 500;

    private final Duration timeout;
    private final Clock clock;
    private final ConcurrentMap<String, Entry> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> rejectionReasons = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public InMemoryApprovalBroker(Duration timeout) {
        this(timeout, Clock.systemUTC());
    }

    public InMemoryApprovalBroker(Duration timeout, Clock clock) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public CompletableFuture<Boolean> register(String callId, String toolName, String argsSummary,
        Map<String, Object> args) {
        return register("", callId, toolName, argsSummary, args);
    }

    @Override
    public CompletableFuture<Boolean> register(String sessionId, String callId, String toolName, String argsSummary,
        Map<String, Object> args) {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        Instant requestedAt = clock.instant();
        Instant expiresAt = requestedAt.plus(timeout);
        CompletableFuture<Boolean> future = new CompletableFuture<Boolean>();
        Entry entry = new Entry(
            new PendingApproval(callId, toolName, argsSummary, args, requestedAt, sessionId, expiresAt), future);
        if (pending.putIfAbsent(callId, entry) != null) {
            throw new IllegalStateException("approval already pending: " + callId);
        }
        future.completeOnTimeout(false, timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((approved, error) -> {
            if (pending.remove(callId, entry)) {
                rejectionReasons.remove(callId);
                notifyListeners();
            }
        });
        notifyListeners();
        return future;
    }

    @Override
    public boolean resolve(String callId, boolean approved) {
        return resolve(callId, approved, "");
    }

    @Override
    public boolean resolve(String callId, boolean approved, String reason) {
        if (callId == null || callId.isBlank()) {
            return false;
        }
        Entry entry = pending.remove(callId);
        if (entry == null) {
            return false;
        }
        String cleanReason = cleanReason(reason);
        if (!approved && !cleanReason.isEmpty()) {
            // 先写入理由再唤醒阻塞线程，保证 takeRejectionReason 读到完整内容
            rejectionReasons.put(callId, cleanReason);
        }
        boolean resolved = entry.future().complete(approved);
        if (!resolved) {
            rejectionReasons.remove(callId);
        }
        notifyListeners();
        return resolved;
    }

    @Override
    public String takeRejectionReason(String callId) {
        if (callId == null || callId.isBlank()) {
            return "";
        }
        String reason = rejectionReasons.remove(callId);
        return reason == null ? "" : reason;
    }

    private static String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        String trimmed = reason.strip();
        return trimmed.length() > MAX_REASON_LENGTH ? trimmed.substring(0, MAX_REASON_LENGTH) : trimmed;
    }

    @Override
    public List<PendingApproval> pending() {
        return pending.values().stream().map(Entry::approval).sorted(Comparator.comparing(PendingApproval::requestedAt))
            .toList();
    }

    public Duration timeout() {
        return timeout;
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
                // 广播监听异常不能影响审批决策
            }
        }
    }

    private record Entry(PendingApproval approval, CompletableFuture<Boolean> future) {
    }

    /** 测试用：当前时间基准，便于断言请求时间。 */
    Instant now() {
        return clock.instant();
    }
}
