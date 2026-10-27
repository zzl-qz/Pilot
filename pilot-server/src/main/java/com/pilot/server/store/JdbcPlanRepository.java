package com.pilot.server.store;

import com.pilot.core.plan.PlanItem;
import com.pilot.core.session.PlanRepository;

import java.util.ArrayList;
import java.util.List;

/** SQLite 版计划存储：每次 update_plan 整体替换该会话的清单。 */
public final class JdbcPlanRepository implements PlanRepository {
    private static final String DELETE = "DELETE FROM plan_items WHERE session_id = ?";
    private static final String INSERT = "INSERT INTO plan_items (session_id, ord, step, status) VALUES (?, ?, ?, ?)";

    private final SqliteStore store;

    public JdbcPlanRepository(SqliteStore store) {
        this.store = store;
    }

    @Override
    public List<PlanItem> load(String sessionId) {
        return JdbcSupport.query(store,
                "SELECT step, status FROM plan_items WHERE session_id = ? ORDER BY ord",
                statement -> statement.setString(1, sessionId),
                rows -> new PlanItem(rows.getString(1), rows.getString(2)),
                "读取计划失败: " + sessionId);
    }

    @Override
    public void save(String sessionId, List<PlanItem> items) {
        List<JdbcSupport.Binder> rows = new ArrayList<>();
        for (int ord = 0; ord < items.size(); ord++) {
            PlanItem item = items.get(ord);
            int position = ord;
            rows.add(statement -> {
                statement.setString(1, sessionId);
                statement.setInt(2, position);
                statement.setString(3, item.step());
                statement.setString(4, item.status());
            });
        }
        JdbcSupport.inTransaction(store, connection -> {
            JdbcSupport.exec(connection, DELETE, statement -> statement.setString(1, sessionId));
            JdbcSupport.batch(connection, INSERT, rows);
        }, "保存计划失败: " + sessionId);
    }

    @Override
    public void delete(String sessionId) {
        JdbcSupport.update(store, DELETE, statement -> statement.setString(1, sessionId),
                "删除计划失败: " + sessionId);
    }
}
