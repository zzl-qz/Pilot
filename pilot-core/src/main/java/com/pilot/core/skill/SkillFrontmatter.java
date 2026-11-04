package com.pilot.core.skill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SKILL.md 头部 frontmatter 的手写解析器（不引入 YAML 依赖，与 {@code PromptLibrary} 同哲学）。
 *
 * <p>键白名单：{@code name} / {@code description} / {@code command} / {@code argument-hint} /
 * {@code allowed-tools}。语法或取值非法一律抛 {@link IllegalArgumentException}，由
 * {@link SkillCatalog} 捕获后把该技能记为无效——宁可排除一个坏技能，也不注入半解析的指引。
 *
 * <p>与 {@code PromptLibrary.parseFrontmatter} 刻意保持独立：那个解析器单键、抛异常语义是
 * 提示词资源的行为契约，泛化它有回归风险；技能键更多、且失败语义是「无效技能」而非「中断启动」。
 */
final class SkillFrontmatter {

    /** 快捷指令名：斜杠 + 小写连字符词。 */
    private static final Pattern COMMAND = Pattern.compile("^/[a-z][a-z0-9-]{0,31}$");
    /** description 是目录段里唯一的判断依据，上限 500 字符。 */
    private static final int MAX_DESCRIPTION_CHARS = 500;
    private static final List<String> KEYS =
        List.of("name", "description", "command", "argument-hint", "allowed-tools");

    private SkillFrontmatter() {
    }

    /** 解析结果：frontmatter 各键与剥离声明头之后的正文。 */
    record Parsed(String name, String description, String command, String argumentHint, List<String> allowedTools,
        String body) {
    }

    /**
     * 解析 SKILL.md 全文。
     *
     * @throws IllegalArgumentException 缺少分隔符、未知键、重复键、description 缺失或超限、
     *                                  command 格式非法、name 为空
     */
    static Parsed parse(String text) {
        List<String> lines = (text == null ? "" : text).lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.get(0).strip())) {
            throw new IllegalArgumentException("SKILL.md 必须以 --- 开头声明 frontmatter");
        }
        int end = -1;
        for (int i = 1; i < lines.size(); i++) {
            if ("---".equals(lines.get(i).strip())) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IllegalArgumentException("frontmatter 缺少结束分隔符 ---");
        }
        String name = null;
        String description = null;
        String command = null;
        String argumentHint = null;
        List<String> allowedTools = null;
        for (int i = 1; i < end; i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("frontmatter 行无法解析：" + line);
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("不支持的键：" + key + "（只支持 " + String.join(" / ", KEYS) + "）");
            }
            switch (key) {
                case "name" -> {
                    if (value.isEmpty()) {
                        throw new IllegalArgumentException("name 为空（缺省时以目录名为准，可不写）");
                    }
                    if (name != null) {
                        throw new IllegalArgumentException("重复的键：name");
                    }
                    name = value;
                }
                case "description" -> {
                    if (description != null) {
                        throw new IllegalArgumentException("重复的键：description");
                    }
                    if (value.isEmpty()) {
                        throw new IllegalArgumentException("description 必填：模型靠它判断何时使用技能");
                    }
                    if (value.length() > MAX_DESCRIPTION_CHARS) {
                        throw new IllegalArgumentException(
                            "description 超过 " + MAX_DESCRIPTION_CHARS + " 字符上限（当前 " + value.length() + "）");
                    }
                    description = value;
                }
                case "command" -> {
                    if (command != null) {
                        throw new IllegalArgumentException("重复的键：command");
                    }
                    if (!value.isEmpty() && !COMMAND.matcher(value).matches()) {
                        throw new IllegalArgumentException("command 必须是 / 加小写连字符词（如 /review）：" + value);
                    }
                    command = value.isEmpty() ? null : value;
                }
                case "argument-hint" -> {
                    if (argumentHint != null) {
                        throw new IllegalArgumentException("重复的键：argument-hint");
                    }
                    argumentHint = value.isEmpty() ? null : value;
                }
                case "allowed-tools" -> {
                    if (allowedTools != null) {
                        throw new IllegalArgumentException("重复的键：allowed-tools");
                    }
                    allowedTools = toolList(value);
                }
                default -> throw new IllegalArgumentException("不支持的键：" + key);
            }
        }
        if (description == null) {
            throw new IllegalArgumentException("缺少 description（必填）：模型靠它判断何时使用技能");
        }
        String body = String.join("\n", lines.subList(end + 1, lines.size())).strip();
        return new Parsed(name, description, command, argumentHint,
            allowedTools == null ? List.of() : List.copyOf(allowedTools), body);
    }

    /** {@code [a, b]} / {@code a, b} / {@code a} 三种写法统一成工具名列表（语法对齐 PromptLibrary）。 */
    private static List<String> toolList(String value) {
        String list = value.strip();
        if (list.startsWith("[") && list.endsWith("]")) {
            list = list.substring(1, list.length() - 1);
        } else if (list.startsWith("[") != list.endsWith("]")) {
            throw new IllegalArgumentException("allowed-tools 列表括号不成对：" + value);
        }
        List<String> names = new ArrayList<>(
            Arrays.stream(list.split("[,，]")).map(String::strip).filter(item -> !item.isEmpty()).toList());
        if (names.stream().distinct().count() != names.size()) {
            throw new IllegalArgumentException("allowed-tools 存在重复项：" + value);
        }
        return names;
    }
}
