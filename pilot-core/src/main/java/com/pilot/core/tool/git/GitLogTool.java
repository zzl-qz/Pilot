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

/** 并行只读工具：查看当前仓库或指定路径的最近提交历史。 */
public final class GitLogTool implements Tool.ParallelReadOnly {
    public static final String NAME = "git_log";
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LINES = 250;

    private final PathValidator paths;
    private final AgentScope scope;

    public GitLogTool(PathValidator paths) {
        this(paths, null);
    }

    public GitLogTool(PathValidator paths, AgentScope scope) {
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
        props.put("limit", Map.of("type", "integer", "description", "可选：返回最近提交条数（1-50，默认 10）"));
        props.put("path", ToolSchemas.string("可选：限定查看某个相对文件或目录的提交历史"));
        props.put("stat", Map.of("type", "boolean", "description", "可选：为 true 时附带每次提交的文件变更统计，默认 false"));
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
        int limit = GitReadSupport.intArg(args, "limit", DEFAULT_LIMIT, 1, 50);
        boolean stat = GitReadSupport.boolArg(args, "stat");

        List<String> command = new ArrayList<>();
        command.add("log");
        command.add("-n");
        command.add(String.valueOf(limit));
        command.add("--date=short");
        command.add("--pretty=format:%h %ad %an | %s");
        if (stat) {
            command.add("--stat");
        }
        if (pathspec != null) {
            command.add("--");
            command.add(pathspec);
        }
        GitReadSupport.RunResult result = GitReadSupport.run(paths.root(), command);
        if (!result.ok()) {
            String detail = result.error() != null ? result.error() : result.output().strip();
            if (detail.contains("does not have any commits yet")) {
                return "当前仓库尚无提交记录。";
            }
            return "ERROR: git log 失败：" + detail;
        }
        String text = result.output().strip();
        if (text.isEmpty()) {
            return "未找到匹配的提交记录。";
        }
        return GitReadSupport.truncateLines(result.output(), MAX_LINES, "可调小 limit 或关闭 stat");
    }
}
