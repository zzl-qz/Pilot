package com.pilot.server.store;

import com.pilot.core.loop.Message;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.tool.ToolCall;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 版对话记忆：一条消息一行，按 seq 排序读回。
 *
 * <p>整表替换而不是增量追加：一次运行的消息量在几十到几百条，替换的确定性更高，
 * 也避免压缩后「删一半」时出现残留。
 */
public final class JdbcConversationRepository implements ConversationRepository {
    private static final TypeReference<List<ToolCall>> TOOL_CALLS = new TypeReference<>() { };

    private final SqliteStore store;
    private final ObjectMapper mapper;

    public JdbcConversationRepository(SqliteStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override
    public List<Message> load(String sessionId) {
        String sql = "SELECT role, content, tool_calls, tool_call_id FROM conversation_messages "
                + "WHERE session_id = ? ORDER BY seq";
        try (Connection connection = store.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                var messages = new ArrayList<Message>();
                while (rows.next()) {
                    messages.add(new Message(
                            rows.getString(1),
                            rows.getString(2),
                            toolCalls(rows.getString(3)),
                            rows.getString(4)));
                }
                return List.copyOf(messages);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取对话记忆失败: " + sessionId, e);
        }
    }

    @Override
    public void save(String sessionId, List<Message> messages) {
        String delete = "DELETE FROM conversation_messages WHERE session_id = ?";
        String insert = "INSERT INTO conversation_messages "
                + "(session_id, seq, role, content, tool_calls, tool_call_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = store.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(delete)) {
                statement.setString(1, sessionId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                String now = Instant.now().toString();
                int seq = 1;
                for (Message message : messages) {
                    statement.setString(1, sessionId);
                    statement.setInt(2, seq++);
                    statement.setString(3, message.role());
                    statement.setString(4, message.content());
                    statement.setString(5, message.toolCalls().isEmpty()
                            ? null : mapper.writeValueAsString(message.toolCalls()));
                    statement.setString(6, message.toolCallId());
                    statement.setString(7, now);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("保存对话记忆失败: " + sessionId, e);
        }
    }

    @Override
    public void delete(String sessionId) {
        String sql = "DELETE FROM conversation_messages WHERE session_id = ?";
        try (Connection connection = store.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除对话记忆失败: " + sessionId, e);
        }
    }

    private List<ToolCall> toolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, TOOL_CALLS);
    }
}
