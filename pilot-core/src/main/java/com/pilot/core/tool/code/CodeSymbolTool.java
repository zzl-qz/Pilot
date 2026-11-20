package com.pilot.core.tool.code;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import com.pilot.core.tool.Availability;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.workspace.PathValidator;

/** 文件结构大纲工具：列出类/接口/方法/函数与精确行号，支持与其它只读工具并行执行。 */
public final class CodeSymbolTool implements Tool.ParallelReadOnly {
    public static final String NAME = "code_symbol";

    private final Supplier<Boolean> ready;
    private final Function<String, String> outline;

    /** 纯本地跨平台构造器：不依赖本机外部编译器，直接经 {@link PathValidator} 安全提取文件结构大纲。 */
    public CodeSymbolTool(PathValidator paths) {
        FileOutline extractor = new FileOutline(Objects.requireNonNull(paths, "paths must not be null"));
        this.ready = () -> true;
        this.outline = extractor::outline;
    }

    /** 兼容构造器：供外部自定义可用性与提取逻辑（如单元测试）。 */
    public CodeSymbolTool(Supplier<Boolean> ready, Function<String, String> outline) {
        this.ready = ready == null ? () -> false : ready;
        this.outline = outline == null ? path -> "" : outline;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查看文件的结构大纲（类、接口、方法、函数与精确行号）。读大文件前先用它快速定位目标方法行号，再按行段 read_file，可与其它只读工具并行调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(Map.of("path", ToolSchemas.string("工作区内的相对文件路径")), List.of("path"));
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
        String path = args == null || args.get("path") == null ? "" : String.valueOf(args.get("path"));
        String text = outline.apply(path);
        return text == null || text.isBlank() ? "ERROR: 没有找到符号：" + path : text;
    }
}
