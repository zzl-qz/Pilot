package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
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
    }
}
