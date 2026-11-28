package com.pilot.core.multi.profile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 自定义子代理目录的扫描器：{@code <dir>/<name>.md} 平铺文件（对齐 Claude Code 的
 * {@code .claude/agents} 惯例），两级目录项目级遮蔽用户级。
 *
 * <p>与技能目录（SKILL.md 在子目录里）刻意不同：代理定义是单文件的角色卡，没有附带资源，
 * 平铺让「一个代理一个文件」在文件列表里一目了然。
 *
 * <p>坏文件不拖垮整个目录：解析失败的条目记入 {@code invalid}（名称 + 原因），
 * 引用一个无效代理会在派发时拿到明确的错误提示，而不是被静默忽略。
 */
public final class AgentProfileCatalog {

    public static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");
    private static final int MAX_PROFILES = 32;
    private static final long MAX_FILE_BYTES = 192L * 1024;
    /** 角色提示词长度上限（对齐技能正文）：代理身份声明不该比技能指引更长。 */
    public static final int MAX_PROMPT_LINES = 300;
    private static final String BUILTIN_INDEX = "agents/index.txt";
    private static final String BUILTIN_PREFIX = "agents/";

    public static final AgentProfileCatalog EMPTY = new AgentProfileCatalog(Map.of(), List.of());

    /** 无效条目：目录名 + 文件 + 原因，随调用方提示给用户或模型。 */
    public record AgentProblem(String name, String file, String reason) {
    }

    private final Map<String, AgentProfile> profiles;
    private final List<AgentProblem> invalid;

    private AgentProfileCatalog(Map<String, AgentProfile> profiles, List<AgentProblem> invalid) {
        this.profiles = profiles;
        this.invalid = invalid;
    }

    /**
     * 扫描代理目录（仅外部目录，不含内置角色）。
     *
     * @param roots 按优先级降序传入（项目级在前）；不存在的目录跳过
     */
    public static AgentProfileCatalog scan(List<Path> roots) {
        return scan(roots, false);
    }

    /**
     * 扫描代理目录并追加随包内置角色（项目级 &gt; 用户级 &gt; 内置级，同名高优先级遮蔽内置）。
     *
     * @param roots 按优先级降序传入（项目级在前）；不存在的目录跳过
     */
    public static AgentProfileCatalog scanWithBuiltins(List<Path> roots) {
        return scan(roots, true);
    }

