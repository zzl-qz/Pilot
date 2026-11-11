package com.pilot.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.session.SessionService;

import tools.jackson.databind.json.JsonMapper;

/** 可重复测量真实 SQLite 的 10 万条回放，显式启用，报告不包含消息内容。 */
@EnabledIfSystemProperty(named = "pilot.events.benchmark", matches = "true")
class EventReplayBenchmarkTest {
    @TempDir
    Path root;

    @Test
    void replay100000FromSqlite() throws Exception {
        var store = new SqliteStore(root.resolve("pilot.db"));
        var repository = new JdbcSessionRepository(store, new EventCodec(JsonMapper.builder().build()));
        repository.create("benchmark", "测量", null, Instant.now());
        try (var connection = store.open()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                "INSERT INTO events(session_id,sequence,run_id,type,payload,created_at) VALUES('benchmark',?,'r','assistant.delta',?,?)")) {
                for (int i = 1; i <= 100000; i++) {
                    statement.setInt(1, i);
                    statement.setString(2, "{\"text\":\"" + "x".repeat(64) + "\"}");
                    statement.setString(3, Instant.EPOCH.toString());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE sessions SET sequence=100000");
            }
            connection.commit();
        }
        var service = new SessionService(Clock.systemUTC(), repository);
        var done = new CountDownLatch(1);
        var count = new AtomicInteger();
        long began = System.nanoTime();
        try (var subscription = service.subscribe("benchmark", 0, event -> {
            assertEquals(count.incrementAndGet(), event.sequence());
            if (event.sequence() == 100000) {
                done.countDown();
            }
        })) {
            assertTrue(done.await(90, TimeUnit.SECONDS), "10 万条回放不得超过测量上限");
            double millis = (System.nanoTime() - began) / 1e6;
            String report = "{\"events\":100000,\"payloadChars\":64,\"sqliteReplayMs\":" + millis
                + ",\"peakQueueBytes\":" + service.streamMetrics().peakQueuedBytes() + "}";
            Files.createDirectories(Path.of("target/event-benchmarks"));
            Files.writeString(Path.of("target/event-benchmarks/sqlite.json"), report);
            System.out.println("EVENT_BENCHMARK " + report);
        }
    }
}
