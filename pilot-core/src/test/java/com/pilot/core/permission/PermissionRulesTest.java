package com.pilot.core.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.message.ToolCall;

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
    void malformedSettingsAreReported() throws IOException {
        PermissionRules rules = rules("{ not json");

        assertTrue(rules.isEmpty());
        assertTrue(rules.loadIssues().stream().anyMatch(issue -> issue.contains("settings.json")));
    }

    @Test
    void brokenSettingsNeverThrow() throws IOException {
        PermissionRules rules = rules("{\"permissions\": \"not-a-map\"}");

        assertTrue(rules.isEmpty());
        assertTrue(rules.loadIssues().stream().anyMatch(issue -> issue.contains("permissions")));
    }

    @Test
    void saveRoundTripsAndKeepsOtherTopLevelKeys() throws IOException {
        Path file = PermissionRules.workspaceFile(workspace);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            {"hooks":{"pre":"x"},"permissions":{"allow":["run_command(old:*)"]}}
            """);

        PermissionRules.save(workspace, List.of("run_command(mvn test:*)"), List.of("run_command(rm -rf:*)"),
            List.of("edit_file(docs/*.md)"));

        PermissionRules saved = PermissionRules.loadWorkspace(workspace);
        assertEquals(List.of("run_command(mvn test:*)"), saved.allowRules());
        assertEquals(List.of("run_command(rm -rf:*)"), saved.denyRules());
        assertEquals(List.of("edit_file(docs/*.md)"), saved.askRules());
        assertTrue(saved.loadIssues().isEmpty());

        String text = Files.readString(file);
        assertTrue(text.contains("\"hooks\""), "其他顶层字段必须保留");
        assertFalse(text.contains("run_command(old:*)"), "旧规则应被整体替换，不是追加");

        assertEquals(PermissionDecision.DENY, saved.evaluate(command("rm -rf /tmp/x")).decision());
        assertEquals(PermissionDecision.ALLOW, saved.evaluate(command("mvn test -q")).decision());
        assertEquals(PermissionDecision.ASK, saved.evaluate(edit("docs/guide.md")).decision());
    }

    @Test
    void savingNoRulesRemovesPermissionsKey() throws IOException {
        Path file = PermissionRules.workspaceFile(workspace);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            {"mcp":{"servers":[]},"permissions":{"deny":["read_file(.env)"]}}
            """);

        PermissionRules.save(workspace, List.of(), List.of(), List.of());

        assertTrue(PermissionRules.loadWorkspace(workspace).isEmpty());
        String text = Files.readString(file);
        assertFalse(text.contains("\"permissions\""), "空规则不应留下空数组");
        assertTrue(text.contains("\"mcp\""));
    }

    @Test
    void savingSkipsBlankRulesAndCreatesMissingDirectory() throws IOException {
        PermissionRules.save(workspace, List.of("  ", "read_file(src/**)"), null, List.of());

        assertEquals(List.of("read_file(src/**)"), PermissionRules.loadWorkspace(workspace).allowRules());
        assertTrue(Files.isRegularFile(PermissionRules.workspaceFile(workspace)));
    }

    @Test
    void browserRulesIsolateLoopbackPortsAndSupportDomainWildcards() throws IOException {
        PermissionRules rules = rules(
            """
                {"permissions":{"allow":["browser(http://localhost:5173)","browser(click:https://*.example.com)"],"deny":["browser(https://admin.example.com)"]}}
                """);

        ToolCall localSamePort =
            new ToolCall("b1", "browser", Map.of("action", "click", "url", "http://localhost:5173/app"));
        ToolCall localOtherPort =
            new ToolCall("b2", "browser", Map.of("action", "click", "url", "http://localhost:5174/app"));
        assertEquals(PermissionDecision.ALLOW, rules.evaluate(localSamePort).decision());
        assertNull(rules.evaluate(localOtherPort), "不同端口的回环服务不得共享站点放行规则");

        ToolCall allowedWildcard =
            new ToolCall("b3", "browser", Map.of("action", "click", "expected_url", "https://docs.example.com/guide"));
        ToolCall wrongActionWildcard =
            new ToolCall("b4", "browser", Map.of("action", "fill", "expected_url", "https://docs.example.com/guide"));
        ToolCall deniedAdmin = new ToolCall("b5", "browser",
            Map.of("action", "click", "expected_url", "https://admin.example.com/settings"));
        assertEquals(PermissionDecision.ALLOW, rules.evaluate(allowedWildcard).decision());
        assertNull(rules.evaluate(wrongActionWildcard));
        assertEquals(PermissionDecision.DENY, rules.evaluate(deniedAdmin).decision());
    }
}
