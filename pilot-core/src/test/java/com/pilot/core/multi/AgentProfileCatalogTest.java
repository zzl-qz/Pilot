package com.pilot.core.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 自定义子代理目录：扫描遮蔽、坏文件排除、任务字段与 profile 的合并语义（不能放宽）。 */
class AgentProfileCatalogTest {
    @TempDir
    Path dir;

    private Path write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
        return dir.resolve(name);
    }

    @Test
    void scansFlatMarkdownFilesAndShadowsByPriority() throws IOException {
        write("reviewer.md", """
            ---
            description: 代码审查员
            tools: [read_*, search_*]
            ---
            只提审查意见，不改代码。
            """);
        write("dba.md", """
            ---
            description: 数据库审查
            files: src/main/resources/db/**
            commands: [mvn test, git diff]
            ---
            数据库脚本与迁移的审查角色。
            """);
        write("ignored.txt", "不是代理定义");
        Files.createDirectories(dir.resolve("subdir"));

        AgentProfileCatalog catalog = AgentProfileCatalog.scan(List.of(dir));

        assertEquals(2, catalog.profiles().size());
        assertEquals("代码审查员", catalog.find("reviewer").orElseThrow().description());
        AgentProfile dba = catalog.find("dba").orElseThrow();
        assertFalse(dba.writable(), "未声明 writable 时默认只读");
        assertEquals(List.of("mvn test", "git diff"), dba.commands());
        assertTrue(dba.scope().allowsPath("src/main/resources/db/V1__init.sql"));
        assertFalse(dba.scope().allowsPath("src/Main.java"));
        // 未声明 tools 时沿用 AgentScope 的默认语义：空白名单 = 全允许（与 dispatch 任务一致）
        assertTrue(dba.scope().allowsTool("read_file"));
        // 声明了 tools 的 profile 按名单裁剪
        AgentProfile reviewer = catalog.find("reviewer").orElseThrow();
        assertTrue(reviewer.scope().allowsTool("read_file"));
        assertFalse(reviewer.scope().allowsTool("edit_file"));
    }

    @Test
    void projectRootShadowsUserRootOfTheSameName() throws IOException {
        Path project = Files.createDirectories(dir.resolve("project"));
        Path user = Files.createDirectories(dir.resolve("user"));
        Files.writeString(project.resolve("reviewer.md"), """
            ---
            description: 项目级审查员
            ---
            项目定义。
            """);
        Files.writeString(user.resolve("reviewer.md"), """
            ---
            description: 用户级审查员
            ---
            用户定义。
            """);

        AgentProfileCatalog catalog = AgentProfileCatalog.scan(List.of(project, user));

        assertEquals("项目级审查员", catalog.find("reviewer").orElseThrow().description(), "roots 按优先级降序传入：项目级在前，遮蔽用户级");
    }

    @Test
    void invalidEntriesAreReportedNotSilentlyDropped() throws IOException {
        write("bad.md", "没有 frontmatter 的文件");
        write("no-desc.md", """
            ---
            name: no-desc
            ---
            正文。
            """);
        write("UPPER.md", """
            ---
            description: 文件名非法
            ---
            正文。
            """);

        AgentProfileCatalog catalog = AgentProfileCatalog.scan(List.of(dir));

        assertTrue(catalog.profiles().isEmpty());
        assertEquals(3, catalog.invalid().size());
        assertTrue(catalog.invalid().stream().anyMatch(problem -> problem.name().equals("bad")));
        assertTrue(catalog.invalid().stream().anyMatch(problem -> problem.name().equals("no-desc")));
        assertTrue(catalog.invalid().stream().anyMatch(problem -> problem.name().equals("UPPER")));
    }

    @Test
    void mergeScopePrefersTaskFieldsAndNeverWidens() {
        AgentProfile profile = new AgentProfile("reviewer", "审查员", List.of("read_*", "search_*"), List.of("src/**"),
            List.of("db/**"), List.of("git diff"), List.of("git push"), true, "角色提示词");

        // 任务未声明：名单用 profile 补缺省；writable 仍以任务为准（false=显式只读）
        AgentScope merged = profile.mergeScope(false, List.of(), List.of(), List.of(), List.of(), List.of());
        assertFalse(merged.writable(), "任务未声明 writable 时是显式只读，profile 的可写声明不能放大");
        assertTrue(merged.allowsPath("src/Main.java"));
        assertFalse(merged.allowsPath("db/V1.sql"), "deny-files 黑名单必须生效");

        // 任务显式收窄（只读 + 更小的文件范围）：以任务为准，profile 不放大
        AgentScope narrowed =
            profile.mergeScope(false, List.of("pilot-core/**"), List.of(), List.of(), List.of(), List.of());
        assertFalse(narrowed.writable(), "任务只读优先于 profile 可写");
        assertFalse(narrowed.allowsPath("src/Main.java"));

        // 任务声明了工具名单：以任务为准
        AgentScope tools = profile.mergeScope(false, List.of(), List.of(), List.of("read_file"), List.of(), List.of());
        assertTrue(tools.allowsTool("read_file"));
        assertFalse(tools.allowsTool("search_text"));
    }

    @Test
    void prefixGoalWrapsRoleAroundTask() {
        AgentProfile profile = new AgentProfile("reviewer", "审查员", List.of(), List.of(), List.of(), List.of(),
            List.of(), false, "你只审查数据库脚本。");

        String goal = profile.prefixGoal("检查 V1 迁移");
        assertTrue(goal.startsWith("【角色设定】\n你只审查数据库脚本。"));
        assertTrue(goal.endsWith("【任务目标】\n检查 V1 迁移"));

        // 没有角色提示词时不包壳，goal 原样
        AgentProfile bare =
            new AgentProfile("x", "d", List.of(), List.of(), List.of(), List.of(), List.of(), false, "");
        assertEquals("任务", bare.prefixGoal("任务"));
    }

    @Test
    void frontmatterRejectsUnknownKeysAndBadLists() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> AgentFrontmatter.parse("""
            ---
            description: d
            unknown-key: v
            ---
            正文
            """));
        Assertions.assertThrows(IllegalArgumentException.class, () -> AgentFrontmatter.parse("""
            ---
            description: d
            tools: [read_*, read_*
            ---
            正文
            """));
        Assertions.assertThrows(IllegalArgumentException.class, () -> AgentFrontmatter.parse("no frontmatter"));
        // writable 取值校验
        Assertions.assertThrows(IllegalArgumentException.class, () -> AgentFrontmatter.parse("""
            ---
            description: d
            writable: maybe
            ---
            正文
            """));
        // 合法全键
        AgentFrontmatter.Parsed parsed = AgentFrontmatter.parse("""
            ---
            description: 合法代理
            tools: [read_*, run_command]
            files: [src/**]
            deny-files: [**/.env]
            commands: [git diff, mvn test]
            deny-commands: [git push]
            writable: true
            ---
            角色
            """);
        assertTrue(parsed.writable());
        assertEquals(List.of("git push"), parsed.denyCommands());
    }
}
