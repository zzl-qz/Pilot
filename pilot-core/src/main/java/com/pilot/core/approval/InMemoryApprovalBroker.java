package com.pilot.core.approval;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 内存版审批注册表：callId → Future。
 * 超时通过 {@link CompletableFuture#completeOnTimeout} 实现，不需要轮询线程。
 */
public final class InMemoryApprovalBroker implements ApprovalBroker {
    private final Duration timeout;
    private final Clock clock;
    private final ConcurrentMap<String, Entry> pending = new ConcurrentHashMap<>();

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
    public CompletableFuture<Boolean> register(String callId, String toolName, String argsSummary) {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        var future = new CompletableFuture<Boolean>();
        var entry = new Entry(new PendingApproval(callId, toolName, argsSummary, clock.instant()), future);
        if (pending.putIfAbsent(callId, entry) != null) {
            throw new IllegalStateException("approval already pending: " + callId);
        }
        future.completeOnTimeout(false, timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((approved, error) -> pending.remove(callId, entry));
        return future;
    }

    @Override
    public boolean resolve(String callId, boolean approved) {
        if (callId == null || callId.isBlank()) {
            return false;
        }
        Entry entry = pending.remove(callId);
        return entry != null && entry.future().complete(approved);
    }

    @Override
    public List<PendingApproval> pending() {
        return pending.values().stream()
                .map(Entry::approval)
                .sorted(Comparator.comparing(PendingApproval::requestedAt))
                .toList();
    }

    public Duration timeout() {
        return timeout;
    }

    private record Entry(PendingApproval approval, CompletableFuture<Boolean> future) {
    }

    /** 测试用：当前时间基准，便于断言请求时间。 */
    Instant now() {
        return clock.instant();
    }
}
