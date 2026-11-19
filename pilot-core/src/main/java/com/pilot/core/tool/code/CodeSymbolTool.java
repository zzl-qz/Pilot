package com.pilot.core.tool.code;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import com.pilot.core.tool.Availability;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;

/** 文件大纲。没有语言检查器时不向模型声明。 */
public final class CodeSymbolTool implements Tool {
    public static final String NAME = "code_symbol";

    private final Supplier<Boolean> ready;
    private final Function<String, String> outline;

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
        return "查看文件大纲。每行格式为 文件:行号: 符号。没有语言检查器时不要调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(Map.of("path", ToolSchemas.string("相对路径")), List.of("path"));
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Availability availability() {
        return Boolean.TRUE.equals(ready.get()) ? Availability.ready() : Availability.unavailable("没有可用的语言检查器");
    }

    @Override
    public String execute(Map<String, Object> args) {
        if (!Boolean.TRUE.equals(ready.get())) {
            return "ERROR: 没有可用的语言检查器";
        }
        String path = args == null || args.get("path") == null ? "" : String.valueOf(args.get("path"));
        String text = outline.apply(path);
        return text == null || text.isBlank() ? "ERROR: 没有找到符号：" + path : text;
    }
}
