package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class AskUserToolTest {
    @Test
    void choiceAndCustomTextBothReturnToTheTask() throws Exception {
        QuestionBroker broker = new QuestionBroker();
        AskUserTool tool = new AskUserTool(broker);
        AtomicReference<String> picked = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            ToolCallScope.bind("call-1");
            try {
                picked.set(tool.execute(Map.of("question", "用哪种方案", "options", List.of("保留", "删除"))));
            } finally {
                ToolCallScope.clear();
            }
        });
        waiter.start();
        Thread.sleep(50);
        assertTrue(broker.answer("call-1", "删除"));
        waiter.join(2000);
        assertTrue(picked.get().contains("删除"), picked.get());

        ToolCallScope.bind("call-2");
        try {
            Thread custom = new Thread(() -> {
                ToolCallScope.bind("call-2");
                try {
                    picked.set(tool.execute(Map.of("question", "用哪种方案", "options", List.of("保留", "删除"))));
                } finally {
                    ToolCallScope.clear();
                }
            });
            custom.start();
            Thread.sleep(50);
            assertTrue(broker.answer("call-2", "我自己写的方案"));
            custom.join(2000);
        } finally {
            ToolCallScope.clear();
        }
        assertTrue(picked.get().contains("我自己写的方案"), picked.get());
        assertFalse(broker.answer("missing", "删除"));
        assertTrue(tool.execute(Map.of("question", "只有一个", "options", List.of("仅此"))).startsWith("ERROR"));
        assertTrue(tool.execute(Map.of("question", "（占位，稍后用）", "options", List.of("a", "b"))).contains("占位"));
    }

    @Test
    void tracksSessionOwnershipCancellationAndTimeoutCleanup() throws Exception {
        QuestionBroker broker = new QuestionBroker();
        AtomicInteger notifications = new AtomicInteger();
        Runnable listener = notifications::incrementAndGet;
        broker.addListener(listener);

        AskUserTool scopedTool = new AskUserTool(broker, "ses_owner");
        AtomicReference<String> cancelResult = new AtomicReference<>();
        Thread waitCancel = new Thread(() -> {
            ToolCallScope.bind("call-session-cancel");
            try {
                cancelResult.set(scopedTool.execute(Map.of("question", "发布到哪", "options", List.of("测试环境", "生产环境"))));
            } finally {
                ToolCallScope.clear();
            }
        });
        waitCancel.start();
        Thread.sleep(60);

        List<QuestionBroker.PendingQuestion> pending = broker.pending();
        assertEquals(1, pending.size());
        assertEquals("ses_owner", pending.getFirst().sessionId());
        assertEquals("发布到哪", pending.getFirst().question());
        assertEquals(List.of("测试环境", "生产环境"), pending.getFirst().options());
        assertNotNull(pending.getFirst().expiresAt());

        // 错误会话不可代答
        assertFalse(broker.answer("ses_other", "call-session-cancel", "测试环境"));
        assertEquals(1, broker.pending().size());

        // 取消时立刻唤醒并从 pending 清除
        assertTrue(broker.cancel("call-session-cancel"));
        waitCancel.join(2000);
        assertTrue(cancelResult.get().contains("已取消"), cancelResult.get());
        assertTrue(broker.pending().isEmpty());
        assertTrue(notifications.get() >= 2);

        // 正确会话可以提交答案
        AtomicReference<String> okResult = new AtomicReference<>();
        Thread waitAnswer = new Thread(() -> {
            ToolCallScope.bind("call-session-ok");
            try {
                okResult.set(scopedTool.execute(Map.of("question", "是否继续", "options", List.of("继续", "停止"))));
            } finally {
                ToolCallScope.clear();
            }
        });
        waitAnswer.start();
        Thread.sleep(60);
        assertTrue(broker.answer("ses_owner", "call-session-ok", "继续"));
        waitAnswer.join(2000);
        assertTrue(okResult.get().contains("继续"), okResult.get());
        assertTrue(broker.pending().isEmpty());

        // 超时退出并清理，不泄漏等待记录
        AtomicReference<String> timeoutResult = new AtomicReference<>();
        Thread waitTimeout = new Thread(() -> {
            ToolCallScope.bind("call-timeout");
            try {
                timeoutResult.set(scopedTool.execute(Map.of("question", "会超时的选择", "options", List.of("选一", "选二")),
                    Duration.ofMillis(40)));
            } finally {
                ToolCallScope.clear();
            }
        });
        waitTimeout.start();
        waitTimeout.join(2000);
        assertTrue(timeoutResult.get().contains("限定时间内"), timeoutResult.get());
        assertTrue(broker.pending().isEmpty());
    }
}
