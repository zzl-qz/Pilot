package com.pilot.core.tool;

import java.util.List;
import java.util.Map;

/**
 * 拼工具参数说明（JSON Schema）的小工具。
 * 每个工具都要告诉模型自己收哪些参数，最常见的就是下面这几种写法，
 * 统一放这里，省得到处手写 Map.of("type", "string", "description", ...)。
 * 带枚举、取值范围、数组元素的少数写法仍然直接用 Map.of。
 */
public final class ToolSchemas {
    private ToolSchemas() {
    }

    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    /** 一个参数对象：列出每个参数、哪些必填，并且不接受没列出的参数。 */
    public static Map<String, Object> object(Map<String, ?> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }
}
