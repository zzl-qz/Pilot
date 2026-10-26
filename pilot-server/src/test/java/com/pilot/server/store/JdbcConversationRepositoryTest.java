package com.pilot.server.store;

import com.pilot.core.loop.Message;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.tool.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

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
        var store = new SqliteStore(database);
        var mapper = JsonMapper.builder().build();
        conversations = new JdbcConversationRepository(store, mapper);
        plans = new JdbcPlanRepository(store);
    }

    @Test
    void persistsConversationAcrossReopen() {
        var messages = List.of(
                Message.user("把 P0-1 实现掉"),
                Message.assistant("先读代码", List.of(new ToolCall("c1", "read_file", Map.of("path", "AgentLoop.java")))),
                Message.tool("c1", "class AgentLoop { ... }"));

        conversations.save("ses_1", messages);

        // 重新打开同一个库，验证真的落盘
        var reopened = new JdbcConversationRepository(new SqliteStore(database), JsonMapper.builder().build());
        List<Message> loaded = reopened.load("ses_1");

        assertEquals(3, loaded.size());
        assertEquals("user", loaded.get(0).role());
        assertEquals("把 P0-1 实现掉", loaded.get(0).content());
        assertTrue(loaded.get(1).hasToolCalls());
        assertEquals("read_file", loaded.get(1).toolCalls().get(0).name());
        assertEquals("AgentLoop.java", loaded.get(1).toolCalls().get(0).args().get("path"));
        assertEquals("c1", loaded.get(2).toolCallId());
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

        var reopened = new JdbcPlanRepository(new SqliteStore(database));
        List<PlanItem> loaded = reopened.load("ses_1");
        assertEquals(2, loaded.size());
        assertEquals(PlanItem.IN_PROGRESS, loaded.get(1).status());

        // update_plan 是整体替换语义
        reopened.save("ses_1", List.of(new PlanItem("新计划", PlanItem.PENDING)));
        assertEquals(1, reopened.load("ses_1").size());
    }
}
