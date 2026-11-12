package com.pilot.core.skill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** 标准 YAML frontmatter；扩展元数据可以保留，但不会提升工具权限或执行代码。 */
final class SkillFrontmatter {
    private static final Pattern COMMAND = Pattern.compile("^/[a-z][a-z0-9-]{0,31}$");
    // 接受生态中略长于规范建议的描述，仍保留明确的上下文上限。
    private static final int MAX_DESCRIPTION_CHARS = 2048;
    private static final int MAX_HEADER_CHARS = 16384;

    private SkillFrontmatter() {
    }

    record Parsed(String name, String description, String command, String argumentHint, List<String> allowedTools,
        String body) {
    }

    static Parsed parse(String text) {
        String normalized = text == null ? "" : text;
        if (normalized.startsWith("\ufeff")) {
            normalized = normalized.substring(1);
        }
        List<String> lines = normalized.lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.getFirst().strip())) {
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
        String header = String.join("\n", lines.subList(1, end));
        if (header.length() > MAX_HEADER_CHARS) {
            throw new IllegalArgumentException("frontmatter 超过 16384 字符上限");
        }
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setNestingDepthLimit(20);
        options.setCodePointLimit(MAX_HEADER_CHARS);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(header);
        } catch (YAMLException failure) {
            throw new IllegalArgumentException("frontmatter YAML 无效：" + failure.getMessage());
        }
        if (loaded == null) {
            loaded = Map.of();
        }
        if (!(loaded instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("frontmatter 必须是字段映射");
        }
        String name = string(values, "name", false);
        if (values.containsKey("name") && (name == null || name.isBlank())) {
            throw new IllegalArgumentException("name 不能为空");
        }
        String description = string(values, "description", true);
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("description 超过 2048 字符上限");
        }
        String command = string(values, "command", false);
        if (command != null && !COMMAND.matcher(command).matches()) {
            throw new IllegalArgumentException("command 必须是 / 加小写连字符词");
        }
        Object hint = values.get("argument-hint");
        // 兼容 Pilot 原有未加引号的 [参数] 写法，标准 YAML 中它是列表。
        String argumentHint = hint instanceof List<?> list ? "[" + String.join(", ", strings(list)) + "]"
            : string(values, "argument-hint", false);
        Object allowed = values.get("allowed-tools");
        List<String> tools = allowed instanceof List<?> list ? strings(list) : allowed == null ? List.of() : Arrays
            .stream(string(values, "allowed-tools", true).split("[,，\\s]+")).filter(value -> !value.isBlank()).toList();
        if (tools.stream().distinct().count() != tools.size()) {
            throw new IllegalArgumentException("allowed-tools 存在重复项");
        }
        return new Parsed(name, description, command, argumentHint, List.copyOf(tools),
            String.join("\n", lines.subList(end + 1, lines.size())).strip());
    }

    private static String string(Map<?, ?> values, String key, boolean required) {
        Object value = values.get(key);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException(key + " 必须是文本");
        }
        String result = value == null ? null : ((String)value).strip();
        if (result == null || result.isEmpty()) {
            if (required) {
                throw new IllegalArgumentException("缺少 " + key + "（必填）");
            }
            return null;
        }
        return result;
    }

    private static List<String> strings(List<?> values) {
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("列表项必须是非空文本");
            }
            result.add(text.strip());
        }
        return result;
    }
}
