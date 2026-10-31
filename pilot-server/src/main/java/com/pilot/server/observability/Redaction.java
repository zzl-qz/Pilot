package com.pilot.server.observability;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 诊断输出脱敏。
 *
 * <p>诊断视图会把命令、错误消息、工具参数拼成人能看的文本，其中可能夹带密钥。
 * 这里只处理最常见的几种形状：Bearer 令牌、{@code api_key=...} 这类键值、以及 sk- 前缀密钥。
 * 它不是完整的 DLP，真正的原则仍然是「不要把密钥放进会被记录的位置」。
 */
public final class Redaction {
    private static final String MASK = "***";
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{6,}");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|passwd|authorization)"
                    + "\\s*[=:]\\s*)(\"?)([^\\s\"',;]{4,})");
    private static final Pattern KNOWN_PREFIX = Pattern.compile("\\b(sk-[A-Za-z0-9_-]{8,}|ghp_[A-Za-z0-9]{8,})");
    private static final int MAX_LENGTH = 2_000;

    private Redaction() {
    }

    public static String text(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String result = BEARER.matcher(value).replaceAll("$1" + MASK);
        Matcher keyValue = KEY_VALUE.matcher(result);
        result = keyValue.replaceAll("$1$2" + MASK);
        result = KNOWN_PREFIX.matcher(result).replaceAll(MASK);
        return result.length() <= MAX_LENGTH ? result : result.substring(0, MAX_LENGTH) + "…";
    }
}
