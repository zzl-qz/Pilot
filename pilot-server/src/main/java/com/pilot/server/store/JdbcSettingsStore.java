package com.pilot.server.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/** SQLite 版设置存储，与其它表共用同一个库文件。 */
public final class JdbcSettingsStore implements SettingsStore {
    private final SqliteStore store;

    public JdbcSettingsStore(SqliteStore store) {
        this.store = store;
    }

    @Override
    public Optional<String> find(String key) {
        String sql = "SELECT value FROM settings WHERE key = ?";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取设置失败: " + key, e);
        }
    }

    @Override
    public void save(String key, String value) {
        saveAll(Map.of(key, value));
    }

    /**
     * 一个事务里写完全部键：任一条失败就整体回滚，读者不会看到写了一半的配置。
     *
     * <p>除 {@link SQLException} 外也兜住运行时异常（例如值为 null），
     * 保证连接关闭前一定回滚，而不是依赖驱动的隐式行为。
     */
    @Override
    public void saveAll(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO settings (key, value) VALUES (?, ?) "
                + "ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        try (Connection connection = store.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (var entry : values.entrySet()) {
                    statement.setString(1, entry.getKey());
                    statement.setString(2, entry.getValue());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("批量保存设置失败", e);
        }
    }

    private static void rollback(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }
}
