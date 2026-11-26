package com.pilot.server.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.browser.BrowserEvidenceRecord;

import tools.jackson.databind.ObjectMapper;

class BrowserEvidenceServiceTest {
    @Test
    void stripedLocksPreserveConcurrentUpdatesAndBoundLockStorage(@TempDir Path temp) throws Exception {
        BrowserEvidenceService service = new BrowserEvidenceService(temp, new ObjectMapper());
        int sessions = 300;
        var pool = Executors.newFixedThreadPool(16);
        try {
            for (int i = 0; i < sessions; i++) {
                String sessionId = "session-" + i;
                pool.submit(() -> service.record(record(sessionId, "one")));
                pool.submit(() -> service.record(record(sessionId, "two")));
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        for (int i = 0; i < sessions; i++) {
            assertEquals(2, service.list("session-" + i, null, 0).size());
        }
    }

    private static BrowserEvidenceRecord record(String sessionId, String id) {
        return new BrowserEvidenceRecord(id, sessionId, sessionId, "run", "", Instant.now(), "test", "ok", "", "", "",
            "", "", "", "", 0L, List.of(), 0, 0, "");
    }
}
