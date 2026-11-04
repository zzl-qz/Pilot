package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.prompt.PromptLibrary;

class ProjectRuleLoaderTest {
    @TempDir
    Path root;

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

    // ---- 记忆注入：独立于约定的通道 ----

    @Test
    void loadsMemoryPerLevelAndKeepsItSeparateFromRules() throws IOException {
        Path workspace = Files.createDirectories(root.resolve("repo/worktree"));
        Files.writeString(root.resolve("MEMORY.md"), "上层记忆：浏览器工具只开回环地址");
        Files.writeString(workspace.resolve("MEMORY.md"), "工作区记忆：mvnw 先 install core 再编 server");
        Files.writeString(workspace.resolve("AGENTS.md"), "约定内容");

        String rules = ProjectRuleLoader.load(workspace, PromptLibrary.classpathOnly());
        String memory = ProjectRuleLoader.loadMemory(workspace, PromptLibrary.classpathOnly());

        // 记忆走独立通道：约定里不混入记忆，记忆里也不混入约定
        assertTrue(rules.contains("约定内容"));
        assertFalse(rules.contains("回环地址"));
        assertTrue(memory.contains("项目记忆"));
        assertTrue(memory.contains("回环地址"));
        assertTrue(memory.contains("先 install"));
        assertTrue(memory.indexOf("上层记忆") < memory.indexOf("工作区记忆"), "上层在前、工作区在后");
        assertFalse(memory.contains("约定内容"));
    }

    @Test
    void draftFileIsDeliberatelyNotInjected() throws IOException {
        Path workspace = Files.createDirectories(root.resolve("repo"));
        Files.createDirectories(workspace.resolve(".pilot"));
        Files.writeString(workspace.resolve(".pilot").resolve("memory-draft.md"), "- [2026-10-31] 候选条目");

        String memory = ProjectRuleLoader.loadMemory(workspace, PromptLibrary.classpathOnly());

        assertFalse(memory.contains("候选条目"), "remember 的候选区刻意不注入：未经人工审阅");
    }

    @Test
    void boundsLongMemoryWithItsOwnBudget() throws IOException {
        Files.writeString(root.resolve("MEMORY.md"), "记".repeat(6_000));

        String memory = ProjectRuleLoader.loadMemory(root, PromptLibrary.classpathOnly());

        assertTrue(memory.contains("记忆文件过长，已截断"));
        assertTrue(memory.length() < 5_500, "记忆预算独立（4k），不与约定共用 24k");
    }
}
