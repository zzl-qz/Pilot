package com.pilot.server.agent;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.pilot.core.prompt.PromptLibrary;

/** 沿工作区祖先目录加载项目规则；每个目录按优先级只取一份，并标明来源。 */
final class ProjectRuleLoader {
    private static final List<String> NAMES = List.of("PILOT.md", "AGENTS.md", "CLAUDE.md");
    private static final int MAX_FILE_CHARS = 8_000;
    private static final int MAX_TOTAL_CHARS = 24_000;
    /** 记忆与约定是两种内容：记忆是沉淀事实，预算独立，避免互相挤占。 */
    private static final List<String> MEMORY_NAMES = List.of("MEMORY.md");
    private static final int MAX_MEMORY_CHARS = 4_000;

    private ProjectRuleLoader() {
    }

    static String load(Path workspace, PromptLibrary prompts) {
        return load(workspace, prompts, NAMES, MAX_TOTAL_CHARS, MAX_FILE_CHARS, "project-rules", "rules", "约定文件过长，已截断",
            true);
    }

    /**
     * 记忆注入：与约定分开的独立通道——{@code MEMORY.md} 是人工审阅后的沉淀事实
     * （remember 工具写的 {@code memory-draft.md} 候选区刻意不注入）。
     * 与约定同目录策略：工作区往上逐层各取一份（每层只有这一个候选，近者优先，
     * 超出预算截断），用户级 {@code ~/.pilot/MEMORY.md} 兜底。
     */
    static String loadMemory(Path workspace, PromptLibrary prompts) {
        return load(workspace, prompts, MEMORY_NAMES, MAX_MEMORY_CHARS, MAX_MEMORY_CHARS, "memory", "memory",
            "记忆文件过长，已截断", false);
    }

    private static String load(Path workspace, PromptLibrary prompts, List<String> names, int totalLimit, int fileLimit,
        String template, String field, String truncated, boolean globalFirst) {
        List<Rule> nearestFirst = new ArrayList<>();
        int remaining = totalLimit;
        for (Path dir = workspace.toAbsolutePath().normalize(); dir != null && remaining > 0; dir = dir.getParent()) {
            Rule rule = firstRule(dir, names, Math.min(fileLimit, remaining), truncated);
            if (rule != null) {
                nearestFirst.add(rule);
                remaining -= rule.content().length();
            }
        }
        Collections.reverse(nearestFirst);
        if (remaining > 0) {
            Rule user = firstRule(userHome(), names, Math.min(fileLimit, remaining), truncated);
            if (user != null) {
                // 全局约定先注入，用户级记忆沿用末尾兜底顺序。
                nearestFirst.add(globalFirst ? 0 : nearestFirst.size(), user);
            }
        }
        return nearestFirst.stream()
            .map(rule -> prompts
                .render("system/" + template, Map.of("source", rule.path().toString(), field, rule.content())).strip())
            .collect(Collectors.joining("\n\n"));
    }

    private static Path userHome() {
        return Path.of(System.getProperty("user.home", "."), ".pilot");
    }

    private static Rule firstRule(Path dir, List<String> names, int limit, String truncated) {
        for (String name : names) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                char[] buffer = new char[limit + 1];
                int length = 0;
                while (length < buffer.length) {
                    int count = reader.read(buffer, length, buffer.length - length);
                    if (count < 0) {
                        break;
                    }
                    length += count;
                }
                String content = new String(buffer, 0, Math.min(length, limit));
                if (content.startsWith("\uFEFF")) {
                    content = content.substring(1);
                }
                content = content.strip();
                if (content.isEmpty()) {
                    continue;
                }
                if (length > limit) {
                    content += "\n…（" + truncated + "）";
                }
                return new Rule(file, content);
            } catch (IOException ignored) {
                // 一份文件不可读时继续尝试当前目录的下一个候选及上层目录。
            }
        }
        return null;
    }

    private record Rule(Path path, String content) {
    }
}
