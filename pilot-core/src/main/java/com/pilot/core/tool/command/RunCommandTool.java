package com.pilot.core.tool.command;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pilot.core.exec.EnvironmentSanitizer;
import com.pilot.core.exec.OsSandbox;
import com.pilot.core.exec.ProcessSupport;
import com.pilot.core.message.ToolCall;
import com.pilot.core.message.ToolNames;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.runtime.ExecutablePath;
import com.pilot.core.runtime.bootstrap.RuntimeEnvironment;
import com.pilot.core.tool.ProducesCheck;
import com.pilot.core.tool.ReadGuard;
import com.pilot.core.tool.ReadTracker;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.ToolResults;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;
import com.pilot.core.util.HostOs;
import com.pilot.core.util.Texts;
import com.pilot.core.workspace.AgentScope;
import com.pilot.core.workspace.PathValidator;

/**
 * 在工作区内执行受限命令，用于构建、测试、运行开发服务和查看仓库。
 *
 * <p>安全边界：
 * <ul>
 *   <li>命令名不靠固定名单放行；提权、越界删除、全局发布和会再执行程序的参数仍拒绝；</li>
 *   <li>解析后不交给 shell 二次解释：单条命令直接以参数数组启动，{@code && || ; |} 串联时每个词都重新加单引号，
 *       引号外的重定向、命令替换、子 shell、后台符一律拒绝；</li>
 *   <li>每个命令段独立判定（{@link CommandPolicy}）：查看类命令只读放行，构建/测试/写文件按档位审批，
 *       发布与全局安装直接拒绝；文件类工具的路径参数必须在工作区内；</li>
 *   <li>{@code git} 允许只读子命令与 {@code add}/{@code commit}，其余写操作需人工执行；</li>
 *   <li>强制超时、输出上限；子进程只继承构建所需的环境变量，凭据名不会传进去，输出里再做一次脱敏。</li>
 * </ul>
 *
 * <p>权限按「本次调用」判定（{@link #readOnly(Map)}）：整条命令都是只读查看时，{@code READ_ONLY} 与
 * {@code ASK} 档位直接放行；其余沿用「READ_ONLY 拒绝、ASK/AUTO_EDIT 逐次审批」。
 */
