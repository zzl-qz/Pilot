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

class ToolOutputPresenterTest {

    @Test
    void oversizedBuildFailureLogCompactsBelowTruncationThresholdWithErrorsHeadTailAndRef() {
        StringBuilder log = new StringBuilder();
        log.append("任务ID: cmd_1\n状态: FAILED\n退出码: 1\n命令: mvnw test\n输出:\n");
        log.append("[INFO] Scanning for projects...\r\n");
        for (int i = 0; i < 600; i++) {
            log.append("[INFO] Compiling source file number ").append(i).append(" padding-text-padding-text\r\n");
        }
        log.append("[ERROR] AssertionFailedError: expected: <true> but was: <false>\r\n");
        for (int i = 0; i < 400; i++) {
            log.append("[INFO] Cleanup step ").append(i).append(" extra-verbose-log-line\r\n");
        }
        log.append("Tests run: 12, Failures: 1, Errors: 0\r\n[ERROR] BUILD FAILURE\r\n");
        String ref = "\n[工具输出存档 run_id=r1 call_id=c1；使用 read_tool_output 分页读取，禁止重放原操作]";

        String compacted = ToolOutputPresenter.compactCommandOutput(log.toString(), ref);

        assertTrue(compacted.length() <= ToolOutputPresenter.MAX_PRESENTED_CHARS,
            "定型输出必须 <= 6500 字符，实际 " + compacted.length());
        assertFalse(compacted.contains("\r"), "Windows CRLF 应统一规范化为 LF");
        assertTrue(compacted.contains("状态: FAILED"), compacted);
        assertTrue(compacted.contains("AssertionFailedError"), compacted);
        assertTrue(compacted.contains("BUILD FAILURE"), compacted);
        assertTrue(compacted.contains("Tests run: 12, Failures: 1"), compacted);
        assertTrue(compacted.endsWith(ref), compacted);

        // 首次定型后的消息低于 8,192 字符，不会被 ContextCompactor.truncateLongToolOutputs 二次改写
        List<Message> history = new ArrayList<>(
            List.of(Message.system("s"), Message.assistant("", List.of(new ToolCall("c1", "run_command", Map.of()))),
                Message.tool("c1", compacted), Message.user("继续")));
        assertEquals(0, ContextCompactor.truncateLongToolOutputs(history, history.size()));
    }

    @Test
    void shortOutputRemainsVerbatim() {
        String raw = "任务ID: cmd_2\n状态: COMPLETED\n退出码: 0\n输出:\nok";
        assertEquals(raw, ToolOutputPresenter.compactCommandOutput(raw, ""));
    }
}
