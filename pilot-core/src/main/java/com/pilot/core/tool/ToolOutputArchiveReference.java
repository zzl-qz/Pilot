package com.pilot.core.tool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 工具输出归档引用的格式与识别规则。 */
public final class ToolOutputArchiveReference {
    public static final String UNARCHIVED_MARKER = "[工具输出未归档，禁止清理原文]";

    private static final Pattern TRAILING_REFERENCE = Pattern
        .compile("(\\[工具输出存档 run_id=[A-Za-z0-9][A-Za-z0-9._-]* call_id=[A-Za-z0-9][A-Za-z0-9._-]*[^\\n\\]]*\\])$");

    private ToolOutputArchiveReference() {
    }

    public static boolean isUnarchived(String text) {
        return text != null && text.stripTrailing().endsWith(UNARCHIVED_MARKER);
    }

    /** 只接受末尾的真实引用，正文提到的格式示例不算归档引用。 */
    public static String trailingReference(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = TRAILING_REFERENCE.matcher(text.stripTrailing());
        return matcher.find() ? matcher.group(1) : null;
    }
}
