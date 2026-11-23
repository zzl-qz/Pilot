package com.pilot.core.util;

import java.util.regex.Pattern;

/**
 * 统一的凭证与密钥脱敏器，供模型请求落盘、错误摘要、命令输出与诊断导出共享。
 *
 * <p>覆盖常见令牌格式（Bearer/Basic、键值与 JSON 密钥字段、主流服务商前缀、PEM 私钥块、URL 内嵌口令）。
 * 脱敏只是纵深防御的一层，仍应优先避免把密钥写入日志或事件存储。
 */
public final class SecretScrubber {
    private static final String MASK = "***";
    private static final Pattern PEM_PRIVATE_KEY =
        Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----");
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)(\\b(?:bearer|basic)\\s+)[A-Za-z0-9._~+/=-]{6,}");
    private static final Pattern URL_CREDENTIALS =
        Pattern.compile("(?i)\\b((?:https?|socks5?|ftp)://)([^:/@\\s]+):([^/@\\s]{2,})@");
    private static final Pattern JSON_SECRET = Pattern.compile(
        "(?i)(\"(?:api[_-]?key|apikey|x-api-key|x_api_key|access[_-]?token|refresh[_-]?token|secret|client[_-]?secret|"
            + "private[_-]?key|password|passwd|authorization)\"\\s*:\\s*\")([^\"]{4,})(\")");
    private static final Pattern KEY_VALUE = Pattern
        .compile("(?i)((?:api[_-]?key|apikey|x-api-key|x_api_key|access[_-]?token|refresh[_-]?token|token|secret|"
            + "client[_-]?secret|private[_-]?key|password|passwd|authorization)\\s*[=:]\\s*)(\"?)([^\\s\"',;&]{4,})");
    private static final Pattern KNOWN_PREFIX = Pattern
        .compile("(?<![A-Za-z0-9_-])(?:sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9]{8,}|github_pat_[A-Za-z0-9_]{16,}|"
            + "AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|glpat-[A-Za-z0-9_-]{10,}|"
            + "hf_[A-Za-z0-9]{12,})");

    private SecretScrubber() {
    }

    /** 替换文本中的常见密钥与凭据片段；空入参返回空串。 */
    public static String scrub(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String result = PEM_PRIVATE_KEY.matcher(value).replaceAll("[REDACTED_PRIVATE_KEY]");
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1$2:" + MASK + "@");
        result = AUTH_SCHEME.matcher(result).replaceAll("$1" + MASK);
        result = JSON_SECRET.matcher(result).replaceAll("$1" + MASK + "$3");
        result = KEY_VALUE.matcher(result).replaceAll("$1$2" + MASK);
        result = KNOWN_PREFIX.matcher(result).replaceAll(MASK);
        return result;
    }

    /** 文本是否包含明文密钥或认证凭据特征（用于高危外发预检）。 */
    public static boolean containsSecretPattern(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return PEM_PRIVATE_KEY.matcher(value).find() || AUTH_SCHEME.matcher(value).find()
            || URL_CREDENTIALS.matcher(value).find() || KNOWN_PREFIX.matcher(value).find();
    }
}
