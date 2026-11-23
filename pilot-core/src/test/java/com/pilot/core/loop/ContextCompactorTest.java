package com.pilot.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pilot.core.message.Attachment;
import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelClientException;
import com.pilot.core.model.ModelProfile;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.model.diagnostics.ModelRequestScope;

class ContextCompactorTest {

    @Test
    void foldsOlderHistoryIntoBudgetedHandoffSummary() {
        List<Message> messages = history(10, 3_000);
        ContextCompactor compactor = new ContextCompactor(summarizing("## 目标\n完成 P0-1"), 2);
        int before = ContextCompactor.totalChars(messages);

        ContextCompactor.Outcome outcome = compactor.compact(messages, 12_000);

        assertTrue(outcome.compacted());
        assertEquals(before, outcome.charactersBefore());
        assertTrue(outcome.charactersAfter() < 12_000, "压缩后应落回预算内，实际 " + outcome.charactersAfter());
        assertTrue(outcome.coveredMessages() >= 14, "应折叠掉大部分旧消息，实际 " + outcome.coveredMessages());

        // system 之后紧接交接摘要，且以 user 角色插回，兼容 OpenAI 协议
        assertEquals("system", messages.get(0).role());
        assertEquals("user", messages.get(1).role());
        assertTrue(messages.get(1).content().contains("交接摘要"));
        assertTrue(messages.get(1).content().contains("完成 P0-1"));
        assertToolPairsIntact(messages);
    }

    @Test
    void compactionKeepsFileHistoryAcrossMultipleSummaries() {
        List<Message> messages = history(10, 3_000);
        ModelClient summarizer = (request, tools) -> {
            assertTrue(request.get(1).content().contains("read_file f0"), "摘要输入要包含实际工具目标，不能只有工具名");
            return new ModelResponse("已完成先前工作", List.of());
        };
        ContextCompactor first = new ContextCompactor(summarizer, 2);

        assertTrue(first.compact(messages, 12_000).compacted());
        assertTrue(messages.get(1).content().contains("<read_files>\nf0\n"));

        for (int i = 100; i < 110; i++) {
            messages.add(
                Message.assistant("step " + i, List.of(new ToolCall("c" + i, "read_file", Map.of("path", "f" + i)))));
            messages.add(Message.tool("c" + i, "x".repeat(3_000)));
        }
        ContextCompactor second = new ContextCompactor(summarizing("继续完成"), 2);

        assertTrue(second.compact(messages, 12_000).compacted());
        assertTrue(messages.get(1).content().contains("f0"), "上一份摘要里的文件路径必须继承");
        assertTrue(messages.get(1).content().contains("f100"));
    }

    @Test
    void compactionTracksModifiedFilesSeparatelyFromReads() {
        List<Message> messages = history(10, 3_000);
        messages.add(1, Message.assistant("edit", List.of(new ToolCall("edit", "edit_file",
            Map.of("path", "src/A.java", "old_string", "old", "new_string", "new")))));
        messages.add(2, Message.tool("edit", "已修改 src/A.java"));
        messages.add(3,
            Message.assistant("read", List.of(new ToolCall("read", "read_file", Map.of("path", "src/A.java")))));
        messages.add(4, Message.tool("read", "content"));

        assertTrue(new ContextCompactor(summarizing("摘要"), 2).compact(messages, 12_000).compacted());
        String summary = messages.get(1).content();
        assertTrue(summary.contains("<modified_files>\nsrc/A.java\n</modified_files>"), summary);
        assertFalse(summary.contains("<read_files>\nsrc/A.java"), summary);
    }

