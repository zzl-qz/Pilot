package com.pilot.core.tool.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ProcessListToolTest {
    @Test
    void listsAtLeastTheCurrentProcessWithStableColumns() {
        ProcessListTool tool = new ProcessListTool();
        String current = String.valueOf(ProcessHandle.current().pid());
        String all = tool.execute(Map.of());
        assertTrue(all.startsWith("进程视图：可见 "), "头部要说明可见数量：" + all);
        assertTrue(all.contains("\nPID\t父PID\t启动时间\t用户\t命令\n"), "列头固定：" + all);
        // 按 PID 升序截断，自己可能排在 limit 之外，所以用 filter 定位自己。
        String self = tool.execute(Map.of("filter", current));
        assertTrue(self.lines().anyMatch(line -> line.startsWith(current + "\t")), "要能列出自己：" + self);
    }

    @Test
    void filterEchoesConditionAndReportsNoMatch() {
        ProcessListTool tool = new ProcessListTool();
        String bySelf = tool.execute(Map.of("filter", String.valueOf(ProcessHandle.current().pid())));
        assertTrue(bySelf.contains("filter="), "过滤时回显条件：" + bySelf);

        String none = tool.execute(Map.of("filter", "no-such-process-3f9a2c"));
        assertTrue(none.contains("没有匹配的进程"), "无匹配要明说：" + none);
        assertFalse(none.contains("\tjava\t"), "无匹配时不该列进程行：" + none);
    }

    @Test
    void limitIsClampedAndReportedWhenTruncated() {
        String text = new ProcessListTool().execute(Map.of("limit", 1));
        long rows = text.lines().skip(2).filter(line -> line.contains("\t")).count();
        assertEquals(1, rows, "limit=1 时只列一行：" + text);
        assertTrue(text.contains("只列前 1 个"), "截断时要说明：" + text);
    }

    @Test
    void sensitiveArgumentsAreRedacted() {
        String redacted = ProcessListTool.redact("curl --token abc123 --password \"p@ss word\" https://example.com");
        assertTrue(redacted.contains("--token ***"), "token 值要遮盖：" + redacted);
        assertTrue(redacted.contains("--password ***"), "password 值要遮盖：" + redacted);
        assertTrue(redacted.contains("https://example.com"), "普通参数保持原样：" + redacted);
        assertFalse(redacted.contains("abc123"), "原值不能残留：" + redacted);
    }

    @Test
    void schemaAcceptsOnlyFilterAndLimit() {
        Map<String, Object> parameters = new ProcessListTool().parameters();
        assertEquals("object", parameters.get("type"));
        assertEquals(java.util.List.of(), parameters.get("required"), "两个参数都可选");
        assertTrue(((Map<?, ?>) parameters.get("properties")).keySet().containsAll(java.util.List.of("filter", "limit")));
        assertTrue(new ProcessListTool().readOnly(), "进程查看是只读");
    }
}