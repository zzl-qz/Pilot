package com.pilot.core.tool;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.pilot.core.workspace.PathValidator;

/** 工具实现的公共小工具：参数取值、二进制判断、摘要截断。 */
public final class ToolSupport {
    private static final int BINARY_SAMPLE_BYTES = 8000;

    private ToolSupport() {
    }

    /** 取字符串参数，空白视为未提供。 */
    public static String stringArg(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }

    /** 解析单文件工具用于记录改动的工作区路径。 */
    static String changedWorkspacePath(PathValidator paths, ToolCall call) {
        String rawPath = ToolSupport.stringArg(call == null ? null : call.args(), "path");
        if (rawPath == null) {
            return null;
        }
        try {
            return paths.display(paths.resolve(rawPath));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 采样前 8KB 是否含 NUL 字节，作为二进制文件的粗略判断。 */
    public static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_SAMPLE_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** 保留现有参数签名格式，只统一对象键的顺序。 */
    public static String argumentSignature(Object value, String nullValue) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .map(entry -> entry.getKey() + "=" + argumentSignature(entry.getValue(), nullValue))
                .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> argumentSignature(item, nullValue))
                .collect(Collectors.joining(",", "[", "]"));
        }
        return Objects.toString(value, nullValue);
    }

    public static String oneLine(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= maxChars ? single : single.substring(0, maxChars) + "…";
    }
}
