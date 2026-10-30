package com.pilot.core.tool;

import com.pilot.core.multi.AgentScope;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.workspace.PathValidator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
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

/**
 * 在工作区内执行受限命令，用于构建与测试验证。
 *
 * <p>安全边界：
 * <ul>
 *   <li>只允许白名单可执行文件；仓库内标准构建 wrapper 可带 {@code ./} 前缀；</li>
 *   <li>不经过 shell，禁止 {@code & | ; < > ^ `$(} 等元字符，避免串联命令；</li>
 *   <li>{@code git} 允许只读子命令与 {@code add}/{@code commit}，其余写操作需人工执行；</li>
 *   <li>强制超时、输出上限，并对疑似密钥的环境变量值做脱敏。</li>
 * </ul>
 *
 * <p>该工具不是只读工具，因此 {@code READ_ONLY} 模式一律拒绝、{@code ASK} 模式逐次审批。
 */
public final class RunCommandTool implements Tool, ProducesCheck {
    private static final Set<String> ALLOWED_EXECUTABLES = Set.of(
            "mvn", "mvnw", "gradle", "gradlew", "git", "java", "javac",
            "node", "npm", "npx", "pnpm", "python", "python3", "pytest", "dotnet", "go", "cargo", "rustc", "tsc");

    private static final Set<String> GIT_READONLY = Set.of(
            "status", "diff", "log", "show", "branch", "rev-parse", "ls-files", "describe", "blame", "remote");

    /**
     * 允许的写操作只有暂存与提交：两者都能用工作区快照或 {@code git revert} 恢复。
     * push / reset / clean / checkout 等会改写远端或不可逆地丢文件，仍要求人工执行。
     */
    private static final Set<String> GIT_WRITE = Set.of("add", "commit");
    private static final Set<String> INSPECTION_FLAGS = Set.of(
            "-v", "--version", "-version", "version", "-h", "--help", "help");

