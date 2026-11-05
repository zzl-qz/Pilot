package com.pilot.server.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite 连接与建表。
 *
 * <p>单用户本地应用，直接用 DriverManager 取连接、不引入连接池；
 * 通过 {@code busy_timeout} 与 WAL 降低并发写冲突。
 */
public final class SqliteStore {
    private static final Logger log = LoggerFactory.getLogger(SqliteStore.class);

    private static final String[] SCHEMA = {
        "CREATE TABLE IF NOT EXISTS automation_documents (kind TEXT NOT NULL, id TEXT NOT NULL, version INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(kind,id))",
        "CREATE INDEX IF NOT EXISTS idx_automation_state ON automation_documents(kind, json_extract(payload,'$.state'))",
        "CREATE TABLE IF NOT EXISTS result_receipts (id TEXT PRIMARY KEY, revision TEXT NOT NULL, seen INTEGER NOT NULL DEFAULT 0, handled INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS scheduled_tasks (id TEXT PRIMARY KEY, version INTEGER NOT NULL, payload TEXT NOT NULL)",
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
                archived INTEGER NOT NULL DEFAULT 0,
                parent_session_id TEXT
            )""", """
            CREATE TABLE IF NOT EXISTS projects (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                base_commit TEXT
            )""", """
            CREATE TABLE IF NOT EXISTS project_folders (
                project_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                path TEXT NOT NULL,
                PRIMARY KEY (project_id, position)
            )""", """
            CREATE TABLE IF NOT EXISTS events (
                session_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                run_id TEXT NOT NULL,
                type TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (session_id, sequence)
            )""", """
            CREATE TABLE IF NOT EXISTS runs (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                status TEXT NOT NULL,
                started_at TEXT NOT NULL,
                finished_at TEXT
            )""", """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )""", """
            CREATE TABLE IF NOT EXISTS conversation_messages (
                session_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                tool_calls TEXT,
                tool_call_id TEXT,
                attachments TEXT,
                image_positions TEXT,
                created_at TEXT NOT NULL,
                PRIMARY KEY (session_id, seq)
            )""", """
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
        // idx_sessions_project 依赖 migrate() 补的 project_id 列，不能在补列之前建
        "CREATE TABLE IF NOT EXISTS queued_messages (session_id TEXT NOT NULL, position INTEGER NOT NULL, content TEXT NOT NULL, PRIMARY KEY (session_id, position))",
        // 可恢复调度状态：重启后靠它判断「做到哪一步、下一步做什么、谁持有租约」。
        // 事件与消息历史只记录发生过什么，不能替代这份最小接管状态。
        """
            CREATE TABLE IF NOT EXISTS run_checkpoints (
                run_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                phase TEXT NOT NULL,
                step INTEGER NOT NULL DEFAULT 0,
                last_tool_call_id TEXT,
                last_successful_step TEXT,
                next_action TEXT,
                workspace TEXT,
                status TEXT NOT NULL,
                owner TEXT,
                lease_expires_at TEXT,
                heartbeat_at TEXT,
                updated_at TEXT NOT NULL
            )""", "CREATE INDEX IF NOT EXISTS idx_run_checkpoints_session ON run_checkpoints(session_id, status)",
        // 工具调用幂等账本：同一运行里参数相同的写文件调用只真正执行一次。
        """
            CREATE TABLE IF NOT EXISTS tool_call_journal (
                run_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                tool TEXT NOT NULL,
                result TEXT NOT NULL,
                changed_paths TEXT,
                checks TEXT,
                post_hash TEXT,
                created_at TEXT NOT NULL,
                PRIMARY KEY (run_id, fingerprint)
            )""", "CREATE INDEX IF NOT EXISTS idx_events_result_time ON events(type, created_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_events_run_sequence ON events(run_id, sequence DESC)",
        "CREATE TABLE IF NOT EXISTS schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)"};

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
    public int deleteEventsBefore(Instant cutoff) {
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
        // 侧边对话：从哪条主对话分叉出来。老库里的会话一律没有父会话，保持为普通对话
        addColumnIfMissing(statement, "sessions", "parent_session_id", "TEXT");
        addColumnIfMissing(statement, "sessions", "updated_at", "TEXT");
        addColumnIfMissing(statement, "sessions", "pinned", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(statement, "sessions", "unread", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(statement, "sessions", "title_manual", "INTEGER NOT NULL DEFAULT 0");
        // 老会话用最后一条事件回填活动时间；无事件时沿用创建时间。仅迁移一次。
        statement.execute("UPDATE sessions SET updated_at = COALESCE((SELECT created_at FROM events "
            + "WHERE session_id = sessions.id ORDER BY sequence DESC LIMIT 1), created_at) WHERE updated_at IS NULL");
        addColumnIfMissing(statement, "projects", "base_commit", "TEXT");
        // 图片附件已经由 AttachmentService 复制到稳定的数据目录；这里持久化引用，
        // 让 checkpoint / resume / 进程重启后仍能重建同一条多模态消息。
        addColumnIfMissing(statement, "conversation_messages", "attachments", "TEXT");
        addColumnIfMissing(statement, "conversation_messages", "image_positions", "TEXT");
        // 索引依赖上面补出来的列，必须放在补列之后
        statement.execute("CREATE INDEX IF NOT EXISTS idx_sessions_project ON sessions(project_id, created_at)");
        // 按父会话找侧边对话（关闭父对话时一并清理）走这条索引
        statement.execute("CREATE INDEX IF NOT EXISTS idx_sessions_parent ON sessions(parent_session_id)");
        statement.execute(
            "CREATE INDEX IF NOT EXISTS idx_sessions_activity ON sessions(pinned DESC, julianday(updated_at) DESC, id)");
        statement.execute(
            "CREATE INDEX IF NOT EXISTS idx_sessions_project_activity ON sessions(project_id, pinned DESC, julianday(updated_at) DESC, id)");
        initializeSearch(statement);
        // 记录结构版本，供数据迁移与备份恢复核对；写不进去不影响运行
        statement.execute("INSERT OR REPLACE INTO schema_meta (key, value) VALUES ('schema_version', '5')");
    }

    private static void initializeSearch(Statement s) throws SQLException {
        s.execute(
            "CREATE TABLE IF NOT EXISTS bookmark_imports (session_id TEXT NOT NULL, fingerprint TEXT NOT NULL, PRIMARY KEY(session_id,fingerprint))");
        s.execute(
            "CREATE TABLE IF NOT EXISTS message_bookmarks (session_id TEXT NOT NULL, sequence INTEGER NOT NULL, title TEXT NOT NULL, preview TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY(session_id,sequence))");
        s.execute(
            "CREATE VIRTUAL TABLE IF NOT EXISTS message_search USING fts5(session_id UNINDEXED, sequence UNINDEXED, text, tokenize='trigram')");
        String types = "'run.started','run.steered','assistant.delta'";
        s.execute("CREATE TRIGGER IF NOT EXISTS message_search_insert AFTER INSERT ON events WHEN new.type IN (" + types
            + ") BEGIN "
            + "INSERT INTO message_search(rowid,session_id,sequence,text) VALUES(new.rowid,new.session_id,new.sequence,COALESCE(json_extract(new.payload,'$.input'),json_extract(new.payload,'$.text'),'')); END");
        s.execute(
            "CREATE TRIGGER IF NOT EXISTS message_search_delete AFTER DELETE ON events BEGIN DELETE FROM message_search WHERE rowid=old.rowid; DELETE FROM message_bookmarks WHERE session_id=old.session_id AND sequence=old.sequence; END");
        s.execute(
            "CREATE TRIGGER IF NOT EXISTS message_search_update AFTER UPDATE ON events BEGIN DELETE FROM message_search WHERE rowid=old.rowid; "
                + "INSERT INTO message_search(rowid,session_id,sequence,text) SELECT new.rowid,new.session_id,new.sequence,COALESCE(json_extract(new.payload,'$.input'),json_extract(new.payload,'$.text'),'') WHERE new.type IN ("
                + types + "); END");
        try (var marker = s.executeQuery("SELECT value FROM schema_meta WHERE key='message_search_v1'")) {
            if (marker.next()) {
                return;
            }
        }
        s.execute(
            "INSERT OR REPLACE INTO message_search(rowid,session_id,sequence,text) SELECT rowid,session_id,sequence,COALESCE(json_extract(payload,'$.input'),json_extract(payload,'$.text'),'') FROM events WHERE type IN ("
                + types + ")");
        s.execute("INSERT INTO schema_meta(key,value) VALUES('message_search_v1','1')");
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
        try (ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
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
