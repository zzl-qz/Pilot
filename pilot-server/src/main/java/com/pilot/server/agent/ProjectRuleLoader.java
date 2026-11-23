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

import com.pilot.core.permission.PermissionRules;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.server.workspace.ProjectRulesFile;

/**
 * 项目约定只认工作区根目录的 AGENTS.md。上层目录、用户目录和其他文件名读到也不算。
 * 记忆仍沿祖先目录和 ~/.pilot 加载，和约定不是一条通道。
 */
final class ProjectRuleLoader {
    /** 记忆与约定是两种内容：记忆是沉淀事实，预算独立，避免互相挤占。 */
    private static final List<String> MEMORY_NAMES = List.of("MEMORY.md");
    private static final int MAX_MEMORY_CHARS = 4_000;

    private ProjectRuleLoader() {
    }

    static String load(Path workspace, PromptLibrary prompts) {
        if (workspace == null) {
            return "";
        }
        Path file = ProjectRulesFile.path(workspace);
        ProjectRulesFile.Preview preview;
        try {
            preview = ProjectRulesFile.read(file);
        } catch (IOException ignored) {
            // 不可读的约定不阻断对话。
            return "";
        }
        String text = preview.text().strip();
        String base = "";
        if (!text.isEmpty()) {
            if (preview.truncated()) {
                text += "\n…（约定文件过长，已截断）";
            }
            base = prompts.render("system/project-rules", Map.of("source", file.toString(), "rules", text)).strip();
        }
        return join(base, scoped(workspace, List.of(), prompts, false));
    }

    /** 有 globs 的规则只在本次碰到匹配路径时返回。无 globs 的规则由 {@link #load} 放入基础约定。 */
    static String scoped(Path workspace, List<String> touched, PromptLibrary prompts) {
        return scoped(workspace, touched, prompts, true);
    }

    private static String scoped(Path workspace, List<String> touched, PromptLibrary prompts, boolean matchedOnly) {
        Path dir = workspace.resolve(".pilot").resolve("rules");
        if (!Files.isDirectory(dir)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int total = 0;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList()) {
                String content = readLimited(file, 4_000);
                if (content.isEmpty()) {
                    continue;
                }
                String globs = globs(content);
                boolean matched = globs.isEmpty() ? !matchedOnly : matches(globs, touched);
                if (!matched) {
                    continue;
                }
                if (total >= 12_000) {
                    out.append("\n…（规则总长度已截断）");
                    break;
                }
                int room = Math.min(4_000, 12_000 - total);
                String body = content.length() > room ? content.substring(0, room) + "\n…（规则文件过长，已截断）" : content;
                out.append("\n\n").append(body);
                total += body.length();
            }
        } catch (IOException ignored) {
            return "";
        }
        return out.toString().strip();
    }

    private static String join(String base, String extra) {
        if (extra == null || extra.isBlank()) {
            return base;
        }
        if (base == null || base.isBlank()) {
            return extra;
        }
        return base + "\n\n" + extra;
    }

    private static String globs(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", 4)) {
            String stripped = line.strip();
            if (stripped.startsWith("globs:")) {
                return stripped.substring("globs:".length()).strip();
            }
        }
        return "";
    }

    private static boolean matches(String globs, List<String> touched) {
        if (touched == null || touched.isEmpty()) {
            return false;
        }
        for (String glob : globs.split(",")) {
            String pattern = glob.strip().replace('\\', '/');
            if (pattern.isEmpty()) {
                continue;
            }
            while (pattern.startsWith("./")) {
                pattern = pattern.substring(2);
            }
            boolean basenameOnly = !pattern.contains("/");
            for (String path : touched) {
                if (path == null || path.isBlank()) {
                    continue;
                }
                String value = path.strip().replace('\\', '/');
                while (value.startsWith("./")) {
                    value = value.substring(2);
                }
                if (PermissionRules.globMatches(pattern, value)) {
                    return true;
                }
                if (basenameOnly) {
                    int slash = value.lastIndexOf('/');
                    String basename = slash >= 0 ? value.substring(slash + 1) : value;
                    if (PermissionRules.globMatches(pattern, basename)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String readLimited(Path file, int limit) {
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
            String content = new String(buffer, 0, Math.min(length, limit)).strip();
            if (length > limit) {
                content += "\n…（规则文件过长，已截断）";
            }
            return content;
        } catch (IOException e) {
            return "";
        }
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
