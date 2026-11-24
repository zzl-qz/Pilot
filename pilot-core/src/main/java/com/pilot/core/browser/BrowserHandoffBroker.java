package com.pilot.core.browser;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 浏览器接管等待协议：当智能体遇到登录、MFA、验证码或支付门控时挂起并等待用户完成，
 * 用户在界面点击「我已完成，交回智能体」或取消后立即唤醒对应的阻塞调用。
 */
public final class BrowserHandoffBroker {
    public enum EventKind {
        REQUESTED, RESUMED, CANCELLED
    }

    public record PendingHandoff(String handoffId, String sessionId, String browserSessionId, String agentId,
        String kind, String reason, String url, Instant requestedAt, Instant expiresAt) {
        public PendingHandoff {
            handoffId = clean(handoffId);
            sessionId = clean(sessionId);
            browserSessionId = clean(browserSessionId);
            agentId = clean(agentId);
            kind = clean(kind).isEmpty() ? "manual" : clean(kind);
            reason = clean(reason);
            url = clean(url);
            requestedAt = requestedAt == null ? Instant.now() : requestedAt;
        }
    }

    public record Resolution(boolean completed, String note) {
        public Resolution {
            note = clean(note);
        }
    }

    public record PendingHandoffEvent(EventKind eventKind, PendingHandoff handoff, String note) {
    }

    private final Clock clock;
    private final ConcurrentMap<String, Entry> waiting = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<PendingHandoffEvent>> listeners = new CopyOnWriteArrayList<>();

    public BrowserHandoffBroker() {
        this(Clock.systemUTC());
    }

    public BrowserHandoffBroker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public CompletableFuture<Resolution> request(PendingHandoff handoff, Duration timeout) {
        if (handoff == null || handoff.browserSessionId().isBlank()) {
            throw new IllegalArgumentException("browserSessionId must not be blank");
        }
        String key = handoff.browserSessionId();
        Instant requestedAt = handoff.requestedAt() != null ? handoff.requestedAt() : clock.instant();
        Instant expiresAt = handoff.expiresAt();
        if (expiresAt == null && timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            expiresAt = requestedAt.plus(timeout);
        }
        PendingHandoff normalized = new PendingHandoff(handoff.handoffId(), handoff.sessionId(), key, handoff.agentId(),
            handoff.kind(), handoff.reason(), handoff.url(), requestedAt, expiresAt);
        CompletableFuture<Resolution> future = new CompletableFuture<>();
        Entry entry = new Entry(normalized, future);
        Entry previous = waiting.put(key, entry);
        if (previous != null) {
            previous.future().complete(new Resolution(false, "被新的接管请求替代"));
        }
        future.whenComplete((ignored, error) -> {
            if (waiting.remove(key, entry)) {
                notifyListeners(new PendingHandoffEvent(EventKind.CANCELLED, normalized, ""));
            }
        });
        notifyListeners(new PendingHandoffEvent(EventKind.REQUESTED, normalized, ""));
        return future;
    }

    public boolean resume(String sessionOrBrowserSessionId, String note) {
        return resolve(sessionOrBrowserSessionId, true, note);
    }

    public boolean cancel(String sessionOrBrowserSessionId, String reason) {
        return resolve(sessionOrBrowserSessionId, false, reason);
    }

    public int cancelBySession(String sessionId, String reason) {
        String key = clean(sessionId);
        if (key.isEmpty()) {
            return 0;
        }
        int cancelled = 0;
        for (Entry entry : List.copyOf(waiting.values())) {
            PendingHandoff handoff = entry.handoff();
            if (key.equals(handoff.sessionId()) || key.equals(handoff.browserSessionId())) {
                if (waiting.remove(handoff.browserSessionId(), entry)) {
                    String text = clean(reason);
                    entry.future().complete(new Resolution(false, text));
                    notifyListeners(new PendingHandoffEvent(EventKind.CANCELLED, handoff, text));
                    cancelled++;
                }
            }
        }
        return cancelled;
    }

    public List<PendingHandoff> pending(String sessionId) {
        String filter = clean(sessionId);
        return waiting.values().stream().map(Entry::handoff)
            .filter(
                item -> filter.isEmpty() || filter.equals(item.sessionId()) || filter.equals(item.browserSessionId()))
            .sorted(Comparator.comparing(PendingHandoff::requestedAt)).toList();
    }

    public Optional<PendingHandoff> pendingForBrowser(String browserSessionId) {
        String key = clean(browserSessionId);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        Entry entry = waiting.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.handoff());
    }

    public void addListener(Consumer<PendingHandoffEvent> listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Consumer<PendingHandoffEvent> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private boolean resolve(String sessionOrBrowserSessionId, boolean completed, String note) {
        String target = clean(sessionOrBrowserSessionId);
        if (target.isEmpty()) {
            return false;
        }
        Entry entry = waiting.get(target);
        if (entry == null) {
            entry = waiting.values().stream().filter(item -> target.equals(item.handoff().sessionId()))
                .min(Comparator.comparing(item -> item.handoff().requestedAt())).orElse(null);
        }
        if (entry == null || !waiting.remove(entry.handoff().browserSessionId(), entry)) {
            return false;
        }
        String text = clean(note);
        boolean done = entry.future().complete(new Resolution(completed, text));
        notifyListeners(
            new PendingHandoffEvent(completed ? EventKind.RESUMED : EventKind.CANCELLED, entry.handoff(), text));
        return done;
    }

    private void notifyListeners(PendingHandoffEvent event) {
        for (Consumer<PendingHandoffEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // 接管事件分发异常不能阻塞唤醒主路径
            }
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private record Entry(PendingHandoff handoff, CompletableFuture<Resolution> future) {
    }
}
