package com.pilot.server.multi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.pilot.server.config.MultiAgentBudget;

/** Thread-safe, dispatch-wide admission and settlement ledger. */
final class MultiAgentBudgetLedger implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor DEADLINES = createDeadlineExecutor();

    private final Object lock = new Object();
    private final int maxDispatchSeconds;
    private final long maxTotalTokens;
    private final long deadlineNanos;
    private final Consumer<Snapshot> listener;
    private final Set<Thread> workers = new LinkedHashSet<>();
    private final Set<Thread> requests = new LinkedHashSet<>();
    /** Lets the response that itself crossed the token cap finish its current model step. */
    private final Set<Thread> completedResponses = new LinkedHashSet<>();
    private final ScheduledFuture<?> deadlineTask;
    private long usedTokens;
    private long estimatedTokens;
    private long reservedTokens;
    private int inFlightRequests;
    private String stopReason = "";
    private String terminalStatus = "";
    private boolean cancellationRequested;

    MultiAgentBudgetLedger(MultiAgentBudget limits, Consumer<Snapshot> listener) {
        MultiAgentBudget effective = limits == null ? MultiAgentBudget.DEFAULT : limits;
        this.maxDispatchSeconds = effective.maxDispatchSeconds();
        this.maxTotalTokens = effective.maxTotalTokens();
        this.deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxDispatchSeconds);
        this.listener = listener == null ? ignored -> {
        } : listener;
        this.deadlineTask = DEADLINES.schedule(this::expire, maxDispatchSeconds, TimeUnit.SECONDS);
    }

    WorkerLease registerWorker() {
        Thread thread = Thread.currentThread();
        synchronized (lock) {
            if (stopReason.isEmpty()) {
                workers.add(thread);
            }
        }
        return new WorkerLease(this, thread);
    }

    RequestPermit beginRequest(long estimatedInputTokens) {
        Thread current = Thread.currentThread();
        long estimate = Math.max(0L, estimatedInputTokens);
        if (System.nanoTime() >= deadlineNanos) {
            expire();
        }
        List<Thread> interrupt = List.of();
        Snapshot snapshot = null;
        RequestPermit permit = null;
        synchronized (lock) {
            completedResponses.remove(current);
            if (stopReason.isEmpty() && maxTotalTokens > 0
                && saturatedAdd(saturatedAdd(usedTokens, reservedTokens), estimate) > maxTotalTokens) {
                interrupt = stopLocked("预估请求输入用量无法在剩余 token 预算内准入", current);
                snapshot = snapshotLocked();
            } else if (stopReason.isEmpty()) {
                reservedTokens = saturatedAdd(reservedTokens, estimate);
                inFlightRequests++;
                requests.add(current);
                permit = new RequestPermit(this, current, estimate);
                snapshot = snapshotLocked();
            }
        }
        interrupt(interrupt);
        publish(snapshot);
        return permit;
    }

    Settlement finishRequest(RequestPermit permit, long tokens, long estimated, boolean responseReceived) {
        List<Thread> interrupt = List.of();
        Snapshot snapshot;
        boolean stoppedBeforeSettle;
        boolean stoppedByThisRequest = false;
        synchronized (lock) {
            stoppedBeforeSettle = !stopReason.isEmpty();
            requests.remove(permit.thread);
            inFlightRequests = Math.max(0, inFlightRequests - 1);
            reservedTokens = Math.max(0L, reservedTokens - permit.reservedTokens);
            usedTokens = saturatedAdd(usedTokens, Math.max(0L, tokens));
            estimatedTokens = saturatedAdd(estimatedTokens, Math.min(Math.max(0L, estimated), Math.max(0L, tokens)));
            if (stopReason.isEmpty() && maxTotalTokens > 0 && usedTokens >= maxTotalTokens) {
                interrupt = stopLocked("派发预算耗尽：累计用量 " + usedTokens + " token 达到上限 " + maxTotalTokens, permit.thread);
                stoppedByThisRequest = true;
                if (responseReceived) {
                    completedResponses.add(permit.thread);
                }
            }
            snapshot = snapshotLocked();
        }
        interrupt(interrupt);
        publish(snapshot);
        return new Settlement(stoppedBeforeSettle, stoppedByThisRequest, snapshot.stopReason());
    }

    long remainingNanos() {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    void expireNow() {
        expire();
    }

    String reason() {
        if (System.nanoTime() >= deadlineNanos) {
            expire();
        }
        synchronized (lock) {
            return stopReason;
        }
    }

    boolean shouldCancelCurrentWorker() {
        if (System.nanoTime() >= deadlineNanos) {
            expire();
        }
        synchronized (lock) {
            if (stopReason.isEmpty()) {
                return false;
            }
            return !completedResponses.contains(Thread.currentThread());
        }
    }

    boolean shouldCancelBeforeTool() {
        return !reason().isEmpty();
    }

    Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    void complete() {
        finish("completed");
    }

    void fail() {
        finish("failed");
    }

    /** 父运行取消：停止准入并中断在途线程，终态记为停止而不是完成。 */
    void stopForCancellation() {
        List<Thread> interrupt;
        Snapshot snapshot;
        synchronized (lock) {
            interrupt = stopReason.isEmpty() ? stopLocked("父运行已取消", null) : List.of();
            terminalStatus = "stopped";
            snapshot = snapshotLocked();
        }
        interrupt(interrupt);
        publish(snapshot);
    }

    private void finish(String status) {
        Snapshot snapshot;
        synchronized (lock) {
            if (!terminalStatus.isEmpty()) {
                return;
            }
            terminalStatus = status;
            snapshot = snapshotLocked();
        }
        publish(snapshot);
    }

    private void expire() {
        List<Thread> interrupt;
        Snapshot snapshot;
        synchronized (lock) {
            if (!stopReason.isEmpty() || !terminalStatus.isEmpty()) {
                return;
            }
            interrupt = stopLocked("超过单次派发墙钟上限 " + maxDispatchSeconds + " 秒", null);
            snapshot = snapshotLocked();
        }
        interrupt(interrupt);
        publish(snapshot);
    }

    private List<Thread> stopLocked(String reason, Thread exclude) {
        if (!stopReason.isEmpty()) {
            return List.of();
        }
        stopReason = reason == null ? "预算软停止" : reason;
        LinkedHashSet<Thread> active = new LinkedHashSet<>(workers);
        active.addAll(requests);
        if (exclude != null) {
            active.remove(exclude);
        }
        cancellationRequested = !active.isEmpty();
        return new ArrayList<>(active);
    }

    private void interrupt(List<Thread> threads) {
        for (Thread thread : threads) {
            thread.interrupt();
        }
    }

    private Snapshot snapshotLocked() {
        String status = !stopReason.isEmpty() ? "stopped" : terminalStatus.isEmpty() ? "running" : terminalStatus;
        return new Snapshot(status, maxDispatchSeconds, maxTotalTokens, usedTokens, estimatedTokens, reservedTokens,
            inFlightRequests, stopReason, cancellationRequested);
    }

    private void publish(Snapshot snapshot) {
        if (snapshot != null) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ignored) {
                // A status event failure must not alter budget enforcement.
            }
        }
    }

    private void unregisterWorker(Thread thread) {
        synchronized (lock) {
            workers.remove(thread);
            completedResponses.remove(thread);
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static ScheduledThreadPoolExecutor createDeadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "pilot-multi-budget-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Override
    public void close() {
        deadlineTask.cancel(false);
        boolean needsFailure;
        synchronized (lock) {
            needsFailure = terminalStatus.isEmpty();
        }
        if (needsFailure) {
            fail();
        }
    }

    record Snapshot(String status, int maxDispatchSeconds, long maxTotalTokens, long usedTokens, long estimatedTokens,
        long reservedTokens, int inFlightRequests, String stopReason, boolean cancellationRequested) {
    }

    record Settlement(boolean stoppedBeforeSettle, boolean stoppedByThisRequest, String stopReason) {
    }

    static final class RequestPermit {
        private final MultiAgentBudgetLedger owner;
        private final Thread thread;
        private final long reservedTokens;
        private final AtomicBoolean finished = new AtomicBoolean();

        private RequestPermit(MultiAgentBudgetLedger owner, Thread thread, long reservedTokens) {
            this.owner = owner;
            this.thread = thread;
            this.reservedTokens = reservedTokens;
        }

        Settlement finish(long tokens, long estimated, boolean responseReceived) {
            return finished.compareAndSet(false, true) ? owner.finishRequest(this, tokens, estimated, responseReceived)
                : new Settlement(true, false, owner.reason());
        }
    }

    static final class WorkerLease implements AutoCloseable {
        private final MultiAgentBudgetLedger owner;
        private final Thread thread;
        private final AtomicBoolean closed = new AtomicBoolean();

        private WorkerLease(MultiAgentBudgetLedger owner, Thread thread) {
            this.owner = owner;
            this.thread = thread;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.unregisterWorker(thread);
            }
        }
    }
}
