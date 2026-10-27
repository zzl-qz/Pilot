package com.pilot.server.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 连接与建表。
 *
 * <p>单用户本地应用，直接用 DriverManager 取连接、不引入连接池；
 * 通过 {@code busy_timeout} 与 WAL 降低并发写冲突。
 */
public final class SqliteStore {
    private static final Logger log = LoggerFactory.getLogger(SqliteStore.class);

    private static final String[] SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                workspace TEXT,
                created_at TEXT NOT NULL,
                status TEXT NOT NULL,
                sequence INTEGER NOT NULL DEFAULT 0
            )""",
            """
            CREATE TABLE IF NOT EXISTS events (
                session_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                run_id TEXT NOT NULL,
                type TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (session_id, sequence)
            )""",
            """
            CREATE TABLE IF NOT EXISTS runs (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                status TEXT NOT NULL,
                started_at TEXT NOT NULL,
                finished_at TEXT
            )""",
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS conversation_messages (
                session_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                tool_calls TEXT,
                tool_call_id TEXT,
                created_at TEXT NOT NULL,
                PRIMARY KEY (session_id, seq)
            )""",
            """
            CREATE TABLE IF NOT EXISTS plan_items (
                session_id TEXT NOT NULL,
                ord INTEGER NOT NULL,
                step TEXT NOT NULL,
                status TEXT NOT NULL,
                PRIMARY KEY (session_id, ord)
            )""",
            "CREATE INDEX IF NOT EXISTS idx_events_session ON events(session_id, sequence)",
            "CREATE INDEX IF NOT EXISTS idx_runs_session ON runs(session_id, status)",
            "CREATE TABLE IF NOT EXISTS queued_messages (session_id TEXT NOT NULL, position INTEGER NOT NULL, content TEXT NOT NULL, PRIMARY KEY (session_id, position))"
    };

    private final String url;
    private final Path path;

    public SqliteStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
        this.url = "jdbc:sqlite:" + this.path;
        initialize();
    }

    public Path path() {
        return path;
    }

    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 4000");
        }
        return connection;
    }

    private void initialize() {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
                for (String ddl : SCHEMA) {
                    statement.execute(ddl);
                }
            }
            log.info("SQLite 存储已就绪: {}", path);
        } catch (Exception e) {
            throw new IllegalStateException("初始化 SQLite 存储失败: " + path, e);
        }
    }
}
