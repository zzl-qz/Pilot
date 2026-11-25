package com.pilot.core.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class InMemoryApprovalBrokerTest {
    @Test
    void tracksSessionIdExpiryAndListeners() throws Exception {
        Instant fixed = Instant.parse("2026-11-23T12:00:00Z");
        InMemoryApprovalBroker broker =
            new InMemoryApprovalBroker(Duration.ofSeconds(300), Clock.fixed(fixed, ZoneOffset.UTC));
        AtomicInteger events = new AtomicInteger();
        broker.addListener(events::incrementAndGet);

        ApprovalBroker sessionScoped = ApprovalBroker.forSession(broker, "ses_123");
        CompletableFuture<Boolean> decision =
            sessionScoped.register("call_1", "run_command", "rm -rf build", Map.of("command", "rm -rf build"));

        assertEquals(1, broker.pending().size());
        PendingApproval pending = broker.pending().getFirst();
        assertEquals("ses_123", pending.sessionId());
        assertEquals(fixed, pending.requestedAt());
        assertEquals(fixed.plusSeconds(300), pending.expiresAt());
        assertEquals(1, events.get());

        assertTrue(sessionScoped.resolve("call_1", true));
        assertTrue(decision.get());
        assertTrue(broker.pending().isEmpty());
        assertEquals(2, events.get());
    }

    @Test
    void notifiesListenersOnTimeout() throws Exception {
        InMemoryApprovalBroker broker = new InMemoryApprovalBroker(Duration.ofMillis(40));
        AtomicInteger events = new AtomicInteger();
        broker.addListener(events::incrementAndGet);

        CompletableFuture<Boolean> decision = broker.register("ses_t", "call_t", "edit_file", "foo.txt", Map.of());
        assertFalse(decision.get());
        // whenComplete 可能略微滞后于 get() 返回，等事件完成
        for (int i = 0; i < 20 && events.get() < 2; i++) {
            Thread.sleep(10);
        }
        assertTrue(broker.pending().isEmpty());
        assertEquals(2, events.get());
    }

    @Test
    void storesAndConsumesRejectionReasonWithoutLeaking() throws Exception {
        InMemoryApprovalBroker broker = new InMemoryApprovalBroker(Duration.ofSeconds(60));
        ApprovalBroker scoped = ApprovalBroker.forSession(broker, "ses_r");
        CompletableFuture<Boolean> decision = scoped.register("call_r", "run_command", "npm install", Map.of());

        assertTrue(scoped.resolve("call_r", false, "  请改用 pnpm，不要直接跑 npm  "));
        assertFalse(decision.get());
        assertEquals("请改用 pnpm，不要直接跑 npm", scoped.takeRejectionReason("call_r"));
        // 消费一次后立即清空
        assertEquals("", scoped.takeRejectionReason("call_r"));
    }
}
