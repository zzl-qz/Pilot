package com.pilot.core.tool;

import java.util.List;

/**
 * 工具的结构化执行结果。
 *
 * <p>{@link #text()} 仍是回传给模型的正文；额外的改动路径和检查项由执行循环直接汇总，
 * 不依赖模型重新解析工具正文。普通工具无需关心这个类型，继续实现 {@link Tool#execute} 即可。
 */
public record ToolResult(String text, List<String> changedPaths, List<String> checks) {
    public ToolResult {
        text = text == null ? "" : text;
        changedPaths = normalized(changedPaths);
        checks = normalized(checks);
    }

    public static ToolResult text(String text) {
        return new ToolResult(text, List.of(), List.of());
    }

    private static List<String> normalized(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }
}
