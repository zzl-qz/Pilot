package com.pilot.server.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.pilot.server.config.PilotDataDir;

import tools.jackson.databind.ObjectMapper;

class MobileReceiptStoreTest {
    @TempDir
    Path directory;
    private static final String PEER = "a".repeat(64);
    private final AtomicLong time = new AtomicLong(1_800_000_000_000L);
    private final Clock clock = new Clock() {
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(time.get());
        }
    };

    private MobileReceiptStore store(int limit) {
        return new MobileReceiptStore(new PilotDataDir(directory.toString()), new ObjectMapper(), clock,
            Duration.ofHours(1), Duration.ofDays(1), limit, 64L * 1024 * 1024);
    }

    private MockHttpServletRequest request(String id) {
        var request = new MockHttpServletRequest("POST", "/api/sessions");
        request.addHeader("X-Pilot-Request-Id", PEER + ":" + id);
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private MockHttpServletResponse execute(MobileReceiptStore store, String id, AtomicInteger calls) throws Exception {
        var response = new MockHttpServletResponse();
        new MobileRequestFilter(store).doFilter(request(id), response, (req, res) -> {
            calls.incrementAndGet();
            res.setContentType("application/json");
            res.getWriter().write("{\"accepted\":true}");
        });
        return response;
    }

    @Test
    void acknowledgedReceiptExpiresButCannotResurrectAfterCleanupRestartOrClockRollback() throws Exception {
        var store = store(10);
        var calls = new AtomicInteger();
        String token = store.issue(PEER);
        assertEquals(200, execute(store, token, calls).getStatus());
        store.acknowledge(PEER, token);
        store.acknowledge(PEER, token);
        assertEquals(0, store.cleanup().lastRemoved());
        time.addAndGet(Duration.ofDays(2).toMillis());
        assertEquals(1, store.cleanup().lastRemoved());
        assertEquals(410, execute(store, token, calls).getStatus());
        time.addAndGet(-Duration.ofDays(4).toMillis());
        var restarted = store(10);
        assertEquals(410, execute(restarted, token, calls).getStatus());
        assertEquals(1, calls.get());
        store.acknowledge(PEER, token); // 清理后重复确认也是幂等的。
        assertEquals(200, execute(restarted, restarted.issue(PEER), calls).getStatus());
    }

    @Test
    void expiredUnreceivedRequestIsRejectedBeforeExecution() throws Exception {
        var store = store(10);
        String id = store.issue(PEER);
        time.addAndGet(Duration.ofHours(1).toMillis());
        var calls = new AtomicInteger();
        assertEquals(410, execute(store, id, calls).getStatus());
        assertEquals(0, calls.get());
        assertEquals(0, store.status().count());
        time.addAndGet(-Duration.ofDays(1).toMillis());
        assertEquals(410, execute(store(10), id, calls).getStatus());
    }

    @Test
    void unconfirmedAndUnknownReceiptsSurviveAndCanBeReconciledAfterExpiry() throws Exception {
        var store = store(10);
        var calls = new AtomicInteger();
        String complete = store.issue(PEER);
        String unknown = store.issue(PEER);
        execute(store, complete, calls);
        assertThrows(IllegalStateException.class, () -> new MobileRequestFilter(store).doFilter(request(unknown),
            new MockHttpServletResponse(), (req, res) -> {
                throw new IllegalStateException("执行后断电");
            }));
        assertThrows(MobileReceiptStore.Rejected.class, () -> store.acknowledge(PEER, unknown));
        time.addAndGet(Duration.ofDays(40).toMillis());
        assertEquals(0, store.cleanup().lastRemoved());
        assertEquals(1, store.status().unknown());
        assertEquals(1, store.status().unconfirmed());
        assertEquals(200, execute(store, complete, calls).getStatus());
        assertEquals(409, execute(store, unknown, calls).getStatus());
        assertEquals(1, calls.get());
        store.acknowledge(PEER, complete);
        assertEquals(0, store.cleanup().lastRemoved()); // 从确认时间起保留。
        time.addAndGet(Duration.ofDays(1).toMillis());
        assertEquals(1, store.cleanup().lastRemoved());
        assertEquals(1, store.status().count());
    }

    @Test
    void tokenIsDeviceBoundAndCannotBeExtendedByEditingExpiry() throws Exception {
        var store = store(10);
        String id = store.issue(PEER);
        assertThrows(MobileReceiptStore.Rejected.class, () -> store.validateNew("b".repeat(64) + ":" + id));
        String forged = id.replace(id.split("\\.")[1], "9999999999999999");
        assertEquals(400, execute(store, forged, new AtomicInteger()).getStatus());
        assertEquals(0, store.status().count());
    }

    @Test
    void oldReceiptsRemainReplayableButNewLegacyRequestsAreRejected() throws Exception {
        var store = store(10);
        store.issue(PEER);
        String old = UUID.randomUUID().toString();
        String fingerprint = MobileReceiptStore
            .digest(("POST /api/sessions?null\n" + MobileReceiptStore.digest("{}".getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.UTF_8));
        Path file = directory.resolve("mobile/receipts/"
            + MobileReceiptStore.digest((PEER + ":" + old).getBytes(StandardCharsets.UTF_8)) + ".json");
        Files.writeString(file, "{\"fingerprint\":\"" + fingerprint
            + "\",\"status\":200,\"contentType\":\"application/json\",\"body\":\"e30=\"}");
        store = store(10);
        var calls = new AtomicInteger();
        assertEquals(200, execute(store, old, calls).getStatus());
        store.acknowledge(PEER, old);
        time.addAndGet(Duration.ofDays(100).toMillis());
        assertEquals(0, store.cleanup().lastRemoved());
        assertEquals(1, store.status().legacy());
        assertEquals(426, execute(store, UUID.randomUUID().toString(), calls).getStatus());
        assertEquals(0, calls.get());
    }

    @Test
    void capacityBlocksNewWorkButNotReplayAcknowledgmentOrCleanup() throws Exception {
        var store = store(1);
        String first = store.issue(PEER);
        String second = store.issue(PEER);
        var calls = new AtomicInteger();
        execute(store, first, calls);
        assertTrue(store.status().capacityReached());
        assertEquals(507, execute(store, second, calls).getStatus());
        assertThrows(MobileReceiptStore.Rejected.class, () -> store.issue(PEER));
        assertEquals(200, execute(store, first, calls).getStatus());
        store.acknowledge(PEER, first);
        time.addAndGet(Duration.ofDays(2).toMillis());
        assertFalse(store.cleanup().capacityReached());
        assertEquals(200, execute(store, store.issue(PEER), calls).getStatus());
        assertEquals(2, calls.get());
    }

    @Test
    void diskFailureAndCorruptReceiptNeverExecuteAgain() throws Exception {
        var store = store(10);
        String id = store.issue(PEER);
        Path file = directory.resolve("mobile/receipts/"
            + MobileReceiptStore.digest((PEER + ":" + id).getBytes(StandardCharsets.UTF_8)) + ".json");
        Files.createDirectory(file); // 跨平台可复现占位写入失败，不依赖 chmod / root 行为。
        var calls = new AtomicInteger();
        assertThrows(Exception.class, () -> execute(store, id, calls));
        assertEquals(0, calls.get());
        Files.delete(file);
        execute(store, id, calls);
        Files.writeString(file, "broken");
        assertThrows(Exception.class, () -> execute(store, id, calls));
        assertEquals(1, store.cleanup().corrupt());
        assertEquals(1, calls.get());
    }

    @Test
    void concurrentRetriesAndCleanupExecuteOnlyOnce() throws Exception {
        var store = store(10);
        String id = store.issue(PEER);
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> {
                new MobileRequestFilter(store).doFilter(request(id), new MockHttpServletResponse(), (req, res) -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    res.getWriter().write("{}");
                });
                return true;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var retry = pool.submit(() -> execute(store, id, calls));
            var cleanup = pool.submit(store::cleanup);
            release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertEquals(200, retry.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(1, cleanup.get(5, TimeUnit.SECONDS).count());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void serverFailureCannotBeAcknowledgedOrDeleted() throws Exception {
        var store = store(10);
        String id = store.issue(PEER);
        new MobileRequestFilter(store).doFilter(request(id), new MockHttpServletResponse(), (req, res) -> {
            ((HttpServletResponse)res).setStatus(500);
        });
        assertThrows(MobileReceiptStore.Rejected.class, () -> store.acknowledge(PEER, id));
        time.addAndGet(Duration.ofDays(50).toMillis());
        assertEquals(1, store.cleanup().unknown());
    }

    @Test
    void failureSavingCompletedResponseKeepsUnknownPlaceholderAcrossRestart() throws Exception {
        var store = new MobileReceiptStore(new PilotDataDir(directory.toString()), new ObjectMapper(), clock,
            Duration.ofHours(1), Duration.ofDays(1), 10, 64L * 1024 * 1024) {
            @Override
            void save(String id, Receipt receipt) throws IOException {
                if (receipt.status() != 0) {
                    throw new IOException("模拟执行完成后磁盘写入失败");
                }
                super.save(id, receipt);
            }
        };
        var calls = new AtomicInteger();
        String id = store.issue(PEER);
        assertThrows(IOException.class, () -> execute(store, id, calls));
        assertEquals(409, execute(store(10), id, calls).getStatus());
        assertEquals(1, calls.get());
    }

    @Test
    void cleanupCannotDeleteBeforeDurableClockCheckpoint() throws Exception {
        var store = store(10);
        String id = store.issue(PEER);
        execute(store, id, new AtomicInteger());
        store.acknowledge(PEER, id);
        time.addAndGet(Duration.ofDays(2).toMillis());
        Path state = directory.resolve("mobile/receipts/state.json");
        byte[] saved = Files.readAllBytes(state);
        Files.delete(state);
        Files.createDirectory(state);
        Files.writeString(state.resolve("occupied"), "无法替换非空目录");
        try {
            assertThrows(IOException.class, store::cleanup);
            assertEquals(1, store.status().count());
        } finally {
            Files.delete(state.resolve("occupied"));
            Files.delete(state);
            Files.write(state, saved);
        }
        assertEquals(1, store.cleanup().lastRemoved());
    }

    @Test
    void anotherDeviceCannotConfirmAnExistingReceipt() throws Exception {
        var store = store(10);
        String id = store.issue(PEER);
        execute(store, id, new AtomicInteger());
        store.acknowledge("b".repeat(64), id);
        time.addAndGet(Duration.ofDays(2).toMillis());
        assertEquals(0, store.cleanup().lastRemoved());
        assertEquals(1, store.status().unconfirmed());
    }

}