    private static final Pattern SHELL_META = Pattern.compile("[&|;<>^`\\n\\r]|\\$\\(");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)(key|token|secret|password|credential|passwd)");
    private static final Pattern EXIT_CODE = Pattern.compile("^退出码: (-?\\d+)", Pattern.MULTILINE);

    private static final int MAX_TOKENS = 64;
    private static final int MAX_OUTPUT_CHARS = 12_000;
    private static final int HEAD_CHARS = 4_000;
    private static final long DEFAULT_TIMEOUT_MS = 120_000L;
    private static final long MAX_TIMEOUT_MS = 600_000L;
    private static final Charset GBK = Charset.forName("GBK");

    private final PathValidator paths;
    private final CommandTaskManager commandTasks;
    private final String sessionId;
    private final CommandChangeTracker changes;

    public RunCommandTool(PathValidator paths) {
        this(paths, null, null, CommandChangeTracker.none());
    }

    public RunCommandTool(PathValidator paths, CommandChangeTracker changes) {
        this(paths, null, null, changes);
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
        return "run_command";
    }

    @Override
    public String description() {
        return PromptLibrary.standard().render("tools/run_command", Map.of(
                "executables", String.join("/", ALLOWED_EXECUTABLES.stream().sorted().toList()),
                "gitReadonly", String.join("/", GIT_READONLY.stream().sorted().toList()),
                "gitWrite", String.join("/", GIT_WRITE.stream().sorted().toList()),
                "backgroundHelp", commandTasks == null ? "本运行环境仅支持同步执行。" :
                        "background=true 会立即返回任务 ID，适合开发服务器或长测试。后台默认不设超时；显式 timeoutSeconds 最大 600。"
                                + "用 command_status 查询实际状态、command_logs 增量读取日志、stop_command 停止进程树。"
                                + "启动成功不代表验证通过；测试必须等到完成并检查退出码。")).strip();
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("command", Map.of("type", "string",
                "description", "完整命令，例如 \"mvn -q test\" 或 \"git status --porcelain\""));
        properties.put("workdir", Map.of("type", "string",
                "description", "可选，工作区内的工作目录，例如 pilot-web；默认工作区根目录"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "minimum", 0, "maximum", 600,
                "description", "前台默认 120 秒，后台默认 0（不设超时），最大 600 秒"));
        if (commandTasks != null) properties.put("background", Map.of("type", "boolean", "default", false,
                "description", "后台运行并立即返回任务 ID；默认 false，等待命令结束"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("command"),
                "additionalProperties", false);
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String execute(Map<String, Object> args) {
        return executeResult(args).text();
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        Preparation preparation = prepare(args);
        if (preparation.error() != null) return ToolResult.text(preparation.error());
        Prepared prepared = preparation.prepared();
        CommandChangeTracker.Handle tracking;
        try {
            tracking = prepared.trackChanges() ? changes.begin(paths.root()) : CommandChangeTracker.Handle.none();
        } catch (RuntimeException e) {
            return ToolResult.text("ERROR: 无法建立命令前文件基线，命令未执行：" + redact(String.valueOf(e.getMessage())));
        }

        String text;
        boolean terminal;
        if (commandTasks == null) {
            text = run(prepared.command(), prepared.displayTokens(), prepared.workdir(), prepared.timeoutMs());
            terminal = true;
        } else {
            ManagedRun managed = runManaged(prepared.command(), prepared.displayTokens(), prepared.workdir(),
                    prepared.background(), prepared.timeoutMs(), tracking);
            text = managed.text();
            terminal = managed.terminal();
        }
        if (!terminal || !tracking.active()) return ToolResult.text(text);
        CommandChangeTracker.Result result;
        try {
            result = changes.finish(tracking);
        } catch (RuntimeException e) {
            result = new CommandChangeTracker.Result(List.of(), false, String.valueOf(e.getMessage()));
        }
        if (!result.complete()) {
            text = "ERROR: 命令已结束，但无法完整确认其工作区变更；不得据此宣称验证通过："
                    + result.warning() + "\n" + text;
        }
        return new ToolResult(text, result.changedPaths(), List.of());
    }

    private Preparation prepare(Map<String, Object> args) {
        String raw = ToolSupport.stringArg(args, "command");
        if (raw == null) {
            return Preparation.error("ERROR: 缺少参数 command");
        }
        if (SHELL_META.matcher(raw).find()) {
            return Preparation.error("ERROR: 命令包含不允许的 shell 元字符（& | ; < > ^ ` $( 或换行），请一次只执行一条命令。");
        }

        List<String> tokens = tokenize(raw);
        if (tokens.isEmpty()) {
            return Preparation.error("ERROR: 命令为空");
        }
        if (tokens.size() > MAX_TOKENS) {
            return Preparation.error("ERROR: 参数过多（上限 " + MAX_TOKENS + " 个）");
        }

        String executable = tokens.get(0).toLowerCase(Locale.ROOT);
        if (Set.of("./mvnw", ".\\mvnw.cmd").contains(executable)) executable = "mvnw";
        if (Set.of("./gradlew", ".\\gradlew.bat").contains(executable)) executable = "gradlew";
        if (!executable.equals(tokens.get(0).toLowerCase(Locale.ROOT))) {
            tokens = new ArrayList<>(tokens);
            tokens.set(0, executable);
        }
        if (executable.contains("/") || executable.contains("\\")) {
            return Preparation.error("ERROR: 只接受白名单内的裸命令名或标准构建 wrapper，不接受路径: " + tokens.get(0));
        }
        if (!ALLOWED_EXECUTABLES.contains(executable)) {
            return Preparation.error("ERROR: 命令不在白名单内: " + tokens.get(0)
                    + "。可用: " + String.join(", ", ALLOWED_EXECUTABLES.stream().sorted().toList()));
        }
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
        AgentScope scope = paths.scope();
        if (scope != null && !scope.allowsCommandArgs(tokens)) {
            return Preparation.error("ERROR: " + scope.commandHint(raw));
        }
        String selectorProblem = packageDirectoryProblem(tokens, workdir);
        if (selectorProblem != null) return Preparation.error("ERROR: " + selectorProblem);
        boolean trackChanges = !(tokens.size() == 2 && INSPECTION_FLAGS.contains(tokens.get(1)));
        if ("git".equals(executable)) {
            String sub = tokens.size() > 1 ? tokens.get(1).toLowerCase(Locale.ROOT) : "";
            if (GIT_READONLY.contains(sub)) {
                trackChanges = false;
            } else if (GIT_WRITE.contains(sub)) {
                if (scope != null && !scope.writable()) {
                    return Preparation.error("ERROR: 只读任务不允许 git " + sub + "，改动请通过沙箱写工具提交");
                }
                String problem = checkGitWrite(tokens, sub);
                if (problem != null) {
                    return Preparation.error("ERROR: " + problem);
                }
            } else {
                return Preparation.error("ERROR: git 只允许只读子命令与 add/commit: "
                        + String.join(", ", GIT_READONLY.stream().sorted().toList())
                        + "，以及 add/commit；push/reset/clean/checkout 等仍需人工执行。");
            }
        }

        Object backgroundArg = args == null ? null : args.get("background");
        if (backgroundArg != null && !(backgroundArg instanceof Boolean)) {
            return Preparation.error("ERROR: background 必须是布尔值");
        }
        boolean background = Boolean.TRUE.equals(backgroundArg);
        if (background && commandTasks == null) return Preparation.error("ERROR: 当前运行环境不支持后台命令");
        List<String> command = withWrapper(tokens);
        long timeoutMs = timeout(args, background);
        return new Preparation(new Prepared(command, tokens, workdir, background, timeoutMs, trackChanges), null);
    }

    /** Package-manager directory selectors must stay inside the same workspace boundary as workdir. */
    private String packageDirectoryProblem(List<String> tokens, Path workdir) {
        if (!Set.of("npm", "pnpm", "yarn", "bun").contains(tokens.getFirst().toLowerCase(Locale.ROOT))) return null;
        for (int index = 1; index < tokens.size(); index++) {
            String token = tokens.get(index);
            String selector = null;
            String value = null;
            if (Set.of("--prefix", "--dir", "--cwd", "-C").contains(token)) {
                selector = token;
                if (++index >= tokens.size()) return selector + " 缺少目录";
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
            if (selector == null) continue;
            if (value.isBlank() || value.startsWith("-")) return selector + " 缺少目录";
            try {
                Path selected = paths.resolve(workdir.resolve(value).toString());
                if (!Files.isDirectory(selected)) return selector + " 不是现有工作区目录：" + value;
            } catch (RuntimeException e) {
                return selector + " 必须指向工作区内的目录：" + value;
            }
        }
        return null;
    }

    /** 提交必须自带消息（否则会拉起编辑器卡到超时），且只允许操作工作区内的相对路径。 */
    private static String checkGitWrite(List<String> tokens, String sub) {
        boolean hasMessage = tokens.stream().anyMatch(token ->
                token.equals("-m") || token.equals("--message") || token.startsWith("--message=")
                        || token.equals("-F") || token.equals("--file"));
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
            boolean absolute = token.startsWith("/") || token.startsWith("\\")
                    || token.matches("^[A-Za-z]:.*");
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
        if (commandTasks != null) return CommandToolSupport.checkSummary(commandTasks, sessionId, result);
        String command = call == null || call.args() == null ? "" : String.valueOf(call.args().getOrDefault("command", ""));
        if (result.startsWith("ERROR: 命令已结束，但无法完整确认其工作区变更")) {
            return ToolSupport.oneLine(command, 90) + " → CHANGE_TRACKING_INCOMPLETE";
        }
        Matcher matcher = EXIT_CODE.matcher(result);
        if (matcher.find()) {
            return ToolSupport.oneLine(command, 90) + " → exit " + matcher.group(1);
        }
        // 超时、启动失败没有退出码，但同样是「验证没通过」，要留在记录里
        if (result.startsWith("ERROR")) {
            return ToolSupport.oneLine(command, 90) + " → " + ToolSupport.oneLine(result, 80);
        }
        return null;
    }

    private ManagedRun runManaged(List<String> command, List<String> displayTokens, Path workdir, boolean background,
                                  long timeoutMs, CommandChangeTracker.Handle tracking) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workdir.toFile()).redirectErrorStream(true);
        useCurrentJdk(builder.environment());
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
        try {
            while (CommandToolSupport.running(task)) {
                Thread.sleep(50);
                task = commandTasks.get(sessionId, taskId);
            }
        } catch (InterruptedException e) {
            commandTasks.stop(sessionId, taskId);
            // 中断调用方前，给进程树的协作终止和强制清理留出有界时间。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (CommandToolSupport.running(task) && System.nanoTime() < deadline) {
                try { Thread.sleep(25); } catch (InterruptedException ignored) { }
                task = commandTasks.get(sessionId, taskId);
            }
            Thread.currentThread().interrupt();
            return new ManagedRun("ERROR: 命令执行被中断\n" + CommandToolSupport.render(task), true);
        }
        if (task == null) return new ManagedRun("ERROR: 命令记录已清理，无法确认最终结果：" + taskId, true);
        CommandTaskManager.LogChunk chunk = commandTasks.logs(sessionId, taskId, 0, MAX_OUTPUT_CHARS);
        String rendered = CommandToolSupport.render(task);
        if (chunk != null) rendered += "\n输出:\n" + chunk.text()
                + (chunk.truncated() || !chunk.eof() ? "\n… 后续日志使用 command_logs，offset=" + chunk.nextOffset() : "");
        return new ManagedRun(rendered, true);
    }

    private String run(List<String> command, List<String> displayTokens, Path workdir, long timeoutMs) {
        long startedAt = System.nanoTime();
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workdir.toFile());
        builder.redirectErrorStream(true);
        useCurrentJdk(builder.environment());

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
        String rendered = render(String.join(" ", displayTokens), command,
                finished ? exitCode(process) : -1, latencyMs, text);
        rendered = "工作目录: " + workdir + "\n" + rendered;
        return finished ? rendered : "ERROR: 命令超时（" + (timeoutMs / 1000) + " 秒）已被终止\n" + rendered;
    }

    /**
     * 子进程继承服务进程的环境变量，其中 {@code JAVA_HOME} 可能指向更老的 JDK
     * （例如系统默认 17），导致 {@code mvn test} 无法加载本项目编译出的 Java 24 字节码。
     *
     * <p>Pilot 自己必然运行在项目要求的 JDK 上，因此把 {@code JAVA_HOME} 与 PATH 首项
     * 都指向当前运行时的 JDK，保证构建命令看到的是同一个 Java。
     */
    private static void useCurrentJdk(Map<String, String> env) {
        String pathKey = env.keySet().stream()
                .filter(key -> key.equalsIgnoreCase("PATH"))
                .findFirst()
                .orElse("PATH");
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path bin = Path.of(javaHome, "bin");
            env.put("JAVA_HOME", javaHome);
            String current = env.getOrDefault(pathKey, "");
            env.put(pathKey, joinPath(bin.toString(), stripPrefix(current, bin.toString())));
        }
        env.put(pathKey, joinPath(runtimeBins(), env.getOrDefault(pathKey, "")));
    }

    private static String render(String displayCommand, List<String> actual, int exitCode,
                                 long latencyMs, String output) {
        StringBuilder out = new StringBuilder()
                .append("命令: ").append(displayCommand).append('\n')
                .append("退出码: ").append(exitCode).append('\n')
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
            boolean unix = !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            if (unix && !Files.isExecutable(wrapper)) command.add("/bin/sh");
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
        String pathEnv = effectivePath();
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        // Windows 上只看带后缀的文件：Maven 的 bin 目录同时有 Unix 脚本 mvn 和 mvn.cmd，
        // 直接执行 Unix 脚本会报 "不是有效的 Win32 应用程序"
        List<String> extensions = windows ? List.of(".exe", ".cmd", ".bat") : List.of("");
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            for (String extension : extensions) {
                try {
                    Path candidate = Path.of(dir.trim(), name + extension);
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                } catch (RuntimeException ignored) {
                    // 跳过非法 PATH 项
                }
            }
        }
        return null;
    }

    /** PATH 前面先放内置的 Python/Node，再放当前 JDK，避免用到系统里另一套解释器。 */
    private static String effectivePath() {
        String base = System.getenv("PATH");
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            base = joinPath(Path.of(javaHome, "bin").toString(), base);
        }
        return joinPath(runtimeBins(), base);
    }

    static String runtimeBins() {
        String extra = System.getProperty("pilot.runtime.bin");
        return extra == null ? "" : extra.strip();
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
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> names = windows
                ? List.of(baseName + ".cmd", baseName + ".bat")
                : List.of(baseName);
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
        if (seconds <= 0) return background ? 0 : DEFAULT_TIMEOUT_MS;
        return Math.min(seconds, MAX_TIMEOUT_MS / 1000) * 1000L;
    }

    private record Prepared(List<String> command, List<String> displayTokens, Path workdir, boolean background,
                            long timeoutMs, boolean trackChanges) { }

    private record Preparation(Prepared prepared, String error) {
        private static Preparation error(String message) {
            return new Preparation(null, message);
        }
    }

    private record ManagedRun(String text, boolean terminal) { }

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
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, GBK);
        }
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
