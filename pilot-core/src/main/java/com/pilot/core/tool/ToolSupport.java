package com.pilot.core.tool;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.pilot.core.message.ToolCall;
import com.pilot.core.workspace.PathValidator;

/** 工具实现的公共小工具：参数取值与参数签名。 */
public final class ToolSupport {
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

    /** 取整数参数并裁剪到 {@code [min, max]}；缺失或无法解析时返回 {@code fallback}。 */
    public static int clampedIntArg(Map<String, Object> args, String name, int fallback, int min, int max) {
        Object raw = args == null ? null : args.get(name);
        if (raw == null) {
            return fallback;
        }
        long parsed;
        if (raw instanceof Number number) {
            parsed = number.longValue();
        } else {
            try {
                parsed = Long.parseLong(raw.toString().strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return (int)Math.max(min, Math.min(max, parsed));
    }

    /** 取布尔参数；支持布尔值与 {@code "true"}/{@code "false"} 字符串，缺失或无法识别时返回 {@code fallback}。 */
    public static boolean boolArg(Map<String, Object> args, String name, boolean fallback) {
        Object raw = args == null ? null : args.get(name);
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text) {
            String stripped = text.strip();
            if ("true".equalsIgnoreCase(stripped)) {
                return true;
            }
            if ("false".equalsIgnoreCase(stripped)) {
                return false;
            }
        }
        return fallback;
    }

    /** 解析单文件工具用于记录改动的工作区路径。 */
    public static String changedWorkspacePath(PathValidator paths, ToolCall call) {
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

    /** 保留现有参数签名格式，只统一对象键的顺序。 */
    public static String argumentSignature(Object value, String nullValue) {
        return argumentSignature(value, item -> Objects.toString(item, nullValue));
    }

    static String argumentSignature(Object value, Function<Object, String> scalar) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .map(entry -> entry.getKey() + "=" + argumentSignature(entry.getValue(), scalar))
                .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> argumentSignature(item, scalar))
                .collect(Collectors.joining(",", "[", "]"));
        }
        return scalar.apply(value);
    }

}
