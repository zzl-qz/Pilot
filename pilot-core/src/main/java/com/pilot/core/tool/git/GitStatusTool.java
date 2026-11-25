package com.pilot.core.tool.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pilot.core.tool.Availability;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.workspace.AgentScope;
import com.pilot.core.workspace.PathValidator;

/** 并行只读工具：查看当前分支与工作区/暂存区变更文件清单。 */
public final class GitStatusTool implements Tool.ParallelReadOnly {
    public static final String NAME = "git_status";
    private static final int MAX_LINES = 200;

    private final PathValidator paths;
    private final AgentScope scope;

    public GitStatusTool(PathValidator paths) {
        this(paths, null);
    }

    public GitStatusTool(PathValidator paths, AgentScope scope) {
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
        return ToolSchemas.object(Map.of("path", ToolSchemas.string("可选：限定查看工作区内的相对文件或目录路径")), List.of());
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
        List<String> command = new ArrayList<>();
        command.add("status");
        command.add("--short");
        command.add("--branch");
        if (pathspec != null) {
            command.add("--");
            command.add(pathspec);
        }
        GitReadSupport.RunResult result = GitReadSupport.run(paths.root(), command);
        if (!result.ok()) {
            String detail = result.error() != null ? result.error() : result.output().strip();
            return "ERROR: git status 失败：" + detail;
        }
        String text = result.output().replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
        List<String> lines = text.lines().toList();
        if (lines.isEmpty()) {
            return "工作区干净（无未提交改动）。";
        }
        if (lines.size() == 1 && lines.getFirst().startsWith("## ")) {
            return lines.getFirst() + "\n工作区干净（无未提交改动）。";
        }
        return GitReadSupport.truncateLines(text, MAX_LINES, "可传 path 限定子目录");
    }
}
