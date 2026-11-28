package com.pilot.core.multi.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * 代理定义文件 {@code <name>.md} 头部 YAML frontmatter 解析器。
 *
 * <p>键白名单：{@code name} / {@code description} / {@code tools} / {@code files} /
 * {@code deny-files} / {@code commands} / {@code deny-commands} / {@code writable}。
 * 语法、未知键或取值非法一律抛 {@link IllegalArgumentException}，由 {@link AgentProfileCatalog}
 * 捕获后把该代理记为无效——宁可排除一个坏代理，也不让半解析的角色进入派发。
 */
final class AgentFrontmatter {

    private static final List<String> KEYS =
        List.of("name", "description", "tools", "files", "deny-files", "commands", "deny-commands", "writable");
    private static final Set<String> LIST_KEYS = Set.of("tools", "files", "deny-files", "commands", "deny-commands");
    private static final Set<String> SCALAR_TEXT_KEYS = Set.of("name", "description");
    private static final Pattern YAML_NULL = Pattern.compile("^(?:~|null|Null|NULL|)$");
    private static final Pattern BLOCK_SCALAR_HEADER = Pattern.compile("^[|>][+-]?\\d*[+-]?(?:\\s+#.*)?$");
    private static final int MAX_DESCRIPTION_CHARS = 500;
    private static final int MAX_HEADER_CHARS = 16_384;

    private AgentFrontmatter() {
    }

    /** 解析结果：frontmatter 各键与剥离声明头之后的正文（角色提示词）。 */
    record Parsed(String name, String description, List<String> tools, List<String> files, List<String> denyFiles,
        List<String> commands, List<String> denyCommands, boolean writable, String body) {
    }

