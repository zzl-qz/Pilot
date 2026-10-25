package com.pilot.server.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcSettingsStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void keepsSavedValueAcrossReopen() {
        Path database = tempDir.resolve("pilot.db");

        new JdbcSettingsStore(new SqliteStore(database)).save("agent.permission-mode", "AUTO_EDIT");

        var reopened = new JdbcSettingsStore(new SqliteStore(database));
        assertEquals("AUTO_EDIT", reopened.find("agent.permission-mode").orElseThrow());
    }

    @Test
    void overwritesExistingKeyAndHidesUnknownKey() {
        var store = new JdbcSettingsStore(new SqliteStore(tempDir.resolve("pilot.db")));

        store.save("agent.permission-mode", "ASK");
        store.save("agent.permission-mode", "AUTO");

        assertEquals("AUTO", store.find("agent.permission-mode").orElseThrow());
        assertTrue(store.find("missing").isEmpty());
    }
}
