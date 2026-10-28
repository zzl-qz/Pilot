package com.pilot.server.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteStoreMigrationTest {
    @TempDir
    Path dir;

    /** 老库没有 project_id 等列时，必须先补列再建索引，否则启动直接失败。 */
    @Test
    void addsProjectColumnsBeforeCreatingTheirIndex() throws Exception {
        Path db = dir.resolve("pilot.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE sessions (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        workspace TEXT,
                        created_at TEXT NOT NULL,
                        status TEXT NOT NULL,
                        sequence INTEGER NOT NULL DEFAULT 0
                    )""");
            statement.execute("INSERT INTO sessions VALUES "
                    + "('s1','旧会话',NULL,'2026-01-01T00:00:00Z','DONE',0)");
        }

        var store = new SqliteStore(db);

        try (var connection = store.open(); var statement = connection.createStatement()) {
            List<String> columns = new ArrayList<>();
            try (var rows = statement.executeQuery("PRAGMA table_info(sessions)")) {
                while (rows.next()) {
                    columns.add(rows.getString("name"));
                }
            }
            assertTrue(columns.containsAll(List.of("project_id", "is_blank", "archived")), columns.toString());

            boolean indexed = false;
            try (var rows = statement.executeQuery("PRAGMA index_list(sessions)")) {
                while (rows.next()) {
                    if ("idx_sessions_project".equals(rows.getString("name"))) {
                        indexed = true;
                    }
                }
            }
            assertTrue(indexed, "idx_sessions_project 应该在补列之后创建成功");
        }
    }
}
