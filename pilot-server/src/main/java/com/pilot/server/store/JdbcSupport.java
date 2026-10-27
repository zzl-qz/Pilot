package com.pilot.server.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC 样板收敛。
 *
 * <p>每个仓储方法原本都要重复「开连接 → prepare → 绑定 → 执行 → 关流 → catch 包异常」，
 * 五个仓储里抄了二十多遍。这里把「取连接 + 关闭资源 + 统一错误消息」收在一处，
 * 仓储只负责 SQL 和行映射。
 */
final class JdbcSupport {

    @FunctionalInterface
    interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }

    @FunctionalInterface
    interface TransactionWork {
        void run(Connection connection) throws SQLException;
    }

    private JdbcSupport() {
    }

    /** 查询多行；返回不可变列表。 */
    static <T> List<T> query(SqliteStore store, String sql, Binder binder, RowMapper<T> mapper, String failure) {
        try (Connection connection = store.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                var result = new ArrayList<T>();
                while (rows.next()) {
                    result.add(mapper.map(rows));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(failure, e);
        }
    }

    /** 查询单行；没有结果返回空。 */
    static <T> Optional<T> queryOne(SqliteStore store, String sql, Binder binder, RowMapper<T> mapper,
                                    String failure) {
        List<T> rows = query(store, sql, binder, mapper, failure);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 执行一条写语句并返回影响行数。 */
    static int update(SqliteStore store, String sql, Binder binder, String failure) {
        try (Connection connection = store.open()) {
            return exec(connection, sql, binder);
        } catch (SQLException e) {
            throw new IllegalStateException(failure, e);
        }
    }

    /** 事务内执行；抛异常自动回滚，成功后提交。 */
    static void inTransaction(SqliteStore store, TransactionWork work, String failure) {
        try (Connection connection = store.open()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(failure, e);
        }
    }

    /** 事务内的单条写语句。 */
    static int exec(Connection connection, String sql, Binder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        }
    }

    /** 事务内的批量写语句。 */
    static void batch(Connection connection, String sql, List<Binder> binders) throws SQLException {
        if (binders.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Binder binder : binders) {
                binder.bind(statement);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
