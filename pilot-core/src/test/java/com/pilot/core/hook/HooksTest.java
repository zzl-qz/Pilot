package com.pilot.core.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HooksTest {
    @TempDir
    Path workspace;

    private void settings(String json) throws IOException {
        Path dir = workspace.resolve(".pilot");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.json"), json);
    }

    @Test
    void postToolUseOutputIsReturnedForMatchingTool() throws IOException {
        settings("""
                {"hooks":{"PostToolUse":[
                  {"matcher":"edit_file|create_file","command":"echo formatted"}
                ]}}
                """);
        Hooks hooks = Hooks.load(workspace);

        assertEquals("formatted", hooks.run(Hooks.Event.POST_TOOL_USE, "edit_file").output());
        assertTrue(hooks.run(Hooks.Event.POST_TOOL_USE, "read_file").output().isEmpty(),
                "未匹配的工具不应执行钩子");
    }

    @Test
    void wildcardMatcherRunsForEveryTool() throws IOException {
        settings("""
                {"hooks":{"PostToolUse":[{"matcher":"*","command":"echo any"}]}}
                """);
        Hooks hooks = Hooks.load(workspace);

        assertEquals("any", hooks.run(Hooks.Event.POST_TOOL_USE, "run_command").output());
    }

    @Test
    void preToolUseBlocksOnlyWhenConfigured() throws IOException {
        settings("""
                {"hooks":{"PreToolUse":[
                  {"matcher":"run_command","command":"exit 1","blockOnFailure":true}
                ]}}
                """);
        Hooks hooks = Hooks.load(workspace);

        Hooks.Result blocked = hooks.run(Hooks.Event.PRE_TOOL_USE, "run_command");

        assertTrue(blocked.blocked(), "配置了 blockOnFailure 时失败必须阻止工具执行");
    }

    @Test
    void failingHookWithoutBlockFlagNeverBlocks() throws IOException {
        settings("""
                {"hooks":{"PreToolUse":[{"matcher":"run_command","command":"exit 1"}]}}
                """);
        Hooks hooks = Hooks.load(workspace);

        assertFalse(hooks.run(Hooks.Event.PRE_TOOL_USE, "run_command").blocked());
    }

    @Test
    void malformedSettingsDegradeToNoHooks() throws IOException {
        settings("{ not json");

        Hooks hooks = Hooks.load(workspace);

        assertEquals("", hooks.run(Hooks.Event.POST_TOOL_USE, "edit_file").output());
    }

    @Test
    void nestedClaudeCodeFormatIsSupported() throws IOException {
        settings("""
                {"hooks":{"PostToolUse":[
                  {"matcher":"edit_file","hooks":[{"type":"command","command":"echo nested"}]}
                ]}}
                """);
        Hooks hooks = Hooks.load(workspace);

        assertEquals("nested", hooks.run(Hooks.Event.POST_TOOL_USE, "edit_file").output());
    }
}
