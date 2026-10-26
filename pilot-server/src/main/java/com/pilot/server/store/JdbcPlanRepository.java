package com.pilot.server.store;

import com.pilot.core.plan.PlanItem;
import com.pilot.core.session.PlanRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** SQLite 版计划存储：每次 update_plan 整体替换该会话的清单。 */
public final class JdbcPlanRepository implements PlanRepository {
    private final SqliteStore store;

    public JdbcPlanRepository(SqliteStore store) {
        this.store = store;
    }

    @Override
    public List<PlanItem> load(String sessionId) {
        String sql = "SELECT step, status FROM plan_items WHERE session_id = ? ORDER BY ord";
        try (Connection connection = store.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                var items = new ArrayList<PlanItem>();
                while (rows.next()) {
                    items.add(new PlanItem(rows.getString(1), rows.getString(2)));
                }
                return List.copyOf(items);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取计划失败: " + sessionId, e);
        }
    }

    @Override
    public void save(String sessionId, List<PlanItem> items) {
        String delete = "DELETE FROM plan_items WHERE session_id = ?";
        String insert = "INSERT INTO plan_items (session_id, ord, step, status) VALUES (?, ?, ?, ?)";
        try (Connection connection = store.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(delete)) {
                statement.setString(1, sessionId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                int ord = 0;
                for (PlanItem item : items) {
                    statement.setString(1, sessionId);
                    statement.setInt(2, ord++);
                    statement.setString(3, item.step());
                    statement.setString(4, item.status());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("保存计划失败: " + sessionId, e);
        }
    }

    @Override
    public void delete(String sessionId) {
        String sql = "DELETE FROM plan_items WHERE session_id = ?";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除计划失败: " + sessionId, e);
        }
    }
}
