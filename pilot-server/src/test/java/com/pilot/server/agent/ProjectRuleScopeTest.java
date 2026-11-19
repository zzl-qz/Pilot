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
}
