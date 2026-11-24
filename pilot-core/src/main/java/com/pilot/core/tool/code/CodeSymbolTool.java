package com.pilot.core.tool.code;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import com.pilot.core.tool.Availability;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.workspace.PathValidator;

/** 文件结构大纲与跨文件符号定义搜索工具：支持查看单文件大纲或按名称/类型跨文件定位定义行号。 */
public final class CodeSymbolTool implements Tool.ParallelReadOnly {
    public static final String NAME = "code_symbol";

    private final Supplier<Boolean> ready;
    private final Function<String, String> outline;
    private final FileOutline extractor;

    /** 纯本地跨平台构造器：不依赖本机外部编译器，直接经 {@link PathValidator} 安全提取大纲与跨文件搜索定义。 */
    public CodeSymbolTool(PathValidator paths) {
        FileOutline local = new FileOutline(Objects.requireNonNull(paths, "paths must not be null"));
        this.ready = () -> true;
        this.outline = local::outline;
        this.extractor = local;
    }

    /** 兼容构造器：供外部自定义可用性与提取逻辑（如单元测试）。 */
    public CodeSymbolTool(Supplier<Boolean> ready, Function<String, String> outline) {
        this.ready = ready == null ? () -> false : ready;
        this.outline = outline == null ? path -> "" : outline;
        this.extractor = null;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查看单文件结构大纲（传 path）或跨文件搜索类/接口/方法/函数定义（传 query，可配 path 目录、glob、kind 过滤）。"
            + "读大文件或找符号定义时先用它定位精确行号，可与其它只读工具并行调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", ToolSchemas.string("相对路径：单文件时输出结构大纲；与 query 同传时限定搜索目录或文件"));
        props.put("query", ToolSchemas.string("可选，跨文件搜索符号定义名（支持精确、前缀与子串匹配）"));
        props.put("glob", ToolSchemas.string("可选，与 query 配合过滤文件，如 *.java 或 **/*.tsx"));
        props.put("kind", ToolSchemas.string("可选，与 query 配合按类型过滤：class / interface / method / function / type"));
        return ToolSchemas.object(props, List.of());
    }

    @Override
    public Availability availability() {
        return Boolean.TRUE.equals(ready.get()) ? Availability.ready() : Availability.unavailable("没有可用的符号大纲提取器");
    }

    @Override
    public String execute(Map<String, Object> args) {
        if (!Boolean.TRUE.equals(ready.get())) {
            return "ERROR: 没有可用的符号大纲提取器";
        }
        String path = readString(args, "path");
        String query = readString(args, "query");
        if (!query.isEmpty()) {
            if (extractor == null) {
                return "ERROR: 当前符号提取器不支持跨文件 query 搜索，请传入 path";
            }
            return extractor.search(query, path, readString(args, "glob"), readString(args, "kind"));
        }
        if (path.isEmpty()) {
            return "ERROR: 请传入 path（查看文件大纲）或 query（跨文件搜索符号定义）";
        }
        String text = outline.apply(path);
        return text == null || text.isBlank() ? "ERROR: 没有找到符号：" + path : text;
    }

    private static String readString(Map<String, Object> args, String key) {
        if (args == null || args.get(key) == null) {
            return "";
        }
        return String.valueOf(args.get(key)).strip();
    }
}
