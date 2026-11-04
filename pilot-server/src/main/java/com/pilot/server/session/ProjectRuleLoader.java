package com.pilot.server.session;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.pilot.core.prompt.PromptLibrary;

/** 沿工作区祖先目录加载项目规则；每个目录按优先级只取一份，并标明来源。 */
final class ProjectRuleLoader {
    private static final List<String> NAMES = List.of("PILOT.md", "AGENTS.md", "CLAUDE.md");
    private static final int MAX_FILE_CHARS = 8_000;
    private static final int MAX_TOTAL_CHARS = 24_000;
    /** 记忆与约定是两种内容：记忆是沉淀事实，预算独立，避免互相挤占。 */
    private static final String MEMORY_NAME = "MEMORY.md";
    private static final int MAX_MEMORY_CHARS = 4_000;

    private ProjectRuleLoader() {
    }

    static String load(Path workspace, PromptLibrary prompts) {
        List<Rule> nearestFirst = new ArrayList<>();
        int remaining = MAX_TOTAL_CHARS;
        for (Path dir = workspace.toAbsolutePath().normalize(); dir != null && remaining > 0; dir = dir.getParent()) {
            Rule rule = firstRule(dir, Math.min(MAX_FILE_CHARS, remaining));
            if (rule == null) {
                continue;
            }
            nearestFirst.add(rule);
            remaining -= rule.content().length();
        }
        Collections.reverse(nearestFirst);
        // 用户级全局约定（~/.pilot/）优先级最低，但保证任何项目都能带上个人偏好
        Rule user = remaining > 0 ? firstRule(userHome(), Math.min(MAX_FILE_CHARS, remaining)) : null;
        if (user != null) {
            nearestFirst.add(0, user);
        }
        return nearestFirst.stream()
            .map(rule -> prompts
                .render("system/project-rules", Map.of("source", rule.path().toString(), "rules", rule.content()))
                .strip())
            .reduce((a, b) -> a + "\n\n" + b).orElse("");
    }

    /**
     * 记忆注入：与约定分开的独立通道——{@code MEMORY.md} 是人工审阅后的沉淀事实
     * （remember 工具写的 {@code memory-draft.md} 候选区刻意不注入）。
     * 与约定同目录策略：工作区往上逐层各取一份（每层只有这一个候选，近者优先，
     * 超出预算截断），用户级 {@code ~/.pilot/MEMORY.md} 兜底。
     */
    static String loadMemory(Path workspace, PromptLibrary prompts) {
        List<Rule> nearestFirst = new ArrayList<>();
        int remaining = MAX_MEMORY_CHARS;
        for (Path dir = workspace.toAbsolutePath().normalize(); dir != null && remaining > 0; dir = dir.getParent()) {
            Rule memory = readMemory(dir, remaining);
            if (memory == null) {
                continue;
            }
            nearestFirst.add(memory);
            remaining -= memory.content().length();
        }
        Rule user = remaining > 0 ? readMemory(userHome(), remaining) : null;
        if (user != null) {
            nearestFirst.add(0, user);
        }
        // 与约定同序：上层在前、工作区在后（近处记忆是细化，覆盖语义在后）
        Collections.reverse(nearestFirst);
        return nearestFirst.stream()
            .map(rule -> prompts
                .render("system/memory", Map.of("source", rule.path().toString(), "memory", rule.content())).strip())
            .reduce((a, b) -> a + "\n\n" + b).orElse("");
    }

    private static Path userHome() {
        return Path.of(System.getProperty("user.home", "."), ".pilot");
    }

    private static Rule readMemory(Path dir, int limit) {
        Path file = dir.resolve(MEMORY_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
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
                return null;
            }
            if (length > limit) {
                content += "\n…（记忆文件过长，已截断）";
            }
            return new Rule(file, content);
        } catch (IOException ignored) {
            // 一份记忆不可读时跳过它，不让整个提示词装配失败
            return null;
        }
    }

    private static Rule firstRule(Path dir, int limit) {
        for (String name : NAMES) {
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
                    content += "\n…（约定文件过长，已截断）";
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
