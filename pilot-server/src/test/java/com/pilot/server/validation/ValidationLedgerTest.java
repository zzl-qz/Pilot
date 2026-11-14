package com.pilot.server.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.common.data.StorageHealth;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.json.JsonMapper;

class ValidationLedgerTest {

    @TempDir
    Path dataDir;

    private ValidationLedger ledger() {
        return new ValidationLedger(new PilotDataDir(dataDir.toString()), JsonMapper.builder().build());
    }

    private static ValidationEvidence evidence(String hash, String status) {
        return new ValidationEvidence("val_1", "ses_1", "run_1", hash, List.of("src/A.java"),
            List.of("mvn -q test → exit 0"), status, "linux|amd64|java25", "run", Instant.now());
    }

    @Test
    void evidenceOnlyCoversTheArtifactItWasRecordedAgainst() {
        ValidationLedger ledger = ledger();
        ledger.record(evidence("hash-a", "passed"));

        assertTrue(ledger.report("ses_1", "hash-a").current());
        ValidationLedger.Report stale = ledger.report("ses_1", "hash-b");
        assertFalse(stale.current());
        assertTrue(stale.reason().contains("旧产物"), stale.reason());
    }

    @Test
    void failedEvidenceNeverCountsAsCompletionProof() {
        ValidationLedger ledger = ledger();
        ledger.record(evidence("hash-a", "failed"));

        ValidationLedger.Report report = ledger.report("ses_1", "hash-a");
        assertFalse(report.current());
        assertTrue(report.reason().contains("没有通过"), report.reason());
    }

    @Test
    void evidenceSurvivesRestart() {
        ledger().record(evidence("hash-a", "passed"));

        ValidationLedger reopened = ledger();
        assertEquals(1, reopened.recent("ses_1").size());
        assertTrue(reopened.report("ses_1", "hash-a").current());
    }

    @Test
    void sessionsAreIsolated() {
        ValidationLedger ledger = ledger();
        ledger.record(evidence("hash-a", "passed"));

        assertTrue(ledger.recent("ses_2").isEmpty());
        assertFalse(ledger.report("ses_2", "hash-a").current());
    }

    @Test
    void deleteRemovesEvidence() {
        ValidationLedger ledger = ledger();
        ledger.record(evidence("hash-a", "passed"));

        assertEquals(1, ledger.deleteBySession("ses_1"));
        assertTrue(ledger.recent("ses_1").isEmpty());
    }

    @Test
    void failedPersistenceCannotBeReportedAsReliableEvidence() throws IOException {
        StorageHealth.reset();
        Path blockedRoot = dataDir.resolve("not-a-directory");
        Files.writeString(blockedRoot, "file");
        ValidationLedger ledger =
            new ValidationLedger(new PilotDataDir(blockedRoot.toString()), JsonMapper.builder().build());
        ledger.record(evidence("hash-a", "passed"));

        ValidationLedger.Report report = ledger.report("ses_1", "hash-a");
        assertFalse(report.current());
        assertTrue(report.reason().contains("未能可靠落盘"), report.reason());
        assertFalse(StorageHealth.snapshot().healthy());
        StorageHealth.reset();
    }

    @Test
    void pendingEvidenceIsUnavailableDuringWriteAndAfterFailure() throws Exception {
        ValidationLedger previous = ledger();
        previous.record(evidence("hash-a", "passed"));
        CountDownLatch serializing = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean fail = new AtomicBoolean(true);
        JsonMapper controlled = new JsonMapper() {
            @Override
            public String writeValueAsString(Object value) {
                if (fail.get()) {
                    serializing.countDown();
                    try {
                        if (!proceed.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("等待写入超时");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    throw new IllegalStateException("模拟证据序列化失败");
                }
                return super.writeValueAsString(value);
            }
        };
        ValidationLedger writer = new ValidationLedger(new PilotDataDir(dataDir.toString()), controlled);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var recording = executor.submit(() -> writer.record(evidence("hash-a", "failed")));
            try {
                assertTrue(serializing.await(5, TimeUnit.SECONDS));
                assertFalse(writer.report("ses_1", "hash-a").current());
                assertFalse(ledger().report("ses_1", "hash-a").current());
            } finally {
                proceed.countDown();
            }
            recording.get(5, TimeUnit.SECONDS);
        }

        assertFalse(ledger().report("ses_1", "hash-a").current());
        fail.set(false);
        writer.record(evidence("hash-a", "passed"));
        assertTrue(ledger().report("ses_1", "hash-a").current());
        StorageHealth.reset();
    }

    @Test
    void recordsIncompleteEvidenceInSqliteWhenTheMarkerCannotBeWritten() throws IOException {
        SqliteStore sqlite = new SqliteStore(dataDir.resolve("pilot.db"));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("sqlite", sqlite);
        var provider = beans.getBeanProvider(SqliteStore.class);
        JsonMapper mapper = JsonMapper.builder().build();
        PilotDataDir normal = new PilotDataDir(dataDir.toString());
        new ValidationLedger(normal, mapper, provider).record(evidence("hash", "passed"));
        Path blocked = Files.writeString(dataDir.resolve("not-directory"), "file");
        PilotDataDir blockedMarker = new PilotDataDir(dataDir.toString()) {
            @Override
            public Path file(String... parts) {
                if (parts.length == 2 && parts[1].endsWith(".pending")) {
                    return blocked.resolve(parts[1]);
                }
                return super.file(parts);
            }
        };
        try {
            new ValidationLedger(blockedMarker, mapper, provider).record(evidence("hash", "failed"));
            assertTrue(sqlite.validationPending("ses_1"));
            assertFalse(new ValidationLedger(normal, mapper, provider).report("ses_1", "hash").current());
        } finally {
            StorageHealth.reset();
        }
    }
}
