package com.pilot.core.workspace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * 子 Agent 的权限视图：能看哪些文件、能调哪些工具、能不能提改动。
 *
 * <p>这是「硬隔离」的输入，不是提示词：后端在 {@code PathValidator} 与工具网关两侧校验，
 * 白名单之外的文件在子 Agent 眼里根本不存在（拦截优先于提示）。
 *
 * <p>通配符语义与 gitignore 接近：
 * <ul>
 *   <li>{@code *} 匹配单个路径段内的任意字符，{@code ?} 匹配单个字符；</li>
 *   <li>{@code **} 跨目录匹配，{@code **}{@code /} 可以匹配零层目录；</li>
 *   <li>无通配符的写法既匹配该文件，也匹配以它开头的子路径（当目录用）。</li>
 * </ul>
 * 禁止清单永远优先于允许清单；允许清单为空表示「整个工作区可见」。
 *
 * <p>命令白名单按「参数前缀」匹配：{@code mvn test} 允许 {@code mvn test -q}，但不允许
 * {@code mvn package}；{@code *} 匹配单个参数，{@code **} 匹配任意后续参数。
 * 工具名同样支持 {@code *} 通配符，例如 {@code read_*}。
 */
public final class AgentScope {
    private final List<String> allowFiles;
    private final List<String> denyFiles;
    private final Set<String> allowTools;
    private final Set<String> denyTools;
    private final List<String> allowCommands;
    private final List<String> denyCommands;
    private final boolean writable;

    private AgentScope(Collection<String> allowFiles, Collection<String> denyFiles, Collection<String> allowTools,
        Collection<String> denyTools, Collection<String> allowCommands, Collection<String> denyCommands,
        boolean writable) {
        this.allowFiles = clean(allowFiles, AgentScope::normalizePath);
        this.denyFiles = clean(denyFiles, AgentScope::normalizePath);
        this.allowTools = Set.copyOf(clean(allowTools, value -> value.strip().toLowerCase(Locale.ROOT)));
        this.denyTools = Set.copyOf(clean(denyTools, value -> value.strip().toLowerCase(Locale.ROOT)));
        this.allowCommands = clean(allowCommands, value -> String.join(" ", commandTokens(value)));
        this.denyCommands = clean(denyCommands, value -> String.join(" ", commandTokens(value)));
        this.writable = writable;
    }

    /** 只读审计视图：能读的东西有限，但一律不许写。 */
    public static AgentScope readOnly(Collection<String> allowFiles, Collection<String> denyFiles) {
        return new AgentScope(allowFiles, denyFiles, List.of(), List.of(), List.of(), List.of(), false);
    }

    /** 可提交沙箱改动的视图；写入只落到沙箱，不碰真实工作区。 */
    public static AgentScope readWrite(Collection<String> allowFiles, Collection<String> denyFiles) {
        return new AgentScope(allowFiles, denyFiles, List.of(), List.of(), List.of(), List.of(), true);
    }

    public AgentScope withTools(Collection<String> allowTools, Collection<String> denyTools) {
        return new AgentScope(allowFiles, denyFiles, allowTools, denyTools, allowCommands, denyCommands, writable);
    }

    /** 命令白名单：空允许清单表示沿用 {@code run_command} 的内置可执行白名单。 */
    public AgentScope withCommands(Collection<String> allowCommands, Collection<String> denyCommands) {
        return new AgentScope(allowFiles, denyFiles, allowTools, denyTools, allowCommands, denyCommands, writable);
    }

    public AgentScope withWritable(boolean writable) {
        return new AgentScope(allowFiles, denyFiles, allowTools, denyTools, allowCommands, denyCommands, writable);
    }

    public List<String> allowFiles() {
        return allowFiles;
    }

    public List<String> denyFiles() {
        return denyFiles;
    }

    public Set<String> allowTools() {
        return allowTools;
    }

    public Set<String> denyTools() {
        return denyTools;
    }

    public List<String> allowCommands() {
        return allowCommands;
    }

    public List<String> denyCommands() {
        return denyCommands;
    }

    public boolean writable() {
        return writable;
    }

