package com.pilot.server.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import com.pilot.core.util.Texts;

/**
 * 验证策略即代码：从工作区 {@code pilot.policy.yaml} 读取“哪些路径必须跑哪些检查”。
 *
 * <p>这是完成门的服务端输入，不是提示词：模型说完成时，任务状态会按这里的规则检查；
 * 缺检查就落到 {@link com.pilot.server.run.TaskControlStatus#VERIFICATION_FAILED}，
 * 不能靠自然语言解释绕过。
 *
 * <p>策略文件不存在时视为“未配置”，不改变现有行为；文件存在但无法解析时 fail-closed，
 * 明确报错比静默放行更安全。
 */
@Service
public class VerificationPolicyService {
    static final String ROOT_POLICY = "pilot.policy.yaml";
    static final String HIDDEN_POLICY = ".pilot/policy.yaml";
    private static final long MAX_POLICY_BYTES = 256 * 1024;
    private static final Pattern EXIT_ZERO = Pattern.compile("^(.*?)\\s*(?:→|->)\\s*exit\\s+0\\s*$", Pattern.DOTALL);

    /**
     * @param configured  是否读到策略文件
     * @param source      策略文件的工作区相对路径
     * @param requirement all / any
     * @param required    命中的必跑命令（去重，保留策略顺序）
     * @param matchedFiles 触发规则的改动文件
     * @param satisfied   已有退出码 0 记录覆盖的命令
     * @param missing     缺失的命令
     * @param error       解析/校验错误；非空时 {@link #satisfied()} 为 false
     */
    public record PolicyReport(boolean configured, String source, String requirement, boolean allowOverride,
        List<String> required, List<String> matchedFiles, List<String> satisfied, List<String> missing, String error,
        boolean passed) {
        public PolicyReport(boolean configured, String source, String requirement, boolean allowOverride,
            List<String> required, List<String> matchedFiles, List<String> satisfied, List<String> missing,
            String error) {
            this(configured, source, requirement, allowOverride, required, matchedFiles, satisfied, missing, error,
                false);
        }

        public PolicyReport {
            requirement = "any".equals(requirement) ? "any" : "all";
            required = copy(required);
            matchedFiles = copy(matchedFiles);
            satisfied = copy(satisfied);
            missing = copy(missing);
            source = source == null ? "" : source;
            error = error == null ? "" : error;
            passed = !configured || (error.isBlank() && (allowOverride || required.isEmpty()
                || ("any".equals(requirement) ? !satisfied.isEmpty() : missing.isEmpty())));
        }

        private static List<String> copy(List<String> value) {
            return value == null ? List.of() : List.copyOf(value);
        }

        public static PolicyReport notConfigured() {
            return new PolicyReport(false, "", "all", false, List.of(), List.of(), List.of(), List.of(), "");
        }

        public static PolicyReport malformed(String source, String error) {
            return new PolicyReport(true, source, "all", false, List.of(), List.of(), List.of(), List.of(), error);
        }
    }

    private record Rule(String pattern, List<String> commands) {
    }

    private record Parsed(List<Rule> rules, String requirement, boolean allowOverride, String error) {
    }

    /** 读取并执行策略；工作区为空或没有策略文件时返回未配置。 */
    public PolicyReport evaluate(Path workspace, List<String> changedFiles, List<String> checks) {
        Path root = workspace == null ? null : workspace.toAbsolutePath().normalize();
        Path file = policyFile(root);
        if (file == null) {
            return PolicyReport.notConfigured();
        }
        String source = relative(root, file);
        Parsed parsed = parse(file);
        if (!parsed.error().isBlank()) {
            return PolicyReport.malformed(source, parsed.error());
        }
        List<String> files = normalizePaths(changedFiles);
        LinkedHashSet<String> required = new LinkedHashSet<>();
        LinkedHashSet<String> matched = new LinkedHashSet<>();
        for (Rule rule : parsed.rules()) {
            boolean hit = files.stream().anyMatch(path -> globMatches(rule.pattern(), path));
            if (!hit) {
                continue;
            }
            files.stream().filter(path -> globMatches(rule.pattern(), path)).forEach(matched::add);
            required.addAll(rule.commands());
        }
        List<String> passing = passingCommands(checks);
        List<String> satisfied = required.stream().filter(command -> covered(command, passing)).toList();
        List<String> missing = required.stream().filter(command -> !covered(command, passing)).toList();
        return new PolicyReport(true, source, parsed.requirement(), parsed.allowOverride(), List.copyOf(required),
            List.copyOf(matched), satisfied, missing, "");
    }

    private static boolean covered(String required, List<String> passing) {
        String wanted = normalizeCommand(required);
        return passing.stream().anyMatch(command -> {
            String actual = normalizeCommand(command);
            return actual.equals(wanted) || actual.startsWith(wanted + " ");
        });
    }

    private static List<String> passingCommands(List<String> checks) {
        List<String> commands = new ArrayList<>();
        for (String check : checks == null ? List.<String>of() : checks) {
            Matcher matcher = EXIT_ZERO.matcher(check == null ? "" : check.strip());
            if (matcher.matches()) {
                String command = normalizeCommand(matcher.group(1));
                if (!command.isBlank()) {
                    commands.add(command);
                }
            }
        }
        return commands;
    }

    private static String normalizeCommand(String value) {
        return Texts.oneLine(value, Integer.MAX_VALUE);
    }

