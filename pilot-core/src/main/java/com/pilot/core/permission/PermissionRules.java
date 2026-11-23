package com.pilot.core.permission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pilot.core.message.ToolCall;
import com.pilot.core.message.ToolNames;

import tools.jackson.databind.ObjectMapper;

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
    private static final Logger LOG = LoggerFactory.getLogger(PermissionRules.class);

    /** @param tool 工具名；为空表示任意工具 */
    public record Rule(String tool, String specifier) {
    }

    private final List<Rule> allow;
    private final List<Rule> deny;
    private final List<Rule> ask;
    private final List<String> loadIssues;

    private PermissionRules(List<Rule> allow, List<Rule> deny, List<Rule> ask, List<String> loadIssues) {
        this.allow = List.copyOf(allow);
        this.deny = List.copyOf(deny);
        this.ask = List.copyOf(ask);
        this.loadIssues = List.copyOf(loadIssues);
    }

    public static PermissionRules none() {
        return new PermissionRules(List.of(), List.of(), List.of(), List.of());
    }

    /** 工作区级规则文件；写入入口与 {@link #load} 读的是同一个文件。 */
    public static Path workspaceFile(Path workspace) {
        return workspace == null ? null : workspace.resolve(".pilot").resolve("settings.json");
    }

    /** 当前生效的规则原文，按 deny、ask、allow 分开，供设置界面展示与编辑。 */
    public List<String> allowRules() {
        return render(allow);
    }

    public List<String> denyRules() {
        return render(deny);
    }

    public List<String> askRules() {
        return render(ask);
    }

    private static List<String> render(List<Rule> rules) {
        return rules.stream()
            .map(rule -> rule.specifier().isEmpty() ? rule.tool() : rule.tool() + "(" + rule.specifier() + ")")
            .toList();
    }

    /**
     * 把三组规则写回工作区 {@code .pilot/settings.json} 的 {@code permissions} 段。
     *
     * <p>文件里其他顶层字段（hooks、mcp 等）原样保留：只替换 {@code permissions} 三个数组，
     * 空数组直接移除对应键。写不了就抛异常，由接口层报告给用户，不静默吞掉。
     */
    public static void save(Path workspace, List<String> allow, List<String> deny, List<String> ask)
        throws IOException {
        Path file = workspaceFile(workspace);
        if (file == null) {
            throw new IOException("没有工作区，无法保存权限规则");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            Map<?, ?> existing = MAPPER.readValue(Files.readAllBytes(file), Map.class);
            if (existing != null) {
                existing.forEach((key, value) -> root.put(String.valueOf(key), value));
            }
        }
        Map<String, Object> permissions = new LinkedHashMap<>();
        putRules(permissions, "deny", deny);
        putRules(permissions, "ask", ask);
        putRules(permissions, "allow", allow);
        if (permissions.isEmpty()) {
            root.remove("permissions");
        } else {
            root.put("permissions", permissions);
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
            StandardCharsets.UTF_8);
    }

    private static void putRules(Map<String, Object> permissions, String key, List<String> rules) {
        if (rules == null) {
            return;
        }
        List<String> cleaned =
            rules.stream().filter(rule -> rule != null && !rule.isBlank()).map(String::strip).toList();
        if (!cleaned.isEmpty()) {
            permissions.put(key, cleaned);
        }
    }

    public boolean isEmpty() {
        return allow.isEmpty() && deny.isEmpty() && ask.isEmpty();
    }

    /** 读取用户级与工作区级配置并合并；解析失败退化为「没有规则」，不影响原有档位。 */
    public static PermissionRules load(Path workspace) {
        List<Rule> allow = new ArrayList<>();
        List<Rule> deny = new ArrayList<>();
        List<Rule> ask = new ArrayList<>();
        List<String> loadIssues = new ArrayList<>();
        merge(userFile(), allow, deny, ask, loadIssues);
        if (workspace != null) {
            merge(workspaceFile(workspace), allow, deny, ask, loadIssues);
        }
        return new PermissionRules(allow, deny, ask, loadIssues);
    }

    /**
     * 只读工作区级文件，不含用户级。
     *
     * <p>设置界面改的就是这一个文件，所以展示与回写必须用同一范围；
     * 否则把 {@link #load} 的合并结果写回去，会把用户级规则复制进工作区文件。
     */
    public static PermissionRules loadWorkspace(Path workspace) {
        return loadSingle(workspaceFile(workspace));
    }

    /** 只读用户级文件（{@code ~/.pilot/settings.json}），供界面以只读方式展示。 */
    public static PermissionRules loadUser() {
        return loadSingle(userFile());
    }

    private static PermissionRules loadSingle(Path file) {
        List<Rule> allow = new ArrayList<>();
        List<Rule> deny = new ArrayList<>();
        List<Rule> ask = new ArrayList<>();
        List<String> loadIssues = new ArrayList<>();
        if (file != null) {
            merge(file, allow, deny, ask, loadIssues);
        }
        return new PermissionRules(allow, deny, ask, loadIssues);
    }

    private static Path userFile() {
        return Path.of(System.getProperty("user.home", ".")).resolve(".pilot").resolve("settings.json");
    }

    public List<String> loadIssues() {
        return loadIssues;
    }

    private static void merge(Path file, List<Rule> allow, List<Rule> deny, List<Rule> ask, List<String> loadIssues) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<?, ?> root;
        try {
            root = MAPPER.readValue(Files.readAllBytes(file), Map.class);
        } catch (IOException | RuntimeException e) {
            String issue = "读取权限配置失败 " + file + "：" + e.getMessage();
            loadIssues.add(issue);
            LOG.warn("{}", issue);
            return;
        }
        if (root == null || !(root.get("permissions") instanceof Map<?, ?> permissions)) {
            String issue = "权限配置缺少 permissions 对象：" + file;
            loadIssues.add(issue);
            LOG.warn("{}", issue);
            return;
        }
        collect(permissions.get("allow"), allow);
        collect(permissions.get("deny"), deny);
        collect(permissions.get("ask"), ask);
    }

    private static void collect(Object value, List<Rule> target) {
        if (!(value instanceof List<?> items)) {
            return;
        }
        for (Object item : items) {
            Rule rule = parse(item == null ? "" : String.valueOf(item));
            if (rule != null) {
                target.add(rule);
            }
        }
    }

    /** 解析 {@code Tool} 或 {@code Tool(specifier)} 两种写法。 */
    private static Rule parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            return null;
        }
        int open = text.indexOf('(');
        if (open < 0 || !text.endsWith(")")) {
            return new Rule(text, "");
        }
        String tool = text.substring(0, open).strip();
        String specifier = text.substring(open + 1, text.length() - 1).strip();
        if (tool.isEmpty()) {
            return null;
        }
        return new Rule(tool, specifier);
    }

    /**
     * @return 命中的决策；{@code null} 表示没有规则命中，交回档位判定
     */
    public PermissionResult evaluate(ToolCall call) {
        if (isEmpty() || call == null) {
            return null;
        }
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
            if (ruleMatches(rule, call)) {
                return true;
            }
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
        if (rule.tool().isEmpty()) {
            return false;
        }
        if (!rule.tool().equalsIgnoreCase(call.name()) && !"*".equals(rule.tool())) {
            return false;
        }
        if (rule.specifier().isEmpty()) {
            return true;
        }
        List<String> targets = targets(call);
        if (targets.isEmpty()) {
            return false;
        }
        boolean command = ToolNames.RUN_COMMAND.equalsIgnoreCase(call.name());
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
            if (value instanceof String text && !text.isBlank()) {
                values.add(text.strip());
            }
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String text && !text.isBlank()) {
                        values.add(text.strip());
                    }
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
        if (specifier.indexOf('*') >= 0) {
            return glob(specifier).matcher(normalized).matches();
        }
        return normalized.equals(specifier.strip().replace('\\', '/'));
    }

    /** 路径规则：{@code *} 不跨目录，{@code **} 跨目录，分隔符统一按 {@code /} 比较。 */
    public static boolean globMatches(String specifier, String target) {
        String normalized = target.strip().replace('\\', '/');
        String pattern = specifier.strip().replace('\\', '/');
        while (pattern.startsWith("./")) {
            pattern = pattern.substring(2);
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
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