    /** 路径是否在视图内；{@code relativePath} 使用工作区相对的 {@code /} 分隔形式。 */
    public boolean allowsPath(String relativePath) {
        String path = normalizePath(relativePath);
        if (path.isEmpty()) {
            return false;
        }
        if (matchesAny(denyFiles, path)) {
            return false;
        }
        return allowFiles.isEmpty() || matchesAny(allowFiles, path);
    }

    /**
     * 目录是否可见：目录本身可能没被白名单直接覆盖，但里面有白名单文件时仍然要能列出来。
     *
     * <p>列出目录只是「看得见目录名」，具体文件仍由 {@link #allowsPath} 逐条过滤。
     */
    public boolean allowsDirectory(String relativePath) {
        String path = normalizePath(relativePath);
        if (path.isEmpty()) {
            return true;
        }
        if (matchesAny(denyFiles, path)) {
            return false;
        }
        for (String glob : denyFiles) {
            if (glob.endsWith("/**") && path.equals(glob.substring(0, glob.length() - 3))) {
                return false;
            }
        }
        if (allowFiles.isEmpty() || allowsPath(path)) {
            return true;
        }
        for (String glob : allowFiles) {
            if (glob.startsWith("**")) {
                return true;
            }
            String literal = literalPrefix(glob, false);
            if (!literal.isEmpty() && (literal.equals(path) || literal.startsWith(path + "/"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 通配符之前的字面前缀，例如 {@code pilot-core/src/**}{@code /*.java} → {@code pilot-core/src}。
     *
     * @param includeCharClasses 为 {@code true} 时同时在 {@code {}} / {@code []} 处截断
     */
    public static String literalPrefix(String glob, boolean includeCharClasses) {
        String text = glob == null ? "" : glob.replace('\\', '/').strip();
        int wildcard = text.length();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '*' || ch == '?' || (includeCharClasses && (ch == '{' || ch == '['))) {
                wildcard = i;
                break;
            }
        }
        String prefix = text.substring(0, wildcard);
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    /** 工具是否在白名单内。允许清单为空表示不额外限制，禁止清单依然生效；支持 {@code *} 通配符。 */
    public boolean allowsTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String name = toolName.strip().toLowerCase(Locale.ROOT);
        if (matchesAny(denyTools, name)) {
            return false;
        }
        return allowTools.isEmpty() || matchesAny(allowTools, name);
    }

    /**
     * 命令是否在视图内：按参数前缀匹配，{@code *} 匹配单个参数，{@code **} 匹配任意后续参数。
     *
     * <p>允许清单为空表示不额外限制（仍受 {@code run_command} 内置可执行白名单约束），
     * 禁止清单永远优先。
     */
    public boolean allowsCommand(String command) {
        return allowsCommandArgs(commandTokens(command));
    }

    /** 使用已经分词好的参数判断，避免引号内的空格被重新拆分。 */
    public boolean allowsCommandArgs(Collection<String> args) {
        if (args == null || args.isEmpty()) {
            return false;
        }
        List<String> tokens = new ArrayList<>();
        for (String arg : args) {
            if (arg != null && !arg.isBlank()) {
                tokens.add(arg.strip().toLowerCase(Locale.ROOT));
            }
        }
        if (tokens.isEmpty()) {
            return false;
        }
        if (denyCommands.stream().anyMatch(pattern -> commandMatches(pattern, tokens))) {
            return false;
        }
        return allowCommands.isEmpty() || allowCommands.stream().anyMatch(pattern -> commandMatches(pattern, tokens));
    }

    /** 给子 Agent 看的权限说明，写进系统提示词；真正的拦截在后端。 */
    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append(allowFiles.isEmpty() ? "- 可读文件：整个工作区" : "- 可读文件：\n" + bullets(allowFiles));
        if (!denyFiles.isEmpty()) {
            out.append("\n- 禁止访问：\n").append(bullets(denyFiles));
        }
        if (!allowTools.isEmpty()) {
            out.append("\n- 可用工具：").append(String.join(" / ", allowTools));
        }
        if (!denyTools.isEmpty()) {
            out.append("\n- 禁用工具：").append(String.join(" / ", denyTools));
        }
        if (!allowCommands.isEmpty()) {
            out.append("\n- 可用命令（按参数前缀匹配）：").append(String.join(" / ", allowCommands));
        }
        if (!denyCommands.isEmpty()) {
            out.append("\n- 禁用命令：").append(String.join(" / ", denyCommands));
        }
        out.append(writable ? "\n- 改动权限：可以在白名单内提交改动，改动只进沙箱，不会直接写入真实工作区" : "\n- 改动权限：只读，不提交任何文件改动");
        return out.toString();
    }

    private static String bullets(Collection<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append("  - ").append(value);
        }
        return out.toString();
    }

    /** 规范化成工作区相对路径：统一 {@code /}、去掉 {@code ./} 前缀与首尾斜杠。 */
    static String normalizePath(String raw) {
        if (raw == null) {
            return "";
        }
        String path = raw.replace('\\', '/').strip();
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** 白名单项是命令的前缀；{@code *} 占一个参数，{@code **} 吞掉剩余参数。 */
    static boolean commandMatches(String pattern, List<String> tokens) {
        List<String> expected = commandTokens(pattern);
        if (expected.isEmpty()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            String part = expected.get(i);
            if ("**".equals(part)) {
                return true;
            }
            if (i >= tokens.size()) {
                return false;
            }
            if (!"*".equals(part) && !part.equals(tokens.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> commandTokens(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        return List.of(command.strip().toLowerCase(Locale.ROOT).split("\\s+"));
    }

    private static boolean matchesAny(Collection<String> globs, String target) {
        return globs.stream().anyMatch(glob -> matches(glob, target));
    }

    static boolean matches(String rawGlob, String rawPath) {
        String glob = normalizePath(rawGlob);
        String path = normalizePath(rawPath);
        if (glob.isEmpty()) {
            return false;
        }
        if (!glob.contains("*") && !glob.contains("?")) {
            return path.equals(glob) || path.startsWith(glob + "/");
        }
        return compile(glob).matcher(path).matches();
    }

    private static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder("^");
        int index = 0;
        while (index < glob.length()) {
            char current = glob.charAt(index);
            if (current == '*') {
                boolean doublestar = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
                if (doublestar) {
                    index += 2;
                    if (index < glob.length() && glob.charAt(index) == '/') {
                        // "**/" 允许零层目录：a/**/b 同时匹配 a/b
                        regex.append("(?:.*/)?");
                        index++;
                    } else {
                        regex.append(".*");
                    }
                    continue;
                }
                regex.append("[^/]*");
            } else if (current == '?') {
                regex.append("[^/]");
            } else {
                if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
                    regex.append('\\');
                }
                regex.append(current);
            }
            index++;
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private static List<String> clean(Collection<String> values, UnaryOperator<String> normalizer) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = normalizer.apply(value);
            if (!normalized.isEmpty()) {
                seen.add(normalized);
            }
        }
        return List.copyOf(seen);
    }

    @Override
    public String toString() {
        return "AgentScope{allow=" + allowFiles + ", deny=" + denyFiles + ", tools=" + allowTools + ", commands="
            + allowCommands + ", writable=" + writable + '}';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AgentScope scope)) {
            return false;
        }
        return writable == scope.writable && allowFiles.equals(scope.allowFiles) && denyFiles.equals(scope.denyFiles)
            && allowTools.equals(scope.allowTools) && denyTools.equals(scope.denyTools)
            && allowCommands.equals(scope.allowCommands) && denyCommands.equals(scope.denyCommands);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowFiles, denyFiles, allowTools, denyTools, allowCommands, denyCommands, writable);
    }

    /** 用于拼错误信息：把白名单说清楚，模型才知道下一步怎么绕。 */
    public String denialHint(String path) {
        return rangeHint("路径 " + path, "文件白名单", "允许范围 ", allowFiles, "禁止范围 ", denyFiles);
    }

    /** 命令越界时的错误信息。 */
    public String commandHint(String command) {
        return rangeHint("命令 " + command, "命令白名单", "允许 ", allowCommands, "禁止 ", denyCommands);
    }

    private static String rangeHint(String subject, String scopeLabel, String allowLabel, List<String> allow,
        String denyLabel, List<String> deny) {
        List<String> parts = new ArrayList<>(2);
        if (!allow.isEmpty()) {
            parts.add(allowLabel + allow);
        }
        if (!deny.isEmpty()) {
            parts.add(denyLabel + deny);
        }
        return subject + " 不在你的" + scopeLabel + "内（" + String.join("；", parts) + "）";
    }
}
