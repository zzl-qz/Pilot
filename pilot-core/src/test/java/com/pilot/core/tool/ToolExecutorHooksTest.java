package com.pilot.core.tool;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.hook.Hooks;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 钩子必须真的参与工具执行：PostToolUse 回注输出，PreToolUse 能拦住执行。 */
class ToolExecutorHooksTest {
    @TempDir
    Path workspace;

    private final AtomicInteger executions = new AtomicInteger();

    private ToolExecutor executor(Hooks hooks) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "edit_file"; }
            @Override public String description() { return "写文件"; }
            @Override public Map<String, Object> parameters() { return Map.of("type", "object"); }
            @Override public boolean readOnly() { return false; }
            @Override public String execute(Map<String, Object> args) {
                executions.incrementAndGet();
                return "已写入";
            }
        });
        return new ToolExecutor(registry, new PolicyPermissionGate(PermissionMode.AUTO),
                ApprovalBroker.denyAll(), (call, result, latencyMs) -> { }, hooks);
    }

    private Hooks hooks(String json) throws IOException {
        Path dir = workspace.resolve(".pilot");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.json"), json);
        return Hooks.load(workspace);
    }

    @Test
    void postToolUseOutputIsAppendedToToolResult() throws IOException {
        Hooks loaded = hooks("""
                {"hooks":{"PostToolUse":[{"matcher":"edit_file","command":"echo 还有 2 处告警"}]}}
                """);
        assertTrue(loaded.run(Hooks.Event.POST_TOOL_USE, "edit_file").output().contains("告警"),
                "配置本身应能命中");
        ToolExecutor executor = executor(loaded);

        ToolExecutor.Outcome outcome = executor.execute(
                new ToolCall("call_1", "edit_file", Map.of("path", "a.txt")), event -> { });

        assertTrue(outcome.result().contains("已写入"), outcome.result());
        assertTrue(outcome.result().contains("还有 2 处告警"), "钩子输出要交给模型：" + outcome.result());
    }

    @Test
    void preToolUseBlockPreventsExecution() throws IOException {
        ToolExecutor executor = executor(hooks("""
                {"hooks":{"PreToolUse":[
                  {"matcher":"edit_file","command":"exit 1","blockOnFailure":true}
                ]}}
                """));

        ToolExecutor.Outcome outcome = executor.execute(
                new ToolCall("call_1", "edit_file", Map.of("path", "a.txt")), event -> { });

        assertEquals(0, executions.get(), "被钩子阻止时工具不得执行");
        assertTrue(outcome.result().startsWith("ERROR: PreToolUse"), outcome.result());
    }

    @Test
    void hooksDoNotRunWhenNoRuleMatches() {
        ToolExecutor executor = executor(Hooks.none());

        ToolExecutor.Outcome outcome = executor.execute(
                new ToolCall("call_1", "edit_file", Map.of("path", "a.txt")), event -> { });

        assertEquals("已写入", outcome.result());
        assertEquals(1, executions.get());
        assertTrue(outcome.changedPaths().isEmpty() || outcome.changedPaths().size() <= 1);
    }

    @Test
    void blankResultsAreStillReturnedUnchanged() throws IOException {
        ToolExecutor executor = executor(hooks("""
                {"hooks":{"PostToolUse":[{"matcher":"other","command":"echo nope"}]}}
                """));

        ToolExecutor.Outcome outcome = executor.execute(
                new ToolCall("call_1", "edit_file", Map.of("path", "a.txt")), event -> { });

        assertEquals(List.of(), outcome.changedPaths());
        assertEquals("已写入", outcome.result());
    }
}
