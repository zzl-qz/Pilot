package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.prompt.PromptLibrary;

class ProjectRuleScopeTest {
    @TempDir
    Path root;

    @Test
    void rulesWithoutGlobsAreAlwaysIncluded() throws Exception {
        Path rules = Files.createDirectories(root.resolve(".pilot/rules"));
        Files.writeString(rules.resolve("always.md"), "始终生效的约定");

        String loaded = ProjectRuleLoader.load(root, PromptLibrary.classpathOnly());

        assertTrue(loaded.contains("始终生效的约定"), loaded);
    }

    @Test
    void globsApplyOnlyAfterAMatchingPathIsTouched() throws Exception {
        Path rules = Files.createDirectories(root.resolve(".pilot/rules"));
        Files.writeString(rules.resolve("web.md"), "globs: pilot-web/**\n只在前端生效");

        assertFalse(ProjectRuleLoader.scoped(root, List.of("pilot-core/A.java"), PromptLibrary.classpathOnly())
            .contains("只在前端生效"));
        assertTrue(ProjectRuleLoader.scoped(root, List.of("pilot-web/src/App.tsx"), PromptLibrary.classpathOnly())
            .contains("只在前端生效"));
    }

    @Test
    void longRuleIsTruncatedAndDoesNotThrow() throws Exception {
        Path rules = Files.createDirectories(root.resolve(".pilot/rules"));
        Files.writeString(rules.resolve("long.md"), "约定".repeat(5_000));

        String loaded = ProjectRuleLoader.load(root, PromptLibrary.classpathOnly());

        assertTrue(loaded.contains("已截断"), loaded);
    }

    @Test
    void matchesStandardSingleStarSubdirAndBasenameGlobsCrossPlatform() throws Exception {
        Path rules = Files.createDirectories(root.resolve(".pilot/rules"));
        Files.writeString(rules.resolve("api.md"), "globs: src/api/*.ts\nAPI 接口规则");
        Files.writeString(rules.resolve("ui.md"), "globs: *.tsx\n组件规则");

        assertTrue(ProjectRuleLoader.scoped(root, List.of("src/api/user.ts"), PromptLibrary.classpathOnly())
            .contains("API 接口规则"));
        assertFalse(ProjectRuleLoader.scoped(root, List.of("src/api/v2/user.ts"), PromptLibrary.classpathOnly())
            .contains("API 接口规则"), "单星号 * 不能跨越子目录");
        assertFalse(ProjectRuleLoader.scoped(root, List.of("src/api/user.ts.bak"), PromptLibrary.classpathOnly())
            .contains("API 接口规则"), "不能把 .ts.bak 误判成 .ts");

        assertTrue(ProjectRuleLoader.scoped(root, List.of("pilot-web\\src\\App.tsx"), PromptLibrary.classpathOnly())
            .contains("组件规则"), "纯后缀模式需匹配深层文件路径并兼容 Windows 反斜杠");
        assertFalse(ProjectRuleLoader.scoped(root, List.of("pilot-web/src/App.tsx.bak"), PromptLibrary.classpathOnly())
            .contains("组件规则"));
    }
}
