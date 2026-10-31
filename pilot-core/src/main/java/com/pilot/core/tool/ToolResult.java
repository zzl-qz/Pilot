package com.pilot.core.tool;

import com.pilot.core.loop.Attachment;

import java.util.List;

/**
 * 工具的结构化执行结果。
 *
 * <p>{@link #text()} 仍是回传给模型的正文；额外的改动路径和检查项由执行循环直接汇总，
 * 不依赖模型重新解析工具正文。{@link #images()} 是工具产出的图片（截图、读到的图片文件），
 * 由执行循环在本批工具结果之后作为一条带图片的消息交给模型，模型客户端发送时再编码。
 * 普通工具无需关心这个类型，继续实现 {@link Tool#execute} 即可。
 */
public record ToolResult(String text, List<String> changedPaths, List<String> checks, List<Attachment> images) {
    public ToolResult {
        text = text == null ? "" : text;
        changedPaths = normalized(changedPaths);
        checks = normalized(checks);
        images = images == null ? List.of() : images.stream()
                .filter(image -> image != null && image.image())
                .distinct()
                .toList();
    }

    public ToolResult(String text, List<String> changedPaths, List<String> checks) {
        this(text, changedPaths, checks, List.of());
    }

    public static ToolResult text(String text) {
        return new ToolResult(text, List.of(), List.of(), List.of());
    }

    /** 带图片的结果；失败文本（ERROR 开头）不应携带图片。 */
    public static ToolResult withImages(String text, List<Attachment> images) {
        return new ToolResult(text, List.of(), List.of(), images);
    }

    private static List<String> normalized(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }
}
