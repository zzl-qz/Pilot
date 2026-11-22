package com.pilot.core.session;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.pilot.core.event.EventType;

/** Merges transient session output while preserving the first event's sequence and arrival time. */
public final class SessionPreviewAggregator {
    public record PreviewInput(String sessionId, String runId, EventType type, String text, boolean reset,
        Instant timestamp) {
    }

    public record PreviewOutput(String sessionId, String runId, EventType type, String text, boolean reset,
        long sequence, Instant timestamp) {
    }

    public record Update(boolean accepted, int pendingSessions) {
    }

    private static final class Pending {
        final String runId;
        final EventType type;
        final long sequence;
        final Instant timestamp;
        final StringBuilder text = new StringBuilder();
        int bytes;
        ScheduledFuture<?> timer;

        Pending(PreviewInput input, long sequence) {
            runId = input.runId();
            type = input.type();
            this.sequence = sequence;
            timestamp = input.timestamp();
        }
    }

    private final PreviewLimits limits;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger pendingSessions = new AtomicInteger();

    public SessionPreviewAggregator(PreviewLimits limits, ScheduledExecutorService scheduler) {
        this.limits = Objects.requireNonNull(limits);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    public Update accept(PreviewInput input, LongSupplier sequenceWithoutPending, Consumer<PreviewOutput> output,
        Consumer<Runnable> timerDispatch) {
        Objects.requireNonNull(input);
        Objects.requireNonNull(sequenceWithoutPending);
        Objects.requireNonNull(output);
        Objects.requireNonNull(timerDispatch);
        Pending current = pending.get(input.sessionId());
        if (current != null && !current.runId.equals(input.runId())) {
            return new Update(false, pendingSessions.get());
        }
        if (current != null && (current.type != input.type() || input.reset())) {
            finish(input.sessionId(), output, false);
            current = null;
        }
        long sequence = current == null ? sequenceWithoutPending.getAsLong() : current.sequence;
        if (sequence < 1) {
            return new Update(false, pendingSessions.get());
        }
        if (input.reset()) {
            output.accept(new PreviewOutput(input.sessionId(), input.runId(), input.type(), input.text(), true,
                sequence, input.timestamp()));
            return new Update(true, pendingSessions.get());
        }
        for (int i = 0; i < input.text().length();) {
            int point = input.text().codePointAt(i);
            int size = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
            if (current != null && current.bytes + size > limits.mergeBytes()) {
                finish(input.sessionId(), output, false);
                current = null;
            }
            if (current == null) {
                if (pendingSessions.incrementAndGet() > limits.sessions()) {
                    pendingSessions.decrementAndGet();
                    output.accept(new PreviewOutput(input.sessionId(), input.runId(), input.type(),
                        input.text().substring(i), false, sequence, input.timestamp()));
                    return new Update(true, pendingSessions.get());
                }
                current = new Pending(input, sequence);
                pending.put(input.sessionId(), current);
                Pending expected = current;
                current.timer = scheduler.schedule(() -> timerDispatch.accept(
                    () -> finishIfCurrent(input.sessionId(), expected, output)), limits.mergeMillis(),
                    TimeUnit.MILLISECONDS);
            }
            current.text.appendCodePoint(point);
            current.bytes += size;
            i += Character.charCount(point);
            if (current.bytes >= limits.mergeBytes()) {
                finish(input.sessionId(), output, false);
                current = null;
            }
        }
        return new Update(true, pendingSessions.get());
    }

    public int finish(String sessionId, Consumer<PreviewOutput> output, boolean discard) {
        Pending value = pending.remove(sessionId);
        if (value != null) {
            pendingSessions.decrementAndGet();
            if (value.timer != null) {
                value.timer.cancel(false);
            }
            if (!discard) {
                output.accept(new PreviewOutput(sessionId, value.runId, value.type, value.text.toString(), false,
                    value.sequence, value.timestamp));
            }
        }
        return pendingSessions.get();
    }

    private void finishIfCurrent(String sessionId, Pending expected, Consumer<PreviewOutput> output) {
        if (pending.remove(sessionId, expected)) {
            pendingSessions.decrementAndGet();
            output.accept(new PreviewOutput(sessionId, expected.runId, expected.type, expected.text.toString(), false,
                expected.sequence, expected.timestamp));
        }
    }

    public int pendingSessions() {
        return pendingSessions.get();
    }
}
