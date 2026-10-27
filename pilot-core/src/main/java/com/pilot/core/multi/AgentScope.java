package com.pilot.core.multi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
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
 */
public final class AgentScope {
    private final List<String> allowFiles;
    private final List<String> denyFiles;
    private final Set<String> allowTools;
    private final Set<String> denyTools;
    private final boolean writable;

    private AgentScope(Collection<String> allowFiles, Collection<String> denyFiles,
                       Collection<String> allowTools, Collection<String> denyTools, boolean writable) {
        this.allowFiles = clean(allowFiles);
        this.denyFiles = clean(denyFiles);
        this.allowTools = cleanTools(allowTools);
        this.denyTools = cleanTools(denyTools);
        this.writable = writable;
    }

    /** 只读审计视图：能读的东西有限，但一律不许写。 */
    public static AgentScope readOnly(Collection<String> allowFiles, Collection<String> denyFiles) {
        return new AgentScope(allowFiles, denyFiles, List.of(), List.of(), false);
    }

    /** 可提交沙箱改动的视图；写入只落到沙箱，不碰真实工作区。 */
    public static AgentScope readWrite(Collection<String> allowFiles, Collection<String> denyFiles) {
        return new AgentScope(allowFiles, denyFiles, List.of(), List.of(), true);
    }

    public AgentScope withTools(Collection<String> allowTools, Collection<String> denyTools) {
        return new AgentScope(allowFiles, denyFiles, allowTools, denyTools, writable);
    }

    public AgentScope withWritable(boolean writable) {
        return new AgentScope(allowFiles, denyFiles, allowTools, denyTools, writable);
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
            String literal = literalPrefix(glob);
            if (!literal.isEmpty() && (literal.equals(path) || literal.startsWith(path + "/"))) {
                return true;
            }
        }
        return false;
    }

    /** 通配符之前的字面前缀，例如 {@code pilot-core/src/**}{@code /*.java} → {@code pilot-core/src}。 */
    private static String literalPrefix(String glob) {
        int wildcard = glob.length();
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '*' || ch == '?') {
                wildcard = i;
                break;
            }
        }
        String prefix = glob.substring(0, wildcard);
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    /** 工具是否在白名单内。允许清单为空表示不额外限制，禁止清单依然生效。 */
    public boolean allowsTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String name = toolName.strip();
        if (denyTools.contains(name)) {
            return false;
        }
        return allowTools.isEmpty() || allowTools.contains(name);
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
        out.append(writable
                ? "\n- 改动权限：可以在白名单内提交改动，改动只进沙箱，不会直接写入真实工作区"
                : "\n- 改动权限：只读，不提交任何文件改动");
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

    private static boolean matchesAny(List<String> globs, String path) {
        for (String glob : globs) {
            if (matches(glob, path)) {
                return true;
            }
        }
        return false;
    }

    static boolean matches(String rawGlob, String rawPath) {
        String glob = normalizePath(rawGlob);
        String path = normalizePath(rawPath);
        boolean dirOnly = rawGlob != null && rawGlob.strip().replace('\\', '/').endsWith("/");
        if (glob.isEmpty()) {
            return false;
        }
        if (!glob.contains("*") && !glob.contains("?")) {
            if (dirOnly) {
                return path.equals(glob) || path.startsWith(glob + "/");
            }
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

    private static List<String> clean(Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        var seen = new LinkedHashSet<String>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            seen.add(normalizePath(value));
        }
        return List.copyOf(seen);
    }

    private static Set<String> cleanTools(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        var seen = new LinkedHashSet<String>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            seen.add(value.strip().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(seen);
    }

    @Override
    public String toString() {
        return "AgentScope{allow=" + allowFiles + ", deny=" + denyFiles
                + ", tools=" + allowTools + ", writable=" + writable + '}';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AgentScope scope)) {
            return false;
        }
        return writable == scope.writable
                && allowFiles.equals(scope.allowFiles)
                && denyFiles.equals(scope.denyFiles)
                && allowTools.equals(scope.allowTools)
                && denyTools.equals(scope.denyTools);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowFiles, denyFiles, allowTools, denyTools, writable);
    }

    /** 用于拼错误信息：把白名单说清楚，模型才知道下一步怎么绕。 */
    public String denialHint(String path) {
        List<String> parts = new ArrayList<>();
        if (!allowFiles.isEmpty()) {
            parts.add("允许范围 " + allowFiles);
        }
        if (!denyFiles.isEmpty()) {
            parts.add("禁止范围 " + denyFiles);
        }
        return "路径 " + path + " 不在你的文件白名单内（" + String.join("；", parts) + "）";
    }
}
