package com.pilot.server.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** 批量写里只要有任何一个键失败，整批都必须回滚，不能留下半新半旧的配置。 */
    @Test
    void rollsBackWholeBatchWhenOneEntryFails() throws SQLException {
        var sqlite = new SqliteStore(tempDir.resolve("pilot.db"));
        var store = new JdbcSettingsStore(sqlite);
        store.save("model.endpoint", "https://old.example/v1");

        try (var connection = sqlite.open(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER fail_on_boom BEFORE INSERT ON settings
                    WHEN NEW.key = 'model.boom'
                    BEGIN SELECT RAISE(ABORT, 'boom'); END""");
        }

        var batch = new LinkedHashMap<String, String>();
        batch.put("model.endpoint", "https://new.example/v1");
        batch.put("model.boom", "x");

        assertThrows(RuntimeException.class, () -> store.saveAll(batch));

        assertEquals("https://old.example/v1", store.find("model.endpoint").orElseThrow(),
                "同一批次里先写的键也必须回滚");
        assertTrue(store.find("model.boom").isEmpty());
    }
}