    /**
     * 解析代理定义全文。
     *
     * @throws IllegalArgumentException 缺少分隔符、未知键、重复键、description 缺失或超限
     */
    static Parsed parse(String text) {
        String normalized = text == null ? "" : text;
        if (normalized.startsWith("\ufeff")) {
            normalized = normalized.substring(1);
        }
        List<String> lines = normalized.lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.getFirst().strip())) {
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
        String header = preprocessHeader(lines.subList(1, end));
        if (header.length() > MAX_HEADER_CHARS) {
            throw new IllegalArgumentException("frontmatter 超过 " + MAX_HEADER_CHARS + " 字符上限");
        }
        Map<?, ?> values = parseYamlMap(header);
        for (Object rawKey : values.keySet()) {
            String key = String.valueOf(rawKey);
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("不支持的键：" + key + "（只支持 " + String.join(" / ", KEYS) + "）");
            }
        }
        String name = stringValue(values, "name");
        if (values.containsKey("name") && (name == null || name.isEmpty())) {
            throw new IllegalArgumentException("name 为空（以文件名为准，可不写）");
        }
        String description = stringValue(values, "description");
        if (!values.containsKey("description")) {
            throw new IllegalArgumentException("缺少 description（必填）");
        }
        if (description == null || description.isEmpty()) {
            throw new IllegalArgumentException("description 必填：模型靠它判断何时引用该代理");
        }
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException(
                "description 超过 " + MAX_DESCRIPTION_CHARS + " 字符上限（当前 " + description.length() + "）");
        }
        List<String> tools = listValue(values.get("tools"), "tools");
        List<String> files = listValue(values.get("files"), "files");
        List<String> denyFiles = listValue(values.get("deny-files"), "deny-files");
        List<String> commands = listValue(values.get("commands"), "commands");
        List<String> denyCommands = listValue(values.get("deny-commands"), "deny-commands");
        boolean writable = parseWritable(values.get("writable"));
        String body = String.join("\n", lines.subList(end + 1, lines.size())).strip();
        return new Parsed(name, description, tools, files, denyFiles, commands, denyCommands, writable, body);
    }

    private static Map<?, ?> parseYamlMap(String header) {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setNestingDepthLimit(20);
        options.setCodePointLimit(MAX_HEADER_CHARS);
        var dumper = new DumperOptions();
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options,
                new StringOnlyResolver()).load(header);
        } catch (YAMLException failure) {
            throw new IllegalArgumentException("frontmatter YAML 无效：" + failure.getMessage());
        }
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("frontmatter 必须是字段映射");
        }
        return map;
    }

    /** 禁用 YAML 1.1 隐式布尔/数字/日期转换，避免 yes/no/on/off 在列表或文本字段里被改写。 */
    private static final class StringOnlyResolver extends Resolver {
        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.NULL, YAML_NULL, "~nN\0");
        }
    }

    /**
     * 预处理 frontmatter 行：
     * 1) 保留多行块标量（如 {@code description: |}）内部文本原样不动；
     * 2) 兼容未加引号且含 {@code ": "} 的单行文本值（如 {@code description: Frontend: React}）；
     * 3) 兼容未加引号且以 {@code *} 开头的列表 glob（如 {@code deny-files: [**}{@code /.env]}），避免被 YAML 误判为别名。
     */
    private static String preprocessHeader(List<String> headerLines) {
        StringBuilder out = new StringBuilder();
        String currentKey = null;
        int blockScalarIndent = -1;
        for (int i = 0; i < headerLines.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = headerLines.get(i);
            int indent = leadingSpaces(line);
            String trimmed = line.strip();
            if (blockScalarIndent >= 0) {
                if (trimmed.isEmpty() || indent > blockScalarIndent) {
                    out.append(line);
                    continue;
                }
                blockScalarIndent = -1;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                out.append(line);
                continue;
            }
            if (indent == 0 && !trimmed.startsWith("- ")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    currentKey = line.substring(0, colon).strip();
                    String rawValue = line.substring(colon + 1).strip();
                    if (BLOCK_SCALAR_HEADER.matcher(rawValue).matches()) {
                        blockScalarIndent = indent;
                        out.append(line);
                        continue;
                    }
                    out.append(preprocessKeyLine(line, colon, currentKey, rawValue));
                    continue;
                }
            }
            if (trimmed.startsWith("- ") && currentKey != null && LIST_KEYS.contains(currentKey)) {
                out.append(preprocessListItemLine(line, trimmed));
                continue;
            }
            out.append(line);
        }
        return out.toString();
    }

    private static String preprocessKeyLine(String line, int colon, String key, String rawValue) {
        if (SCALAR_TEXT_KEYS.contains(key)) {
            if (!rawValue.isEmpty() && !isQuoted(rawValue) && rawValue.contains(": ")) {
                return line.substring(0, colon + 1) + " " + singleQuote(rawValue);
            }
            return line;
        }
        if (!LIST_KEYS.contains(key) || rawValue.isEmpty() || isQuoted(rawValue)) {
            return line;
        }
        String valueNoComment = stripInlineComment(rawValue);
        if (valueNoComment.startsWith("[") && valueNoComment.endsWith("]")) {
            String inner = valueNoComment.substring(1, valueNoComment.length() - 1);
            if (!inner.contains("*")) {
                return line;
            }
            List<String> parts = splitFlowItems(inner).stream().map(part -> {
                String s = part.strip();
                return s.startsWith("*") ? singleQuote(s) : s;
            }).toList();
            return line.substring(0, colon + 1) + " [" + String.join(", ", parts) + "]";
        }
        if (valueNoComment.startsWith("*")) {
            return line.substring(0, colon + 1) + " " + singleQuote(valueNoComment);
        }
        return line;
    }

    private static String preprocessListItemLine(String line, String trimmed) {
        String item = stripInlineComment(trimmed.substring(2).strip());
        if (item.startsWith("*")) {
            int dashEnd = line.indexOf("- ") + 2;
            return line.substring(0, dashEnd) + singleQuote(item);
        }
        return line;
    }

    private static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static boolean isQuoted(String value) {
        return value.startsWith("'") || value.startsWith("\"");
    }

    /** 剥离引号外的 YAML 行内注释（{@code #} 前须有空白）。 */
    private static String stripInlineComment(String value) {
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\' && i + 1 < value.length()) {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '#' && i > 0 && Character.isWhitespace(value.charAt(i - 1))) {
                return value.substring(0, i).stripTrailing();
            }
        }
        return value;
    }

    /** 按引号外的逗号拆分 YAML 流式数组项，避免破坏引号内的逗号。 */
    private static List<String> splitFlowItems(String inner) {
        List<String> result = new ArrayList<>();
        int start = 0;
        char quote = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\' && i + 1 < inner.length()) {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ',') {
                result.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        result.add(inner.substring(start));
        return result;
    }

    private static String singleQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String stringValue(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " 必须是文本");
        }
        return text.strip();
    }

    /** 兼容 YAML 列表与逗号分隔文本；仅按逗号拆分以保留带空格的命令前缀（如 {@code mvn test}）。 */
    private static List<String> listValue(Object raw, String key) {
        if (raw == null) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry == null) {
                    continue;
                }
                if (!(entry instanceof String text)) {
                    throw new IllegalArgumentException(key + " 的元素必须是文本：" + entry);
                }
                String item = text.strip();
                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
        } else if (raw instanceof String text) {
            for (String part : text.split("[,，]")) {
                String item = part.strip();
                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
        } else {
            throw new IllegalArgumentException(key + " 必须是列表或逗号分隔文本");
        }
        if (items.stream().distinct().count() != items.size()) {
            throw new IllegalArgumentException(key + " 存在重复项：" + raw);
        }
        return List.copyOf(items);
    }

    private static boolean parseWritable(Object raw) {
        if (raw == null) {
            return false;
        }
        if (!(raw instanceof String textValue)) {
            throw new IllegalArgumentException("writable 只接受 true/false：" + raw);
        }
        String text = textValue.strip().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "yes", "1" -> true;
            case "false", "no", "0", "" -> false;
            default -> throw new IllegalArgumentException("writable 只接受 true/false：" + raw);
        };
    }
}
