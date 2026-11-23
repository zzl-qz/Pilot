package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.memory.MemoryStore;
import com.pilot.core.memory.MemoryType;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.server.workspace.ProjectRulesFile;

class ProjectRuleLoaderTest {
    @TempDir
    Path root;

    @Test
    void onlyTheWorkspaceRootAgentsFileCounts() throws IOException {
        Path workspace = Files.createDirectories(root.resolve("repo/worktree"));
        Files.writeString(root.resolve("AGENTS.md"), "上层规则");
        Files.writeString(workspace.resolve("CLAUDE.md"), "别的文件名");
        Files.writeString(workspace.resolve("PILOT.md"), "也不认");

        String missing = ProjectRuleLoader.load(workspace, PromptLibrary.classpathOnly());

        assertTrue(missing.isEmpty(), missing);
        Files.writeString(workspace.resolve("AGENTS.md"), "工作区规则");
        String rules = ProjectRuleLoader.load(workspace, PromptLibrary.classpathOnly());
        assertTrue(rules.contains(workspace.resolve("AGENTS.md").toString()));
        assertTrue(rules.contains("工作区规则"));
        assertFalse(rules.contains("上层规则"));
        assertFalse(rules.contains("别的文件名"));
        assertFalse(rules.contains("也不认"));
    }

    @Test
    void boundsLongRuleWithoutReadingWholeFile() throws IOException {
        Files.writeString(root.resolve("AGENTS.md"), "A".repeat(20_000));

        String rules = ProjectRuleLoader.load(root, PromptLibrary.classpathOnly());

        assertTrue(rules.contains("约定文件过长，已截断"));
        assertTrue(rules.length() < 9_000);
    }

    @Test
    void editorAndPromptIgnoreSymlinkedRules() throws IOException {
        Path other = Files.writeString(root.resolve("other.md"), "不应注入");
        Path file = ProjectRulesFile.path(root);
        try {
            Files.createSymbolicLink(file, other);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.assumeTrue(false, "当前系统不能创建符号链接: " + e);
        }
        assertTrue(ProjectRulesFile.read(file).text().isEmpty());
        assertTrue(ProjectRuleLoader.load(root, PromptLibrary.classpathOnly()).isEmpty());
    }

    @Test
    void stripsBomAndDoesNotSplitEmojiAtLimit() throws IOException {
        Path file = ProjectRulesFile.path(root);
        String prefix = "A".repeat(ProjectRulesFile.MAX_CHARS - 1);
        Files.writeString(file, "\uFEFF" + prefix + "B");
        var exact = ProjectRulesFile.read(file);
        Assertions.assertEquals(prefix + "B", exact.text());
        assertFalse(exact.truncated());

        Files.writeString(file, "\uFEFF" + prefix + "😀");
        var clipped = ProjectRulesFile.read(file);
        Assertions.assertEquals(prefix, clipped.text());
        assertTrue(clipped.truncated());
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

    @Test
    void loadsExternalMemoryStoreIndexAndRejectsSymlinkedLegacyMemory() throws IOException {
        Path workspace = Files.createDirectories(root.resolve("repo"));
        Path memoryRoot = root.resolve("pilot-data/memory");
        MemoryStore store = new MemoryStore(memoryRoot);
        store.addDraft(workspace, MemoryType.PROJECT, "未审草稿", "绝不能注入未审候选", "防污染");
        store.saveEntry(workspace, MemoryStore.SCOPE_PROJECT, null, MemoryType.PROJECT, "Test Rule",
            "Run mvnw verify before merge", "Keep build green");
        store.saveEntry(workspace, MemoryStore.SCOPE_USER, null, MemoryType.USER, "Lang", "Communicate in Chinese", "");

        Path outside = Files.writeString(root.resolve("secret.md"), "符号链接泄露内容");
        try {
            Files.createSymbolicLink(workspace.resolve("MEMORY.md"), outside);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.assumeTrue(false, "当前系统不能创建符号链接: " + e);
        }

        String memory = ProjectRuleLoader.loadMemory(workspace, memoryRoot, PromptLibrary.classpathOnly());
        assertTrue(memory.contains("test-rule"), memory);
        assertTrue(memory.contains("lang"), memory);
        assertFalse(memory.contains("绝不能注入未审候选"));
        assertFalse(memory.contains("符号链接泄露内容"));
    }
}
