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
    void skipsSectionsWhoseToolsAreNotRegistered() {
        var prompts = PromptLibrary.classpathOnly();

        // spawn_agent 与 dispatch_agents 都在：子代理选择行与调度段都注入
        String full = prompts.system(VARS, java.util.Set.of(
                "read_file", "list_dir", "search_text", "edit_file", "create_file",
                "run_command", "update_plan", "spawn_agent", "dispatch_agents"));
        assertTrue(full.contains("## 工具选择：子代理与派发"));
        assertTrue(full.contains("## 多智能体调度"));

        // 只有 spawn_agent：dispatch_agents 相关段整体消失
        String withoutDispatch = prompts.system(VARS, java.util.Set.of(
                "read_file", "list_dir", "search_text", "edit_file", "create_file",
                "run_command", "update_plan", "spawn_agent"));
        assertFalse(withoutDispatch.contains("dispatch_agents"), "工具未注册时提示词不应出现它的用法教学");
        assertFalse(withoutDispatch.contains("多智能体调度"));

        // 两个都没有：连子代理选择表也不出现
        String bare = prompts.system(VARS, java.util.Set.of("read_file", "run_command"));
        assertFalse(bare.contains("spawn_agent"));
        assertTrue(bare.contains("## 工具选择"), "主选择表与工具无关，不应被过滤");
    }

    @Test
    void renderStripsFrontmatterFromSectionBody(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("plan"));
        Files.writeString(dir.resolve("plan/front.md"),
                "---\nrequires: [read_file]\n---\n正文内容 {{openSteps}}");

        var prompts = new PromptLibrary(dir);
        String rendered = prompts.render("plan/front", Map.of("openSteps", "第一步"));

        assertFalse(rendered.contains("---"), "frontmatter 不应进入正文");
        assertFalse(rendered.contains("requires"));
        assertTrue(rendered.contains("正文内容 第一步"));
    }

    @Test
    void rejectsMalformedFrontmatter(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("system"));
        Files.writeString(dir.resolve("system/unknown-key.md"),
                "---\nunknown_key: value\n---\n正文");
        Files.writeString(dir.resolve("system/unclosed-list.md"),
                "---\nrequires: [a\n---\n正文");
        Files.writeString(dir.resolve("system/no-end-delim.md"),
                "---\nrequires: [a]\n正文缺结束分隔符");

        var prompts = new PromptLibrary(dir);

        var error = assertThrows(IllegalArgumentException.class,
                () -> prompts.render("system/unknown-key", Map.of()));
        assertTrue(error.getMessage().contains("只支持 requires"));

        assertThrows(IllegalArgumentException.class,
                () -> prompts.render("system/unclosed-list", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> prompts.render("system/no-end-delim", Map.of()));
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
                "gitWrite", "add/commit",
                "backgroundHelp", "后台返回任务 ID");

        for (String tool : List.of("read_file", "list_dir", "search_text", "edit_file",
                "create_file", "run_command", "command_status", "command_logs", "stop_command",
                "update_plan", "spawn_agent", "dispatch_agents")) {
            String text = prompts.render("tools/" + tool, variables);
            assertFalse(text.isBlank(), tool);
            assertFalse(text.contains("{{"), tool + " 仍有未替换的变量");
        }
    }
}
