package com.pilot.server.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.Message;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.tool.ToolCall;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * SQLite 版对话记忆：一条消息一行，按 seq 排序读回。
 *
 * <p>整表替换而不是增量追加：一次运行的消息量在几十到几百条，替换的确定性更高，
 * 也避免压缩后「删一半」时出现残留。
 */
public final class JdbcConversationRepository implements ConversationRepository {
    private static final TypeReference<List<ToolCall>> TOOL_CALLS = new TypeReference<>() {};
    private static final TypeReference<List<Attachment>> ATTACHMENTS = new TypeReference<>() {};
    private static final TypeReference<List<Integer>> IMAGE_POSITIONS = new TypeReference<>() {};

    private final SqliteStore store;
    private final ObjectMapper mapper;

    public JdbcConversationRepository(SqliteStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override
    public List<Message> load(String sessionId) {
        return JdbcSupport.query(store,
            "SELECT role, content, tool_calls, tool_call_id, attachments, image_positions FROM conversation_messages "
                + "WHERE session_id = ? ORDER BY seq",
            statement -> statement.setString(1, sessionId),
            rows -> new Message(rows.getString(1), rows.getString(2), toolCalls(rows.getString(3)), rows.getString(4),
                attachments(rows.getString(5)), imagePositions(rows.getString(6))),
            "读取对话记忆失败: " + sessionId);
    }

    @Override
    public void save(String sessionId, List<Message> messages) {
        String now = Instant.now().toString();
        List<JdbcSupport.Binder> rows = new ArrayList<>();
        for (int seq = 0; seq < messages.size(); seq++) {
            Message message = messages.get(seq);
            int position = seq + 1;
            String toolCalls = message.toolCalls().isEmpty() ? null : mapper.writeValueAsString(message.toolCalls());
            String attachments =
                message.attachments().isEmpty() ? null : mapper.writeValueAsString(message.attachments());
            String imagePositions =
                message.imagePositions().isEmpty() ? null : mapper.writeValueAsString(message.imagePositions());
            rows.add(statement -> {
                statement.setString(1, sessionId);
                statement.setInt(2, position);
                statement.setString(3, message.role());
                statement.setString(4, message.content());
                statement.setString(5, toolCalls);
                statement.setString(6, message.toolCallId());
                statement.setString(7, attachments);
                statement.setString(8, imagePositions);
                statement.setString(9, now);
            });
        }
        JdbcSupport.inTransaction(store, connection -> {
            JdbcSupport.exec(connection, "DELETE FROM conversation_messages WHERE session_id = ?",
                statement -> statement.setString(1, sessionId));
            JdbcSupport.batch(connection, "INSERT INTO conversation_messages "
                + "(session_id, seq, role, content, tool_calls, tool_call_id, attachments, image_positions, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        }, "保存对话记忆失败: " + sessionId);
    }

    @Override
    public void delete(String sessionId) {
        JdbcSupport.update(store, "DELETE FROM conversation_messages WHERE session_id = ?",
            statement -> statement.setString(1, sessionId), "删除对话记忆失败: " + sessionId);
    }

    private List<ToolCall> toolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, TOOL_CALLS);
    }

    private List<Attachment> attachments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, ATTACHMENTS);
    }

    private List<Integer> imagePositions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, IMAGE_POSITIONS);
    }
}