public final class RunCommandTool implements Tool, ProducesCheck {
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)(key|token|secret|password|credential|passwd)");
    private static final Pattern EXIT_CODE = Pattern.compile("^退出码: (-?\\d+)", Pattern.MULTILINE);

    private static final boolean WINDOWS = HostOs.isWindows();
    private static final int MAX_TOKENS = 64;
    private static final int MAX_OUTPUT_CHARS = 12_000;
    private static final int HEAD_CHARS = 4_000;
    private static final long DEFAULT_TIMEOUT_MS = 120_000L;
    private static final long MAX_TIMEOUT_MS = 600_000L;
    /** 测试里改短，生产用 ForegroundPace 的默认值。 */
    long silenceLimitMs = ForegroundPace.SILENCE_LIMIT_MS;
    long holdLimitMs = ForegroundPace.HOLD_LIMIT_MS;

    private final PathValidator paths;
    private final CommandTaskManager commandTasks;
    private final String sessionId;
    private final CommandChangeTracker changes;
    private ReadGuard reads = ReadGuard.allowAll();
    /** 只读命令在工作区指纹没变、且仍在有效期内时不再执行。测试可改短。 */
    long readOnlyCacheTtlMs = 15_000L;
    private final Map<String, CachedReadOnly> readOnlyAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> LIVE_PROBES = Set.of("curl", "lsof", "ss", "netstat", "ps", "tasklist");
    private static final Set<String> FILE_DUMPS = Set.of("cat", "nl", "head", "tail", "sed", "bat", "less", "more");
    private static final Set<String> GIT_HISTORY = Set.of("log", "show", "blame");
    private static final int MAX_HISTORY_LOOKUPS = 4;
    private final java.util.concurrent.atomic.AtomicInteger historyLookups =
        new java.util.concurrent.atomic.AtomicInteger();

    private record CachedReadOnly(int revision, long at) {
    }

    public RunCommandTool(PathValidator paths) {
        this(paths, null, null, CommandChangeTracker.none());
    }

    public RunCommandTool(PathValidator paths, CommandChangeTracker changes) {
        this(paths, null, null, changes);
    }

    /** 和读文件共用一份观察记录，文件改过之后只读命令会重新执行。 */
    public RunCommandTool withReads(ReadGuard reads) {
        this.reads = reads == null ? ReadGuard.allowAll() : reads;
        return this;
    }

    /** 会话运行使用受托管命令；独立沙箱工具继续使用同步构造器。 */
    public RunCommandTool(PathValidator paths, CommandTaskManager commandTasks, String sessionId) {
        this(paths, commandTasks, sessionId, CommandChangeTracker.none());
    }

    public RunCommandTool(PathValidator paths, CommandTaskManager commandTasks, String sessionId,
        CommandChangeTracker changes) {
        this.paths = Objects.requireNonNull(paths, "paths must not be null");
        this.commandTasks = commandTasks;
        this.sessionId = commandTasks == null ? null : Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.changes = changes == null ? CommandChangeTracker.none() : changes;
    }

    @Override
    public String name() {
        return ToolNames.RUN_COMMAND;
    }

    @Override
    public String description() {
        return PromptLibrary.standard()
            .render("tools/run_command",
                Map.of("inspection", String.join("/", CommandPolicy.INSPECTION.stream().sorted().toList()),
                    "development", String.join("/", CommandPolicy.DEVELOPMENT.stream().sorted().toList()), "mutators",
                    String.join("/", CommandPolicy.FILE_MUTATORS.stream().sorted().toList()), "gitReadonly",
                    String.join("/", CommandPolicy.GIT_READONLY.stream().sorted().toList()), "gitWrite",
                    String.join("/", CommandPolicy.GIT_WRITE.stream().sorted().toList()), "backgroundHelp",
                    commandTasks == null ? "本运行环境仅支持同步执行。"
                        : "background=true 会立即返回任务 ID，适合开发服务器或长测试。后台默认不设超时；显式 timeoutSeconds 最大 600。"
                            + "用 command_status 查询实际状态、command_logs 增量读取日志、stop_command 停止进程树。"
                            + "启动成功不代表验证通过；测试必须等到完成并检查退出码。"))
            .strip();
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("command", ToolSchemas.string("完整命令，例如 \"mvn -q test\" 或 \"git status --porcelain\""));
        properties.put("workdir", ToolSchemas.string("可选，工作区内的工作目录，例如 pilot-web；默认工作区根目录"));
        properties.put("timeoutSeconds",
            Map.of("type", "integer", "minimum", 0, "maximum", 600, "description", "前台默认 120 秒，后台默认 0（不设超时），最大 600 秒"));
        if (commandTasks != null) {
            properties.put("background",
                Map.of("type", "boolean", "default", false, "description", "后台运行并立即返回任务 ID；默认 false，等待命令结束"));
        }
        return ToolSchemas.object(properties, List.of("command"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    /**
     * 按本次命令判定：整条命令都是查看类（ls/cat/rg/git status/curl localhost …）才算只读。
     * 无法通过校验的命令也放行到执行层——它只会返回具体错误，没有必要让用户为一条必然失败的命令点审批。
     */
    @Override
    public boolean readOnly(Map<String, Object> args) {
        Preparation preparation = prepare(args);
        if (preparation.error() != null) {
            // 准备失败不等于只读：例如 Windows 上缺少可用 shell 时链式命令只是无法执行，
            // 仍要按命令本身判断，否则会绕过审批。
            return staticReadOnly(ToolSupport.stringArg(args, "command"));
        }
        // 后台任务会常驻进程，哪怕命令本身是查看类也要走审批。
        return !preparation.prepared().background() && preparation.prepared().readOnly();
    }

    @Override
    public boolean confirm(Map<String, Object> args) {
        String raw = ToolSupport.stringArg(args, "command");
        if (raw == null || raw.isBlank()) {
            return false;
        }
        CommandChain.Parsed parsed = CommandChain.parse(raw);
        if (parsed.error() != null) {
            return false;
        }
        for (CommandChain.Segment segment : parsed.segments()) {
            CommandPolicy.Prefix prefix = CommandPolicy.prefix(segment.tokens());
            if (prefix.error() != null || prefix.tokens().isEmpty()) {
                return false;
            }
            if (CommandPolicy.confirms(prefix.tokens())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只按命令文本判断只读性。注定被策略拒绝的命令按只读放行，避免用户先审批再看到同一条错误。
     * 名字不在旧名单里的命令会真的执行，因此不再当成只读。
     */
    private boolean staticReadOnly(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        CommandChain.Parsed parsed = CommandChain.parse(raw);
        if (parsed.error() != null) {
            return true;
        }
        boolean readOnly = true;
        for (CommandChain.Segment segment : parsed.segments()) {
            CommandPolicy.Prefix prefix = CommandPolicy.prefix(segment.tokens());
            if (prefix.error() != null || prefix.tokens().isEmpty()) {
                return true;
            }
            List<String> tokens = prefix.tokens();
            if (CommandPolicy.blocked(tokens, paths.root(), paths) != null || CommandPolicy.forbidden(tokens) != null
                || CommandPolicy.arguments(tokens, paths.root(), paths) != null) {
                return true;
            }
            readOnly &= CommandPolicy.readOnly(tokens);
        }
        return readOnly;
    }

    @Override
    public String execute(Map<String, Object> args) {
        return executeResult(args).text();
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        Preparation preparation = prepare(args);
        if (preparation.error() != null) {
            return ToolResult.text(preparation.error());
        }
        Prepared prepared = preparation.prepared();
        String raw = ToolSupport.stringArg(args, "command");
        String cacheKey = raw + "\n" + prepared.workdir();
        String repeated = repeatedLook(raw);
        if (repeated != null) {
            return ToolResult.text(repeated);
        }
        if (!prepared.readOnly() || prepared.background()) {
            readOnlyAt.clear();
        } else {
            CachedReadOnly cached = readOnlyAt.get(cacheKey);
            long age = cached == null ? Long.MAX_VALUE : System.currentTimeMillis() - cached.at();
            if (cached != null && cached.revision() == reads.revision() && age <= readOnlyCacheTtlMs
                && cachesReadOnlyCommand(raw)) {
                return ToolResult.text("与上次相同，未再执行。命令: " + raw);
            }
        }
        CommandChangeTracker.Handle tracking;
        String trackingWarning = "";
        try {
            tracking = prepared.trackChanges() ? changes.begin(paths.root()) : CommandChangeTracker.Handle.none();
        } catch (RuntimeException e) {
            // 基线是 best-effort 的旁路记录，不是命令执行的前置条件。
            // 建立失败时命令照常执行，只把「本轮改动未纳入追踪」告诉模型和界面；
            // 真正的错误原因保留在警告里，避免只看到一句“命令未执行”。
            tracking = CommandChangeTracker.Handle.none();
            String cause =
                e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
            trackingWarning = ToolResults.CHANGE_TRACKING_UNAVAILABLE + "（" + redact(cause) + "）";
        }

        String text;
        boolean terminal;
        if (commandTasks == null) {
            text = run(prepared.command(), prepared.displayTokens(), prepared.workdir(), prepared.timeoutMs(),
                prepared.env());
            terminal = true;
        } else {
            ManagedRun managed = runManaged(prepared.command(), prepared.displayTokens(), prepared.workdir(),
                prepared.background(), prepared.timeoutMs(), tracking, prepared.env());
            text = managed.text();
            terminal = managed.terminal();
        }
        if (prepared.readOnly() && !prepared.background() && succeeded(text) && cachesReadOnlyCommand(raw)) {
            readOnlyAt.put(cacheKey, new CachedReadOnly(reads.revision(), System.currentTimeMillis()));
        }
        if (!terminal || !tracking.active()) {
            return ToolResult.text(withTrackingWarning(text, trackingWarning));
        }
        CommandChangeTracker.Result result;
        try {
            result = changes.finish(tracking);
        } catch (RuntimeException e) {
            result = new CommandChangeTracker.Result(List.of(), false, String.valueOf(e.getMessage()));
        }
        if (!result.complete()) {
            text = ToolResults.CHANGE_TRACKING_INCOMPLETE + "；不得据此宣称验证通过：" + result.warning() + "\n" + text;
        }
        return new ToolResult(withTrackingWarning(text, trackingWarning), result.changedPaths(), List.of());
    }

    /** 换行号、cat/sed、反复翻 git 历史，都不再把同一份材料送回去。 */
    private String repeatedLook(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        if (gitHistory(command) && historyLookups.incrementAndGet() > MAX_HISTORY_LOOKUPS) {
            return "与上次相同，未再执行。命令: " + command + "\n提交历史已经看过，不要再翻 git log、show、blame。根据已有内容修改文件。";
        }
        for (Path file : dumpedFiles(command)) {
            if (!reads.deliverRead(file)) {
                return "文件未变化，沿用刚才读过的 " + paths.display(file) + "。不要再用 cat、sed、head 重读。根据上文修改，或改看别的文件。";
            }
        }
        return null;
    }

    private static boolean gitHistory(String command) {
        String[] tokens = command.strip().split("\\s+");
        if (tokens.length < 2 || !"git".equals(CommandPolicy.leaf(tokens[0]))) {
            return false;
        }
        return GIT_HISTORY.contains(tokens[1]);
    }

    private List<Path> dumpedFiles(String command) {
        String[] tokens = command.strip().split("\\s+");
        if (tokens.length == 0 || !FILE_DUMPS.contains(CommandPolicy.leaf(tokens[0]))) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        for (String token : tokens) {
            if (token.startsWith("-") || token.contains("|") || token.contains(";") || token.contains("&")
                || token.contains(",") || token.contains("*")) {
                continue;
            }
            String cleaned = token.replace("'", "").replace("\"", "");
            try {
                Path file = paths.resolve(cleaned);
                if (Files.isRegularFile(file)) {
                    found.add(file);
                }
            } catch (RuntimeException ignored) {
                // 不是工作区文件就留给命令自己执行
            }
        }
        return found;
    }

    /** 端口、进程和网络状态不跟着工作区文件走，不能因为文件没变就跳过。 */
    static boolean cachesReadOnlyCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String leaf = CommandPolicy.leaf(command.strip().split("\\s+")[0]);
        return !LIVE_PROBES.contains(leaf);
    }

    private static boolean succeeded(String text) {
        if (text == null || text.startsWith("ERROR:")) {
            return false;
        }
        return text.contains("退出码: 0") || text.contains("状态: COMPLETED");
    }

    private static String withTrackingWarning(String text, String warning) {
        if (warning == null || warning.isBlank()) {
            return text;
        }
        return text + "\n\n" + warning;
    }

    private Preparation prepare(Map<String, Object> args) {
        String raw = ToolSupport.stringArg(args, "command");
        if (raw == null) {
            return Preparation.error("ERROR: 缺少参数 command");
        }
        CommandChain.Parsed parsed = CommandChain.parse(raw);
        if (parsed.error() != null) {
            return Preparation.error("ERROR: " + parsed.error());
        }
        List<CommandChain.Segment> segments = new ArrayList<>(parsed.segments());
        List<String> operators = new ArrayList<>(parsed.operators());

        Path workdir = paths.root();
        Object requestedWorkdir = args == null ? null : args.get("workdir");
        if (requestedWorkdir != null) {
            if (!(requestedWorkdir instanceof String directory) || directory.isBlank()) {
                return Preparation.error("ERROR: workdir 必须是工作区内的非空目录路径");
            }
            try {
                workdir = paths.resolve(directory);
            } catch (RuntimeException e) {
                return Preparation.error("ERROR: workdir 无效：" + e.getMessage());
            }
            if (!Files.isDirectory(workdir)) {
                return Preparation.error("ERROR: workdir 不是现有目录：" + directory);
            }
        }
        // 模型习惯写 "cd sub && npm test"：开头的 cd 折算成工作目录，目录仍须在工作区内
        while (!segments.isEmpty() && segments.getFirst().tokens().getFirst().equals("cd")) {
            List<String> cd = segments.getFirst().tokens();
            if (segments.size() == 1 || cd.size() != 2
                || !(operators.getFirst().equals("&&") || operators.getFirst().equals(";"))) {
                return Preparation.error("ERROR: cd 只能写成命令开头的 \"cd 目录 && 命令\"；" + "目录不会保留到下一次调用，请改用 workdir 参数");
            }
            try {
                workdir = paths.resolve(workdir.resolve(cd.get(1)).toString());
            } catch (RuntimeException e) {
                return Preparation.error("ERROR: cd 目标无效：" + e.getMessage());
            }
            if (!Files.isDirectory(workdir)) {
                return Preparation.error("ERROR: cd 目标不是现有目录：" + cd.get(1));
            }
            segments.removeFirst();
            operators.removeFirst();
        }

        boolean chained = segments.size() > 1;
        List<SegmentCheck> checks = new ArrayList<>(segments.size());
        boolean readOnly = true;
        boolean trackChanges = false;
        for (CommandChain.Segment segment : segments) {
            SegmentCheck check = checkSegment(segment, workdir);
            if (check.error() != null) {
                return Preparation.error("ERROR: " + check.error());
            }
            checks.add(check);
            readOnly &= check.readOnly();
            trackChanges |= check.trackChanges();
        }
        boolean redirected = checks.stream().anyMatch(check -> check.redirect() != null);

        Object backgroundArg = args == null ? null : args.get("background");
        if (backgroundArg != null && !(backgroundArg instanceof Boolean)) {
            return Preparation.error("ERROR: background 必须是布尔值");
        }
        boolean background = Boolean.TRUE.equals(backgroundArg);
        if (background && commandTasks == null) {
            return Preparation.error("ERROR: 当前运行环境不支持后台命令");
        }
        long timeoutMs = timeout(args, background);

        if (!chained && !redirected) {
            SegmentCheck only = checks.getFirst();
            return new Preparation(new Prepared(withWrapper(only.tokens()), only.tokens(), workdir, background,
                timeoutMs, trackChanges, readOnly, only.env()), null);
        }
        // 串联或重定向才进 shell。每段仍用解析出的绝对路径，shell 只负责连接符和已经校验过的重定向。
        List<List<String>> checked = checks.stream().map(SegmentCheck::tokens).toList();
        String script = script(withWrapperSegments(checked), checks, operators, segments);
        String display = String.join(" ", segmentsDisplay(checked, operators));
        if (WINDOWS) {
            List<String> shell = windowsShell(script, operators);
            if (shell == null) {
                return Preparation
                    .error("ERROR: Windows 上 && 与 || 需要 PowerShell 7（pwsh.exe）；" + "请安装 PowerShell 7、改用 ; 连接，或拆成多次调用");
            }
            return new Preparation(
                new Prepared(shell, List.of(display), workdir, background, timeoutMs, trackChanges, readOnly, Map.of()),
                null);
        }
        return new Preparation(new Prepared(List.of("/bin/sh", "-c", script), List.of(display), workdir, background,
            timeoutMs, trackChanges, readOnly, Map.of()), null);
    }

    private List<List<String>> withWrapperSegments(List<List<String>> checked) {
        List<List<String>> wrapped = new ArrayList<>(checked.size());
        for (List<String> tokens : checked) {
            wrapped.add(withWrapper(tokens));
        }
        return wrapped;
    }

    /**
     * 把多段命令拼成脚本。Windows 上用 PowerShell：每段加 {@code &} 调用操作符并做 PowerShell 单引号转义；
     * 其他平台用 POSIX 单引号。
     */
    private static String script(List<List<String>> resolved, List<SegmentCheck> checks, List<String> operators,
        List<CommandChain.Segment> segments) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < resolved.size(); i++) {
            if (i > 0) {
                out.append(' ').append(operators.get(i - 1)).append(' ');
            }
            List<String> tokens = resolved.get(i);
            SegmentCheck check = checks.get(i);
            if (WINDOWS) {
                // 脚本块把环境变量限制在这一段，避免前缀泄漏到后面的命令。
                out.append("& { ");
                check.env().forEach(
                    (name, value) -> out.append("$env:").append(name).append('=').append(psQuote(value)).append("; "));
                out.append("& ").append(psQuote(tokens.get(0)));
            } else {
                check.env().forEach(
                    (name, value) -> out.append(name).append('=').append(CommandChain.quote(value)).append(' '));
                out.append(CommandChain.quote(tokens.get(0)));
            }
            for (int j = 1; j < tokens.size(); j++) {
                out.append(' ').append(WINDOWS ? psQuote(tokens.get(j)) : CommandChain.quote(tokens.get(j)));
            }
            if (check.redirect() != null) {
                out.append(check.append() ? " >> " : " > ");
                out.append(
                    WINDOWS ? psQuote(check.redirect().toString()) : CommandChain.quote(check.redirect().toString()));
            }
            if (segments.get(i).mergeStderr()) {
                out.append(" 2>&1");
            }
            if (WINDOWS) {
                out.append(" }");
            }
        }
        return out.toString();
    }

    /** PowerShell 单引号字面量；内部的单引号写成两个。 */
    private static String psQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * Windows 上执行链式命令的 shell。优先 PowerShell 7（{@code pwsh} 支持 &&、||、; 和管道），
     * 回退 Windows PowerShell 5.1（只支持 ; 和管道）。返回 null 表示当前环境无法执行该连接符组合。
     */
    private static List<String> windowsShell(String script, List<String> operators) {
        boolean shortCircuit = operators.contains("&&") || operators.contains("||");
        Path pwsh = resolveOnPath("pwsh");
        if (pwsh != null) {
            return List.of(pwsh.toString(), "-NoProfile", "-NonInteractive", "-Command", script);
        }
        if (shortCircuit) {
            return null;
        }
        Path powerShell = resolveOnPath("powershell");
        if (powerShell != null) {
            return List.of(powerShell.toString(), "-NoProfile", "-NonInteractive", "-Command", script);
        }
        return null;
    }

    private static List<String> segmentsDisplay(List<List<String>> segments, List<String> operators) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                parts.add(operators.get(i - 1));
            }
            parts.add(String.join(" ", segments.get(i)));
        }
        return parts;
    }

    private record SegmentCheck(List<String> tokens, Map<String, String> env, boolean readOnly, boolean trackChanges,
        Path redirect, boolean append, String error) {
        static SegmentCheck error(String message) {
            return new SegmentCheck(List.of(), Map.of(), false, false, null, false, message);
        }
    }

    /** 单个命令段的全部静态校验；通过后返回规范化的参数与「只读 / 是否需要追踪变更」。 */
    private SegmentCheck checkSegment(CommandChain.Segment segment, Path workdir) {
        CommandPolicy.Prefix prefix = CommandPolicy.prefix(segment.tokens());
        if (prefix.error() != null) {
            return SegmentCheck.error(prefix.error());
        }
        List<String> tokens = prefix.tokens();
        if (tokens.size() > MAX_TOKENS) {
            return SegmentCheck.error("参数过多（上限 " + MAX_TOKENS + " 个）");
        }
        String executable = CommandPolicy.leaf(tokens.get(0));
        if (executable.indexOf('(') >= 0 || executable.indexOf(')') >= 0) {
            return SegmentCheck.error("不支持括号，它会被当成子 shell");
        }
        boolean hasSeparator = tokens.get(0).indexOf('/') >= 0 || tokens.get(0).indexOf('\\') >= 0;
        boolean wrapper = executable.equals("mvnw") || executable.equals("gradlew");
        if (wrapper || !hasSeparator && !executable.equals(tokens.get(0))) {
            tokens = new ArrayList<>(tokens);
            tokens.set(0, executable);
        }
        String blocked = CommandPolicy.blocked(tokens, workdir, paths);
        if (blocked != null) {
            return SegmentCheck.error(blocked);
        }
        String selfProtection = SelfProtection.check(tokens);
        if (selfProtection != null) {
            return SegmentCheck.error(selfProtection);
        }
        boolean confirm = CommandPolicy.confirms(tokens);
        if (!confirm) {
            String forbidden = CommandPolicy.forbidden(tokens);
            if (forbidden != null) {
                return SegmentCheck.error(forbidden);
            }
        }

        AgentScope scope = paths.scope();
        if (scope != null && !scope.allowsCommandArgs(tokens)) {
            return SegmentCheck.error(scope.commandHint(String.join(" ", tokens)));
        }
        String selectorProblem = packageDirectoryProblem(tokens, workdir);
        if (selectorProblem != null) {
            return SegmentCheck.error(selectorProblem);
        }
        String argumentProblem = CommandPolicy.arguments(tokens, workdir, paths);
        if (argumentProblem != null) {
            return SegmentCheck.error(argumentProblem);
        }

        boolean trackChanges = !(tokens.size() == 2 && CommandPolicy.INSPECTION_FLAGS.contains(tokens.get(1)));
        if (CommandPolicy.INSPECTION.contains(executable) || "curl".equals(executable)) {
            trackChanges = false;
        }
        if ("git".equals(executable)) {
            String sub = tokens.size() > 1 ? tokens.get(1).toLowerCase(Locale.ROOT) : "";
            if (CommandPolicy.GIT_READONLY.contains(sub)) {
                trackChanges = false;
            } else if (CommandPolicy.GIT_WRITE.contains(sub) || CommandPolicy.GIT_MUTATING.contains(sub)) {
                if (scope != null && !scope.writable()) {
                    return SegmentCheck.error("只读任务不允许 git " + sub + "，改动请通过沙箱写工具提交");
                }
                if (CommandPolicy.GIT_WRITE.contains(sub)) {
                    String problem = checkGitWrite(tokens, sub);
                    if (problem != null) {
                        return SegmentCheck.error(problem);
                    }
                }
            } else if (scope != null && !scope.writable()) {
                return SegmentCheck.error("只读任务不允许 git " + sub + "，改动请通过沙箱写工具提交");
            }
        }
        if (scope != null && !scope.writable() && (CommandPolicy.FILE_MUTATORS.contains(executable)
            || Set.of("rm", "rmdir", "del", "erase", "unlink").contains(executable))) {
            return SegmentCheck.error("只读任务不允许 " + executable + "，改动请通过沙箱写工具提交");
        }
        Path redirect = null;
        if (segment.redirectTo() != null) {
            try {
                redirect = paths.resolve(workdir.resolve(segment.redirectTo()).toString());
            } catch (RuntimeException e) {
                return SegmentCheck.error("输出重定向必须写在工作区内：" + segment.redirectTo());
            }
            if (Files.isDirectory(redirect)) {
                return SegmentCheck.error("输出重定向目标不能是目录：" + segment.redirectTo());
            }
            trackChanges = true;
        }
        boolean segmentReadOnly = redirect == null && CommandPolicy.readOnly(tokens);
        return new SegmentCheck(tokens, prefix.env(), segmentReadOnly, trackChanges, redirect, segment.append(), null);
    }

    /** Package-manager directory selectors must stay inside the same workspace boundary as workdir. */
    private String packageDirectoryProblem(List<String> tokens, Path workdir) {
        if (!Set.of("npm", "pnpm", "yarn", "bun").contains(tokens.getFirst().toLowerCase(Locale.ROOT))) {
            return null;
        }
        for (int index = 1; index < tokens.size(); index++) {
            String token = tokens.get(index);
            String selector = null;
            String value = null;
            if (Set.of("--prefix", "--dir", "--cwd", "-C").contains(token)) {
                selector = token;
                if (++index >= tokens.size()) {
                    return selector + " 缺少目录";
                }
                value = tokens.get(index);
            } else {
                for (String flag : List.of("--prefix=", "--dir=", "--cwd=")) {
                    if (token.startsWith(flag)) {
                        selector = flag.substring(0, flag.length() - 1);
                        value = token.substring(flag.length());
                        break;
                    }
                }
            }
            if (selector == null) {
                continue;
            }
            if (value.isBlank() || value.startsWith("-")) {
                return selector + " 缺少目录";
            }
            try {
                Path selected = paths.resolve(workdir.resolve(value).toString());
                if (!Files.isDirectory(selected)) {
                    return selector + " 不是现有工作区目录：" + value;
                }
            } catch (RuntimeException e) {
                return selector + " 必须指向工作区内的目录：" + value;
            }
        }
        return null;
    }

    /** 提交必须自带消息（否则会拉起编辑器卡到超时），且只允许操作工作区内的相对路径。 */
    private static String checkGitWrite(List<String> tokens, String sub) {
        boolean hasMessage = tokens.stream().anyMatch(token -> token.equals("-m") || token.equals("--message")
            || token.startsWith("--message=") || token.equals("-F") || token.equals("--file"));
        if ("commit".equals(sub) && !hasMessage) {
            return "git commit 必须带 -m \"提交说明\"，否则会打开编辑器并卡住";
        }
        boolean skipNext = false;
        for (String token : tokens.subList(2, tokens.size())) {
            if (skipNext) {
                skipNext = false;
                continue;
            }
            if (token.equals("-m") || token.equals("--message") || token.equals("-F") || token.equals("--file")) {
                skipNext = true;
                continue;
            }
            if (token.startsWith("-")) {
                continue;
            }
            boolean absolute = token.startsWith("/") || token.startsWith("\\") || token.matches("^[A-Za-z]:.*");
            if (absolute || token.contains("..")) {
                return "git " + sub + " 只允许工作区内的相对路径: " + token;
            }
        }
        return null;
    }

    @Override
    public String checkSummary(ToolCall call, String result) {
        if (result == null) {
            return null;
        }
        if (commandTasks != null) {
            return CommandToolSupport.checkSummary(commandTasks, sessionId, result);
        }
        String command =
            call == null || call.args() == null ? "" : String.valueOf(call.args().getOrDefault("command", ""));
        if (ToolResults.isChangeTrackingIncomplete(result)) {
            return Texts.oneLine(command, 90) + " → CHANGE_TRACKING_INCOMPLETE";
        }
        Matcher matcher = EXIT_CODE.matcher(result);
        if (matcher.find()) {
            return Texts.oneLine(command, 90) + " → exit " + matcher.group(1);
        }
        // 超时、启动失败没有退出码，但同样是「验证没通过」，要留在记录里
        if (ToolResults.isError(result)) {
            return Texts.oneLine(command, 90) + " → " + Texts.oneLine(result, 80);
        }
        return null;
    }

    private ManagedRun runManaged(List<String> command, List<String> displayTokens, Path workdir, boolean background,
        long timeoutMs, CommandChangeTracker.Handle tracking, Map<String, String> env) {
        CommandLaunch launch = launch(command, workdir, env);
        if (launch.builder() == null) {
            return new ManagedRun("ERROR: " + launch.label(), true);
        }
        ProcessBuilder builder = launch.builder();
        CommandTaskManager.Task task;
        try {
            task = commandTasks.start(sessionId, String.join(" ", displayTokens), builder, background, timeoutMs,
                tracking.active() ? tracking : null);
        } catch (RuntimeException e) {
            return new ManagedRun("ERROR: 无法启动命令：" + redact(String.valueOf(e.getMessage())), true);
        }
        String taskId = task.taskId();
        if (background && CommandToolSupport.running(task)) {
            return new ManagedRun(CommandToolSupport.render(task), false);
        }
        boolean longCommand = ForegroundPace.expectsLong(String.join(" ", displayTokens));
        long heldFrom = System.nanoTime();
        try {
            while (CommandToolSupport.running(task)) {
                Thread.sleep(50);
                task = commandTasks.get(sessionId, taskId);
                if (!CommandToolSupport.running(task)) {
                    break;
                }
                long heldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - heldFrom);
                ForegroundPace.Release release = ForegroundPace.release(longCommand,
                    commandTasks.quietMillis(sessionId, taskId), heldMs, silenceLimitMs, holdLimitMs);
                if (release != ForegroundPace.Release.NONE) {
                    commandTasks.markBackground(sessionId, taskId);
                    task = commandTasks.get(sessionId, taskId);
                    return new ManagedRun(handoff(task, release), false);
                }
            }
        } catch (InterruptedException e) {
            commandTasks.stop(sessionId, taskId);
            // 中断调用方前，给进程树的协作终止和强制清理留出有界时间。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (CommandToolSupport.running(task) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ignored) {
                }
                task = commandTasks.get(sessionId, taskId);
            }
            Thread.currentThread().interrupt();
            return new ManagedRun("ERROR: 命令执行被中断\n" + CommandToolSupport.render(task), true);
        }
        if (task == null) {
            return new ManagedRun("ERROR: 命令记录已清理，无法确认最终结果：" + taskId, true);
        }
        CommandTaskManager.LogChunk chunk = commandTasks.logs(sessionId, taskId, 0, MAX_OUTPUT_CHARS);
        String rendered = quickFailure(task) + CommandToolSupport.render(task);
        if (chunk != null) {
            rendered += "\n输出:\n" + chunk.text()
                + (chunk.truncated() || !chunk.eof() ? "\n… 后续日志使用 command_logs，offset=" + chunk.nextOffset() : "");
        }
        return new ManagedRun(launch.label() + "\n" + rendered, true);
    }

    private static String handoff(CommandTaskManager.Task task, ForegroundPace.Release release) {
        String reason = release == ForegroundPace.Release.SILENT
            ? "这条命令已经有一段时间没有新输出，不像测试或构建那种正常变慢。已转到后台，没有杀掉。" + "先用 command_logs 看它停在哪，再用 command_status 确认。不要再启动一条同样的命令。"
            : "前台已经等了 2 分钟，命令还在跑，已转到后台。到设置的时限才会停。" + "用 command_status 看结果，不要再启动一条同样的命令。";
        return reason + "\n" + CommandToolSupport.render(task);
    }

    /** 几秒内就非 0 退出，多半是命令写错，不要让模型把它当成还在跑的长任务。 */
    private static String quickFailure(CommandTaskManager.Task task) {
        if (task == null || task.exitCode() == null || task.exitCode() == 0 || task.startedAt() == null
            || task.finishedAt() == null) {
            return "";
        }
        long took = java.time.Duration.between(task.startedAt(), task.finishedAt()).toMillis();
        if (took < 0 || took > ForegroundPace.QUICK_FAIL_MS) {
            return "";
        }
        return "这条命令很快就失败了，多半是调用方式不对。先根据下面的错误改命令，不要当成还在运行。\n";
    }

    private String run(List<String> command, List<String> displayTokens, Path workdir, long timeoutMs,
        Map<String, String> env) {
        long startedAt = System.nanoTime();
        CommandLaunch launch = launch(command, workdir, env);
        if (launch.builder() == null) {
            return "ERROR: " + launch.label();
        }
        ProcessBuilder builder = launch.builder();

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return "ERROR: 无法启动命令 " + String.join(" ", displayTokens) + "：" + e.getMessage();
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // stdin 关闭失败不影响执行
        }

        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(process));
        boolean finished;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return "ERROR: 命令执行被中断";
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String text = decode(await(output));
        long latencyMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        String rendered =
            render(String.join(" ", displayTokens), command, finished ? exitCode(process) : -1, latencyMs, text);
        rendered = launch.label() + "\n工作目录: " + workdir + "\n" + rendered;
        return finished ? rendered : "ERROR: 命令超时（" + (timeoutMs / 1000) + " 秒）已被终止\n" + rendered;
    }

    /**
     * 先丢掉凭据和与构建无关的变量，再把 JDK 指到当前运行时。
     * 子进程不再继承服务进程的整份环境。
     */
    private CommandLaunch launch(List<String> command, Path workdir, Map<String, String> extra) {
        OsSandbox.Launch sandbox = OsSandbox.wrap(command, paths.root());
        if (sandbox.command().isEmpty()) {
            return new CommandLaunch(null, sandbox.label());
        }
        ProcessBuilder builder =
            new ProcessBuilder(sandbox.command()).directory(workdir.toFile()).redirectErrorStream(true);
        prepareEnvironment(builder.environment());
        // 显式前缀放在 JDK 默认值之后，这样 JAVA_HOME=/path mvn 才会用到用户指定的值。
        if (extra != null) {
            builder.environment().putAll(extra);
        }
        return new CommandLaunch(builder, sandbox.label());
    }

    private record CommandLaunch(ProcessBuilder builder, String label) {
    }

    static void prepareEnvironment(Map<String, String> env) {
        EnvironmentSanitizer sanitizer = EnvironmentSanitizer.defaults();
        env.keySet().removeIf(key -> !sanitizer.allowed(key));
        useCurrentJdk(env);
        // 分页器和 git 询问会把没有终端的命令一直挂住。放在清洗之后，避免被允许名单删掉。
        env.putIfAbsent("CI", "true");
        env.put("PAGER", "cat");
        env.put("GIT_PAGER", "cat");
        env.put("GIT_TERMINAL_PROMPT", "0");
    }

    /**
     * 子进程继承服务进程的环境变量，其中 {@code JAVA_HOME} 可能指向更老的 JDK
     * （例如系统默认 17），导致 {@code mvn test} 无法加载本项目编译出的 Java 24 字节码。
     *
     * <p>Pilot 自己必然运行在项目要求的 JDK 上，因此把 {@code JAVA_HOME} 与 PATH 首项
     * 都指向当前运行时的 JDK，保证构建命令看到的是同一个 Java。
     */
    private static void useCurrentJdk(Map<String, String> env) {
        String pathKey = ExecutablePath.key(env);
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path bin = Path.of(javaHome, "bin");
            env.put("JAVA_HOME", javaHome);
            String current = env.getOrDefault(pathKey, "");
            env.put(pathKey, joinPath(bin.toString(), stripPrefix(current, bin.toString())));
        }
        env.put(pathKey, joinPath(RuntimeEnvironment.bundledBinPath().strip(), env.getOrDefault(pathKey, "")));
    }

    private static String render(String displayCommand, List<String> actual, int exitCode, long latencyMs,
        String output) {
        StringBuilder out = new StringBuilder().append("命令: ").append(displayCommand).append('\n').append("退出码: ")
            .append(exitCode).append('\n').append("耗时: ").append(latencyMs).append(" ms\n");
        if (!actual.isEmpty() && !displayCommand.equals(String.join(" ", actual))) {
            out.append("实际执行: ").append(String.join(" ", actual)).append('\n');
        }
        out.append("输出:\n").append(truncate(redact(output)));
        while (!out.isEmpty() && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /** 优先使用工作区里的构建 wrapper；否则在 PATH 上解析真实可执行文件（Windows 上是 .cmd）。 */
    private List<String> withWrapper(List<String> tokens) {
        String head = tokens.get(0).toLowerCase(Locale.ROOT);
        List<String> command = new ArrayList<>();
        Path wrapper = null;
        // mvnw/gradlew 本身也映射到工作区 wrapper：直接按名字查 PATH 是找不到的
        if ("mvn".equals(head) || "mvnw".equals(head)) {
            wrapper = findWrapper("mvnw");
        } else if ("gradle".equals(head) || "gradlew".equals(head)) {
            wrapper = findWrapper("gradlew");
        }
        if (wrapper != null) {
            // Git 检出或打包可能丢失执行位；只为已识别的构建 wrapper 加解释器，绝不执行 shell -c。
            boolean unix = !HostOs.isWindows();
            if (unix && !Files.isExecutable(wrapper)) {
                command.add("/bin/sh");
            }
            command.add(wrapper.toAbsolutePath().toString());
        } else {
            Path resolved = resolveOnPath(tokens.get(0));
            command.add(resolved == null ? tokens.get(0) : resolved.toAbsolutePath().toString());
        }
        command.addAll(tokens.subList(1, tokens.size()));
        return needsShell(command.get(0)) ? withShell(command) : command;
    }

    private static boolean needsShell(String executable) {
        String lower = executable.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cmd") || lower.endsWith(".bat");
    }

    private static List<String> withShell(List<String> command) {
        List<String> wrapped = new ArrayList<>(command.size() + 2);
        wrapped.add("cmd.exe");
        wrapped.add("/c");
        wrapped.addAll(command);
        return wrapped;
    }

    /** 在 PATH 中查找可执行文件；Windows 需要补全 .exe/.cmd/.bat 后缀。 */
    static Path resolveOnPath(String name) {
        boolean windows = HostOs.isWindows();
        return resolveOnPath(name, effectivePath(), windows);
    }

    static Path resolveOnPath(String name, String path, boolean windows) {
        var directories = ExecutablePath.directories(path, String::trim);
        // Windows 上只看带后缀的文件：Maven 的 bin 目录同时有 Unix 脚本 mvn 和 mvn.cmd，
        // 直接执行 Unix 脚本会报 "不是有效的 Win32 应用程序"
        String[] names = windows ? new String[] {name + ".exe", name + ".cmd", name + ".bat"} : new String[] {name};
        return ExecutablePath.candidates(directories, names).filter(Files::isRegularFile).findFirst().orElse(null);
    }

    /** PATH 前面先放内置的 Python/Node，再放当前 JDK，避免用到系统里另一套解释器。 */
    private static String effectivePath() {
        String base = System.getenv("PATH");
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            base = joinPath(Path.of(javaHome, "bin").toString(), base);
        }
        return joinPath(RuntimeEnvironment.bundledBinPath().strip(), base);
    }

    private static String joinPath(String front, String rest) {
        if (front == null || front.isBlank()) {
            return rest == null ? "" : rest;
        }
        rest = stripPrefix(rest, front);
        if (rest == null || rest.isBlank()) {
            return front;
        }
        return front + File.pathSeparator + rest;
    }

    private static String stripPrefix(String path, String prefix) {
        if (path == null || path.isBlank() || prefix == null || prefix.isBlank()) {
            return path == null ? "" : path;
        }
        String head = prefix + File.pathSeparator;
        return path.startsWith(head) ? path.substring(head.length()) : path.equals(prefix) ? "" : path;
    }

    private Path findWrapper(String baseName) {
        boolean windows = HostOs.isWindows();
        List<String> names = windows ? List.of(baseName + ".cmd", baseName + ".bat") : List.of(baseName);
        for (String name : names) {
            Path candidate = paths.root().resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static long timeout(Map<String, Object> args, boolean background) {
        Object value = args == null ? null : args.get("timeoutSeconds");
        long fallback = background ? 0 : DEFAULT_TIMEOUT_MS / 1000;
        long seconds = fallback;
        if (value instanceof Number number) {
            seconds = number.longValue();
        } else if (value != null) {
            try {
                seconds = Long.parseLong(value.toString().trim());
            } catch (NumberFormatException ignored) {
                seconds = fallback;
            }
        }
        if (seconds <= 0) {
            return background ? 0 : DEFAULT_TIMEOUT_MS;
        }
        return Math.min(seconds, MAX_TIMEOUT_MS / 1000) * 1000L;
    }

    private record Prepared(List<String> command, List<String> displayTokens, Path workdir, boolean background,
        long timeoutMs, boolean trackChanges, boolean readOnly, Map<String, String> env) {
    }

    private record Preparation(Prepared prepared, String error) {
        private static Preparation error(String message) {
            return new Preparation(null, message);
        }
    }

    private record ManagedRun(String text, boolean terminal) {
    }

    /** 支持单双引号的最小分词器：不展开变量，不做通配符。 */
    static List<String> tokenize(String raw) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean hasToken = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                hasToken = true;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (hasToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
                continue;
            }
            current.append(ch);
            hasToken = true;
        }
        if (hasToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static byte[] readAll(Process process) {
        try (InputStream stream = process.getInputStream()) {
            return stream.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static byte[] await(CompletableFuture<byte[]> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return new byte[0];
        }
    }

    private static int exitCode(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    /** 优先按 UTF-8 严格解码；失败时回退 GBK（Windows 中文控制台常见）。 */
    static String decode(byte[] bytes) {
        return ProcessSupport.decode(bytes);
    }

    private static String redact(String text) {
        String result = text;
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String value = entry.getValue();
            if (value == null || value.length() < 8 || !SECRET_KEY.matcher(entry.getKey()).find()) {
                continue;
            }
            if (result.contains(value)) {
                result = result.replace(value, "***");
            }
        }
        return result;
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        String head = text.substring(0, HEAD_CHARS);
        String tail = text.substring(text.length() - (MAX_OUTPUT_CHARS - HEAD_CHARS));
        return head + "\n… 输出已截断（共 " + text.length() + " 字符）…\n" + tail;
    }
}