    private static AgentProfileCatalog scan(List<Path> roots, boolean includeBuiltins) {
        Map<String, AgentProfile> byName = new LinkedHashMap<>();
        List<AgentProblem> problems = new ArrayList<>();
        if (roots != null) {
            for (Path root : roots) {
                if (root == null || !Files.isDirectory(root)) {
                    continue;
                }
                List<Path> files;
                try (Stream<Path> entries = Files.list(root)) {
                    files = entries.filter(Files::isRegularFile).filter(path -> !Files.isSymbolicLink(path))
                        .filter(path -> path.getFileName().toString().endsWith(".md"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
                } catch (IOException e) {
                    problems.add(new AgentProblem("（目录）", root.toString(), "扫描失败：" + e.getClass().getSimpleName()));
                    continue;
                }
                for (Path file : files) {
                    load(file, byName, problems);
                }
            }
        }
        if (includeBuiltins) {
            loadBuiltins(byName, problems);
        }
        return new AgentProfileCatalog(ordered(byName), List.copyOf(problems));
    }

    /** 按名排序固化顺序，避免 Map.copyOf 在重启间打乱工具描述而破坏提示词缓存。 */
    private static Map<String, AgentProfile> ordered(Map<String, AgentProfile> byName) {
        Map<String, AgentProfile> sorted = new LinkedHashMap<>();
        byName.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(sorted);
    }

    private static void loadBuiltins(Map<String, AgentProfile> byName, List<AgentProblem> problems) {
        ClassLoader loader = AgentProfileCatalog.class.getClassLoader();
        String indexText = readResource(loader, BUILTIN_INDEX);
        if (indexText == null || indexText.isBlank()) {
            return;
        }
        for (String line : indexText.lines().toList()) {
            String name = line.strip();
            if (name.isEmpty() || name.startsWith("#")) {
                continue;
            }
            if (byName.containsKey(name)) {
                continue;
            }
            String source = "builtin/" + name + ".md";
            if (!hasCapacity(name, source, byName, problems)) {
                continue;
            }
            String content = readResource(loader, BUILTIN_PREFIX + name + ".md");
            if (content == null) {
                continue;
            }
            parseAndStore(name, source, content, byName, problems);
        }
    }

    /** 读取随包内置角色的原始 Markdown 定义；不存在或名称不合法时返回空串。 */
    public static String readBuiltinContent(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            return "";
        }
        String content = readResource(AgentProfileCatalog.class.getClassLoader(), BUILTIN_PREFIX + name + ".md");
        return content == null ? "" : content;
    }

    private static String readResource(ClassLoader loader, String path) {
        try (InputStream stream = loader.getResourceAsStream(path)) {
            return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void load(Path file, Map<String, AgentProfile> byName, List<AgentProblem> problems) {
        String fileName = file.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - ".md".length());
        // 同名代理已被更高优先级来源加载：遮蔽是设计行为，不亮牌
        if (byName.containsKey(name)) {
            return;
        }
        if (!hasCapacity(name, file.toString(), byName, problems)) {
            return;
        }
        if (!VALID_NAME.matcher(name).matches()) {
            problems.add(new AgentProblem(name, file.toString(), "文件名必须匹配 ^[a-z][a-z0-9-]{0,63}$"));
            return;
        }
        String text;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                problems.add(new AgentProblem(name, file.toString(), "代理定义文件超过读取上限 192KB"));
                return;
            }
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            problems.add(new AgentProblem(name, file.toString(), "读取失败：" + e.getClass().getSimpleName()));
            return;
        }
        parseAndStore(name, file.toString(), text, byName, problems);
    }

    private static boolean hasCapacity(String name, String source, Map<String, AgentProfile> byName,
        List<AgentProblem> problems) {
        if (byName.size() >= MAX_PROFILES) {
            problems.add(new AgentProblem(name, source, "有效代理数已达上限 " + MAX_PROFILES + "，本代理被跳过"));
            return false;
        }
        return true;
    }

    /** 解析并校验单个代理定义（名称、frontmatter、正文非空与行数上限），作为扫描与写接口的单一事实来源。 */
    public static AgentProfile parseProfile(String name, String text) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("文件名必须匹配 ^[a-z][a-z0-9-]{0,63}$");
        }
        AgentFrontmatter.Parsed parsed = AgentFrontmatter.parse(text);
        if (parsed.name() != null && !parsed.name().equals(name)) {
            throw new IllegalArgumentException("name 声明（" + parsed.name() + "）与文件名不一致；文件名是真源名");
        }
        if (parsed.body().isEmpty()) {
            throw new IllegalArgumentException("正文为空：代理定义只有 frontmatter，没有角色提示词");
        }
        if (parsed.body().lines().count() > MAX_PROMPT_LINES) {
            throw new IllegalArgumentException("角色提示词超过 " + MAX_PROMPT_LINES + " 行上限");
        }
        return new AgentProfile(name, parsed.description(), parsed.tools(), parsed.files(), parsed.denyFiles(),
            parsed.commands(), parsed.denyCommands(), parsed.writable(), parsed.body());
    }

    private static void parseAndStore(String name, String source, String text, Map<String, AgentProfile> byName,
        List<AgentProblem> problems) {
        try {
            byName.put(name, parseProfile(name, text));
        } catch (IllegalArgumentException e) {
            problems.add(new AgentProblem(name, source, e.getMessage()));
        }
    }

    /** @return 按名查找；不存在返回空。 */
    public Optional<AgentProfile> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(profiles.get(name.strip()));
    }

    public Map<String, AgentProfile> profiles() {
        return profiles;
    }

    public List<AgentProblem> invalid() {
        return invalid;
    }

    /** 工具描述里拼接的可用名单：让模型知道有哪些角色可以直接引用。 */
    public String nameListText() {
        if (profiles.isEmpty()) {
            return "";
        }
        return String.join("、", profiles.keySet());
    }
}
