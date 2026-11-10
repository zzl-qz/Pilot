package com.pilot.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AutomationStoreTest {
    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void prefixQueriesMatchLiteralIdsInMemoryAndSqlite(boolean persistent) {
        AutomationStore store = new AutomationStore(persistent ? new SqliteStore(temp.resolve("prefix.db")) : null);
        List<String> ids = List.of("ses_A:file:2", "ses_A:file:1", "ses_AA:file:1", "ses_a:file:1",
            "ses_*?[中]%_:file:1", "ses_XY中%_:file:1", "ses_*?[中]%_:file:2", "中文😀:1");
        ids.forEach(id -> store.put("version", id, 0, "{}"));
        store.put("annotation", "ses_A:file:1", 0, "{}");
        for (String prefix : List.of("ses_A:", "ses_A:file:", "ses_a:", "ses_*?[中]%_:", "中文😀:", "missing:", "")) {
            List<String> expected = ids.stream().filter(id -> id.startsWith(prefix)).sorted().toList();
            assertEquals(expected, store.list("version", prefix).stream().map(AutomationStore.Document::id).toList(),
                prefix);
        }
        assertEquals(List.of(), store.list("missing", "ses_A:"));
    }
}
