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
    void memoryRepositoryStoresAndRemovesQueues() {
        var repository = new QueuedMessageRepository();
        repository.save("session-1", List.of("first"));
        assertEquals(List.of("first"), repository.load("session-1"));
        repository.save("session-1", List.of());
        assertEquals(List.of(), repository.load("session-1"));
    }

    @Test
    void queueSurvivesRepositoryReopenAndCanBeReplaced() {
        Path database = tempDir.resolve("pilot.db");
        var repository = new QueuedMessageRepository(new SqliteStore(database));
        repository.save("session-1", List.of("first", "second"));

        var reopened = new QueuedMessageRepository(new SqliteStore(database));
        assertEquals(List.of("first", "second"), reopened.load("session-1"));
        reopened.save("session-1", List.of("edited"));
        assertEquals(List.of("edited"), repository.load("session-1"));
    }
}
