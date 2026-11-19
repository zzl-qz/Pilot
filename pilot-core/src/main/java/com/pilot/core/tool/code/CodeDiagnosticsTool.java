package com.pilot.core.tool.code;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import com.pilot.core.tool.Availability;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;

/** 改完代码后的行级诊断。没有检查器时不向模型声明。 */
public final class CodeDiagnosticsTool implements Tool {
    public static final String NAME = "code_diagnostics";
    private static final int MAX_LINES = 50;

    private final Supplier<String> checker;
    private final Function<String, String> diagnose;

    public CodeDiagnosticsTool(Supplier<String> checker) {
        this(checker, null);
    }

    /** ready 用非空文本表示检查器存在；diagnose 非空时按路径真正执行检查。 */
    public CodeDiagnosticsTool(Supplier<String> ready, Function<String, String> diagnose) {
        this.checker = ready == null ? () -> "" : ready;
        this.diagnose = diagnose;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查看已改文件的行级错误。每行格式为 文件:行号: 说明。没有检查器时不要调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(Map.of("path", ToolSchemas.string("可选，只看这个相对路径")), List.of());
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Availability availability() {
        String text = checker.get();
        return text == null || text.isBlank() ? Availability.unavailable("没有可用的代码检查器") : Availability.ready();
    }

    @Override
    public String execute(Map<String, Object> args) {
        String path = args == null || args.get("path") == null ? "" : String.valueOf(args.get("path"));
        String text = diagnose == null ? checker.get() : diagnose.apply(path);
        if (text == null || text.isBlank()) {
            return "ERROR: 没有可用的代码检查器";
        }
        List<String> lines = text.lines().filter(line -> line.contains(":")).limit(MAX_LINES + 1).toList();
        boolean more = lines.size() > MAX_LINES;
        String body = String.join("\n", more ? lines.subList(0, MAX_LINES) : lines);
        return more ? body + "\n… 还有更多诊断，已截断到 " + MAX_LINES + " 条" : body;
    }
}
