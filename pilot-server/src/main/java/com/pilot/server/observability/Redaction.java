package com.pilot.server.observability;

import com.pilot.core.util.SecretScrubber;

/**
 * 诊断输出脱敏。
 *
 * <p>诊断视图会把命令、错误消息、工具参数拼成人能看的文本，其中可能夹带密钥。
 * 规则统一委托给 {@link SecretScrubber}，并做长度截断避免诊断载荷失控。
 */
public final class Redaction {
    private static final int MAX_LENGTH = 2_000;

    private Redaction() {
    }

    public static String text(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String result = SecretScrubber.scrub(value);
        return result.length() <= MAX_LENGTH ? result : result.substring(0, MAX_LENGTH) + "…";
    }
}
