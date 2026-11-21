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
    /** Windows 上常见的 Unix 文件命令：没有对应的可执行文件，原样重试不会变好。 */
    private static final List<String> UNIX_FILE_COMMANDS = List.of("ls", "cat", "head", "tail", "find", "dir");
    /** 各平台都可能有、但未必安装的搜索命令：缺失时改用 search_text 更可靠。 */
    private static final List<String> SEARCH_COMMANDS = List.of("rg", "grep");
    /** 启动失败里代表「可执行文件不存在」的两种写法（Windows 与 POSIX）。 */
    private static final Pattern MISSING_EXECUTABLE = Pattern.compile("CreateProcess error=2|Cannot run program");
    /**
     * Windows PowerShell 5.1 链式脚本里每个管道单元执行后的退出码捕获。必须先置 null 的
     * {@code $global:LASTEXITCODE}：块内赋值不加 {@code global:} 只会建块内局部变量，原生命令写入的值盖不住，
     * 而 5.1 里原生命令非 0 退出时 {@code $?} 仍为 True。置 null 后若仍为空，说明命令没启动（找不到可执行文件），
     * 此时看 {@code $?}。
     */
    private static final String EXIT_CODE_CAPTURE =
        "; $script:pilotCode = if ($null -ne $global:LASTEXITCODE) { $global:LASTEXITCODE }"
            + " elseif ($?) { 0 } else { 1 }";
    /** 连续到第几次同类缺失命令时要求换工具。 */
    private static final int MISSING_COMMAND_ESCALATE_AT = 2;
    /** 连续缺失文件命令导致的启动失败次数；换成其他失败就清零。 */
    private int missingCommandFailures;
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
    private static final Set<String> FILE_DUMPS =
        Set.of("cat", "nl", "head", "tail", "sed", "bat", "less", "more", "type", "get-content", "gc");

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
                Map.of("inspection", inspectionList(), "development",
                    String.join("/", CommandPolicy.DEVELOPMENT.stream().sorted().toList()), "mutators",
                    String.join("/", CommandPolicy.FILE_MUTATORS.stream().sorted().toList()), "gitReadonly",
                    String.join("/", CommandPolicy.GIT_READONLY.stream().sorted().toList()), "gitWrite",
                    String.join("/", CommandPolicy.GIT_WRITE.stream().sorted().toList()), "backgroundHelp",
                    commandTasks == null ? "本运行环境仅支持同步执行。"
                        : "background=true 会立即返回任务 ID，适合开发服务器或长测试。后台默认不设超时；显式 timeoutSeconds 最大 600。"
                            + "用 command_status 查询实际状态、command_logs 增量读取日志、stop_command 停止进程树。"
                            + "启动成功不代表验证通过；测试必须等到完成并检查退出码。" + windowsFileCommandNote()))
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
            properties.put("interactive", Map.of("type", "boolean", "default", false,
                "description", "保留标准输入，启动后用 command_input 写输入（需要 background=true 且只有单条命令）；默认 false"));
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
                prepared.background(), prepared.timeoutMs(), tracking, prepared.env(), prepared.interactive());
            text = managed.text();
            terminal = managed.terminal();
        }
        // 三平台都在结果尾部标明这次交给谁执行：直接启动、cmd、PowerShell 或 /bin/sh。
        // 必须追加而不是加在开头：结果以 ERROR: 开头是全仓判断失败的口径（succeeded、DocumentIntake 等）。
        if (!prepared.shell().isEmpty() && !text.isEmpty()) {
            text = text + "\nShell: " + prepared.shell();
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
        } else if (!result.warning().isBlank()) {
            text = text + "\n\n" + ToolResults.CHANGE_TRACKING_PARTIAL + "（" + result.warning() + "）";
        }
        return new ToolResult(withTrackingWarning(text, trackingWarning), result.changedPaths(), List.of());
    }

    /** 整文件已经通过 read_file 完整交付且未变化、未因压缩失效时，不再用 cat/type/Get-Content 重复倾倒同一份全文。 */
    private String repeatedLook(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        for (Path file : dumpedFiles(command)) {
            String stamp = reads.observationStamp(file);
            if (!stamp.isEmpty() && reads.isFullyDelivered(file, stamp)) {
                String ranges = ReadGuard.LineRange.formatAll(reads.deliveredRanges(file, stamp));
                return "文件未变化，沿用刚才通过 read_file 读过的 " + paths.display(file) + "（已交付区间：" + ranges
                    + "）。不要再重复倾倒全文；如需查看特定片段请用 read_file 指定行号，或改看别的文件。";
            }
        }
        return null;
    }

    private List<Path> dumpedFiles(String command) {
        String[] tokens = command.strip().split("\\s+");
        if (tokens.length == 0 || !FILE_DUMPS.contains(CommandPolicy.leaf(tokens[0]).toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        for (int index = 1; index < tokens.length; index++) {
            String token = tokens[index];
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
        Object interactiveArg = args == null ? null : args.get("interactive");
        if (interactiveArg != null && !(interactiveArg instanceof Boolean)) {
            return Preparation.error("ERROR: interactive 必须是布尔值");
        }
        boolean interactive = Boolean.TRUE.equals(interactiveArg);
        if (interactive) {
            if (commandTasks == null) {
                return Preparation.error("ERROR: 当前运行环境不支持交互式命令");
            }
            if (!background) {
                // 前台要等命令结束才返回，模型没有机会在中间写输入，只会等到超时。
                return Preparation.error("ERROR: interactive 只能配 background=true；前台调用等不到输入");
            }
            if (chained || redirected) {
                return Preparation.error("ERROR: interactive 只支持单条命令，不能串联、管道或重定向");
            }
        }

        if (!chained && !redirected) {
            SegmentCheck only = checks.getFirst();
            List<String> command = withWrapper(only.tokens());
            return new Preparation(new Prepared(command, only.tokens(), workdir, background,
                timeoutMs, trackChanges, readOnly, only.env(), interactive, shellLabel(command)), null);
        }
        // 串联或重定向才进 shell。每段仍用解析出的绝对路径，shell 只负责连接符和已经校验过的重定向。
        List<List<String>> checked = checks.stream().map(SegmentCheck::tokens).toList();
        String display = String.join(" ", segmentsDisplay(checked, operators));
        if (WINDOWS) {
            List<List<String>> resolved = withWrapperSegments(checked);
            Path pwsh = resolveOnPath("pwsh");
            Path shell = pwsh != null ? pwsh : resolveOnPath("powershell");
            if (shell == null) {
                return Preparation.error("ERROR: Windows 上执行命令需要 PowerShell（powershell.exe 或 pwsh.exe），当前都没找到");
            }
            boolean shortCircuit = operators.contains("&&") || operators.contains("||");
            String scriptText = shortCircuit ? chainScript(resolved, checks, operators, segments)
                : script(resolved, checks, operators, segments);
            List<String> commandLine =
                List.of(shell.toString(), "-NoProfile", "-NonInteractive", "-Command", scriptText);
            return new Preparation(new Prepared(commandLine, List.of(display), workdir, background, timeoutMs,
                trackChanges, readOnly, Map.of(), false, shellLabel(commandLine)), null);
        }
        String scriptText = script(withWrapperSegments(checked), checks, operators, segments);
        return new Preparation(new Prepared(List.of("/bin/sh", "-c", scriptText), List.of(display), workdir, background,
            timeoutMs, trackChanges, readOnly, Map.of(), false, shellLabel(List.of("/bin/sh"))), null);
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
            out.append(segmentScript(resolved.get(i), checks.get(i), segments.get(i)));
        }
        return out.toString();
    }

    /**
     * PowerShell 5.1 不认 {@code &&}/{@code ||}，而 7 不能从包裹命令的脚本块可靠地返回原生退出码。
     * 两个版本统一按左结合改写成判断上一条执行结果的 if 分支。
     * 跳过不执行的段不会改写 {@code $pilotCode}，所以连续多个连接符仍然是左结合语义（{@code a && b || c}
     * 在 a 失败时会执行 c）。脚本结尾用 {@code exit $pilotCode} 把最后一段的退出码交回去。
     */
    private static String chainScript(List<List<String>> resolved, List<SegmentCheck> checks, List<String> operators,
        List<CommandChain.Segment> segments) {
        List<String> units = new ArrayList<>();
        List<String> gates = new ArrayList<>();
        StringBuilder unit = new StringBuilder(segmentScript(resolved.get(0), checks.get(0), segments.get(0)));
        for (int i = 1; i < resolved.size(); i++) {
            String operator = operators.get(i - 1);
            String text = segmentScript(resolved.get(i), checks.get(i), segments.get(i));
            if (operator.equals("|")) {
                unit.append(" | ").append(text);
                continue;
            }
            units.add(unit.toString());
            gates.add(operator);
            unit = new StringBuilder(text);
        }
        units.add(unit.toString());
        return chainScript(units, gates);
    }

    /** 把已经转义好的段脚本拼成 PowerShell 5.1 能执行的链式脚本；{@code gates.get(k-1)} 是第 k 段前的连接符。 */
    static String chainScript(List<String> units, List<String> gates) {
        StringBuilder out = new StringBuilder("$pilotCode = 0; ");
        for (int i = 0; i < units.size(); i++) {
            String gate = i == 0 ? null : gates.get(i - 1);
            boolean gated = "&&".equals(gate) || "||".equals(gate);
            if (gated) {
                out.append("if ($pilotCode ").append(gate.equals("&&") ? "-eq" : "-ne").append(" 0) { ");
            }
            // 捕获放在管道单元外层：块内的 $? 会恒为 True，而管道里各段并发执行，内层赋值不可靠。
            out.append("& { $global:LASTEXITCODE = $null; ").append(units.get(i)).append(EXIT_CODE_CAPTURE)
                .append(" }");
            if (gated) {
                out.append(" }");
            }
            out.append("; ");
        }
        return out.append("exit $pilotCode").toString();
    }

    /** 单段命令的脚本文本：Windows 用 PowerShell 引号与脚本块，其他平台用 POSIX 引号。 */
    private static String segmentScript(List<String> tokens, SegmentCheck check, CommandChain.Segment segment) {
        StringBuilder out = new StringBuilder();
        if (WINDOWS) {
            // 脚本块把环境变量限制在这一段，避免前缀泄漏到后面的命令。
            out.append("& { ");
            check.env().forEach(
                (name, value) -> out.append("$env:").append(name).append('=').append(psQuote(value)).append("; "));
            out.append("& ").append(psQuote(tokens.get(0)));
        } else {
            check.env()
                .forEach((name, value) -> out.append(name).append('=').append(CommandChain.quote(value)).append(' '));
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
        if (segment.mergeStderr()) {
            out.append(" 2>&1");
        }
        if (WINDOWS) {
            out.append(" }");
        }
        return out.toString();
    }

    /** PowerShell 单引号字面量；内部的单引号写成两个。 */
    private static String psQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
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
        String command = CommandToolSupport.summarizeCommand(
            call == null || call.args() == null ? "" : String.valueOf(call.args().getOrDefault("command", "")));
        if (ToolResults.isChangeTrackingIncomplete(result)) {
            return command + " → CHANGE_TRACKING_INCOMPLETE";
        }
        Matcher matcher = EXIT_CODE.matcher(result);
        if (matcher.find()) {
            return command + " → exit " + matcher.group(1);
        }
        // 超时、启动失败没有退出码，但同样是「验证没通过」，要留在记录里
        if (ToolResults.isError(result)) {
            return command + " → " + Texts.oneLine(result, 80);
        }
        return null;
    }

    private ManagedRun runManaged(List<String> command, List<String> displayTokens, Path workdir, boolean background,
        long timeoutMs, CommandChangeTracker.Handle tracking, Map<String, String> env, boolean interactive) {
        CommandLaunch launch = launch(command, workdir, env);
        if (launch.builder() == null) {
            return new ManagedRun("ERROR: " + launch.label(), true);
        }
        ProcessBuilder builder = launch.builder();
        CommandTaskManager.Task task;
        try {
            task = commandTasks.start(sessionId, String.join(" ", displayTokens), builder, background, timeoutMs,
                tracking.active() ? tracking : null, interactive);
        } catch (RuntimeException e) {
            return new ManagedRun(launchFailure(command, displayTokens, String.valueOf(e.getMessage())), true);
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
        String rendered = quickFailure(task) + CommandToolSupport.render(task);
        String sandboxHint = CommandToolSupport.nestedSandboxHint(commandTasks, task);
        if (!sandboxHint.isEmpty()) {
            rendered = sandboxHint + "\n" + rendered;
        }
        rendered += formatCompletedOutput(taskId);
        return new ManagedRun(launch.label() + "\n" + rendered, true);
    }

    /**
     * 已结束命令的首次日志视图：短输出原样展示；超过 6,000 字符时直接同时展示开头与结尾（Maven/JUnit/Node 的
     * 汇总与失败栈都在尾部），避免模型为了看结尾的 BUILD SUCCESS/FAILURE 连续翻多次 command_logs。
     */
    private String formatCompletedOutput(String taskId) {
        long total = commandTasks.logLength(sessionId, taskId);
        if (total <= 6_000L) {
            CommandTaskManager.LogChunk chunk = commandTasks.logs(sessionId, taskId, 0, MAX_OUTPUT_CHARS);
            if (chunk == null) {
                return "";
            }
            return "\n输出:\n" + chunk.text()
                + (chunk.truncated() || !chunk.eof() ? "\n… 后续日志使用 command_logs，offset=" + chunk.nextOffset() : "");
        }
        int headLimit = 1_800;
        int tailLimit = 2_400;
        CommandTaskManager.LogChunk head = commandTasks.logs(sessionId, taskId, 0, headLimit);
        long tailOffset = Math.max(headLimit, total - tailLimit);
        CommandTaskManager.LogChunk tail = commandTasks.logs(sessionId, taskId, tailOffset, tailLimit + 200);
        if (head == null && tail == null) {
            return "";
        }
        String headText = head == null ? "" : head.text().replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
        String tailText = tail == null ? "" : tail.text().replace("\r\n", "\n").replace('\r', '\n').stripLeading();
        long omitted = Math.max(0L, tailOffset - (head == null ? 0L : head.nextOffset()));
        StringBuilder out = new StringBuilder("\n输出（完整日志共 ").append(total).append(" 字符，已展示开头与结尾）:\n");
        out.append("[输出开头]\n").append(headText);
        if (omitted > 0) {
            out.append("\n\n…（中间省略约 ").append(omitted).append(" 字符；如需查看中间日志请用 command_logs，offset=")
                .append(head == null ? headLimit : head.nextOffset()).append("）…\n\n");
        } else {
            out.append('\n');
        }
        out.append("[输出结尾]\n").append(tailText);
        return out.toString();
    }

    private static String handoff(CommandTaskManager.Task task, ForegroundPace.Release release) {
        String reason = release == ForegroundPace.Release.SILENT
            ? "这条命令已经有一段时间没有新输出，不像测试或构建那种正常变慢。已转到后台，没有杀掉。" + "先用 command_logs 看它停在哪，再用 command_status 确认。不要再启动一条同样的命令。"
            : "前台已经等了 2 分钟，命令还在跑，已转到后台。到设置的时限才会停。" + "用 command_status 看结果，不要再启动一条同样的命令。";
        return reason + "\n" + CommandToolSupport.render(task);
    }

    /** 仅对极快触发的命令/语法/可执行文件错误（exit 2/126/127）给调用方式提示，不误报正常的测试/lint/grep exit 1。 */
    private static String quickFailure(CommandTaskManager.Task task) {
        if (task == null || task.exitCode() == null || task.startedAt() == null || task.finishedAt() == null) {
            return "";
        }
        int code = task.exitCode();
        if (code != 2 && code != 126 && code != 127) {
            return "";
        }
        long took = java.time.Duration.between(task.startedAt(), task.finishedAt()).toMillis();
        if (took < 0 || took > 1_500L) {
            return "";
        }
        return "这条命令很快就失败了，多半是调用方式不对。先根据下面的错误改命令，不要当成还在运行。\n";
    }

    /** 可用命令清单；Windows 上不把 Unix 文件命令写成可用。 */
    private static String inspectionList() {
        return String.join("/", CommandPolicy.INSPECTION.stream()
            .filter(name -> !WINDOWS || !UNIX_FILE_COMMANDS.contains(name)).sorted().toList());
    }

    /** Windows 上没有这些 Unix 文件命令，工具描述里直接给出替代工具。 */
    private static String windowsFileCommandNote() {
        return WINDOWS ? "Windows 上 ls/cat/head/tail/find/dir 没有对应可执行文件，请改用 list_dir / read_file / search_text。" : "";
    }

    /** 取命令的叶子名（去掉目录与 .exe 后缀）。 */
    private static String executableName(String token) {
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        String name = slash >= 0 ? token.substring(slash + 1) : token;
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    /** 这次启动失败是不是「Unix 文件命令在当前系统上不存在」。 */
    static boolean missingFileCommand(String executable, String message) {
        boolean fileCommand = UNIX_FILE_COMMANDS.contains(executable) || SEARCH_COMMANDS.contains(executable);
        return fileCommand && MISSING_EXECUTABLE.matcher(message).find();
    }

    /** 缺可执行文件时的纠偏文本；连续第二次起要求换工具，不再原样重试。 */
    static String launchFailureText(String headline, String executable, int consecutiveFailures) {
        String hint = fileToolHint(executable);
        if (consecutiveFailures >= MISSING_COMMAND_ESCALATE_AT) {
            hint += "\n同类错误已经连续出现两次：不要原样重试，" + fileToolEscalation(executable);
        }
        return headline + "\n" + hint;
    }

    /** 按缺失的命令和当前系统给替代方式，三平台都给对应建议。 */
    private static String fileToolHint(String executable) {
        if (SEARCH_COMMANDS.contains(executable)) {
            return "当前环境找不到 " + executable + "，搜内容改用 search_text，找文件用 glob_files，不要再重试这个名字。";
        }
        if (WINDOWS) {
            return "Windows 上 ls/cat/head/tail/find/dir 没有对应可执行文件，请改用 list_dir / read_file / search_text，"
                + "不要再把它们当成可用命令。";
        }
        return "当前环境找不到 " + executable + "，列目录用 list_dir、读文件用 read_file，比依赖系统命令更可靠。";
    }

    /** 连续失败后的升级建议，同样按系统区分 shell 等价命令。 */
    private static String fileToolEscalation(String executable) {
        if (SEARCH_COMMANDS.contains(executable) || !WINDOWS) {
            return "不再依赖系统命令，直接用 list_dir / read_file / search_text 完成任务。";
        }
        return "换文件工具或 PowerShell 等价命令（Get-ChildItem、Get-Content、Select-String）。";
    }

    /**
     * 启动失败的结果文本。
     *
     * <p>Windows 上没有 ls/cat/head/tail/find/dir 这些 Unix 文件命令，
     * 结果里直接给出替代工具，避免模型拿同一条命令反复重试。
     */
    private String launchFailure(List<String> command, List<String> displayTokens, String message) {
        String headline = "ERROR: 无法启动命令 " + String.join(" ", displayTokens) + "：" + redact(message);
        String executable = command.isEmpty() ? "" : executableName(command.get(0));
        if (!missingFileCommand(executable, message)) {
            missingCommandFailures = 0;
            return headline;
        }
        missingCommandFailures++;
        return launchFailureText(headline, executable, missingCommandFailures);
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
            return launchFailure(command, displayTokens, String.valueOf(e.getMessage()));
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
        StringBuilder out = new StringBuilder();
        if (exitCode != 0) {
            String sandboxHint = CommandToolSupport.nestedSandboxHint(output, exitCode);
            if (!sandboxHint.isEmpty()) {
                out.append(sandboxHint).append('\n');
            }
        }
        out.append("命令: ").append(displayCommand).append('\n').append("退出码: ").append(exitCode).append('\n')
            .append("耗时: ").append(latencyMs).append(" ms\n");
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

    /**
     * 结果里展示的 shell 标识。直接启动、cmd 包装、PowerShell 与 POSIX sh 四种形态各自可辨，
     * 三平台都能看出这次到底把命令交给了谁——只看「实际执行」里的一串参数看不出这件事。
     */
    private static String shellLabel(List<String> command) {
        if (command.isEmpty()) return "直接执行";
        String head = executableName(command.get(0)).toLowerCase(Locale.ROOT);
        return switch (head) {
            case "cmd" -> "cmd";
            case "pwsh", "powershell" -> HostOs.isWindows() ? "PowerShell" : "pwsh";
            case "sh", "bash", "zsh", "dash" -> "/bin/sh";
            default -> "直接执行";
        };
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
        return resolveOnPath(name, effectivePath(), HostOs.isWindows());
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
        long timeoutMs, boolean trackChanges, boolean readOnly, Map<String, String> env, boolean interactive,
        String shell) {
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
                if (CommandChain.isDoubleQuoteEscape(quote, raw, i)) {
                    current.append(raw.charAt(++i));
                } else if (ch == quote) {
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
