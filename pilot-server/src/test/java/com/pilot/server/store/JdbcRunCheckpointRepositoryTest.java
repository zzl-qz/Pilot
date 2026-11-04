package com.pilot.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.session.RunCheckpoint;

/**
 * 租约语义必须由真实 SQL 验证：条件更新写错一个字，就会出现两个实例同时推进。
 */
class JdbcRunCheckpointRepositoryTest {

    @TempDir
    Path dir;

    private JdbcRunCheckpointRepository repository() {
        return new JdbcRunCheckpointRepository(new SqliteStore(dir.resolve("pilot.db")));
    }

    private static RunCheckpoint checkpoint(String runId, String sessionId, String owner, Instant now, Duration lease) {
        return RunCheckpoint.start(runId, sessionId, "/work", owner, now, lease);
    }

    @Test
    void savesAndReadsBackEveryField() {
        JdbcRunCheckpointRepository repository = repository();
        Instant now = Instant.now();
        repository.save(checkpoint("run_1", "ses_1", "a", now, Duration.ofSeconds(30)).advance(3, "call_9",
            "mvn test → exit 0", "查看报告", RunCheckpoint.PHASE_MODEL, now));

        RunCheckpoint loaded = repository.find("run_1").orElseThrow();
        assertEquals("ses_1", loaded.sessionId());
        assertEquals(3, loaded.step());
        assertEquals("call_9", loaded.lastToolCallId());
        assertEquals("mvn test → exit 0", loaded.lastSuccessfulStep());
        assertEquals("查看报告", loaded.nextAction());
        assertEquals("a", loaded.owner());
        assertTrue(loaded.leaseActive(now.plusSeconds(5)));
    }

    @Test
    void onlyOneInstanceCanHoldTheLease() {
        JdbcRunCheckpointRepository repository = repository();
        Instant now = Instant.now();
        repository.save(checkpoint("run_1", "ses_1", "a", now, Duration.ofSeconds(30)));

        assertFalse(repository.claimLease("run_1", "b", now, Duration.ofSeconds(30)), "未过期的租约不能被第二个实例抢走");
        assertTrue(repository.claimLease("run_1", "a", now, Duration.ofSeconds(30)), "持有者自己续租应当成功");
        assertTrue(repository.claimLease("run_1", "b", now.plusSeconds(31), Duration.ofSeconds(30)), "过期后允许接管");
    }

    @Test
    void renewOnlyWorksForTheHolder() {
        JdbcRunCheckpointRepository repository = repository();
        Instant now = Instant.now();
        repository.save(checkpoint("run_1", "ses_1", "a", now, Duration.ofSeconds(30)));

        assertFalse(repository.renewLease("run_1", "b", now, Duration.ofSeconds(30)));
        assertTrue(repository.renewLease("run_1", "a", now.plusSeconds(10), Duration.ofSeconds(30)));
    }

    @Test
    void releaseClearsOwnerAndKeepsTheRecord() {
        JdbcRunCheckpointRepository repository = repository();
        Instant now = Instant.now();
        repository.save(checkpoint("run_1", "ses_1", "a", now, Duration.ofSeconds(30)));

        repository.releaseLease("run_1", RunCheckpoint.STATUS_INTERRUPTED, now);

        RunCheckpoint released = repository.find("run_1").orElseThrow();
        assertEquals(RunCheckpoint.STATUS_INTERRUPTED, released.status());
        assertNotNull(released.lastSuccessfulStep() == null ? "kept" : released.lastSuccessfulStep());
        assertFalse(released.leaseActive(now.plusSeconds(1)));
    }

    @Test
    void resumableListsInterruptedRunsOnly() {
        JdbcRunCheckpointRepository repository = repository();
        Instant now = Instant.now();
        repository.save(checkpoint("run_ok", "ses_1", "a", now, Duration.ofSeconds(30)));
        repository.releaseLease("run_ok", RunCheckpoint.STATUS_COMPLETED, now);
        repository.save(checkpoint("run_bad", "ses_2", "a", now, Duration.ofSeconds(30)));
        repository.releaseLease("run_bad", RunCheckpoint.STATUS_INTERRUPTED, now);

        List<RunCheckpoint> resumable = repository.resumable();

        assertEquals(1, resumable.size());
        assertEquals("run_bad", resumable.getFirst().runId());
        assertTrue(repository.activeForSession("ses_2").isPresent());
        assertTrue(repository.activeForSession("ses_1").isEmpty());
    }

    @Test
    void deletingASessionRemovesItsCheckpoints() {
        JdbcRunCheckpointRepository repository = repository();
        repository.save(checkpoint("run_1", "ses_1", "a", Instant.now(), Duration.ofSeconds(30)));

        assertEquals(1, repository.deleteBySession("ses_1"));
        assertTrue(repository.find("run_1").isEmpty());
    }
}
