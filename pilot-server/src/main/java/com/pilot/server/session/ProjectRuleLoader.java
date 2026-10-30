package com.pilot.server.session;

import com.pilot.core.prompt.PromptLibrary;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 沿工作区祖先目录加载项目规则；每个目录按优先级只取一份，并标明来源。 */
final class ProjectRuleLoader {
    private static final List<String> NAMES = List.of("PILOT.md", "AGENTS.md", "CLAUDE.md");
    private static final int MAX_FILE_CHARS = 8_000;
    private static final int MAX_TOTAL_CHARS = 24_000;

    private ProjectRuleLoader() { }

    static String load(Path workspace, PromptLibrary prompts) {
        List<Rule> nearestFirst = new ArrayList<>();
        int remaining = MAX_TOTAL_CHARS;
        for (Path dir = workspace.toAbsolutePath().normalize(); dir != null && remaining > 0; dir = dir.getParent()) {
            Rule rule = firstRule(dir, Math.min(MAX_FILE_CHARS, remaining));
            if (rule == null) continue;
            nearestFirst.add(rule);
            remaining -= rule.content().length();
        }
        Collections.reverse(nearestFirst);
        return nearestFirst.stream()
                .map(rule -> prompts.render("system/project-rules",
                        Map.of("source", rule.path().toString(), "rules", rule.content())).strip())
                .reduce((a, b) -> a + "\n\n" + b).orElse("");
    }

    private static Rule firstRule(Path dir, int limit) {
        for (String name : NAMES) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                char[] buffer = new char[limit + 1];
                int length = 0;
                while (length < buffer.length) {
                    int count = reader.read(buffer, length, buffer.length - length);
                    if (count < 0) break;
                    length += count;
                }
                String content = new String(buffer, 0, Math.min(length, limit));
                if (content.startsWith("\uFEFF")) content = content.substring(1);
                content = content.strip();
                if (content.isEmpty()) continue;
                if (length > limit) content += "\n…（约定文件过长，已截断）";
                return new Rule(file, content);
            } catch (IOException ignored) {
                // 一份文件不可读时继续尝试当前目录的下一个候选及上层目录。
            }
        }
        return null;
    }

    private record Rule(Path path, String content) { }
}
