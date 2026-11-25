package com.pilot.core.tool.git;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pilot.core.tool.Availability;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.workspace.AgentScope;
import com.pilot.core.workspace.PathValidator;

/** 并行只读工具：查看工作区或暂存区 Git diff，带按路径过滤与行数截断保护。 */
public final class GitDiffTool implements Tool.ParallelReadOnly {
    public static final String NAME = "git_diff";
    private static final int DEFAULT_MAX_LINES = 200;

    private final PathValidator paths;
    private final AgentScope scope;

    public GitDiffTool(PathValidator paths) {
        this(paths, null);
    }

    public GitDiffTool(PathValidator paths, AgentScope scope) {
        this.paths = Objects.requireNonNull(paths, "paths must not be null");
        this.scope = scope;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Availability availability() {
        return GitReadSupport.availability(paths);
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("staged", Map.of("type", "boolean", "description", "可选：为 true 时查看已暂存改动（--cached），默认 false 看未暂存改动"));
        props.put("path", ToolSchemas.string("可选：限定查看工作区内的相对文件或目录路径"));
        props.put("stat_only", Map.of("type", "boolean", "description", "可选：为 true 时只返回文件增删统计（--stat），默认 false"));
        props.put("max_lines", Map.of("type", "integer", "description", "可选：最多返回行数（20-800，默认 200）"));
        return ToolSchemas.object(props, List.of());
    }

    @Override
    public String execute(Map<String, Object> args) {
        String repositoryError = GitReadSupport.repositoryError(paths);
        if (repositoryError != null) {
            return repositoryError;
        }
        String pathspec;
        try {
            pathspec = GitReadSupport.resolveScopedPathspec(paths, scope, ToolSupport.stringArg(args, "path"));
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        boolean staged = GitReadSupport.boolArg(args, "staged");
        boolean statOnly = GitReadSupport.boolArg(args, "stat_only");
        int maxLines = GitReadSupport.intArg(args, "max_lines", DEFAULT_MAX_LINES, 20, 800);

        List<String> command = new ArrayList<>();
        command.add("diff");
        command.add("--no-color");
        if (staged) {
            command.add("--cached");
        }
        if (statOnly) {
            command.add("--stat");
        }
        if (pathspec != null) {
            command.add("--");
            command.add(pathspec);
        }
        GitReadSupport.RunResult result = GitReadSupport.run(paths.root(), command);
        if (!result.ok()) {
            String detail = result.error() != null ? result.error() : result.output().strip();
            return "ERROR: git diff 失败：" + detail;
        }
        String text = result.output().strip();
        if (text.isEmpty()) {
            return staged ? "暂存区无差异改动。" : "工作区无未暂存差异改动。";
        }
        return GitReadSupport.truncateLines(result.output(), maxLines, "可传 path 或 stat_only=true 缩小范围");
    }
}
