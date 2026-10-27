package com.pilot.server.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
                sequence INTEGER NOT NULL DEFAULT 0,
                project_id TEXT,
                is_blank INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0
            )""",
            """
            CREATE TABLE IF NOT EXISTS projects (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                base_commit TEXT
            )""",
            """
            CREATE TABLE IF NOT EXISTS project_folders (
                project_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                path TEXT NOT NULL,
                PRIMARY KEY (project_id, position)
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
            // events 的主键 (session_id, sequence) 本身就是最左前缀索引，
            // 再建一个 idx_events_session 只会让每次写入多维护一棵 B 树
            "DROP INDEX IF EXISTS idx_events_session",
            "CREATE INDEX IF NOT EXISTS idx_runs_session ON runs(session_id, status)",
            "CREATE INDEX IF NOT EXISTS idx_sessions_project ON sessions(project_id, created_at)",
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

    /**
     * 删除早于 {@code cutoff} 的事件，返回删除条数。
     *
     * <p>{@code created_at} 是 {@link java.time.Instant#toString()} 的固定格式，
     * 字典序等价于时间序，可以直接比较。
     */
    public int deleteEventsBefore(java.time.Instant cutoff) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM events WHERE created_at < ?")) {
            statement.setString(1, cutoff.toString());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理历史事件失败", e);
        }
    }

    /** 收缩 WAL：删除大量行之后，把日志文件截回去，避免磁盘只涨不落。 */
    public void checkpoint() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException e) {
            log.debug("WAL checkpoint 失败（不影响使用）", e);
        }
    }

    /**
     * 老库补列。
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} 对已存在的表什么都不做，加列只能靠 {@code ALTER TABLE}；
     * SQLite 没提供 {@code ADD COLUMN IF NOT EXISTS}，先查一次表结构再补。
     * 老会话的 project_id 留空，由界面归到「未分组」，不强行猜项目；
     * 老会话一律视为「非空白、未归档」，保持原样。
     */
    private static void migrate(Statement statement) throws SQLException {
        addColumnIfMissing(statement, "sessions", "project_id", "TEXT");
        addColumnIfMissing(statement, "sessions", "is_blank", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(statement, "sessions", "archived", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(statement, "projects", "base_commit", "TEXT");
    }

    private static void addColumnIfMissing(Statement statement, String table, String column, String definition)
            throws SQLException {
        if (hasColumn(statement, table, column)) {
            return;
        }
        statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        log.info("已为 {} 表补充 {} 列", table, column);
    }

    private static boolean hasColumn(Statement statement, String table, String column) throws SQLException {
        try (var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (column.equals(rows.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private void initialize() {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
                // WAL 下 NORMAL 已经足够安全：崩溃最多丢最后几个事务，但不会损坏库，
                // 换来的是每个事件少一次 fsync
                statement.execute("PRAGMA synchronous = NORMAL");
                statement.execute("PRAGMA wal_autocheckpoint = 1000");
                statement.execute("PRAGMA temp_store = MEMORY");
                for (String ddl : SCHEMA) {
                    statement.execute(ddl);
                }
                migrate(statement);
            }
            log.info("SQLite 存储已就绪: {}", path);
        } catch (Exception e) {
            throw new IllegalStateException("初始化 SQLite 存储失败: " + path, e);
        }
    }
}
