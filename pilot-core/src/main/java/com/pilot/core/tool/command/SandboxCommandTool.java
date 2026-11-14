package com.pilot.core.tool.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.pilot.core.exec.DependencyCache;
import com.pilot.core.exec.ExecutionBackends;
import com.pilot.core.exec.ExecutionLimits;
import com.pilot.core.exec.ExecutionRequest;
import com.pilot.core.exec.IsolatedWorkspace;
import com.pilot.core.message.ToolCall;
import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.ProducesCheck;
import com.pilot.core.tool.SandboxSink;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.workspace.PathValidator;

/** 子任务命令只用于校验提案，必须在容器中的临时副本执行；命令产物不自动合入提案。 */
public final class SandboxCommandTool implements Tool, ProducesCheck {
    private final PathValidator paths;
    private final SandboxSink proposals;
    private final ExecutionBackends.Selection execution;
    private final RunCommandTool metadata;

    public SandboxCommandTool(PathValidator paths, SandboxSink proposals, ExecutionBackends.Selection execution) {
        this.paths = paths;
        this.proposals = proposals;
        this.execution = execution;
        this.metadata = new RunCommandTool(paths);
    }

    @Override
    public String name() {
        return ToolNames.RUN_COMMAND;
    }

    @Override
    public String description() {
        return metadata.description()
            + "\n子任务仅支持隔离校验：命令在临时副本中执行，看到当前提案与上游内容。源文件改动请用 edit_file/create_file；命令生成的临时产物不会合入提案。没有容器隔离时拒绝执行。";
    }

    @Override
    public Map<String, Object> parameters() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("command", ToolSchemas.string("项目校验命令，例如 npm test"));
        properties.put("workdir", ToolSchemas.string("工作区内相对目录"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 600, "default", 120));
        return ToolSchemas.object(properties, List.of("command"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public boolean confirm(Map<String, Object> args) {
        return metadata.confirm(args);
    }

    @Override
    public String execute(Map<String, Object> args) {
        if (execution == null || !execution.isolated() || execution.degraded() || !execution.backend().available()) {
            return "ERROR: 子任务命令需要可用的容器隔离后端，命令未执行。请配置 pilot.execution.isolation=container 与包含项目工具链的镜像。";
        }
        String raw = ToolSupport.stringArg(args, "command");
        if (raw == null || raw.isBlank()) {
            return "ERROR: command 不能为空";
        }
        var chain = CommandChain.parse(raw);
        if (chain.error() != null) {
            return "ERROR: " + chain.error();
        }
        List<List<String>> commands = new ArrayList<>();
        for (int i = 0; i < chain.segments().size(); i++) {
            var segment = chain.segments().get(i);
            List<String> tokens = new ArrayList<>(segment.tokens());
            String executable = tokens.getFirst().toLowerCase(Locale.ROOT);
            if (Set.of("./mvnw", ".\\mvnw.cmd", "mvnw.cmd").contains(executable)) {
                executable = "mvnw";
            }
            if (Set.of("./gradlew", ".\\gradlew.bat", "gradlew.bat").contains(executable)) {
                executable = "gradlew";
            }
            tokens.set(0, executable);
            if (!CommandPolicy.knownExecutable(executable)) {
                return "ERROR: 不支持该命令，请使用工作目录参数和项目校验命令";
            }
            String forbidden = CommandPolicy.forbidden(tokens);
            if (forbidden != null) {
                return "ERROR: " + forbidden;
            }
            if (paths.scope() != null && !paths.scope().allowsCommandArgs(tokens)) {
                return "ERROR: " + paths.scope().commandHint(raw);
            }
            commands.add(tokens);
        }
        try {
            String requested = ToolSupport.stringArg(args, "workdir");
            Path relative = requested == null || requested.isBlank() ? Path.of("") : Path.of(requested);
            if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
                return "ERROR: workdir 必须是工作区内相对目录";
            }
            long timeout = 120;
            if (args.containsKey("timeoutSeconds")) {
                Object value = args.get("timeoutSeconds");
                if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                    || n.doubleValue() != n.longValue() || n.longValue() < 1 || n.longValue() > 600) {
                    return "ERROR: timeoutSeconds 必须为 1 到 600 的整数";
                }
                timeout = n.longValue();
            }
            var base = execution.settings().limits();
            try (var copy = IsolatedWorkspace.createForBuild(paths, proposals.proposedFiles());
                var cache = DependencyCache.acquire(execution.settings().cacheRoot(), paths.root(),
                    execution.backend().cacheIdentity(), copy.root())) {
                Path workdir =
                    new PathValidator(copy.root()).resolve(relative.toString().isEmpty() ? "." : relative.toString());
                if (!Files.isDirectory(workdir)) {
                    return "ERROR: workdir 不在可见的隔离副本中";
                }
                List<String> pieces = new ArrayList<>();
                for (int i = 0; i < commands.size(); i++) {
                    if (i > 0) {
                        pieces.add(chain.operators().get(i - 1));
                    }
                    var tokens = withWrapper(commands.get(i), workdir, copy.root());
                    pieces.add(String.join(" ", tokens.stream().map(CommandChain::quote).toList())
                        + (chain.segments().get(i).mergeStderr() ? " 2>&1" : ""));
                }
                var result =
                    execution.backend()
                        .run(
                            ExecutionRequest
                                .shell(String.join(" ", pieces), workdir, copy.root(), Map.of("CI", "true"),
                                    execution.settings().network(), new ExecutionLimits(timeout, base.maxOutputBytes(),
                                        base.memoryMb(), base.cpus(), base.pidsLimit()))
                                .withDependencyCache(cache.path()));
                copy.verifySourceUnchanged();
                if (!result.error().isEmpty() || result.timedOut()) {
                    return "ERROR: 隔离校验未完成：" + (result.timedOut() ? "命令超时" : result.error()) + "\n" + result.output();
                }
                return "退出码: " + result.exitCode() + "\n隔离校验（临时产物不合入提案）\n" + result.output()
                    + (copy.excludedDirectories().isEmpty() ? ""
                        : "\n副本排除生成目录：" + String.join("、", copy.excludedDirectories()))
                    + (result.truncated() ? "\n（输出已截断）" : "");
            }
        } catch (Exception e) {
            return "ERROR: 隔离校验失败：" + e.getMessage();
        }
    }

    private static List<String> withWrapper(List<String> tokens, Path workdir, Path root) {
        String executable = tokens.getFirst();
        String wrapper = switch (executable) {
            case "mvn", "mvnw" -> "mvnw";
            case "gradle", "gradlew" -> "gradlew";
            default -> null;
        };
        if (wrapper == null) {
            return tokens;
        }
        for (Path dir = workdir; dir != null && dir.startsWith(root); dir = dir.getParent()) {
            Path script = dir.resolve(wrapper);
            if (!Files.isRegularFile(script)) {
                continue;
            }
            List<String> result =
                new ArrayList<>(List.of("sh", workdir.relativize(script).toString().replace('\\', '/')));
            result.addAll(tokens.subList(1, tokens.size()));
            return result;
        }
        if (executable.equals(wrapper)) {
            throw new IllegalArgumentException("可见副本中缺少 " + wrapper + "，Linux 容器需要 POSIX wrapper");
        }
        return tokens;
    }

    @Override
    public String checkSummary(ToolCall call, String result) {
        return metadata.checkSummary(call, result);
    }
}