    @Test
    void failedOrSkippedToolCallsDoNotBecomeFileHistory() {
        List<Message> messages = history(10, 3_000);
        messages.add(1, Message.assistant("edit",
            List.of(new ToolCall("failed-edit", "edit_file", Map.of("path", "src/Untouched.java")))));
        messages.add(2, Message.tool("failed-edit", "ERROR: 原文没有匹配"));
        messages.add(3, Message.assistant("read",
            List.of(new ToolCall("skipped-read", "read_file", Map.of("path", "src/Unread.java")))));
        messages.add(4, Message.tool("skipped-read", "SKIPPED: 用户已纠正任务"));

        assertTrue(new ContextCompactor(summarizing("摘要"), 2).compact(messages, 12_000).compacted());
        String summary = messages.get(1).content();
        assertFalse(summary.contains("src/Untouched.java"), summary);
        assertFalse(summary.contains("src/Unread.java"), summary);
    }

    @Test
    void keepsHistoryWhenSummaryIsNotShorter() {
        // 可折叠的部分很小，摘要却很长：省不下预算，只会丢信息，应当放弃这次压缩
        List<Message> messages = history(4, 50);
        ContextCompactor compactor = new ContextCompactor(summarizing("摘要".repeat(2_500)), 2);
        int before = messages.size();

        ContextCompactor.Outcome outcome = compactor.compact(messages, 150);

        assertFalse(outcome.compacted(), "没有收益的压缩不应改动历史");
        assertEquals(before, messages.size());
    }

    @Test
    void keepsHistoryWhenSummaryFails() {
        List<Message> messages = history(10, 3_000);
        ModelClient failing = (request, tools) -> {
            throw new ModelClientException("模型挂了");
        };
        ContextCompactor compactor = new ContextCompactor(failing, 2);
        int before = messages.size();

        ContextCompactor.Outcome outcome = compactor.compact(messages, 12_000);

        assertFalse(outcome.compacted());
        assertEquals(before, messages.size());
    }

    @Test
    void leavesHistoryAloneWhenUnderBudget() {
        List<Message> messages = history(3, 10);
        ContextCompactor compactor = new ContextCompactor(summarizing("不该被调用"), 2);

        ContextCompactor.Outcome outcome = compactor.compact(messages, 10_000);

        assertFalse(outcome.compacted());
        assertEquals(7, messages.size());
    }

    @Test
    void prunesOldToolOutputsButKeepsToolIdentity() {
        List<Message> messages = history(6, 5_000);

        int pruned = ContextCompactor.pruneToolOutputs(messages, 20_000);

        assertTrue(pruned >= 2, "窗口外的旧输出应被替换，实际 " + pruned);
        String prunedText = messages.get(2).content();
        assertTrue(prunedText.startsWith("[旧工具输出已清理"), prunedText);
        assertTrue(prunedText.contains("read_file"), "占位符要说明原本读的是什么");
    }

    @Test
    void neverPrunesTheNewestToolObservation() {
        List<Message> messages = history(6, 5_000);
        Message newest = messages.getLast();

        ContextCompactor.pruneToolOutputs(messages, 20_000);

        assertEquals(newest, messages.getLast());
    }

    @Test
    void truncatesOversizedToolOutputKeepingHeadAndTail() {
        List<Message> messages = history(1, 20_000);

        int changed = ContextCompactor.truncateLongToolOutputs(messages, 3);

        assertEquals(1, changed);
        String content = messages.get(2).content();
        assertTrue(content.length() < 6_000, "截断后应远小于原文，实际 " + content.length());
        assertTrue(content.startsWith("x".repeat(100)), "保留开头");
        assertTrue(content.contains("中间已省略"));
        assertTrue(content.endsWith("x".repeat(100)), "保留结尾");
    }

