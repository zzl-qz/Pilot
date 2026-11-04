package com.pilot.core.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.tool.ToolCall;

class PermissionRulesTest {
    @TempDir
    Path workspace;

    private PermissionRules rules(String json) throws IOException {
        Path dir = workspace.resolve(".pilot");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.json"), json);
        return PermissionRules.load(workspace);
    }

    private static ToolCall command(String command) {
        return new ToolCall("call_1", "run_command", Map.of("command", command));
    }

    private static ToolCall edit(String path) {
        return new ToolCall("call_2", "edit_file", Map.of("path", path));
    }

    @Test
    void denyBeatsAllowForSameTool() throws IOException {
        PermissionRules rules = rules("""
            {"permissions":{"allow":["run_command"],"deny":["run_command(rm -rf:*)"]}}
            """);

        assertEquals(PermissionDecision.DENY, rules.evaluate(command("rm -rf /tmp/x")).decision());
        assertEquals(PermissionDecision.ALLOW, rules.evaluate(command("mvn test")).decision());
    }

    @Test
    void commandPrefixRuleMatchesOnlyThatCommand() throws IOException {
        PermissionRules rules = rules("""
            {"permissions":{"ask":["run_command(npm run test:*)"]}}
            """);

        assertEquals(PermissionDecision.ASK, rules.evaluate(command("npm run test -- --watch")).decision());
        assertNull(rules.evaluate(command("npm publish")), "未命中规则应回落档位");
    }

    @Test
    void pathGlobMatchesWorkspaceFiles() throws IOException {
        PermissionRules rules = rules("""
            {"permissions":{"deny":["edit_file(**/.env)"],"allow":["edit_file(src/*.java)"]}}
            """);

        assertEquals(PermissionDecision.DENY, rules.evaluate(edit("config/.env")).decision(), "** 应跨目录匹配");
        assertEquals(PermissionDecision.ALLOW, rules.evaluate(edit("src/Main.java")).decision());
        assertNull(rules.evaluate(edit("docs/readme.md")));
    }

    @Test
    void bareToolRuleAppliesToEveryCall() throws IOException {
        PermissionRules rules = rules("""
            {"permissions":{"ask":["edit_file"]}}
            """);

        assertNotNull(rules.evaluate(edit("anything.txt")));
        assertEquals(PermissionDecision.ASK, rules.evaluate(edit("anything.txt")).decision());
    }

    @Test
    void missingSettingsMeanNoRules() {
        PermissionRules rules = PermissionRules.load(workspace);

        assertTrue(rules.isEmpty());
        assertNull(rules.evaluate(edit("a.txt")), "没有规则时必须交回档位判定");
    }

    @Test
    void brokenSettingsNeverThrow() throws IOException {
        PermissionRules rules = rules("{\"permissions\": \"not-a-map\"}");

        assertTrue(rules.isEmpty());
    }
}
