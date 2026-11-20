package com.pilot.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.RequestBudget;

class ContextIntegrityTest {
    @Test
    void protectsEveryUnobservedResultAcrossAllBudgetLayers() {
        List<Message> messages = new ArrayList<>(List.of(Message.system("system"), Message.user("原始目标"),
            Message.assistant("",
                List.of(new ToolCall("a", "read_file", Map.of()), new ToolCall("b", "read_file", Map.of()),
                    new ToolCall("c", "read_file", Map.of()))),
            Message.tool("a", "A".repeat(30000)), Message.tool("b", "B".repeat(30000)),
            Message.tool("c", "C".repeat(30000))));
        var budget = new ContextBudget((m, c, t) -> ContextCompactor.Outcome.none(ContextCompactor.totalChars(m)));
        budget.apply(messages, 40000, e -> {
        }, 2);
        for (Message message : messages) {
            if ("tool".equals(message.role())) {
                assertEquals(30000, message.content().length());
            }
        }
        RequestBudget.validateProtocol(messages);
    }

    @Test
    void preservesUserGoalVerbatimThroughRepeatedCompactionEvenWhenSummaryOmitsIt() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("system"));
        messages.add(Message.user("USER_GOAL_所有功能都要完成，不只修改文档"));
        for (int i = 0; i < 90; i++) {
            messages.add(Message.assistant("观察" + "x".repeat(2000), List.of()));
        }
        var compactor = new ContextCompactor((m, t) -> new ModelResponse("没有保留目标的模型摘要", List.of()));
        assertTrue(compactor.compact(messages, 20000).compacted());
        assertTrue(messages.get(1).content().contains("USER_GOAL_所有功能都要完成，不只修改文档"));
        assertTrue(messages.get(1).content().contains("source_sha256"));
        for (int i = 0; i < 40; i++) {
            messages.add(Message.assistant("y".repeat(2000), List.of()));
        }
        assertTrue(compactor.compact(messages, 20000).compacted());
        assertTrue(messages.get(1).content().contains("USER_GOAL_所有功能都要完成，不只修改文档"));
    }

    @Test
    void archiveFailureDoesNotDestroyOnlyCopyAndArchiveReferenceSurvivesPruning() {
        List<Message> messages =
            new ArrayList<>(List.of(Message.system("s"), Message.tool("lost", "x".repeat(12000) + "\n[工具输出未归档，禁止清理原文]"),
                Message.tool("saved", "y".repeat(12000) + "\n[工具输出存档 run_id=r call_id=saved；使用 read_tool_output]"),
                Message.user("next")));
        ContextCompactor.pruneToolOutputs(messages, 100, 3);
        ContextCompactor.truncateLongToolOutputs(messages, 3);
        assertTrue(messages.get(1).content().length() > 12000);
        assertTrue(messages.get(2).content().contains("run_id=r call_id=saved"));
    }

    @Test
    void documentMentioningUnarchivedLiteralDoesNotBlockPruningCompactionOrHardDrop() {
        String archivedDoc = "规格说明：保留 `[工具输出未归档` 保护。\n" + "正文".repeat(3_500)
            + "\n[工具输出存档 run_id=r1 call_id=doc1；使用 read_tool_output 分页读取，禁止重放原操作]";
        ArrayList<Message> messages = new ArrayList<>();
        messages.add(Message.system("s"));
        messages.add(Message.user("实现功能"));
        messages.add(Message.assistant("", List.of(new ToolCall("doc1", "read_file", Map.of("path", "tasks.md")))));
        messages.add(Message.tool("doc1", archivedDoc));
        for (int i = 0; i < 8; i++) {
            messages.add(Message.assistant("step " + i, List.of(new ToolCall("c" + i, "read_file", Map.of()))));
            messages.add(Message.tool("c" + i, "x".repeat(3_000)));
        }

        int pruned = ContextCompactor.pruneToolOutputs(messages, 10_000, messages.size() - 2);
        assertTrue(pruned >= 1);
        assertTrue(messages.get(3).content().startsWith("[旧工具输出已清理"), "含文档字面量但已正常归档的读文件结果必须可被清理");

        var compactor = new ContextCompactor((m, t) -> new ModelResponse("正常交接摘要", List.of()), 2);
        assertTrue(compactor.compact(messages, 12_000).compacted(), "文档里出现未归档字面量绝不能瘫痪整轮摘要压缩");
    }

    @Test
    void proseMentioningArchivePlaceholderIsNotCopiedAsTrailingArchiveReference() {
        String proseDoc =
            "规格要求：末尾附标准 `[工具输出存档 run_id=... call_id=...]` 引用，总长严格 <= 6,500 字符。\n" + "其他章节正文".repeat(1_200);
        ArrayList<Message> messages = new ArrayList<>();
        messages.add(Message.system("s"));
        messages.add(Message.user("任务"));
        messages.add(Message.assistant("", List.of(new ToolCall("doc1", "read_tool_output", Map.of()))));
        messages.add(Message.tool("doc1", proseDoc));
        for (int i = 0; i < 6; i++) {
            messages.add(Message.assistant("step " + i, List.of(new ToolCall("c" + i, "read_file", Map.of()))));
            messages.add(Message.tool("c" + i, "x".repeat(3_000)));
        }

        int pruned = ContextCompactor.pruneToolOutputs(messages, 10_000, messages.size() - 2);
        assertTrue(pruned >= 1);
        String placeholder = messages.get(3).content();
        assertTrue(placeholder.startsWith("[旧工具输出已清理"), placeholder);
        assertTrue(placeholder.length() < 250, "正文里的占位示例绝不能被截取进清理占位符: len=" + placeholder.length());
        assertFalse(placeholder.contains("其他章节正文"), placeholder);

        String pins = ContextPins
            .capture(List.of(Message.system("s"), Message.user("任务"), Message.tool("doc1", proseDoc)), 3, "hash");
        assertFalse(pins.contains("run_id=..."), "正文示例不应进入 evidence_refs: " + pins);
    }

    @Test
    void repeatedMetadataMergeDoesNotRecursivelyCopySerializedReferences() {
        String reference = "[工具输出存档 run_id=r call_id=c；使用 read_tool_output]";
        String metadata = ContextPins.capture(
            List.of(Message.system("s"), Message.user("最初任务"), Message.tool("c", "result" + reference)), 3, "hash");
        int initialLength = metadata.length();
        for (int i = 0; i < 10; i++) {
            metadata = ContextPins.capture(List.of(Message.system("s"), Message.user("[上下文交接摘要]summary" + metadata)), 2,
                "hash");
            assertEquals(initialLength, metadata.length());
        }
        String malformed = "[上下文交接摘要]\n<pilot_context_metadata>{bad}</pilot_context_metadata>";
        assertTrue(
            ContextPins.capture(List.of(Message.system("s"), Message.user(malformed)), 2, "hash").contains("{bad}"));
    }

    @Test
    void repeatedOverLimitLimitAndCaptureStayBounded() {
        String goal = "USER_GOAL_所有功能都要完成";
        int cap = 1_500;
        String metadata = ContextPins.capture(List.of(Message.system("s"), Message.user(goal)), 2, "hash");
        for (int round = 0; round < 10; round++) {
            String request = "第 " + round + " 轮用户输入" + "细节".repeat(400);
            metadata = ContextPins.limit(ContextPins.capture(
                List.of(Message.system("s"), Message.user("[上下文交接摘要]摘要" + metadata), Message.user(request)), 3, "hash"),
                cap);
            assertTrue(metadata.length() <= cap, String.valueOf(metadata.length()));
            assertTrue(metadata.endsWith("</pilot_context_metadata>"), metadata);
            assertEquals(metadata.indexOf("<pilot_context_metadata>"),
                metadata.lastIndexOf("<pilot_context_metadata>"));
            assertTrue(metadata.contains(goal), metadata);
        }
    }

    @Test
    void foldsHistoryWithoutPinningRuntimeReminders() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("system"));
        messages.add(Message.user("USER_GOAL_保留支付开关"));
        for (int round = 0; round < 6; round++) {
            messages.add(Message.user("<system-reminder>\n[Runtime task context]\n第 " + round + " 版计划"));
            messages.add(Message.assistant("观察" + "x".repeat(2000), List.of()));
        }
        var compactor = new ContextCompactor((m, t) -> new ModelResponse("摘要", List.of()), 2);
        var outcome = compactor.compact(messages, 9000);
        assertTrue(outcome.compacted());
        assertTrue(outcome.coveredMessages() >= 6, String.valueOf(outcome.coveredMessages()));
        String summary = messages.get(1).content();
        assertTrue(summary.contains("USER_GOAL_保留支付开关"), summary);
        assertFalse(summary.contains("[Runtime task context]"), summary);
        assertFalse(summary.contains("第 3 版计划"), summary);
    }
}
