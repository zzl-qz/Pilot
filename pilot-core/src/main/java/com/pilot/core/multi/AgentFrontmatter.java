package com.pilot.core.multi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 代理定义文件 {@code <name>.md} 头部 frontmatter 的手写解析器（不引入 YAML 依赖，
 * 与 {@code SkillFrontmatter} 同哲学、互不依赖：键集合不同、失败语义独立演进）。
 *
 * <p>键白名单：{@code name} / {@code description} / {@code tools} / {@code files} /
 * {@code deny-files} / {@code commands} / {@code deny-commands} / {@code writable}。
 * 语法或取值非法一律抛 {@link IllegalArgumentException}，由 {@link AgentProfileCatalog}
 * 捕获后把该代理记为无效——宁可排除一个坏代理，也不让半解析的角色进入派发。
 */
final class AgentFrontmatter {

    private static final List<String> KEYS = List.of(
            "name", "description", "tools", "files", "deny-files", "commands", "deny-commands", "writable");
    private static final int MAX_DESCRIPTION_CHARS = 500;

    private AgentFrontmatter() {
    }

    /** 解析结果：frontmatter 各键与剥离声明头之后的正文（角色提示词）。 */
    record Parsed(String name, String description, List<String> tools, List<String> files,
                  List<String> denyFiles, List<String> commands, List<String> denyCommands,
                  boolean writable, String body) {
    }

    /**
     * 解析代理定义全文。
     *
     * @throws IllegalArgumentException 缺少分隔符、未知键、重复键、description 缺失或超限
     */
    static Parsed parse(String text) {
        List<String> lines = (text == null ? "" : text).lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.get(0).strip())) {
            throw new IllegalArgumentException("代理定义必须以 --- 开头声明 frontmatter");
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
        List<String> tools = null;
        List<String> files = null;
        List<String> denyFiles = null;
        List<String> commands = null;
        List<String> denyCommands = null;
        Boolean writable = null;
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
                throw new IllegalArgumentException("不支持的键：" + key
                        + "（只支持 " + String.join(" / ", KEYS) + "）");
            }
            switch (key) {
                case "name" -> {
                    if (value.isEmpty()) throw new IllegalArgumentException("name 为空（以文件名为准，可不写）");
                    if (name != null) throw new IllegalArgumentException("重复的键：name");
                    name = value;
                }
                case "description" -> {
                    if (description != null) throw new IllegalArgumentException("重复的键：description");
                    if (value.isEmpty()) {
                        throw new IllegalArgumentException("description 必填：模型靠它判断何时引用该代理");
                    }
                    if (value.length() > MAX_DESCRIPTION_CHARS) {
                        throw new IllegalArgumentException("description 超过 "
                                + MAX_DESCRIPTION_CHARS + " 字符上限（当前 " + value.length() + "）");
                    }
                    description = value;
                }
                case "tools" -> {
                    if (tools != null) throw new IllegalArgumentException("重复的键：tools");
                    tools = list(value, key);
                }
                case "files" -> {
                    if (files != null) throw new IllegalArgumentException("重复的键：files");
                    files = list(value, key);
                }
                case "deny-files" -> {
                    if (denyFiles != null) throw new IllegalArgumentException("重复的键：deny-files");
                    denyFiles = list(value, key);
                }
                case "commands" -> {
                    if (commands != null) throw new IllegalArgumentException("重复的键：commands");
                    commands = list(value, key);
                }
                case "deny-commands" -> {
                    if (denyCommands != null) throw new IllegalArgumentException("重复的键：deny-commands");
                    denyCommands = list(value, key);
                }
                case "writable" -> {
                    if (writable != null) throw new IllegalArgumentException("重复的键：writable");
                    writable = switch (value.toLowerCase()) {
                        case "true", "yes", "1" -> true;
                        case "false", "no", "0", "" -> false;
                        default -> throw new IllegalArgumentException("writable 只接受 true/false：" + value);
                    };
                }
                default -> throw new IllegalArgumentException("不支持的键：" + key);
            }
        }
        if (description == null) {
            throw new IllegalArgumentException("缺少 description（必填）");
        }
        String body = String.join("\n", lines.subList(end + 1, lines.size())).strip();
        return new Parsed(name, description,
                tools == null ? List.of() : tools,
                files == null ? List.of() : files,
                denyFiles == null ? List.of() : denyFiles,
                commands == null ? List.of() : commands,
                denyCommands == null ? List.of() : denyCommands,
                writable != null && writable,
                body);
    }

    /** {@code [a, b]} / {@code a, b} / {@code a} 三种写法统一成列表（语法对齐技能解析器）。 */
    private static List<String> list(String value, String key) {
        String list = value.strip();
        if (list.startsWith("[") && list.endsWith("]")) {
            list = list.substring(1, list.length() - 1);
        } else if (list.startsWith("[") != list.endsWith("]")) {
            throw new IllegalArgumentException(key + " 列表括号不成对：" + value);
        }
        List<String> items = new ArrayList<>(Arrays.stream(list.split("[,，]"))
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .toList());
        if (items.stream().distinct().count() != items.size()) {
            throw new IllegalArgumentException(key + " 存在重复项：" + value);
        }
        return items;
    }
}
