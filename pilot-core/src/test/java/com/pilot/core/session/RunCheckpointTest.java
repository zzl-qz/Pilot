package com.pilot.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class RunCheckpointTest {
    private static final Instant NOW = Instant.parse("2026-10-31T08:00:00Z");

    @Test
    void newCheckpointIsLeasedToItsOwner() {
        RunCheckpoint checkpoint =
            RunCheckpoint.start("run_1", "ses_1", "/work", "instance-a", NOW, Duration.ofSeconds(30));

        assertEquals(RunCheckpoint.PHASE_STARTING, checkpoint.phase());
        assertEquals(RunCheckpoint.STATUS_RUNNING, checkpoint.status());
        assertTrue(checkpoint.leaseActive(NOW.plusSeconds(10)));
        assertFalse(checkpoint.leasedByOther("instance-a", NOW));
        assertTrue(checkpoint.leasedByOther("instance-b", NOW));
    }

    @Test
    void expiredLeaseCanBeTakenOver() {
        RunCheckpoint checkpoint =
            RunCheckpoint.start("run_1", "ses_1", "/work", "instance-a", NOW, Duration.ofSeconds(30));

        assertFalse(checkpoint.leasedByOther("instance-b", NOW.plusSeconds(31)), "租约过期后其他实例应能接管");
    }

    @Test
    void advanceKeepsStepMonotonicAndRecordsNextAction() {
        RunCheckpoint checkpoint = RunCheckpoint.start("run_1", "ses_1", "/work", "a", NOW, Duration.ofSeconds(30));

        RunCheckpoint advanced = checkpoint.advance(3, "call_9", "mvn test → exit 0", "查看失败用例",
            RunCheckpoint.PHASE_MODEL, NOW.plusSeconds(5));
        RunCheckpoint older = advanced.advance(1, "call_10", null, null, RunCheckpoint.PHASE_TOOL, NOW.plusSeconds(9));

        assertEquals(3, advanced.step());
        assertEquals("call_9", advanced.lastToolCallId());
        assertEquals("mvn test → exit 0", advanced.lastSuccessfulStep());
        assertEquals("查看失败用例", advanced.nextAction());
        // step 只增不减：乱序回调不能把进度改回去
        assertEquals(3, older.step());
        assertEquals("mvn test → exit 0", older.lastSuccessfulStep());
    }

    @Test
    void heartbeatMovesLeaseForwardWithoutChangingProgress() {
        RunCheckpoint checkpoint = RunCheckpoint.start("run_1", "ses_1", "/work", "a", NOW, Duration.ofSeconds(30));
        RunCheckpoint beat = checkpoint.heartbeat("a", NOW.plusSeconds(20), Duration.ofSeconds(30));

        assertEquals(checkpoint.step(), beat.step());
        assertTrue(beat.leaseExpiresAt().isAfter(checkpoint.leaseExpiresAt()));
    }

    @Test
    void rejectsInvalidIdentity() {
        assertThrows(IllegalArgumentException.class,
            () -> RunCheckpoint.start("", "ses_1", "/work", "a", NOW, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
            () -> RunCheckpoint.start("run_1", null, "/work", "a", NOW, Duration.ofSeconds(1)));
    }
}
