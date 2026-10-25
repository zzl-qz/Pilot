package com.pilot.server.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        String sql = "INSERT INTO settings (key, value) VALUES (?, ?) "
                + "ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存设置失败: " + key, e);
        }
    }
}