    @Test
    void dropsOldestGroupsOnlyWhenStillOverBudget() {
        List<Message> messages = history(10, 3_000);

        int dropped = ContextCompactor.dropOldestGroups(messages, 7_000);

        assertTrue(dropped >= 10, "应丢弃最旧的消息组，实际 " + dropped);
        assertEquals("system", messages.get(0).role());
        assertEquals("user", messages.get(1).role());
        assertTrue(messages.get(1).content().contains("已因上下文超限丢弃"));
        assertTrue(ContextCompactor.totalChars(messages) <= 7_500, "丢弃后应回到预算附近");
        assertToolPairsIntact(messages);
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

    /** 双预算：字符还很空、但 token 已经吃紧时，也要按 token 压。 */
    @Test
    void compactsWhenTokenBudgetIsTheTighterOne() {
        List<Message> messages = history(20, 3_000);
        ContextCompactor compactor = new ContextCompactor(summarizing("摘要"), 2);

        ContextCompactor.Outcome outcome = compactor.compact(messages, 100_000, 8_000);

        assertTrue(outcome.compacted(), "token 预算更紧时应以 token 为准");
        int tokens = ContextCompactor.estimateTokens(messages);
        assertTrue(tokens < 8_000, "压缩后应落回 token 预算内，实际 " + tokens);
    }

    /** 压完要留出足够余量，否则再跑十几步就得压第二次。 */
    @Test
    void leavesHalfTheBudgetFreeAfterCompaction() {
        List<Message> messages = history(20, 3_000);
        ContextCompactor compactor = new ContextCompactor(summarizing("摘要"), 2);

        ContextCompactor.Outcome outcome = compactor.compact(messages, 20_000, 20_000);

        assertTrue(outcome.compacted());
        assertTrue(ContextCompactor.totalChars(messages) <= 11_000,
            "压缩后应降到目标的一半附近，实际 " + ContextCompactor.totalChars(messages));
    }

    @Test
    void defaultCompactorKeepsFiveRecentActionObservationGroups() {
        List<Message> messages = history(10, 3_000);
        ContextCompactor compactor = new ContextCompactor(summarizing("摘要"));

        ContextCompactor.Outcome outcome = compactor.compact(messages, 12_000);

        assertTrue(outcome.compacted());
        long toolResults = messages.stream().filter(message -> "tool".equals(message.role())).count();
        assertEquals(5, toolResults, "压缩摘要之外应保留最近五组完整工具结果");
        assertToolPairsIntact(messages);
    }

    @Test
    void pinsImageAttachmentsWhenTheirMessageIsCompacted() {
        Attachment image = new Attachment("shot.png", "image/png", "/stable/attachments/shot.png");
        ArrayList<Message> messages = new ArrayList<>();
        messages.add(Message.system("sys"));
        messages.add(Message.user("请根据这张图实现", List.of(image)));
        messages.addAll(history(10, 3_000).subList(1, 21));
        ContextCompactor compactor = new ContextCompactor(summarizing("目标：按截图实现"), 2);

        ContextCompactor.Outcome outcome = compactor.compact(messages, 12_000);

        assertTrue(outcome.compacted());
        assertEquals(List.of(image), messages.get(1).attachments(), "折叠旧文字时必须把原图引用固定在交接摘要上");
    }

    @Test
    void pinsImageAttachmentsWhenHardDroppingOldGroups() {
        Attachment image = new Attachment("shot.png", "image/png", "/stable/attachments/shot.png");
        ArrayList<Message> messages = new ArrayList<>();
        messages.add(Message.system("sys"));
        messages.add(Message.user("请看图", List.of(image)));
        messages.addAll(history(10, 3_000).subList(1, 21));

        int dropped = ContextCompactor.dropOldestGroups(messages, 7_000);

        assertTrue(dropped > 0);
        assertEquals(List.of(image), messages.get(1).attachments(), "硬裁剪也只能丢文字，不能丢失任务所依赖的原图");
    }

    @Test
    void repeatedHardDropsKeepPinnedMetadataBoundedClosedAndCapPinnedImages() {
        ArrayList<Message> messages = new ArrayList<>();
        messages.add(Message.system("sys"));
        for (int i = 1; i <= 4; i++) {
            messages.add(Message.user("用户图任务 " + i,
                List.of(new Attachment("u" + i + ".png", "image/png", "/stable/u" + i + ".png"))));
        }
        messages.addAll(history(12, 3_000).subList(1, 25));

        int dropped = ContextCompactor.dropOldestGroups(messages, 9_000, 4_000);
        assertTrue(dropped > 0);
        String pinned = messages.get(1).content();
        assertTrue(pinned.endsWith("</pilot_context_metadata>"), pinned);
        assertTrue(pinned.length() <= 4_500, "硬裁剪元数据必须有界，实际 " + pinned.length());
        assertEquals(ContextCompactor.MAX_PINNED_USER_IMAGES, messages.get(1).attachments().size());
        assertTrue(pinned.contains("u1.png") && pinned.contains("u2.png"), "更早原图应转为路径引用保留");
    }

    /** 前缀复用：带上原历史与工具定义，末尾只追加禁工具整理指令，并置 toolsDisabled。 */
    @Test
    void prefixSummaryReusesHistoryAndToolsBeforeAppendingInstruction() {
        List<Message> messages = history(10, 3_000);
        List<Message> original = List.copyOf(messages);
        List<ToolDefinition> definitions = List.of(new ToolDefinition("read_file", "read", Map.of("type", "object")));
        ArrayList<List<Message>> requests = new ArrayList<>();
        ArrayList<List<ToolDefinition>> sentTools = new ArrayList<>();
        ModelClient client = new ModelClient() {
            public ModelProfile profile() {
                return ModelProfile.resolve(null, "m", "generic", "auto", "", null, null, "prefix", null);
            }

            public ModelResponse chat(List<Message> request, List<ToolDefinition> tools) {
                requests.add(List.copyOf(request));
                sentTools.add(List.copyOf(tools));
                assertTrue(ModelRequestScope.current().toolsDisabled(), "前缀复用摘要必须禁工具");
                assertEquals("compaction", ModelRequestScope.current().purpose());
                return new ModelResponse("摘要", List.of());
            }
        };

        ContextCompactor.Outcome outcome = new ContextCompactor(client, 2).withTools(() -> definitions)
            .withTokenLimit(() -> 104_000).withCharLimit(() -> 400_000).compact(messages, 8_000);

        assertTrue(outcome.compacted());
        assertEquals(1, requests.size(), "只应发一次前缀复用请求");
        assertEquals(original, requests.getFirst().subList(0, original.size()), "前缀必须与当前会话一致");
        assertEquals(original.size() + 1, requests.getFirst().size(), "末尾只追加一条整理指令");
        assertTrue(requests.getFirst().getLast().content().contains("不调用工具"));
        assertEquals(definitions, sentTools.getFirst());
        assertFalse(ModelRequestScope.current().toolsDisabled(), "调用栈退出后必须恢复");
    }

    /** 端点回了工具调用：前缀复用结果不可用，静默回落短摘录，不阻断压缩。 */
    @Test
    void prefixSummaryFallsBackToShortInputWhenEndpointReturnsToolCalls() {
        List<Message> messages = history(10, 3_000);
        List<ToolDefinition> definitions = List.of(new ToolDefinition("read_file", "read", Map.of("type", "object")));
        ArrayList<List<Message>> requests = new ArrayList<>();
        ModelClient client = new ModelClient() {
            public ModelProfile profile() {
                return ModelProfile.resolve(null, "m", "generic", "auto", "", null, null, "prefix", null);
            }

            public ModelResponse chat(List<Message> request, List<ToolDefinition> tools) {
                requests.add(List.copyOf(request));
                return tools.isEmpty() ? new ModelResponse("回落摘要", List.of())
                    : new ModelResponse("", List.of(new ToolCall("bad", "read_file", Map.of("path", "f0"))));
            }
        };

        ContextCompactor.Outcome outcome = new ContextCompactor(client, 2).withTools(() -> definitions)
            .withTokenLimit(() -> 104_000).withCharLimit(() -> 400_000).compact(messages, 8_000);

        assertTrue(outcome.compacted(), "前缀被拒绝时仍要完成压缩");
        assertEquals(2, requests.size(), "前缀失败后应再发一次短摘录请求");
        assertTrue(requests.getFirst().size() > 2);
        assertEquals(2, requests.getLast().size(), "短摘录只带系统提示与摘录正文");
        assertTrue(messages.get(1).content().contains("回落摘要"));
    }

    /** 字符上限不够时直接走短摘录，不拿字符总数去比 token 预算。 */
    @Test
    void skipsPrefixWhenCharacterCapLeavesNoHeadroom() {
        List<Message> messages = history(10, 3_000);
        List<ToolDefinition> definitions = List.of(new ToolDefinition("read_file", "read", Map.of("type", "object")));
        ArrayList<List<Message>> requests = new ArrayList<>();
        ModelClient client = new ModelClient() {
            public ModelProfile profile() {
                return ModelProfile.resolve(null, "m", "generic", "auto", "", null, null, "prefix", null);
            }

            public ModelResponse chat(List<Message> request, List<ToolDefinition> tools) {
                requests.add(List.copyOf(request));
                return new ModelResponse("短摘录摘要", List.of());
            }
        };

        ContextCompactor.Outcome outcome = new ContextCompactor(client, 2).withTools(() -> definitions)
            .withTokenLimit(() -> 104_000).withCharLimit(() -> 20_000).compact(messages, 8_000);

        assertTrue(outcome.compacted());
        assertEquals(1, requests.size());
        assertEquals(2, requests.getFirst().size(), "字符护栏不足时只能发短摘录");
    }

    /** 中文按 1 字符 1 token 估算，不能沿用「4 字符 1 token」把中文上下文算少。 */
    @Test
    void estimatesChineseTokensHigherThanAscii() {
        List<Message> ascii = List.of(Message.user("a".repeat(100)));
        List<Message> chinese = List.of(Message.user("中".repeat(100)));

        assertEquals(25, ContextCompactor.estimateTokens(ascii));
        assertEquals(100, ContextCompactor.estimateTokens(chinese));
    }

    @Test
    void stripsAnalysisScratchpadAndKeepsSummaryAcrossVariants() {
        assertEquals("正式摘要",
            ContextCompactor.extractSummary("<analysis>先核对文件</analysis>\n<summary>\n正式摘要\n</summary>"));
        assertEquals("正文", ContextCompactor.extractSummary("<ANALYSIS>草稿</ANALYSIS><SUMMARY>正文</SUMMARY>"));
        assertEquals("无标签摘要", ContextCompactor.extractSummary("  无标签摘要  "));
        assertEquals("只写了正文", ContextCompactor.extractSummary("<analysis>ok</analysis>\n只写了正文"));
        assertEquals("", ContextCompactor.extractSummary("<analysis>被 max_tokens 截断在思考阶段"),
            "未闭合 analysis 必须丢弃并判失败，绝不把草稿塞回上下文");
        assertEquals("半截正文", ContextCompactor.extractSummary("<analysis>ok</analysis><summary>半截正文"));
    }

    @Test
    void dualStageCompactionStripsAnalysisFromConversationAndPassesBoundedFocus() {
        List<Message> messages = history(10, 3_000);
        ArrayList<String> prompts = new ArrayList<>();
        ModelClient client = (request, tools) -> {
            prompts.add(request.getFirst().content());
            return new ModelResponse("<analysis>草稿泄露检测标记_SECRET</analysis>\n<summary>\n## 原始目标\n保留契约\n</summary>",
                List.of());
        };

        String focus = "保留 API 契约 " + "x".repeat(600);
        ContextCompactor.Outcome outcome = new ContextCompactor(client, 2).compact(messages, 12_000, 12_000, focus);

        assertTrue(outcome.compacted());
        assertFalse(messages.get(1).content().contains("草稿泄露检测标记_SECRET"));
        assertTrue(messages.get(1).content().contains("保留契约"));
        assertTrue(prompts.getFirst().contains("保留 API 契约"));
        assertFalse(prompts.getFirst().contains("x".repeat(550)), "focus 超出上限必须截断");
    }

    @Test
    void prefixCompactionIncludesFocusInstruction() {
        List<Message> messages = history(10, 3_000);
        List<ToolDefinition> definitions = List.of(new ToolDefinition("read_file", "read", Map.of("type", "object")));
        ArrayList<String> tailPrompts = new ArrayList<>();
        ModelClient client = new ModelClient() {
            @Override
            public ModelProfile profile() {
                return ModelProfile.resolve(null, "m", "generic", "auto", "", null, null, "prefix", null);
            }

            @Override
            public ModelResponse chat(List<Message> request, List<ToolDefinition> tools) {
                tailPrompts.add(request.getLast().content());
                return new ModelResponse("<analysis>核对</analysis><summary>前缀复用摘要</summary>", List.of());
            }
        };

        ContextCompactor.Outcome outcome =
            new ContextCompactor(client, 2).withTools(() -> definitions).withTokenLimit(() -> 104_000)
                .withCharLimit(() -> 400_000).compact(messages, 8_000, 8_000, "保留数据库 schema 决策");

        assertTrue(outcome.compacted());
        assertTrue(tailPrompts.getFirst().contains("保留数据库 schema 决策"));
        assertTrue(messages.get(1).content().contains("前缀复用摘要"));
        assertFalse(messages.get(1).content().contains("<analysis>"));
    }

    @Test
    void explicitCacheProfileUsesPrefixCompactionOnlyAfterObservedCacheHit() {
        List<ToolDefinition> definitions = List.of(new ToolDefinition("read_file", "read", Map.of("type", "object")));
        ModelProfile anthropicShort =
            ModelProfile.resolve(null, "claude-sonnet-4-6", "anthropic", "explicit", "", null, null, "short", null);

        ArrayList<List<Message>> hitRequests = new ArrayList<>();
        ModelClient hitClient = new ModelClient() {
            @Override
            public ModelProfile profile() {
                return anthropicShort;
            }

            @Override
            public ModelResponse chat(List<Message> request, List<ToolDefinition> tools) {
                hitRequests.add(List.copyOf(request));
                return new ModelResponse("<summary>显式缓存前缀摘要</summary>", List.of());
            }
        };
        List<Message> hitMessages = history(10, 3_000);
        ContextCompactor.Outcome hitOutcome =
            new ContextCompactor(hitClient, 2).withTools(() -> definitions).withTokenLimit(() -> 104_000)
                .withCharLimit(() -> 400_000).withObservedCacheHit(() -> true).compact(hitMessages, 8_000);
        assertTrue(hitOutcome.compacted());
        assertEquals(1, hitRequests.size());
        assertTrue(hitRequests.getFirst().size() > 2, "观测到缓存命中后显式缓存端点应走前缀复用压缩");

        ArrayList<List<Message>> coldRequests = new ArrayList<>();
        ModelClient coldClient = new ModelClient() {
            @Override
            public ModelProfile profile() {
                return anthropicShort;
            }

            @Override
            public ModelResponse chat(List<Message> request, List<ToolDefinition> tools) {
                coldRequests.add(List.copyOf(request));
                return new ModelResponse("冷启动短摘要", List.of());
            }
        };
        List<Message> coldMessages = history(10, 3_000);
        ContextCompactor.Outcome coldOutcome =
            new ContextCompactor(coldClient, 2).withTools(() -> definitions).withTokenLimit(() -> 104_000)
                .withCharLimit(() -> 400_000).withObservedCacheHit(() -> false).compact(coldMessages, 8_000);
        assertTrue(coldOutcome.compacted());
        assertEquals(1, coldRequests.size());
        assertEquals(2, coldRequests.getFirst().size(), "未命中缓存前仍走短摘录压缩");
    }

    private static List<Message> history(int groups, int charsPerTool) {
        ArrayList<Message> messages = new ArrayList<Message>();
        messages.add(Message.system("sys"));
        for (int i = 0; i < groups; i++) {
            messages.add(
                Message.assistant("step " + i, List.of(new ToolCall("c" + i, "read_file", Map.of("path", "f" + i)))));
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
