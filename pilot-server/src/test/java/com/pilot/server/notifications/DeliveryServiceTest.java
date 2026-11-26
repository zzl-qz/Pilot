package com.pilot.server.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.server.results.ResultInboxService;
import com.pilot.server.store.AutomationStore;
import com.pilot.server.store.SqliteStore;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

@com.pilot.server.WindowsJdkLoopbackSensitive
class DeliveryServiceTest {
    @TempDir
    Path root;
    final MutableClock clock = new MutableClock();
    ResultInboxService results;
    AutomationStore store;
    DeliveryService service;
    AtomicInteger status = new AtomicInteger(503);
    List<String> keys = new ArrayList<>();

    @BeforeEach
    void setup() {
        store = new AutomationStore(new SqliteStore(root.resolve("outbox.db")));
        results = mock(ResultInboxService.class);
        when(results.deliveryItems(any(Instant.class))).thenReturn(List.of());
        service = make(store);
    }

    private static final DeliveryService.HostResolver TEST_RESOLVER = host -> switch (host) {
        case "example.test", "example.com", "hooks.example.com" ->
            new InetAddress[] {InetAddress.getByName("93.184.216.34")};
        case "rebind.example", "localhost.attacker.example" -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
        case "ula.example" -> new InetAddress[] {InetAddress.getByName("fd00::1")};
        case "cgnat.example" -> new InetAddress[] {InetAddress.getByName("100.64.0.1")};
        default -> {
            if (host.matches("[0-9.]+") || host.contains(":")) {
                yield new InetAddress[] {InetAddress.getByName(host)};
            }
            throw new UnknownHostException(host);
        }
    };

    DeliveryService make(AutomationStore store) {
        return new DeliveryService(store, JsonMapper.builder().build(), results, clock, (target, id, payload) -> {
            keys.add(id);
            return status.get();
        }, TEST_RESOLVER);
    }

    @AfterEach
    void close() {
        service.close();
    }

    ResultInboxService.Item item(Instant at) {
        return new ResultInboxService.Item("run-a", "v1", "session", "维护完成", "CHAT", "COMPLETED", "测试通过", at, "run-a",
            null, false, false, List.of(), List.of(), List.of(), null);
    }

    @Test
    void persistentOutboxDeduplicatesAndRetriesOnlyDeliveryWithStableKey() {
        service.configure(0, true, "https://example.com/hook");
        clock.at = clock.at.plusSeconds(1);
        when(results.deliveryItems(any(Instant.class))).thenReturn(List.of(item(clock.instant())));
        service.collect();
        service.collect();
        assertEquals(2, service.list().size());
        var first = service.claim(DeliveryChannel.WEBHOOK);
        assertNull(service.claim(DeliveryChannel.WEBHOOK));
        service.send(first);
        assertEquals("RETRY", service.get(first.id()).state());
        var persisted = new AutomationStore(new SqliteStore(root.resolve("outbox.db")));
        service.close();
        service = make(persisted);
        clock.at = service.get(first.id()).nextAt();
        var second = service.claim(DeliveryChannel.WEBHOOK);
        assertNotEquals(first.lease(), second.lease());
        assertThrows(IllegalStateException.class,
            () -> service.receipt(first.id(), new DeliveryService.Receipt(first.lease(), true, "")));
        status.set(204);
        service.send(second);
        assertEquals("DELIVERED", service.get(first.id()).state());
        assertEquals(List.of(first.id(), first.id()), keys);
        service.collect();
        assertNull(service.claim(DeliveryChannel.WEBHOOK));
    }

    @Test
    void expiredLeaseRetriesAndAttemptLimitRequiresExplicitRetry() {
        service.configure(0, true, "");
        when(results.deliveryItems(any(Instant.class))).thenReturn(List.of(item(clock.instant())));
        service.collect();
        String id = null;
        for (int i = 0; i < 6; i++) {
            var d = service.claim(DeliveryChannel.DESKTOP);
            assertNotNull(d);
            id = d.id();
            clock.at = d.nextAt().plusSeconds(1);
        }
        assertNull(service.claim(DeliveryChannel.DESKTOP));
        var failed = service.get(id);
        assertEquals("FAILED", failed.state());
        service.retry(id, failed.version());
        assertNotNull(service.claim(DeliveryChannel.DESKTOP));
        assertThrows(IllegalStateException.class, () -> service.retry(failed.id(), failed.version()));
    }

    @Test
    void receiptAtExactLeaseExpiryIsRejectedBeforeAnotherClientClaims() {
        service.configure(0, true, "");
        when(results.deliveryItems(any(Instant.class))).thenReturn(List.of(item(clock.instant())));
        service.collect();
        var first = service.claim(DeliveryChannel.DESKTOP);
        clock.at = first.nextAt();
        assertThrows(IllegalStateException.class,
            () -> service.receipt(first.id(), new DeliveryService.Receipt(first.lease(), true, "")));
        var second = service.claim(DeliveryChannel.DESKTOP);
        assertNotEquals(first.lease(), second.lease());
        service.receipt(second.id(), new DeliveryService.Receipt(second.lease(), true, ""));
        assertEquals("DELIVERED", service.get(second.id()).state());
    }

    @Test
    void delayedWorkerCannotSendUsingExpiredOrReplacedLease() {
        service.configure(0, false, "https://example.com/hook");
        when(results.deliveryItems(any(Instant.class))).thenReturn(List.of(item(clock.instant())));
        service.collect();
        var first = service.claim(DeliveryChannel.WEBHOOK);
        clock.at = first.nextAt();
        service.send(first);
        assertTrue(keys.isEmpty(), "在备份或停顿后恢复的 worker 必须先核对租约");
        var second = service.claim(DeliveryChannel.WEBHOOK);
        service.send(first);
        assertTrue(keys.isEmpty(), "旧 worker 不能使用已被接管的租约发送");
        status.set(204);
        service.send(second);
        service.send(second);
        assertEquals(List.of(first.id()), keys);
        assertEquals("DELIVERED", service.get(first.id()).state());
    }

