package com.pilot.core.quality;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.pilot.core.message.ToolNames;
import com.pilot.core.quality.CodebaseAuditor.AuditSnapshot;
import com.pilot.core.tool.PromptedTool;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolResults;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.workspace.PathValidator;

/**
 * 存量代码只读静态审计工具。
 * 一次性扫描工作区或子路径的生产源码，返回重复块、魔法值、结构坏味道、性能陷阱与边界隐患账本。
 */
public final class AuditCodebaseTool extends PromptedTool implements Tool.ParallelReadOnly {
    public static final int DEFAULT_MAX_FINDINGS = 40;
    public static final int MAX_FINDINGS_LIMIT = 100;
    private static final List<String> FOCUS_VALUES = List.of("all", "duplicates", "magic", "performance", "boundary");
    private static final Set<String> ALLOWED_FOCUS = Set.copyOf(FOCUS_VALUES);

    private final PathValidator paths;

    public AuditCodebaseTool(PathValidator paths) {
        super(ToolNames.AUDIT_CODEBASE);
        this.paths = Objects.requireNonNull(paths, "paths must not be null");
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> focusSchema = Map.of("type", "string", "enum", FOCUS_VALUES, "description",
            "审计焦点：all（默认，包含全部类别与结构臃肿）| duplicates | magic | performance | boundary");
        return ToolSchemas.object(
            Map.of("path", ToolSchemas.string("目标目录或单个源码文件的工作区相对路径，默认 \".\""), "focus", focusSchema, "max_findings",
                ToolSchemas.integer("最多展示的具体问题条数，默认 " + DEFAULT_MAX_FINDINGS + "，最大 " + MAX_FINDINGS_LIMIT)),
            List.of());
    }

    @Override
    public String execute(Map<String, Object> args) {
        String rawPath = ToolSupport.stringArg(args, "path");
        String scope = rawPath == null ? "." : rawPath;
        String rawFocus = ToolSupport.stringArg(args, "focus");
        String focus = rawFocus == null ? "all" : rawFocus.toLowerCase(Locale.ROOT);
        if (!ALLOWED_FOCUS.contains(focus)) {
            return error("focus 非法（仅支持 " + String.join(" | ", FOCUS_VALUES) + "）");
        }
        int maxFindings = DEFAULT_MAX_FINDINGS;
        Object rawMax = args == null ? null : args.get("max_findings");
        if (rawMax instanceof Number number) {
            maxFindings = number.intValue();
        } else if (rawMax != null) {
            try {
                maxFindings = Integer.parseInt(rawMax.toString().strip());
            } catch (NumberFormatException e) {
                return error("max_findings 必须是整数");
            }
        }
        if (maxFindings < 1) {
            return error("max_findings 必须大于等于 1");
        }
        maxFindings = Math.min(maxFindings, MAX_FINDINGS_LIMIT);

        Path target;
        try {
            target = paths.resolve(scope);
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return error("路径不存在或为符号链接: " + scope);
        }
        String relative = paths.display(target).replace('\\', '/');
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && !paths.allows(target)) {
            return error(denialText(target));
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && !".".equals(relative)
            && !paths.allowsDirectory(target)) {
            return error(denialText(target));
        }
        String label = ".".equals(relative) ? "整个工作区" : relative;
        AuditSnapshot snapshot = CodebaseAuditor.audit(paths, scope);
        return snapshot.renderReport(label, focus, maxFindings);
    }

    private static String error(String message) {
        return ToolResults.ERROR + ": " + message;
    }

    private String denialText(Path target) {
        String rel = paths.display(target);
        return paths.scope() == null ? "路径不在允许范围内: " + rel : paths.scope().denialHint(rel);
    }
}