    private Path policyFile(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return null;
        }
        Path root = workspace.resolve(ROOT_POLICY);
        if (Files.isRegularFile(root)) {
            return root;
        }
        Path hidden = workspace.resolve(HIDDEN_POLICY);
        return Files.isRegularFile(hidden) ? hidden : null;
    }

    private Parsed parse(Path file) {
        String text;
        try {
            if (Files.size(file) > MAX_POLICY_BYTES) {
                return new Parsed(List.of(), "all", false, "策略文件超过 256 KB");
            }
            text = Files.readString(file, StandardCharsets.UTF_8).replace("\uFEFF", "");
        } catch (IOException e) {
            return new Parsed(List.of(), "all", false, "读取策略文件失败：" + e.getMessage());
        }
        Object loaded;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(20);
            options.setNestingDepthLimit(20);
            options.setCodePointLimit((int)MAX_POLICY_BYTES);
            loaded = new Yaml(new SafeConstructor(options)).load(text);
        } catch (YAMLException e) {
            return new Parsed(List.of(), "all", false, "策略 YAML 无效：" + e.getMessage());
        }
        if (loaded == null) {
            return new Parsed(List.of(), "all", false, "");
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            return new Parsed(List.of(), "all", false, "策略顶层必须是字段映射");
        }
        Object checks = root.get("checks");
        List<Rule> rules = new ArrayList<>();
        String error = parseChecks(checks, rules);
        if (!error.isBlank()) {
            return new Parsed(List.of(), "all", false, error);
        }
        String requirement = "all";
        boolean allowOverride = false;
        Object completion = root.get("completion");
        if (completion != null) {
            if (!(completion instanceof Map<?, ?> map)) {
                return new Parsed(List.of(), "all", false, "completion 必须是字段映射");
            }
            Object require = map.get("require");
            if (require != null) {
                String value = String.valueOf(require).strip().toLowerCase(Locale.ROOT);
                if (!"all".equals(value) && !"any".equals(value)) {
                    return new Parsed(List.of(), "all", false, "completion.require 只接受 all / any");
                }
                requirement = value;
            }
            Object override = map.get("allowOverride");
            if (override != null) {
                if (!(override instanceof Boolean)) {
                    return new Parsed(List.of(), "all", false, "completion.allowOverride 必须是布尔值");
                }
                allowOverride = (Boolean)override;
            }
        }
        return new Parsed(List.copyOf(rules), requirement, allowOverride, "");
    }

    private String parseChecks(Object checks, List<Rule> rules) {
        if (checks == null) {
            return "";
        }
        if (checks instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String pattern = String.valueOf(entry.getKey()).strip();
                if (pattern.isEmpty()) {
                    return "checks 的路径模式不能为空";
                }
                List<String> commands = commands(entry.getValue());
                if (commands.isEmpty()) {
                    return "checks." + pattern + " 至少需要一条命令";
                }
                rules.add(new Rule(pattern, commands));
            }
            return "";
        }
        if (checks instanceof List<?> list) {
            List<String> commands = commands(list);
            if (commands.isEmpty()) {
                return "checks 列表不能为空";
            }
            rules.add(new Rule("**", commands));
            return "";
        }
        if (checks instanceof String text) {
            List<String> commands = commands(text);
            return commands.isEmpty() ? "checks 至少需要一条命令" : addRule(rules, "**", commands);
        }
        return "checks 必须是路径到命令的映射，或命令列表";
    }

    private static String addRule(List<Rule> rules, String pattern, List<String> commands) {
        rules.add(new Rule(pattern, commands));
        return "";
    }

    private static List<String> commands(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String text) {
            return text.isBlank() ? List.of() : List.of(text.strip());
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String command = String.valueOf(item).strip();
                if (!command.isEmpty()) {
                    result.add(command);
                }
            }
            return List.copyOf(result);
        }
        return List.of();
    }

    private static List<String> normalizePaths(List<String> files) {
        if (files == null) {
            return List.of();
        }
        return files.stream().filter(file -> file != null && !file.isBlank()).map(file -> {
            String path = file.replace('\\', '/').strip();
            while (path.startsWith("./")) {
                path = path.substring(2);
            }
            return path;
        }).filter(path -> !path.isEmpty()).distinct().toList();
    }

    /** 与 gitignore 接近的 glob：** 跨目录，* 单段，? 单字符；无通配符按目录前缀匹配。 */
    static boolean globMatches(String pattern, String path) {
        if (pattern == null || path == null) {
            return false;
        }
        String glob = pattern.replace('\\', '/').strip();
        String target = path.replace('\\', '/').strip();
        while (glob.startsWith("./")) {
            glob = glob.substring(2);
        }
        while (target.startsWith("./")) {
            target = target.substring(2);
        }
        if (glob.isEmpty()) {
            return false;
        }
        if ("*".equals(glob) || "**".equals(glob)) {
            return true;
        }
        if (glob.indexOf('*') < 0 && glob.indexOf('?') < 0) {
            return target.equals(glob) || target.startsWith(glob.endsWith("/") ? glob : glob + "/");
        }
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char character = glob.charAt(index);
            if (character == '*') {
                if (index + 1 < glob.length() && glob.charAt(index + 1) == '*') {
                    index++;
                    if (index + 1 < glob.length() && glob.charAt(index + 1) == '/') {
                        index++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (character == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(character)));
            }
        }
        regex.append('$');
        return target.matches(regex.toString());
    }

    private static String relative(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return file.toString();
        }
    }
}
