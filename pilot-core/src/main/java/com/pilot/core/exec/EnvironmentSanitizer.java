package com.pilot.core.exec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 子进程环境变量过滤。
 *
 * <p>模型跑的命令不需要模型密钥、云凭据和会话令牌。默认继承宿主环境会让一次
 * {@code env} / {@code printenv} 就把凭据打印进日志，再随工具结果进入模型上下文。
 * 这里做两层限制：先剔除名字像凭据的变量，再只放行名字在允许列表里的变量。
 */
public final class EnvironmentSanitizer {
    /** 名字命中这些片段的变量一律不传给子进程。 */
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(key|token|secret|password|passwd|credential|session|cookie|auth|private|"
                    + "connection_string|dsn).*");
    /** 无凭据也必需的基础变量。 */
    private static final Set<String> BASE_ALLOWED = Set.of(
            "PATH", "HOME", "USERPROFILE", "TEMP", "TMP", "TMPDIR", "SYSTEMROOT", "WINDIR",
            "COMSPEC", "PATHEXT", "LANG", "LC_ALL", "TZ", "JAVA_HOME", "MAVEN_HOME", "GRADLE_HOME",
            "CI", "GITHUB_ACTIONS", "GITHUB_WORKSPACE", "BUILD_NUMBER", "NODE_ENV",
            "NODE_HOME", "NPM_CONFIG_CACHE", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
            "http_proxy", "https_proxy", "no_proxy", "PYTHONIOENCODING", "PILOT_SAFE_ENV");

    private final Set<String> extraAllowed;

    public EnvironmentSanitizer(Set<String> extraAllowed) {
        this.extraAllowed = extraAllowed == null ? Set.of() : Set.copyOf(extraAllowed);
    }

    public static EnvironmentSanitizer defaults() {
        return new EnvironmentSanitizer(Set.of());
    }

    /** 从宿主环境挑选可以继承的部分。 */
    public Map<String, String> inherit(Map<String, String> host) {
        Map<String, String> result = new LinkedHashMap<>();
        if (host == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : host.entrySet()) {
            String name = entry.getKey();
            if (name == null || entry.getValue() == null) {
                continue;
            }
            if (allowed(name)) {
                result.put(name, entry.getValue());
            }
        }
        return result;
    }

    /** 调用方显式追加的变量：同样走凭据过滤，避免「显式传入」变成绕过。 */
    public Map<String, String> merge(Map<String, String> host, Map<String, String> extra) {
        Map<String, String> result = new LinkedHashMap<>(inherit(host));
        if (extra != null) {
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if (!allowed(entry.getKey())) {
                    continue;
                }
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public boolean allowed(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (SECRET_NAME.matcher(upper).matches()) {
            return false;
        }
        if (BASE_ALLOWED.contains(upper) || BASE_ALLOWED.contains(name)) {
            return true;
        }
        for (String allowed : extraAllowed) {
            if (allowed.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 用于诊断：哪些变量被剔除了（只给名字，不给值）。 */
    public List<String> rejectedNames(Map<String, String> host) {
        if (host == null) {
            return List.of();
        }
        return host.keySet().stream().filter(name -> !allowed(name)).sorted().toList();
    }
}
