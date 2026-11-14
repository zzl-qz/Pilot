package com.pilot.core.tool;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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
