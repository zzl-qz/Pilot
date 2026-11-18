package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;

class RequestBudgetRepairTest {
    private final ToolCall first = new ToolCall("call_a", "read_file", Map.of());
    private final ToolCall second = new ToolCall("call_b", "read_file", Map.of());

    @Test
    void fillsCallsLeftAtTheEndOfHistory() {
        var messages = new ArrayList<Message>();
        messages.add(Message.assistant("先看两处", List.of(first, second)));
        RequestBudget.closeDanglingToolCalls(messages);
        RequestBudget.validateProtocol(messages);
        assertEquals("call_a", messages.get(1).toolCallId());
        assertEquals("call_b", messages.get(2).toolCallId());
        assertTrue(messages.get(1).content().contains("没有执行"));
    }

    @Test
    void keepsARealResultThatArrivedAfterTheNextUserMessage() {
        var messages = new ArrayList<Message>();
        messages.add(Message.assistant("", List.of(first, second)));
        messages.add(Message.user("继续"));
        messages.add(Message.tool("call_b", "文件正文"));
        RequestBudget.closeDanglingToolCalls(messages);
        RequestBudget.validateProtocol(messages);
        assertEquals("call_a", messages.get(1).toolCallId());
        assertEquals("文件正文", messages.get(2).content());
        assertEquals("user", messages.get(3).role());
        assertFalse(messages.stream().skip(4).anyMatch(message -> "call_b".equals(message.toolCallId())));
    }

    @Test
    void fillsOnlyTheMissingCallWhenSomeResultsAreAlreadyPresent() {
        var messages = new ArrayList<Message>();
        messages.add(Message.assistant("", List.of(first, second)));
        messages.add(Message.tool("call_a", "已读"));
        messages.add(Message.user("继续"));
        RequestBudget.closeDanglingToolCalls(messages);
        RequestBudget.validateProtocol(messages);
        assertEquals("已读", messages.get(1).content());
        assertEquals("call_b", messages.get(2).toolCallId());
        assertEquals("user", messages.get(3).role());
    }

    @Test
    void dropsOrphanResultsAndBlankOrDuplicateCallIds() {
        var duplicate = new ToolCall("call_a", "read_file", Map.of());
        var blank = new ToolCall("  ", "read_file", Map.of());
        var messages = new ArrayList<Message>();
        messages.add(Message.tool("stray", "不该留下"));
        messages.add(Message.assistant("", List.of(first, duplicate, blank)));
        messages.add(Message.tool("call_a", "正文"));
        RequestBudget.closeDanglingToolCalls(messages);
        RequestBudget.validateProtocol(messages);
        assertEquals(1, messages.get(0).toolCalls().size());
        assertEquals("正文", messages.get(1).content());
        assertTrue(messages.stream().noneMatch(message -> "stray".equals(message.toolCallId())));
    }

    @Test
    void leavesAnAlreadyValidTranscriptUntouched() {
        var messages = new ArrayList<>(List.of(Message.system("s"), Message.user("目标"),
            Message.assistant("", List.of(second, first)), Message.tool("call_b", "B"), Message.tool("call_a", "A")));
        var before = List.copyOf(messages);
        RequestBudget.closeDanglingToolCalls(messages);
        assertEquals(before, messages);
    }
}
