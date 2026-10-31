package com.pilot.core.permission;

import com.pilot.core.tool.ToolCall;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 细粒度权限规则，格式与 Claude Code / Codex 的 settings 一致：
 *
 * <pre>{@code
 * {
 *   "permissions": {
 *     "deny":  ["run_command(rm -rf:*)", "read_file(.env)"],
 *     "ask":   ["edit_file(docs/*.md)"],
 *     "allow": ["run_command(mvn test:*)", "edit_file(src/**)", "read_file"]
 *   }
 * }
 * }</pre>
 *
 * <p>判定顺序为 deny、ask、allow；都没有命中时交回档位（READ_ONLY/ASK/AUTO_EDIT/AUTO）。
 * 规则来自配置文件而不是模型，工作区级覆盖用户级。
 */
public final class PermissionRules {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** @param tool 工具名；为空表示任意工具 */
    public record Rule(String tool, String specifier) {
    }

    private final List<Rule> allow;
    private final List<Rule> deny;
    private final List<Rule> ask;

    private PermissionRules(List<Rule> allow, List<Rule> deny, List<Rule> ask) {
        this.allow = List.copyOf(allow);
        this.deny = List.copyOf(deny);
        this.ask = List.copyOf(ask);
    }

    public static PermissionRules none() {
        return new PermissionRules(List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return allow.isEmpty() && deny.isEmpty() && ask.isEmpty();
    }

    /** 读取用户级与工作区级配置并合并；解析失败退化为「没有规则」，不影响原有档位。 */
    public static PermissionRules load(Path workspace) {
        List<Rule> allow = new ArrayList<>();
        List<Rule> deny = new ArrayList<>();
        List<Rule> ask = new ArrayList<>();
        Path userHome = Path.of(System.getProperty("user.home", "."));
        merge(userHome.resolve(".pilot").resolve("settings.json"), allow, deny, ask);
        if (workspace != null) {
            merge(workspace.resolve(".pilot").resolve("settings.json"), allow, deny, ask);
        }
        return allow.isEmpty() && deny.isEmpty() && ask.isEmpty()
                ? none() : new PermissionRules(allow, deny, ask);
    }

    private static void merge(Path file, List<Rule> allow, List<Rule> deny, List<Rule> ask) {
        if (!Files.isRegularFile(file)) return;
        Map<?, ?> root;
        try {
            root = MAPPER.readValue(Files.readAllBytes(file), Map.class);
        } catch (IOException | RuntimeException e) {
            return;
        }
        if (root == null || !(root.get("permissions") instanceof Map<?, ?> permissions)) return;
        collect(permissions.get("allow"), allow);
        collect(permissions.get("deny"), deny);
        collect(permissions.get("ask"), ask);
    }

    private static void collect(Object value, List<Rule> target) {
        if (!(value instanceof List<?> items)) return;
        for (Object item : items) {
            Rule rule = parse(item == null ? "" : String.valueOf(item));
            if (rule != null) target.add(rule);
        }
    }

    /** 解析 {@code Tool} 或 {@code Tool(specifier)} 两种写法。 */
    private static Rule parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) return null;
        int open = text.indexOf('(');
        if (open < 0 || !text.endsWith(")")) return new Rule(text, "");
        String tool = text.substring(0, open).strip();
        String specifier = text.substring(open + 1, text.length() - 1).strip();
        if (tool.isEmpty()) return null;
        return new Rule(tool, specifier);
    }

    /**
     * @return 命中的决策；{@code null} 表示没有规则命中，交回档位判定
     */
    public PermissionResult evaluate(ToolCall call) {
        if (isEmpty() || call == null) return null;
        if (matches(deny, call)) {
            return PermissionResult.deny("命中 deny 规则：" + describe(deny, call));
        }
        if (matches(ask, call)) {
            return PermissionResult.ask("命中 ask 规则：" + describe(ask, call));
        }
        if (matches(allow, call)) {
            return PermissionResult.allow();
        }
        return null;
    }

    private static boolean matches(List<Rule> rules, ToolCall call) {
        for (Rule rule : rules) {
            if (ruleMatches(rule, call)) return true;
        }
        return false;
    }

    private static String describe(List<Rule> rules, ToolCall call) {
        for (Rule rule : rules) {
            if (ruleMatches(rule, call)) {
                return rule.specifier().isEmpty() ? rule.tool() : rule.tool() + "(" + rule.specifier() + ")";
            }
        }
        return "";
    }

    private static boolean ruleMatches(Rule rule, ToolCall call) {
        if (rule.tool().isEmpty()) return false;
        if (!rule.tool().equalsIgnoreCase(call.name()) && !"*".equals(rule.tool())) return false;
        if (rule.specifier().isEmpty()) return true;
        List<String> targets = targets(call);
        if (targets.isEmpty()) return false;
        boolean command = "run_command".equalsIgnoreCase(call.name());
        for (String target : targets) {
            if (command ? commandMatches(rule.specifier(), target) : globMatches(rule.specifier(), target)) {
                return true;
            }
        }
        return false;
    }

    /** 收集用于匹配的字符串参数：命令、路径、模式、目标。 */
    private static List<String> targets(ToolCall call) {
        Map<String, Object> args = call.args() == null ? Map.of() : call.args();
        List<String> values = new ArrayList<>();
        for (String key : List.of("path", "file_path", "filePath", "command", "pattern", "target")) {
            Object value = args.get(key);
            if (value instanceof String text && !text.isBlank()) values.add(text.strip());
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String text && !text.isBlank()) values.add(text.strip());
                }
            }
        }
        return values;
    }

    /** 命令规则：{@code mvn test:*} 表示前缀，含 {@code *} 时按通配，否则要求完全相同。 */
    private static boolean commandMatches(String specifier, String command) {
        String normalized = command.strip().replace('\\', '/');
        if (specifier.endsWith(":*")) {
            String prefix = specifier.substring(0, specifier.length() - 2).strip().replace('\\', '/');
            return normalized.equals(prefix) || normalized.startsWith(prefix + " ")
                    || normalized.startsWith(prefix + "/");
        }
        if (specifier.indexOf('*') >= 0) return glob(specifier).matcher(normalized).matches();
        return normalized.equals(specifier.strip().replace('\\', '/'));
    }

    /** 路径规则：{@code *} 不跨目录，{@code **} 跨目录，分隔符统一按 {@code /} 比较。 */
    private static boolean globMatches(String specifier, String target) {
        String normalized = target.strip().replace('\\', '/');
        String pattern = specifier.strip().replace('\\', '/');
        while (pattern.startsWith("./")) pattern = pattern.substring(2);
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return glob(pattern).matcher(normalized).matches();
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append('.');
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

}
