package com.pilot.server.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 队列暂停状态要跨重启保留，并且删除会话时能被清理。 */
class QueueHoldTest {
    @TempDir
    Path root;

    @Test
    void queueHoldSurvivesRestartAndCanBeCleared() {
        Path db = root.resolve("queue.db");
        var repository = new QueuedMessageRepository(new SqliteStore(db));
        repository.save("ses_a", List.of("任务一", "任务二"));
        assertFalse(repository.held("ses_a"));
        repository.setHeld("ses_a", true);

        var reopened = new QueuedMessageRepository(new SqliteStore(db));
        assertTrue(reopened.held("ses_a"), "重新打开数据库后仍显示暂停");
        assertTrue(reopened.held("ses_other") == false);

        reopened.clearHold("ses_a");
        assertFalse(new QueuedMessageRepository(new SqliteStore(db)).held("ses_a"));
    }

    @Test
    void memoryRepositoryKeepsHoldPerSession() {
        var repository = new QueuedMessageRepository();
        repository.setHeld("ses_a", true);
        assertTrue(repository.held("ses_a"));
        assertFalse(repository.held("ses_b"));
        repository.clearHold("ses_a");
        assertFalse(repository.held("ses_a"));
    }
}
