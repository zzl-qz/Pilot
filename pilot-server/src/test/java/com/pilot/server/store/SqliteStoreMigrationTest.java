package com.pilot.server.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
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

        SqliteStore store = new SqliteStore(db);

        try (Connection connection = store.open(); Statement statement = connection.createStatement()) {
            List<String> columns = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("PRAGMA table_info(sessions)")) {
                while (rows.next()) {
                    columns.add(rows.getString("name"));
                }
            }
            assertTrue(columns.containsAll(List.of("project_id", "is_blank", "archived")), columns.toString());

            boolean indexed = false;
            try (ResultSet rows = statement.executeQuery("PRAGMA index_list(sessions)")) {
                while (rows.next()) {
                    if ("idx_sessions_project".equals(rows.getString("name"))) {
                        indexed = true;
                    }
                }
            }
            assertTrue(indexed, "idx_sessions_project 应该在补列之后创建成功");
        }
    }

    @Test
    void addsAttachmentColumnToLegacyConversationTable() throws Exception {
        Path db = dir.resolve("legacy-conversation.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE conversation_messages (
                        session_id TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        tool_calls TEXT,
                        tool_call_id TEXT,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY (session_id, seq)
                    )""");
            statement.execute("INSERT INTO conversation_messages VALUES "
                    + "('s1',1,'user','看图',NULL,NULL,'2026-01-01T00:00:00Z')");
        }

        SqliteStore store = new SqliteStore(db);

        try (Connection connection = store.open(); Statement statement = connection.createStatement()) {
            List<String> columns = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("PRAGMA table_info(conversation_messages)")) {
                while (rows.next()) columns.add(rows.getString("name"));
            }
            assertTrue(columns.contains("attachments"), columns.toString());
            try (ResultSet rows = statement.executeQuery(
                    "SELECT content, attachments FROM conversation_messages WHERE session_id='s1'")) {
                assertTrue(rows.next());
                assertTrue("看图".equals(rows.getString(1)));
                assertTrue(rows.getString(2) == null, "旧消息附件列应保持 NULL");
            }
        }
    }
}
