package com.pilot.core.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HooksTest {
    @TempDir
    Path workspace;

    private void settings(String json) throws IOException {
        Path dir = workspace.resolve(".pilot");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.json"), json);
    }

    /** 这些用例只验证配置解析，执行器权限由 HookPermissionsTest 覆盖。 */
    private Hooks.Result run(Hooks hooks, Hooks.Event event, String name) {
        return hooks.run(event, name, command -> {
            var tool = new HookCommandTool(command, null);
            tool.execute(Map.of());
            return tool.result("");
        });
    }

    @Test
    void postToolUseOutputIsReturnedForMatchingTool() throws IOException {
        settings("""
            {"hooks":{"PostToolUse":[
              {"matcher":"edit_file|create_file","command":"echo formatted"}
            ]}}
            """);
        Hooks hooks = Hooks.load(workspace);

        assertTrue(run(hooks, Hooks.Event.POST_TOOL_USE, "edit_file").output().contains("formatted"));
        assertTrue(run(hooks, Hooks.Event.POST_TOOL_USE, "read_file").output().isEmpty(), "未匹配的工具不应执行钩子");
    }

    @Test
    void wildcardMatcherRunsForEveryTool() throws IOException {
        settings("""
            {"hooks":{"PostToolUse":[{"matcher":"*","command":"echo any"}]}}
            """);
        Hooks hooks = Hooks.load(workspace);

        assertTrue(run(hooks, Hooks.Event.POST_TOOL_USE, "run_command").output().contains("any"));
    }

    @Test
    void preToolUseBlocksOnlyWhenConfigured() throws IOException {
        settings("""
            {"hooks":{"PreToolUse":[
              {"matcher":"run_command","command":"exit 1","blockOnFailure":true}
            ]}}
            """);
        Hooks hooks = Hooks.load(workspace);

        Hooks.Result blocked = run(hooks, Hooks.Event.PRE_TOOL_USE, "run_command");

        assertTrue(blocked.blocked(), "配置了 blockOnFailure 时失败必须阻止工具执行");
    }

    @Test
    void failingHookWithoutBlockFlagNeverBlocks() throws IOException {
        settings("""
            {"hooks":{"PreToolUse":[{"matcher":"run_command","command":"exit 1"}]}}
            """);
        Hooks hooks = Hooks.load(workspace);

        assertFalse(run(hooks, Hooks.Event.PRE_TOOL_USE, "run_command").blocked());
    }

    @Test
    void malformedSettingsDegradeToNoHooks() throws IOException {
        settings("{ not json");

        Hooks hooks = Hooks.load(workspace);

        assertEquals("", run(hooks, Hooks.Event.POST_TOOL_USE, "edit_file").output());
    }

    @Test
    void nestedClaudeCodeFormatIsSupported() throws IOException {
        settings("""
            {"hooks":{"PostToolUse":[
              {"matcher":"edit_file","hooks":[{"type":"command","command":"echo nested"}]}
            ]}}
            """);
        Hooks hooks = Hooks.load(workspace);

        assertTrue(run(hooks, Hooks.Event.POST_TOOL_USE, "edit_file").output().contains("nested"));
    }
}
