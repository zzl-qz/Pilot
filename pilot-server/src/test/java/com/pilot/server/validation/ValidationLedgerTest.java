package com.pilot.server.validation;

import com.pilot.server.config.PilotDataDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
