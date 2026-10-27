package com.pilot.core.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptLibraryTest {

    private static final Map<String, String> VARS = Map.of(
            "platform", "Windows 11",
            "today", "2026-10-27",
            "workspace", "pilot");

    @Test
    void assemblesSystemPromptWithEnvironment() {
        String prompt = PromptLibrary.classpathOnly().system(VARS);

        assertTrue(prompt.contains("你是 Pilot"));
        assertTrue(prompt.contains("Windows 11"));
        assertTrue(prompt.contains("## 工具选择"));
        assertTrue(prompt.contains("## 结论怎么写"));
        assertFalse(prompt.contains("{{"), "系统提示词不应残留未替换的变量");
    }

    @Test
    void rendersDynamicSections() {
        var prompts = PromptLibrary.classpathOnly();

        String cont = prompts.render("plan/continue", Map.of("openSteps", "- 写测试\n- 跑构建"));
        assertTrue(cont.contains("- 写测试"));
        assertFalse(cont.contains("{{"));

        String brief = prompts.render("system/plan-context", Map.of("planItems", "- [in_progress] 改代码"));
        assertTrue(brief.contains("- [in_progress] 改代码"));
    }

    @Test
    void failsLoudlyOnMissingVariable() {
        var prompts = PromptLibrary.classpathOnly();

        var error = assertThrows(IllegalArgumentException.class,
                () -> prompts.render("plan/continue", Map.of()));

        assertTrue(error.getMessage().contains("openSteps"));
    }

    @Test
    void externalFileOverridesClasspathAndMissingSectionFallsBack(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("system"));
        Files.writeString(dir.resolve("system/base.md"), "覆盖后的身份 {{workspace}}");

        var prompts = new PromptLibrary(dir);

        assertEquals("覆盖后的身份 {{workspace}}", prompts.get("system/base").strip());
        assertTrue(prompts.render("system/base", VARS).contains("覆盖后的身份 pilot"));
        assertTrue(prompts.get("system/tools").contains("## 工具选择"));
    }

    @Test
    void rejectsPathTraversal() {
        var prompts = PromptLibrary.classpathOnly();

        assertThrows(IllegalArgumentException.class, () -> prompts.get("../secrets"));
    }

    /** 每个工具都要有描述模板，否则工具定义里会出现「找不到提示词资源」。 */
    @Test
    void loadsEveryToolDescription() {
        var prompts = PromptLibrary.classpathOnly();
        var variables = Map.of(
                "executables", "mvn/mvnw",
                "gitReadonly", "status/diff",
                "gitWrite", "add/commit");

        for (String tool : List.of("read_file", "list_dir", "search_text", "edit_file",
                "create_file", "run_command", "update_plan", "spawn_agent", "dispatch_agents")) {
            String text = prompts.render("tools/" + tool, variables);
            assertFalse(text.isBlank(), tool);
            assertFalse(text.contains("{{"), tool + " 仍有未替换的变量");
        }
    }
}