    @Test
    void disablingChannelAfterClaimPreventsWorkerFromSendingToOldTarget() {
        var settings = service.configure(0, false, "https://example.com/hook");
        when(results.deliveryItems(any(Instant.class))).thenReturn(List.of(item(clock.instant())));
        service.collect();
        var claimed = service.claim(DeliveryChannel.WEBHOOK);
        service.configure(settings.version(), false, "https://example.com/new");
        service.send(claimed);
        assertTrue(keys.isEmpty());
        assertEquals("CANCELLED", service.get(claimed.id()).state());
    }

    @Test
    void enablingDoesNotFloodOldResultsAndDisabledOrChangedChannelsDoNotSend() {
        when(results.deliveryItems(any(Instant.class))).thenReturn(List.of(item(clock.instant().minusSeconds(1))));
        var settings = service.configure(0, false, "https://example.com/hook");
        assertTrue(service.list().isEmpty());
        when(results.deliveryItems(any(Instant.class))).thenReturn(List.of(item(clock.instant())));
        service.collect();
        service.configure(settings.version(), false, "https://example.com/new");
        assertNull(service.claim(DeliveryChannel.WEBHOOK));
        assertEquals("CANCELLED", service.list().getFirst().state());
        assertTrue(keys.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.configure(2, false, "file:///etc/passwd"));
    }

    @Test
    void realHttpSenderPreservesKeyAndPayloadAndDoesNotFollowRedirects() throws Exception {
        var receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bodies = new CopyOnWriteArrayList<String>();
        var headers = new CopyOnWriteArrayList<String>();
        var redirected = new AtomicInteger();
        var response = new AtomicInteger(302);
        receiver.createContext("/hook", exchange -> {
            headers.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Location", "/other");
            exchange.sendResponseHeaders(response.get(), -1);
            exchange.close();
        });
        receiver.createContext("/other", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        service.close();
        var permissiveResolver =
            (DeliveryService.HostResolver)host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};
        service = new DeliveryService(store, JsonMapper.builder().build(), results, clock,
            DeliveryService.httpSender(null), permissiveResolver);
        try {
            // 验证真实 HttpClient：保留 Idempotency-Key 与 JSON 载荷，且不跟随 302 重定向
            String localTarget = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
            // 直接测试 httpSender(null) 走真实 HttpClient 时不跟随 302
            int firstStatus = DeliveryService.httpSender(null).send(localTarget, "idemp-1",
                "{\"resultId\":\"run-a\",\"summary\":\"测试通过\"}");
            assertEquals(302, firstStatus);
            assertEquals(0, redirected.get());
            response.set(204);
            int secondStatus = DeliveryService.httpSender(null).send(localTarget, "idemp-1",
                "{\"resultId\":\"run-a\",\"summary\":\"测试通过\"}");
            assertEquals(204, secondStatus);
            assertEquals(List.of("idemp-1", "idemp-1"), headers);
            var body = JsonMapper.builder().build().readTree(bodies.getFirst());
            assertEquals("run-a", body.get("resultId").asText());
            assertEquals("测试通过", body.get("summary").asText());
            assertEquals(0, redirected.get());

            // 默认带 SSRF 校验的 httpSender 即使收到私网/回环目标也必须在发包前拒绝，不触达本地端口
            assertThrows(IllegalArgumentException.class,
                () -> DeliveryService.httpSender(TEST_RESOLVER).send(localTarget, "idemp-ssrf", "{}"));
            assertEquals(2, headers.size());
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void rejectsPrivateTargetsAndMasksSecretsFromSettings() {
        for (String target : List.of("http://localhost/hook", "http://localhost.attacker.example/hook",
            "http://127.0.0.1:8080/hook", "http://[::1]:8080/hook", "http://10.0.0.2/hook", "http://192.168.1.2/hook",
            "http://169.254.169.254/latest", "http://[fd00::1]/hook", "http://100.64.0.1/hook", "http://0.0.0.0/hook",
            "http://rebind.example/hook", "http://ula.example/hook", "http://cgnat.example/hook")) {
            assertThrows(IllegalArgumentException.class, () -> service.configure(0, false, target), target);
        }
        var configured = service.configure(0, false, "https://hooks.example.com/token/private-token?key=private-key");
        assertEquals("https://hooks.example.com/token/***?key=***", configured.webhookUrl());
        assertEquals("https://hooks.example.com/token/***?key=***", service.settings().webhookUrl());
        var view = service.settings();
        var updated = service.configure(view.version(), false, view.webhookUrl());
        assertEquals("https://hooks.example.com/token/***?key=***", updated.webhookUrl());
        assertEquals("https://hooks.example.com/token/***?key=***", service.settings().webhookUrl());
        // 再次投递仍使用库中保留的真实未脱敏地址
        when(results.deliveryItems(any(Instant.class))).thenReturn(List.of(item(clock.instant())));
        service.collect();
        var claimed = service.claim(DeliveryChannel.WEBHOOK);
        assertNotNull(claimed);
        assertEquals("https://hooks.example.com/token/private-token?key=private-key", claimed.target());
    }

    static class MutableClock extends Clock {
        Instant at = Instant.parse("2026-11-03T00:00:00Z");

        public Instant instant() {
            return at;
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId z) {
            return this;
        }
    }
}
