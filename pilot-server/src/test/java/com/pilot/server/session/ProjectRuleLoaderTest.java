package com.pilot.server.session;

import com.pilot.core.prompt.PromptLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRuleLoaderTest {
    @TempDir Path root;

    @Test
    void loadsAncestorAndWorkspaceRulesWithSourcesAndOneFilePerDirectory() throws IOException {
        Path workspace = Files.createDirectories(root.resolve("repo/worktree"));
        Files.writeString(root.resolve("AGENTS.md"), "上层规则");
        Files.writeString(root.resolve("CLAUDE.md"), "同层低优先级规则");
        Files.writeString(workspace.resolve("PILOT.md"), "工作区规则");

        String rules = ProjectRuleLoader.load(workspace, PromptLibrary.classpathOnly());

        assertTrue(rules.contains(root.resolve("AGENTS.md").toString()));
        assertTrue(rules.contains(workspace.resolve("PILOT.md").toString()));
        assertTrue(rules.indexOf("上层规则") < rules.indexOf("工作区规则"));
        assertFalse(rules.contains("同层低优先级规则"));
    }

    @Test
    void boundsLongRuleWithoutReadingWholeFile() throws IOException {
        Files.writeString(root.resolve("PILOT.md"), "A".repeat(20_000));

        String rules = ProjectRuleLoader.load(root, PromptLibrary.classpathOnly());

        assertTrue(rules.contains("约定文件过长，已截断"));
        assertTrue(rules.length() < 9_000);
    }
}
