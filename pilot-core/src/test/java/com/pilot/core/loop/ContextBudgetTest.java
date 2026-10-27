package com.pilot.core.loop;

import com.pilot.core.event.ContextCompacted;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelClientException;
import com.pilot.core.tool.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBudgetTest {

    @Test
    void leavesHistoryAloneWellUnderBudget() {
        var messages = textHistory(3, 100);
        var calls = new AtomicInteger();
        var budget = new ContextBudget(recording(calls, covered(5)));
        var events = new ArrayList<SessionEvent>();

        boolean compacted = budget.apply(messages, 100_000, events::add);

        assertFalse(compacted);
        assertEquals(0, calls.get(), "没到水位不该花模型调用");
        assertTrue(events.isEmpty());
        assertEquals(7, messages.size());
    }

    @Test
    void compactsAndReportsWhenOverTheThreshold() {
        var messages = textHistory(4, 1_000);
        int before = ContextCompactor.totalChars(messages);
        // usage ≈ 0.85：越过预压缩水位，但没到强制档，只压一轮
        int limit = (int) (before / 0.85);
        var calls = new AtomicInteger();
        var budget = new ContextBudget(recording(calls, covered(5)));
        var events = new ArrayList<SessionEvent>();

        boolean compacted = budget.apply(messages, limit, events::add);

        assertTrue(compacted);
        assertEquals(1, calls.get(), "越过水位应压一次");
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof ContextCompacted);
    }

    /** 摘要没有收益时不要每步都重试：退避到上下文再涨 5% 之后。 */
    @Test
    void backsOffAfterAFruitlessCompaction() {
        var messages = textHistory(4, 1_000);
        int limit = (int) (ContextCompactor.totalChars(messages) / 0.85);
        var calls = new AtomicInteger();
        // covered == 0 表示压不动（摘要不比原文短、或模型失败）
        var budget = new ContextBudget(recording(calls, notCompacted()));

        boolean first = budget.apply(messages, limit, event -> { });
        assertFalse(first, "没有收益的压缩不算压缩");
        assertEquals(1, calls.get());

        boolean second = budget.apply(messages, limit, event -> { });
        assertFalse(second);
        assertEquals(1, calls.get(), "退避期间不应再调用摘要模型");
    }

    @Test
    void retriesAfterContextGrowsPastTheBackoffWatermark() {
        var messages = textHistory(4, 1_000);
        int limit = (int) (ContextCompactor.totalChars(messages) / 0.85);
        var calls = new AtomicInteger();
        var budget = new ContextBudget(recording(calls, notCompacted()));

        budget.apply(messages, limit, event -> { });
        assertEquals(1, calls.get());

        // 又读了一大段文件：上下文涨过退避水位，应该再试一次
        messages.add(Message.assistant("新读到的内容".repeat(2_000), List.of()));
        messages.add(Message.user("继续"));
        budget.apply(messages, limit, event -> { });

        assertEquals(2, calls.get(), "上下文继续增长后应重新尝试压缩");
    }

    /** 摘要失败也必须有兜底，否则下一次请求直接超窗。 */
    @Test
    void hardDropsWhenCompactionIsNotEnough() {
        var messages = toolHistory(30, 8_000);
        ModelClient failing = (request, tools) -> {
            throw new ModelClientException("摘要模型挂了");
        };
        var budget = new ContextBudget(new ContextCompactor(failing, 2)::compact);

        budget.apply(messages, 60_000, event -> { });

        assertTrue(ContextCompactor.totalChars(messages) <= 60_000,
                "兜底后应回到预算内，实际 " + ContextCompactor.totalChars(messages));
        assertEquals("system", messages.get(0).role());
        // 不花模型调用的第一层兜底确实执行过：旧工具输出被换成了占位符
        assertTrue(messages.stream().anyMatch(message -> "tool".equals(message.role())
                        && message.content().startsWith("[旧工具输出已清理")),
                "摘要失败时应该先靠占位符把体积降下来");
    }

    @Test
    void usageTakesTheFullerOfCharsAndTokens() {
        var ascii = List.of(Message.user("a".repeat(400)));
        var chinese = List.of(Message.user("中".repeat(400)));

        // 同样 400 字符：英文约 100 token，中文约 400 token。字符预算宽松时取 token 口径
        assertTrue(ContextBudget.usage(chinese, 10_000, 1_000) > ContextBudget.usage(ascii, 10_000, 1_000));
        // 字符预算更紧时取字符口径：两者字符数相同，占用也应该相同
        assertEquals(ContextBudget.usage(chinese, 1_000, 10_000), ContextBudget.usage(ascii, 1_000, 10_000));
    }

    /** 纯文本历史：prune / truncate 只处理 tool 消息，这里可以精确控制水位。 */
    private static List<Message> textHistory(int groups, int chars) {
        var messages = new ArrayList<Message>();
        messages.add(Message.system("sys"));
        for (int i = 0; i < groups; i++) {
            messages.add(Message.assistant("a".repeat(chars), List.of()));
            messages.add(Message.user("u".repeat(chars)));
        }
        return messages;
    }

    private static List<Message> toolHistory(int groups, int charsPerTool) {
        var messages = new ArrayList<Message>();
        messages.add(Message.system("sys"));
        for (int i = 0; i < groups; i++) {
            messages.add(Message.assistant("step " + i,
                    List.of(new ToolCall("c" + i, "read_file", Map.of("path", "f" + i)))));
            messages.add(Message.tool("c" + i, "x".repeat(charsPerTool)));
        }
        return messages;
    }

    private static ContextCompactor.Outcome covered(int messages) {
        return new ContextCompactor.Outcome(messages, 12_000, 8_000, "交接摘要");
    }

    private static ContextCompactor.Outcome notCompacted() {
        return new ContextCompactor.Outcome(0, 12_000, 12_000, "");
    }

    private static ContextBudget.Compactor recording(AtomicInteger calls, ContextCompactor.Outcome outcome) {
        return (messages, charBudget, tokenBudget) -> {
            calls.incrementAndGet();
            return outcome;
        };
    }
}
