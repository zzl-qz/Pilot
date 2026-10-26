package com.pilot.core.loop;

import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelClientException;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.tool.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompactorTest {

    @Test
    void foldsOldestGroupsIntoHandoffSummary() {
        var messages = history(8, 400);
        var compactor = new ContextCompactor(summarizing("## 目标\n完成 P0-1"), 6);

        ContextCompactor.Outcome outcome = compactor.compact(messages, 300);

        assertTrue(outcome.compacted());
        assertEquals(4, outcome.coveredMessages());
        assertTrue(outcome.charactersAfter() < outcome.charactersBefore());

        // system 之后紧接交接摘要，且以 user 角色插回，兼容 OpenAI 协议
        assertEquals("system", messages.get(0).role());
        assertEquals("user", messages.get(1).role());
        assertTrue(messages.get(1).content().contains("交接摘要"));
        assertTrue(messages.get(1).content().contains("完成 P0-1"));

        // 压缩不能把 tool 调用与结果切开：每个 tool 消息前面必须是一条带 tool_calls 的 assistant
        assertEquals(14, messages.size());
        assertToolPairsIntact(messages);
    }

    @Test
    void keepsHistoryWhenSummaryFails() {
        var messages = history(8, 400);
        ModelClient failing = (request, tools) -> {
            throw new ModelClientException("模型挂了");
        };
        var compactor = new ContextCompactor(failing, 6);

        ContextCompactor.Outcome outcome = compactor.compact(messages, 300);

        assertFalse(outcome.compacted());
        assertEquals(17, messages.size());
    }

    @Test
    void leavesHistoryAloneWhenUnderBudget() {
        var messages = history(3, 10);
        var compactor = new ContextCompactor(summarizing("不该被调用"), 6);

        ContextCompactor.Outcome outcome = compactor.compact(messages, 10_000);

        assertFalse(outcome.compacted());
        assertEquals(7, messages.size());
    }

    private static void assertToolPairsIntact(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (!"tool".equals(messages.get(i).role())) {
                continue;
            }
            Message previous = messages.get(i - 1);
            assertTrue(previous.hasToolCalls() || "tool".equals(previous.role()),
                    "tool 消息前面必须是 assistant(tool_calls) 或另一条 tool 消息");
        }
    }

    private static List<Message> history(int groups, int charsPerTool) {
        var messages = new ArrayList<Message>();
        messages.add(Message.system("sys"));
        for (int i = 0; i < groups; i++) {
            messages.add(Message.assistant("step " + i,
                    List.of(new ToolCall("c" + i, "read_file", Map.of("path", "f" + i)))));
            messages.add(Message.tool("c" + i, "x".repeat(charsPerTool)));
        }
        return messages;
    }

    private static ModelClient summarizing(String text) {
        return (request, tools) -> {
            assertEquals(2, request.size());
            assertTrue(tools.isEmpty(), "压缩请求不应带工具定义");
            return new ModelResponse(text, List.of());
        };
    }
}
