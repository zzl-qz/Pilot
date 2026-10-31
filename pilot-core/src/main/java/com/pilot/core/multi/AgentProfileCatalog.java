package com.pilot.core.multi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");
    private static final int MAX_PROFILES = 32;
    /** 角色提示词长度上限（对齐技能正文）：代理身份声明不该比技能指引更长。 */
    private static final int MAX_PROMPT_LINES = 300;

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
     * 扫描代理目录。
     *
     * @param roots 按优先级降序传入（项目级在前）；不存在的目录跳过
     */
    public static AgentProfileCatalog scan(List<Path> roots) {
        Map<String, AgentProfile> byName = new LinkedHashMap<>();
        List<AgentProblem> problems = new ArrayList<>();
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> entries = Files.list(root)) {
                files = entries.filter(Files::isRegularFile)
                        .filter(path -> !Files.isSymbolicLink(path))
                        .filter(path -> path.getFileName().toString().endsWith(".md"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            } catch (IOException e) {
                problems.add(new AgentProblem("（目录）", root.toString(),
                        "扫描失败：" + e.getClass().getSimpleName()));
                continue;
            }
            for (Path file : files) {
                load(file, byName, problems);
            }
        }
        return new AgentProfileCatalog(Map.copyOf(byName), List.copyOf(problems));
    }

    private static void load(Path file, Map<String, AgentProfile> byName, List<AgentProblem> problems) {
        String fileName = file.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - ".md".length());
        // 同名代理已被更高优先级来源加载：遮蔽是设计行为，不亮牌
        if (byName.containsKey(name)) {
            return;
        }
        if (byName.size() >= MAX_PROFILES) {
            problems.add(new AgentProblem(name, file.toString(),
                    "有效代理数已达上限 " + MAX_PROFILES + "，本代理被跳过"));
            return;
        }
        if (!VALID_NAME.matcher(name).matches()) {
            problems.add(new AgentProblem(name, file.toString(),
                    "文件名必须匹配 ^[a-z][a-z0-9-]{0,63}$"));
            return;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            problems.add(new AgentProblem(name, file.toString(),
                    "读取失败：" + e.getClass().getSimpleName()));
            return;
        }
        AgentFrontmatter.Parsed parsed;
        try {
            parsed = AgentFrontmatter.parse(text);
        } catch (IllegalArgumentException e) {
            problems.add(new AgentProblem(name, file.toString(), e.getMessage()));
            return;
        }
        if (parsed.name() != null && !parsed.name().equals(name)) {
            problems.add(new AgentProblem(name, file.toString(),
                    "name 声明（" + parsed.name() + "）与文件名不一致；文件名是真源名"));
            return;
        }
        if (parsed.body().isEmpty()) {
            problems.add(new AgentProblem(name, file.toString(),
                    "正文为空：代理定义只有 frontmatter，没有角色提示词"));
            return;
        }
        if (parsed.body().lines().count() > MAX_PROMPT_LINES) {
            problems.add(new AgentProblem(name, file.toString(),
                    "角色提示词超过 " + MAX_PROMPT_LINES + " 行上限"));
            return;
        }
        byName.put(name, new AgentProfile(name, parsed.description(), parsed.tools(),
                parsed.files(), parsed.denyFiles(), parsed.commands(), parsed.denyCommands(),
                parsed.writable(), parsed.body()));
    }

    /** @return 按名查找；不存在返回空。 */
    public java.util.Optional<AgentProfile> find(String name) {
        if (name == null || name.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(profiles.get(name.strip()));
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
