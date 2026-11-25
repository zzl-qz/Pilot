package com.pilot.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pilot.core.message.Message;

import tools.jackson.databind.ObjectMapper;

class ContextPinsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MARKER = "\n<pilot_context_metadata>";
    private static final String END = "</pilot_context_metadata>";

    @Test
    void keepsTheGoalWhenReadListIsLonger() {
        String goal = "用户原始目标：保留支付开关";
        String reads = "已读文件".repeat(400);
        String pins = "\n<pilot_context_metadata>{\"schema_version\":1,\"source_sha256\":\"abc\","
            + "\"covered_messages\":2,\"evidence_refs\":[\"" + reads + "\"],\"user_requests\":[\"" + goal
            + "\"]}</pilot_context_metadata>";

        String fitted = ContextPins.limit(pins, 500);

        assertTrue(fitted.contains(goal), fitted);
        assertFalse(fitted.contains(reads));
        assertTrue(fitted.length() <= 500, String.valueOf(fitted.length()));
    }

    @Test
    void excludesInternalInjectedMessagesFromUserRequests() {
        String goal = "用户原始目标：保留支付开关";
        List<Message> messages = new ArrayList<>(List.of(Message.system("system"), Message.user(goal)));
        for (int round = 0; round < 5; round++) {
            messages.add(Message.user("<system-reminder>\n[Runtime task context]\n第 " + round + " 版计划"));
        }
        messages.add(Message.toolImages("旧的工具截图", List.of()));
        messages.add(Message.user("## 仓库现状：一堆文件"));
        messages.add(Message.user("[系统提示·模型切换]切到新模型"));
        messages.add(Message.user("   <system-reminder>缩进后的提醒</system-reminder>"));

        String pins = ContextPins.capture(messages, messages.size(), "hash");

        assertEquals(List.of(goal), requests(pins));
        assertFalse(pins.contains("<system-reminder>"), pins);
    }

    @Test
    void keepsMetadataClosedAndBoundedAcrossOverLimitRounds() {
        int cap = 1_500;
        String goal = "最初任务：保留支付开关";
        String metadata = ContextPins.capture(List.of(Message.system("s"), Message.user(goal)), 2, "hash");
        for (int round = 0; round < 10; round++) {
            String correction = "第 " + round + " 轮用户纠正" + "细节".repeat(400);
            metadata = ContextPins.limit(
                ContextPins.capture(List.of(Message.system("s"),
                    Message.user("[上下文交接摘要]第 " + round + " 轮摘要" + metadata), Message.user(correction)), 3, "hash"),
                cap);
            assertTrue(metadata.length() <= cap, String.valueOf(metadata.length()));
            assertTrue(metadata.endsWith(END), metadata);
            assertEquals(1, occurrences(metadata, MARKER));
            assertEquals(goal, requests(metadata).get(0));
            assertTrue(requests(metadata).size() >= 2 && requests(metadata).getLast().contains("第 " + round + " 轮用户纠正"),
                "超预算时应淘汰中间旧请求并保留最新一轮用户纠正：" + requests(metadata));
        }
    }

    @Test
    void preservesOriginalGoalAndLatestSteeringByDroppingMiddleRequestsFirst() {
        String goal = "最初目标：完成订单重构";
        String mid1 = "中间补充一：" + "甲".repeat(300);
        String mid2 = "中间补充二：" + "乙".repeat(300);
        String latest = "最新纠正：不要改数据库字段名";
        String pins = ContextPins.capture(List.of(Message.system("s"), Message.user(goal), Message.user(mid1),
            Message.user(mid2), Message.user(latest)), 5, "hash");

        String fitted = ContextPins.limit(pins, 400);

        List<String> kept = requests(fitted);
        assertEquals(List.of(goal, latest), kept);
        assertTrue(fitted.length() <= 400);
    }

    @Test
    void truncatesInsideFirstRequestFieldWhenSingleGoalExceedsLimit() {
        String head = "HEAD_用户目标";
        String tail = "TAIL_验收条件";
        String goal = head + "要求".repeat(25_000) + tail;
        String pins = ContextPins.capture(List.of(Message.system("s"), Message.user(goal)), 2, "hash");

        String fitted = ContextPins.limit(pins, 30_000);

        assertTrue(fitted.length() <= 30_000, String.valueOf(fitted.length()));
        assertTrue(fitted.endsWith(END), String.valueOf(fitted.length()));
        assertEquals(1, occurrences(fitted, MARKER));
        assertTrue(fitted.contains("超长目标已截断"));
        String fittedGoal = requests(fitted).get(0);
        assertTrue(fittedGoal.startsWith(head), fittedGoal.substring(0, 20));
        assertTrue(fittedGoal.endsWith(tail), fittedGoal.substring(fittedGoal.length() - 20));
        // 已定型的元数据再被限额也不能再变，否则每轮压缩都会改写 messages[1]。
        assertEquals(fitted, ContextPins.limit(fitted, 30_000));
        assertEquals(ContextPins.capture(List.of(Message.system("s"), Message.user(goal)), 2, "hash"),
            ContextPins.limit(pins, 60_000));
        String next =
            ContextPins.capture(List.of(Message.system("s"), Message.user("[上下文交接摘要]\n" + fitted)), 2, "hash");
        assertEquals(List.of(fittedGoal), requests(next));
        assertEquals(1, occurrences(next, MARKER));
    }

    @Test
    void healsLegacyNestedDirtyRecordInOnePass() {
        String goal = "HEAL_用户原始目标：保留支付开关";
        String reference = "[工具输出存档 run_id=legacy call_id=c1；使用 read_tool_output]";
        String dirty = legacyNested(goal, reference);
        // 真实脏记录是 64,553 字符、嵌套 11 层、五万多个转义反斜杠，这里复刻同量级样本。
        assertTrue(dirty.length() > 60_000, String.valueOf(dirty.length()));

        String healed = ContextPins.limit(
            ContextPins.capture(List.of(Message.system("s"), Message.user("[上下文交接摘要]旧版摘要" + dirty)), 2, "hash"),
            16_000);

        assertTrue(healed.length() <= 16_000, String.valueOf(healed.length()));
        assertTrue(healed.endsWith(END), String.valueOf(healed.length()));
        assertEquals(1, occurrences(healed, MARKER));
        assertEquals(List.of(goal), requests(healed));
        assertTrue(healed.contains("call_id=c1"), healed);
        assertEquals(healed,
            ContextPins.capture(List.of(Message.system("s"), Message.user("[上下文交接摘要]\n" + healed)), 2, "hash"));
    }

    @Test
    void unparseablePinsCollapseToClosedShell() {
        String pins = "已读文件".repeat(400);

        String shell = ContextPins.limit(pins, 400);

        assertTrue(shell.length() <= 400, String.valueOf(shell.length()));
        assertTrue(shell.endsWith(END), shell);
        assertEquals(1, occurrences(shell, MARKER));
        assertFalse(shell.contains("钉住的上下文过长"));
        assertTrue(ContextPins.limit(pins, 60).isEmpty());
    }

    /** 复刻旧版缺陷：尾巴拼在闭合标签外，下一轮把整份旧摘要连同转义反斜杠一起再嵌一层。 */
    private static String legacyNested(String goal, String reference) {
        String metadata = metadata(List.of(goal), List.of(reference));
        for (int layer = 0; layer < 11; layer++) {
            String text = "[上下文交接摘要]旧版摘要第 " + layer + " 层" + metadata + "…（钉住的上下文过长，已保留末尾的目标）";
            metadata = metadata(List.of(text), List.of());
        }
        return metadata;
    }

    private static String metadata(List<String> requests, List<String> references) {
        return MARKER + JSON.writeValueAsString(Map.of("schema_version", 1, "source_sha256", "hash", "covered_messages",
            1, "user_requests", requests, "evidence_refs", references)) + END;
    }

    private static List<String> requests(String pins) {
        var metadata = JSON.readTree(pins.substring(pins.indexOf(MARKER) + MARKER.length(), pins.lastIndexOf(END)));
        List<String> requests = new ArrayList<>();
        metadata.path("user_requests").forEach(item -> requests.add(item.asText()));
        return requests;
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + part.length())) {
            count++;
        }
        return count;
    }
}
