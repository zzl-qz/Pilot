package com.pilot.server.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueuedMessageRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void movingQueueAndSteeringPersistsTogetherAndRollsBackOnFailure() {
        var repository = new QueuedMessageRepository(new SqliteStore(tempDir.resolve("atomic.db")));
        repository.save("s", List.of("guide", "next"));
        repository.saveAll(java.util.Map.of("s", List.of("next"), "steer:s", List.of("guide")));
        assertEquals(List.of("next"), repository.load("s"));
        assertEquals(List.of("guide"), repository.load("steer:s"));
        var invalid = new java.util.LinkedHashMap<String, List<String>>();
        invalid.put("s", List.of()); invalid.put("steer:s", java.util.Arrays.asList((String) null));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> repository.saveAll(invalid));
        assertEquals(List.of("next"), repository.load("s"));
        assertEquals(List.of("guide"), repository.load("steer:s"));
    }

    @Test
    void memoryRepositoryStoresAndRemovesQueues() {
        QueuedMessageRepository repository = new QueuedMessageRepository();
        repository.save("session-1", List.of("first"));
        assertEquals(List.of("first"), repository.load("session-1"));
        repository.save("session-1", List.of());
        assertEquals(List.of(), repository.load("session-1"));
    }

    @Test
    void queueSurvivesRepositoryReopenAndCanBeReplaced() {
        Path database = tempDir.resolve("pilot.db");
        QueuedMessageRepository repository = new QueuedMessageRepository(new SqliteStore(database));
        repository.save("session-1", List.of("first", "second"));

        QueuedMessageRepository reopened = new QueuedMessageRepository(new SqliteStore(database));
        assertEquals(List.of("first", "second"), reopened.load("session-1"));
        reopened.save("session-1", List.of("edited"));
        assertEquals(List.of("edited"), repository.load("session-1"));
    }
}
