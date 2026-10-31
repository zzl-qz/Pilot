package com.pilot.server.store;

import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.Message;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.tool.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcConversationRepositoryTest {
    @TempDir
    Path tempDir;

    private Path database;
    private JdbcConversationRepository conversations;
    private JdbcPlanRepository plans;

    @BeforeEach
    void setUp() {
        database = tempDir.resolve("pilot.db");
        SqliteStore store = new SqliteStore(database);
        JsonMapper mapper = JsonMapper.builder().build();
        conversations = new JdbcConversationRepository(store, mapper);
        plans = new JdbcPlanRepository(store);
    }

    @Test
    void persistsConversationAcrossReopen() throws Exception {
        Path image = tempDir.resolve("attachments").resolve("shot.png").toAbsolutePath();
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{1, 2, 3});
        List<Message> messages = List.of(
                Message.user("把 P0-1 实现掉", List.of(new Attachment("shot.png", "image/png", image.toString()))),
                Message.assistant("先读代码", List.of(new ToolCall("c1", "read_file", Map.of("path", "AgentLoop.java")))),
                Message.tool("c1", "class AgentLoop { ... }"));

        conversations.save("ses_1", messages);

        // 重新打开同一个库，验证真的落盘
        JdbcConversationRepository reopened = new JdbcConversationRepository(new SqliteStore(database), JsonMapper.builder().build());
        List<Message> loaded = reopened.load("ses_1");

        assertEquals(3, loaded.size());
        assertEquals("user", loaded.get(0).role());
        assertEquals("把 P0-1 实现掉", loaded.get(0).content());
        assertEquals(List.of(new Attachment("shot.png", "image/png", image.toString())),
                loaded.get(0).attachments());
        assertTrue(Files.isRegularFile(Path.of(loaded.get(0).attachments().getFirst().path())),
                "附件引用应继续指向 AttachmentService 的稳定落盘文件");
        assertTrue(loaded.get(1).hasToolCalls());
        assertEquals("read_file", loaded.get(1).toolCalls().get(0).name());
        assertEquals("AgentLoop.java", loaded.get(1).toolCalls().get(0).args().get("path"));
        assertEquals("c1", loaded.get(2).toolCallId());
    }

    @Test
    void readsLegacyRowsWithoutAttachments() throws Exception {
        try (var connection = new SqliteStore(database).open();
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO conversation_messages "
                    + "(session_id, seq, role, content, tool_calls, tool_call_id, created_at) VALUES "
                    + "('legacy', 1, 'user', '旧消息', NULL, NULL, '2026-01-01T00:00:00Z')");
        }

        List<Message> loaded = conversations.load("legacy");

        assertEquals(1, loaded.size());
        assertTrue(loaded.getFirst().attachments().isEmpty());
    }

    @Test
    void replacesPreviousConversationOnSave() {
        conversations.save("ses_1", List.of(Message.user("第一轮"), Message.assistant("好的", List.of())));
        conversations.save("ses_1", List.of(Message.user("压缩后的摘要")));

        assertEquals(1, conversations.load("ses_1").size());
        assertEquals("压缩后的摘要", conversations.load("ses_1").get(0).content());
    }

    @Test
    void storesPlanAsWholeList() {
        plans.save("ses_1", List.of(new PlanItem("读代码", PlanItem.COMPLETED),
                new PlanItem("写实现", PlanItem.IN_PROGRESS)));

        JdbcPlanRepository reopened = new JdbcPlanRepository(new SqliteStore(database));
        List<PlanItem> loaded = reopened.load("ses_1");
        assertEquals(2, loaded.size());
        assertEquals(PlanItem.IN_PROGRESS, loaded.get(1).status());

        // update_plan 是整体替换语义
        reopened.save("ses_1", List.of(new PlanItem("新计划", PlanItem.PENDING)));
        assertEquals(1, reopened.load("ses_1").size());
    }
}
