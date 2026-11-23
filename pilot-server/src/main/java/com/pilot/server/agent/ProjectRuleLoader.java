package com.pilot.server.agent;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.pilot.core.memory.MemoryStore;
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
     * 记忆注入：与约定分开的独立通道——优先加载数据目录下经过人工审阅的 {@link MemoryStore} 索引
     * （同一 Git 仓库的 worktree 共享），再向下兼容加载手写 {@code MEMORY.md}；
     * 候选区均不注入提示词。总预算独立（4,000 字符 / 200 行）。
     */
    static String loadMemory(Path workspace, PromptLibrary prompts) {
        return loadMemory(workspace, null, prompts);
    }

    static String loadMemory(Path workspace, Path dataMemoryRoot, PromptLibrary prompts) {
        if (workspace == null) {
            return "";
        }
        int[] remaining = {MAX_MEMORY_CHARS, MemoryStore.MAX_INDEX_LINES};
        List<String> rendered = new ArrayList<>();
        MemoryStore store = dataMemoryRoot == null ? null : new MemoryStore(dataMemoryRoot);

        if (store != null) {
            MemoryStore.IndexView projectIndex = store.loadIndex(workspace, MemoryStore.SCOPE_PROJECT);
            appendBoundedMemory(rendered, store.projectDir(workspace).resolve(MemoryStore.INDEX_FILE),
                projectIndex.text(), prompts, remaining);
        }
        if (remaining[0] > 0 && remaining[1] > 0) {
            for (Rule rule : collectAncestors(workspace, MEMORY_NAMES, remaining[0], "记忆文件过长，已截断")) {
                appendBoundedMemory(rendered, rule.path(), rule.content(), prompts, remaining);
            }
        }
        if (store != null) {
            MemoryStore.IndexView userIndex = store.loadIndex(workspace, MemoryStore.SCOPE_USER);
            appendBoundedMemory(rendered, store.userDir().resolve(MemoryStore.INDEX_FILE), userIndex.text(), prompts,
                remaining);
        }
        if (remaining[0] > 0 && remaining[1] > 0) {
            Rule user = firstRule(userHome(), MEMORY_NAMES, remaining[0], "记忆文件过长，已截断");
            if (user != null) {
                appendBoundedMemory(rendered, user.path(), user.content(), prompts, remaining);
            }
        }
        return String.join("\n\n", rendered);
    }

    private static void appendBoundedMemory(List<String> rendered, Path source, String rawText, PromptLibrary prompts,
        int[] remaining) {
        if (remaining[0] <= 0 || remaining[1] <= 0 || rawText == null || rawText.isBlank()) {
            return;
        }
        String bounded = fitMemoryBudget(rawText, remaining[0], remaining[1]);
        if (bounded.isEmpty()) {
            return;
        }
        rendered.add(renderMemory(prompts, source.toString(), bounded));
        remaining[0] -= bounded.length();
        remaining[1] -= countLines(bounded);
    }

    private static List<Rule> collectAncestors(Path workspace, List<String> names, int totalLimit, String truncated) {
        List<Rule> nearestFirst = new ArrayList<>();
        int remaining = totalLimit;
        for (Path dir = workspace.toAbsolutePath().normalize(); dir != null && remaining > 0; dir = dir.getParent()) {
            Rule rule = firstRule(dir, names, remaining, truncated);
            if (rule != null) {
                nearestFirst.add(rule);
                remaining -= rule.content().length();
            }
        }
        Collections.reverse(nearestFirst);
        return nearestFirst;
    }

    private static String renderMemory(PromptLibrary prompts, String source, String content) {
        return prompts.render("system/memory", Map.of("source", source, "memory", content)).strip();
    }

    private static String fitMemoryBudget(String raw, int maxChars, int maxLines) {
        if (raw == null || raw.isBlank() || maxChars <= 0 || maxLines <= 0) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        boolean truncated = false;
        String[] lines = text.split("\n", -1);
        if (lines.length > maxLines) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < maxLines; i++) {
                if (i > 0) {
                    out.append('\n');
                }
                out.append(lines[i]);
            }
            text = out.toString();
            truncated = true;
        }
        if (text.length() > maxChars && !text.endsWith("已截断）")) {
            int end = maxChars;
            if (Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            text = text.substring(0, end);
            truncated = true;
        }
        if (truncated && !text.endsWith("已截断）")) {
            text = text.stripTrailing() + "\n…（记忆文件过长，已截断）";
        }
        return text;
    }

    private static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\n", -1).length;
    }

    private static Path userHome() {
        return Path.of(System.getProperty("user.home", "."), ".pilot");
    }

    private static Rule firstRule(Path dir, List<String> names, int limit, String truncated) {
        for (String name : names) {
            Path file = dir.resolve(name);
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
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
