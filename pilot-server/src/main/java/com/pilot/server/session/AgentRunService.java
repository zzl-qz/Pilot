package com.pilot.server.session;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.AssistantPreview;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.event.PlanUpdated;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunStarted;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.ToolRegistry;
import tools.jackson.databind.ObjectMapper;
import com.pilot.core.trace.JsonlTraceRecorder;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.ModelSettings;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.multi.DispatchAgentsTool;
import com.pilot.server.multi.MultiAgentService;
import com.pilot.server.workspace.RunSnapshotService;
import com.pilot.server.workspace.WorkspaceToolFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 在独立线程上执行 {@link AgentLoop}，把循环产生的事件写入会话。
 *
 * <p>对话记忆通过 {@code ConversationRepository} 落盘（默认 SQLite），每步结束都会 checkpoint，
 * 因此进程重启后可以用 {@code /continue} 接着跑；计划通过 {@code PlanRepository} 保存，
 * 并在每次运行时重新注入 system，保证压缩之后计划仍然可见。
 */
@Component
public class AgentRunService implements RunStarter {
    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);
    private static final int MAX_AUTO_CONTINUE = 3;
    /** 提示词正文在 {@code prompts/} 资源里，这里只做变量注入与拼接。 */
    private static final PromptLibrary PROMPTS = PromptLibrary.standard();
    /** 项目约定文件，按优先级取第一个存在的。 */
    private static final List<String> CONVENTION_FILES = List.of("PILOT.md", "AGENTS.md", "CLAUDE.md");
    private static final int MAX_CONVENTION_CHARS = 8_000;
    /** 仓库快照：顶层条目与未提交改动都限行，避免 system 被大仓库撑爆。 */
    private static final int MAX_TREE_ENTRIES = 30;
    private static final int MAX_GIT_CHANGES = 12;
    private static final long GIT_TIMEOUT_MS = 2_000L;
    private static final Set<String> IGNORED_ENTRIES = Set.of(
            ".git", ".pilot", ".idea", ".vscode", ".gradle", ".venv", "__pycache__",
            "node_modules", "target", "build", "dist", "out");

    private final SessionService sessions;
    private final ModelClient modelClient;
    private final WorkspaceToolFactory toolFactory;
    private final PermissionGate permissionGate;
    private final ApprovalBroker approvals;
    private final AgentProperties properties;
    private final ModelSettings modelSettings;
    private final RunSnapshotService snapshots;
    private final RunRegistry registry;
    private final com.pilot.core.session.RunRepository runRepository;
    private final ExecutorService executor;
    private final ConversationRepository conversations;
    private final com.pilot.core.session.PlanRepository plans;
    private final ConcurrentMap<String, RunHandle> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<String>> inboxes = new ConcurrentHashMap<>();
    private final com.pilot.server.store.QueuedMessageRepository queuedMessages;
    private final ConcurrentMap<String, Integer> autoContinueAttempts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> autoContinueSignature = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final PilotDataDir dataDir;
    private final MultiAgentService multiAgent;
    private final MultiAgentProperties multiProperties;

    public AgentRunService(SessionService sessions, ModelClient modelClient, WorkspaceToolFactory toolFactory,
                           PermissionGate permissionGate, ApprovalBroker approvals,
                           AgentProperties properties, ModelSettings modelSettings,
                           RunSnapshotService snapshots, RunRegistry registry,
                           com.pilot.core.session.RunRepository runRepository,
                           ConversationRepository conversations,
                           com.pilot.core.session.PlanRepository plans, ObjectMapper objectMapper,
                           PilotDataDir dataDir, com.pilot.server.store.QueuedMessageRepository queuedMessages,
                           MultiAgentService multiAgent, MultiAgentProperties multiProperties) {
        this.sessions = sessions;
        this.conversations = conversations;
        this.plans = plans;
        this.modelClient = modelClient;
        this.toolFactory = toolFactory;
        this.permissionGate = permissionGate;
        this.approvals = approvals;
        this.properties = properties;
        this.modelSettings = modelSettings;
        this.snapshots = snapshots;
        this.registry = registry;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.dataDir = dataDir;
        this.queuedMessages = queuedMessages;
        this.multiAgent = multiAgent;
        this.multiProperties = multiProperties;
        this.executor = Executors.newFixedThreadPool(properties.runThreads(), runnable -> {
            Thread thread = new Thread(runnable, "pilot-agent-run");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start(String sessionId, String runId, String input) {
        launch(sessionId, runId, input, List.of(), false);
    }

    @Override
    public void start(String sessionId, String runId, String input, List<Attachment> attachments) {
        launch(sessionId, runId, input, attachments == null ? List.of() : attachments, false);
    }

    /** 续跑：不追加用户消息，用落盘的记忆接着跑。 */
    @Override
    public void resume(String sessionId, String runId) {
        launch(sessionId, runId, null, List.of(), true);
    }

    @Override
    public boolean resumable(String sessionId) {
        return registry.activeRun(sessionId).isEmpty() && !conversations.load(sessionId).isEmpty();
    }

    @Override
    public boolean steer(String sessionId, String text) {
        if (text == null || text.isBlank() || registry.activeRun(sessionId).isEmpty()) {
            return false;
        }
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            queue.offer(text.strip());
            persistQueue(sessionId, queue);
        }
        return true;
    }

    @Override
    public List<String> queued(String sessionId) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            return List.copyOf(queue);
        }
    }

    @Override
    public boolean editQueued(String sessionId, int index, String text) {
        if (text == null || text.isBlank()) return false;
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            if (index < 0 || index >= queue.size()) {
                return false;
            }
            var items = new ArrayList<>(queue);
            items.set(index, text.strip());
            queue.clear();
            queue.addAll(items);
            persistQueue(sessionId, queue);
            return true;
        }
    }

    @Override
    public boolean removeQueued(String sessionId, int index) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            if (index < 0 || index >= queue.size()) {
                return false;
            }
            var items = new ArrayList<>(queue);
            items.remove(index);
            queue.clear();
            queue.addAll(items);
            persistQueue(sessionId, queue);
            return true;
        }
    }

    @Override
    public void rewindBeforeUserInput(String sessionId, int occurrence, String originalInput) {
        if (occurrence < 1 || originalInput == null) {
            return;
        }
        List<Message> history = conversations.load(sessionId);
        int seen = 0;
        int cut = -1;
        for (int i = 0; i < history.size(); i++) {
            Message message = history.get(i);
            if (!"user".equals(message.role())) {
                continue;
            }
            String text = message.content() == null ? "" : message.content();
            // 自动续跑塞进去的提示不是用户自己写的，不能拿来对位置
            if (text.startsWith("[自动继续]") || !text.equals(originalInput)) {
                continue;
            }
            seen++;
            if (seen == occurrence) {
                cut = i;
                break;
            }
        }
        if (cut >= 0) {
            conversations.save(sessionId, history.subList(0, cut));
        }
        plans.delete(sessionId);
    }

    private Deque<String> inbox(String sessionId) {
        return inboxes.computeIfAbsent(sessionId, key -> new ArrayDeque<>(queuedMessages.load(key)));
    }

    private void persistQueue(String sessionId, Deque<String> queue) {
        queuedMessages.save(sessionId, List.copyOf(queue));
    }

    /** 每轮采样前拉一条用户追加的指令；没有就返回 null。 */
    private String pollSteer(String sessionId) {
        Deque<String> queue = inbox(sessionId);
        synchronized (queue) {
            String next = queue.poll();
            if (next != null) persistQueue(sessionId, queue);
            return next;
        }
    }

    private void launch(String sessionId, String runId, String input, List<Attachment> attachments,
                        boolean continuation) {
        var handle = new RunHandle();
        runs.put(runId, handle);
        registry.register(runId, sessionId);
        runRepository.started(runId, sessionId, java.time.Instant.now());
        handle.future = executor.submit(() -> {
            String status = "FAILED";
            try {
                status = execute(sessionId, runId, input, attachments, handle, continuation);
            } finally {
                runRepository.finished(runId, status, java.time.Instant.now());
                runs.remove(runId, handle);
            }
            boolean continued = maybeAutoContinue(sessionId, status);
            registry.unregister(runId);
            if (!continued && !"CANCELLED".equals(status)) {
                drainQueue(sessionId);
            }
        });
    }

    /** 这轮结束时队列里还有话，就按顺序自动开下一轮，不让用户再点一次发送。 */
    private void drainQueue(String sessionId) {
        if (registry.activeRun(sessionId).isPresent()) {
            return;
        }
        String next = pollSteer(sessionId);
        if (next == null || next.isBlank()) {
            return;
        }
        String runId = "run_" + java.util.UUID.randomUUID();
        sessions.publish(sessionId, runId, new RunStarted(next));
        launch(sessionId, runId, next, List.of(), false);
    }

    /**
     * 步数触顶时，如果计划里还有未完成项就自动接上下一轮。
     *
     * <p>两道刹车：最多连续 {@value #MAX_AUTO_CONTINUE} 次；计划状态连续两轮没有变化即视为卡住，停下等人工介入。
     */
    private boolean maybeAutoContinue(String sessionId, String status) {
        if (!"MAX_STEPS".equals(status)) {
            autoContinueAttempts.remove(sessionId);
            autoContinueSignature.remove(sessionId);
            return false;
        }
        var items = plans.load(sessionId);
        if (items.stream().allMatch(com.pilot.core.plan.PlanItem::done)) {
            autoContinueAttempts.remove(sessionId);
            autoContinueSignature.remove(sessionId);
            return false;
        }

        String signature = items.stream()
                .map(item -> item.step() + "=" + item.status())
                .collect(java.util.stream.Collectors.joining("|"));
        if (signature.equals(autoContinueSignature.get(sessionId))) {
            // 计划两轮没变，说明模型在原地打转
            autoContinueAttempts.remove(sessionId);
            autoContinueSignature.remove(sessionId);
            return false;
        }
        int attempt = autoContinueAttempts.merge(sessionId, 1, Integer::sum);
        if (attempt > MAX_AUTO_CONTINUE) {
            autoContinueAttempts.remove(sessionId);
            return false;
        }

        autoContinueSignature.put(sessionId, signature);
        String runId = "run_" + java.util.UUID.randomUUID();
        publish(sessionId, runId, new RunContinued(attempt, MAX_AUTO_CONTINUE, "步数触顶，按未完成计划继续"));
        launch(sessionId, runId, null, List.of(), true);
        return true;
    }

    @Override
    public java.util.Optional<String> activeRun(String sessionId) {
        return registry.activeRun(sessionId);
    }

    /** 重新生成：先丢掉上一轮对话（含最后一条用户消息），再用同样的输入重跑，避免历史重复。 */
    @Override
    public void regenerate(String sessionId, String runId, String input) {
        List<Message> history = conversations.load(sessionId);
        int lastUser = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role())) {
                lastUser = i;
                break;
            }
        }
        if (lastUser >= 0) {
            conversations.save(sessionId, history.subList(0, lastUser));
        }
        start(sessionId, runId, input);
    }

    @Override
    public boolean cancel(String runId) {
        RunHandle handle = runs.get(runId);
        if (handle == null) {
            return false;
        }
        handle.cancelled.set(true);
        var future = handle.future;
        if (future != null) {
            future.cancel(true);
        }
        denyPendingApprovals();
        log.info("Run {} cancel requested", runId);
        return true;
    }

    /** 取消后不再有用户决策，挂起的审批直接按拒绝收尾，避免 Agent 线程继续等待。 */
    private void denyPendingApprovals() {
        for (var pending : approvals.pending()) {
            approvals.resolve(pending.callId(), false);
        }
    }

    /**
     * 组装本轮 system：正文全部来自 {@code prompts/} 资源，这里只注入环境与当前计划。
     *
     * <p>计划每轮重新注入：压缩会折叠历史，但 system 是重建的，长期约束不会丢
     * （与 Claude Code 把约束放 CLAUDE.md 的思路一致）。
     */
    private String systemPrompt(String sessionId, String workspace) {
        String prompt = PROMPTS.system(PromptLibrary.environment(workspace));
        String repository = repositoryBrief(workspace);
        if (!repository.isEmpty()) {
            prompt = prompt + "\n\n" + repository;
        }
        String rules = projectRules(workspace);
        if (!rules.isEmpty()) {
            prompt = prompt + "\n\n" + rules;
        }
        String plan = planBrief(sessionId);
        return plan.isEmpty() ? prompt : prompt + "\n\n" + plan;
    }

    /**
     * 仓库现状快照：顶层结构、git 分支与未提交改动、构建方式。
     *
     * <p>顶级 agent 都会把这几项注入上下文，模型不用从零摸索，也不会覆盖用户手上的未提交改动。
     * 每轮只做一次目录列举 + 一次带超时的 {@code git status}，读不到就降级，不影响运行。
     */
    private String repositoryBrief(String workspace) {
        Path root;
        try {
            root = toolFactory.resolve(workspace);
        } catch (RuntimeException e) {
            return "";
        }
        if (!Files.isDirectory(root)) {
            return "";
        }
        try {
            return PROMPTS.render("system/repository", Map.of(
                    "tree", workspaceTree(root),
                    "git", gitState(root),
                    "build", buildHint(root))).strip();
        } catch (RuntimeException e) {
            log.debug("生成仓库摘要失败", e);
            return "";
        }
    }

    private static String workspaceTree(Path root) {
        try (var entries = Files.list(root)) {
            List<String> names = entries
                    .map(path -> {
                        String name = path.getFileName().toString();
                        return Files.isDirectory(path) ? name + "/" : name;
                    })
                    .filter(name -> !IGNORED_ENTRIES.contains(name.endsWith("/")
                            ? name.substring(0, name.length() - 1)
                            : name))
                    .sorted()
                    .limit(MAX_TREE_ENTRIES + 1L)
                    .toList();
            if (names.isEmpty()) {
                return "（空目录）";
            }
            boolean truncated = names.size() > MAX_TREE_ENTRIES;
            String listed = String.join("\n", names.stream()
                    .limit(MAX_TREE_ENTRIES)
                    .map(name -> "- " + name)
                    .toList());
            return truncated ? listed + "\n- …（其余条目省略）" : listed;
        } catch (IOException e) {
            return "（读取目录失败）";
        }
    }

    private static String gitState(Path root) {
        if (!Files.isDirectory(root.resolve(".git"))) {
            return "不是 git 仓库";
        }
        String branch = branchName(root);
        String status = runGit(root, "status", "--porcelain=v1", "-uall");
        if (status == null) {
            return "分支 `" + branch + "`（无法读取未提交改动）";
        }
        List<String> lines = status.lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            return "分支 `" + branch + "`，工作区干净";
        }
        String listed = lines.stream()
                .limit(MAX_GIT_CHANGES)
                .map(line -> "- " + line.strip())
                .collect(Collectors.joining("\n"));
        String more = lines.size() > MAX_GIT_CHANGES
                ? "\n- …（还有 " + (lines.size() - MAX_GIT_CHANGES) + " 个）"
                : "";
        return "分支 `" + branch + "`，有 " + lines.size() + " 个文件未提交：\n" + listed + more;
    }

    private static String branchName(Path root) {
        try {
            String head = Files.readString(root.resolve(".git").resolve("HEAD"), StandardCharsets.UTF_8).strip();
            String prefix = "ref: refs/heads/";
            if (head.startsWith(prefix)) {
                return head.substring(prefix.length());
            }
            return head.isEmpty() ? "unknown" : "detached@" + head.substring(0, Math.min(7, head.length()));
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static String runGit(Path root, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("--no-pager");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        try {
            Process process = builder.start();
            if (!process.waitFor(GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String buildHint(Path root) {
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return "Maven 项目：优先用工作区的 `mvnw`（Windows 是 `mvnw.cmd`），不要依赖系统 maven";
        }
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            return "Gradle 项目：用工作区的 `gradlew`";
        }
        if (Files.isRegularFile(root.resolve("package.json"))) {
            return "Node 项目：先看 package.json 的 scripts，再跑 `npm test` / `npm run build` 这类脚本";
        }
        if (Files.isRegularFile(root.resolve("pyproject.toml"))
                || Files.isRegularFile(root.resolve("requirements.txt"))) {
            return "Python 项目：用 `pytest` 或项目里声明的命令";
        }
        if (Files.isRegularFile(root.resolve("Cargo.toml"))) {
            return "Rust 项目：`cargo test` / `cargo build`";
        }
        if (Files.isRegularFile(root.resolve("go.mod"))) {
            return "Go 项目：`go build ./...` / `go test ./...`";
        }
        return "未识别到构建文件，改动后按项目实际情况验证";
    }

    /**
     * 把工作区里的项目约定注入 system：项目规范应该跟着仓库走，而不是写死在代码里。
     * 目前只读根目录的 {@code PILOT.md} / {@code AGENTS.md} / {@code CLAUDE.md}，取第一个存在的。
     */
    private String projectRules(String workspace) {
        Path root;
        try {
            root = toolFactory.resolve(workspace);
        } catch (RuntimeException e) {
            return "";
        }
        for (String name : CONVENTION_FILES) {
            Path file = root.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String rules = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (rules.isEmpty()) {
                    continue;
                }
                if (rules.length() > MAX_CONVENTION_CHARS) {
                    rules = rules.substring(0, MAX_CONVENTION_CHARS) + "\n…（约定文件过长，已截断）";
                }
                return PROMPTS.render("system/project-rules",
                        Map.of("source", name, "rules", rules)).strip();
            } catch (IOException e) {
                log.warn("读取项目约定文件失败：{}", file, e);
                return "";
            }
        }
        return "";
    }

    private String planBrief(String sessionId) {
        var items = plans.load(sessionId);
        if (items.isEmpty()) {
            return "";
        }
        var open = items.stream().filter(item -> !item.done()).toList();
        if (open.isEmpty()) {
            return PROMPTS.render("system/plan-done", Map.of()).strip();
        }
        String list = open.stream()
                .map(item -> "- [" + item.status() + "] " + item.step())
                .collect(Collectors.joining("\n"));
        return PROMPTS.render("system/plan-context", Map.of("planItems", list)).strip();
    }

    private String execute(String sessionId, String runId, String input, List<Attachment> attachments,
                           RunHandle handle, boolean continuation) {
        List<Message> history = conversations.load(sessionId);
        String workspace = sessions.find(sessionId).map(Session::workspace).orElse(null);
        PlanSink planSink = (items, explanation) -> {
            plans.save(sessionId, items);
            publish(sessionId, runId, new PlanUpdated(
                    items.stream()
                            .map(item -> new PlanUpdated.PlanItemPayload(item.step(), item.status()))
                            .toList(),
                    explanation));
        };
        ToolRegistry tools = toolFactory.registryFor(workspace, planSink);
        Consumer<SessionEvent> sink = event -> publish(sessionId, runId, event);
        Consumer<List<Message>> checkpoint = messages -> conversations.save(sessionId, messages);
        var config = new AgentLoop.Config(properties.maxSteps(), properties.repeatedToolCallLimit(),
                systemPrompt(sessionId, workspace),
                // 收尾时读一次计划，把「本轮是否真的做完」一并写进 run.completed
                () -> plans.load(sessionId),
                // 计划没做完时自动继续，而不是停下来等用户点「继续执行」
                properties.maxPlanContinues(),
                properties.contextMaxChars());
        // 子代理与父代理共用工具集（其中不含 spawn_agent，避免递归派生）
        tools.register(new SpawnAgentTool(modelClient, tools, permissionGate, approvals, config,
                () -> conversations.load(sessionId), sink, handle.cancelled::get));
        // 多智能体调度：父 Agent 只能派活，子 Agent 的改动一律进沙箱
        if (multiProperties.enabled()) {
            tools.register(new DispatchAgentsTool(multiAgent, sessionId, runId, workspace, sink,
                    handle.cancelled::get));
        }
        // 与会话数据库、快照保持一致，统一落在数据目录（默认项目下的 .pilot）
        var traceRecorder = new JsonlTraceRecorder(
                dataDir.file("traces", sessionId + ".jsonl"), objectMapper);
        var loop = new AgentLoop(modelClient, tools, permissionGate, approvals, config, handle.cancelled::get,
                () -> pollSteer(sessionId), traceRecorder,
                delta -> sessions.preview(sessionId, runId, new AssistantPreview(delta)));

        snapshots.bind(runId, toolFactory.resolve(workspace));
        try {
            AgentLoop.Result result = continuation
                    ? loop.resume(history, sink, checkpoint)
                    : loop.run(input, attachments, history, sink, checkpoint);
            conversations.save(sessionId, result.conversation());
            log.info("Agent run {} completed: failureCode={}, messages={}, workspace={}", runId, result.failureCode(),
                    result.conversation().size(), workspace == null ? properties.workspace() : workspace);
            if (result.failureCode() == null) {
                return "COMPLETED";
            }
            // 步数触顶要单独返回，外层才会按未完成计划自动接下一轮。
            // 以前一律收成 FAILED，自动继续的判断永远进不去。
            if ("CANCELLED".equals(result.failureCode()) || "MAX_STEPS".equals(result.failureCode())) {
                return result.failureCode();
            }
            return "FAILED";
        } catch (RuntimeException e) {
            log.error("Agent run {} failed unexpectedly", runId, e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            publish(sessionId, runId, new RunFailed("INTERNAL_ERROR", "执行失败：" + reason, false));
            return "FAILED";
        } finally {
            snapshots.clear();
        }
    }

    private static final class RunHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Future<?> future;
    }

    private void publish(String sessionId, String runId, SessionEvent event) {
        try {
            sessions.publish(sessionId, runId, event);
        } catch (RuntimeException e) {
            log.warn("Dropped event {} for session {}", event.type().wireName(), sessionId, e);
        }
    }

    @PostConstruct
    void logConfiguration() {
        ModelSettings.Snapshot snapshot = modelSettings.snapshot();
        if (!snapshot.hasApiKey()) {
            log.warn("模型 API Key 未配置，模型请求会失败；"
                    + "可在界面「模型设置」里填写，或设置环境变量 PILOT_MODEL_API_KEY");
        } else {
            log.info("模型已配置：model={} endpoint={} workspace={}",
                    snapshot.model(), snapshot.endpoint(), properties.workspace());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
