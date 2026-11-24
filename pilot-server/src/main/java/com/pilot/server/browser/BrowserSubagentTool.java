package com.pilot.server.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.browser.BrowserBackend;
import com.pilot.core.browser.BrowserCommand;
import com.pilot.core.browser.BrowserEvidenceRecord;
import com.pilot.core.browser.BrowserEvidenceRecorder;
import com.pilot.core.browser.BrowserTool;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailureCode;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.SubagentFinished;
import com.pilot.core.event.SubagentStarted;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.ExecutionPolicy;
import com.pilot.core.message.Attachment;
import com.pilot.core.message.Message;
import com.pilot.core.message.ToolNames;
import com.pilot.core.model.MeteredModelClient;
import com.pilot.core.model.ModelClient;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.tool.Availability;
import com.pilot.core.tool.PromptedTool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.multi.SpawnAgentTool;

/**
 * {@code browser_agent}：独立隔离的浏览器子代理工具。
 *
 * <p>每个子代理拥有独立的浏览器上下文（{@code sessionId::browser::agentId}）与对话历史，
 * 只暴露禁用了任意脚本 {@code evaluate} 的结构化 {@code browser} 工具，
 * 并通过 {@code <browser_host_facts>} 向主代理交回宿主机真实记录与最新截图附件。
 */
public final class BrowserSubagentTool extends PromptedTool {
    public static final int DEFAULT_MAX_STEPS = 24;
    public static final int DEFAULT_MAX_SPAWNS_PER_RUN = 6;
    public static final int DEFAULT_MAX_CONCURRENT = 3;
    private static final int MAX_RETAINED_CONTEXTS = 6;
    private static final int MAX_SUMMARY_CHARS = 6_000;
    private static final PromptLibrary PROMPTS = PromptLibrary.standard();

    public record Budget(int maxSteps, int maxSpawnsPerRun, int maxConcurrent) {
        public static final Budget DEFAULT =
            new Budget(DEFAULT_MAX_STEPS, DEFAULT_MAX_SPAWNS_PER_RUN, DEFAULT_MAX_CONCURRENT);

        public Budget {
            maxSteps = maxSteps < 2 ? DEFAULT_MAX_STEPS : Math.min(maxSteps, 40);
            maxSpawnsPerRun = maxSpawnsPerRun < 1 ? DEFAULT_MAX_SPAWNS_PER_RUN : Math.min(maxSpawnsPerRun, 16);
            maxConcurrent = maxConcurrent < 1 ? DEFAULT_MAX_CONCURRENT : Math.min(maxConcurrent, 8);
        }
    }

    private record SubagentContext(String agentId, String browserSessionId, String label, List<Message> messages) {
    }

    private final BrowserBackend backend;
    private final String sessionId;
    private final String runId;
    private final PathValidator paths;
    private final ModelClient client;
    private final PermissionGate permissionGate;
    private final ApprovalBroker approvals;
    private final AgentLoop.Config parentConfig;
    private final Consumer<SessionEvent> events;
    private final BooleanSupplier cancelled;
    private final SpawnAgentTool.UsageSink usageSink;
    private final com.pilot.core.browser.BrowserHandoffBroker handoffs;
    private final BrowserEvidenceRecorder evidenceSink;
    private final Budget budget;
    private final Semaphore parallelSlots;
    private final AtomicInteger runSpawns = new AtomicInteger();
    private final Map<String, SubagentContext> contexts = new LinkedHashMap<>();
    private final Deque<String> contextOrder = new ArrayDeque<>();

    public BrowserSubagentTool(BrowserBackend backend, String sessionId, String runId, PathValidator paths,
        ModelClient client, PermissionGate permissionGate, ApprovalBroker approvals, AgentLoop.Config config,
        Consumer<SessionEvent> events, BooleanSupplier cancelled, SpawnAgentTool.UsageSink usageSink,
        com.pilot.core.browser.BrowserHandoffBroker handoffs, BrowserEvidenceRecorder evidenceSink) {
        this(backend, sessionId, runId, paths, client, permissionGate, approvals, config, events, cancelled, usageSink,
            handoffs, evidenceSink, Budget.DEFAULT);
    }

    public BrowserSubagentTool(BrowserBackend backend, String sessionId, String runId, PathValidator paths,
        ModelClient client, PermissionGate permissionGate, ApprovalBroker approvals, AgentLoop.Config config,
        Consumer<SessionEvent> events, BooleanSupplier cancelled, SpawnAgentTool.UsageSink usageSink,
        com.pilot.core.browser.BrowserHandoffBroker handoffs, BrowserEvidenceRecorder evidenceSink, Budget budget) {
        super(ToolNames.BROWSER_AGENT);
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
        this.sessionId = sessionId == null || sessionId.isBlank() ? "default" : sessionId.strip();
        this.runId = runId == null ? "" : runId.strip();
        this.paths = Objects.requireNonNull(paths, "paths must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate must not be null");
        this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
        this.parentConfig = Objects.requireNonNull(config, "config must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled must not be null");
        this.usageSink = usageSink == null ? SpawnAgentTool.UsageSink.none() : usageSink;
        this.handoffs = handoffs;
        this.evidenceSink = evidenceSink == null ? BrowserEvidenceRecorder.none() : evidenceSink;
        this.budget = budget == null ? Budget.DEFAULT : budget;
        this.parallelSlots = new Semaphore(this.budget.maxConcurrent());
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task", ToolSchemas.string("要由浏览器子代理完成的页面任务、验证步骤或验收要求"));
        properties.put("url", ToolSchemas.string("可选起始 URL（本机回环地址或外网公开 HTTPS）"));
        properties.put("external", ToolSchemas.bool("起始页面属于外网公开 HTTPS 时设为 true"));
        properties.put("purpose", ToolSchemas.string("外网浏览或交互的目的说明，便于用户审批与记录"));
        properties.put("resume_agent_id", ToolSchemas.string("复用已有浏览器子代理（填入上次返回的 agent_id），继续其原页面与上下文"));
        properties.put("label", ToolSchemas.string("在界面浏览器泳道显示的简短名称，例如「移动端回归」"));
        return ToolSchemas.object(properties, List.of("task"));
    }

    /** 子代理内部每步仍受 PermissionGate 与 ApprovalBroker 保护，工具委派本身不阻塞 READ_ONLY/ASK 规划。 */
    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Availability availability() {
        return backend.availability();
    }

    @Override
    public String execute(Map<String, Object> args) {
        return executeResult(args).text();
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        String task = str(args == null ? null : args.get("task"));
        if (task.isBlank()) {
            return ToolResult.text("ERROR: task 不能为空");
        }
        String url = str(args == null ? null : args.get("url"));
        boolean external = args != null && Boolean.TRUE.equals(args.get("external"));
        String purpose = str(args == null ? null : args.get("purpose"));
        String resumeId = str(args == null ? null : args.get("resume_agent_id"));
        String requestedLabel = str(args == null ? null : args.get("label"));

        SubagentContext resumed = null;
        if (!resumeId.isBlank()) {
            synchronized (contexts) {
                resumed = contexts.get(resumeId);
            }
            if (resumed == null) {
                return ToolResult.text("ERROR: 没有找到浏览器子代理 " + resumeId + " 的已有上下文，无法续跑");
            }
        } else {
            int spawn = runSpawns.incrementAndGet();
            if (spawn > budget.maxSpawnsPerRun()) {
                return ToolResult.text("ERROR: 本轮浏览器子代理创建次数已达上限 " + budget.maxSpawnsPerRun()
                    + " 次；请基于现有结果汇总交付，或传入 resume_agent_id 复用已有浏览器子代理。");
            }
        }

        String agentId = resumed != null ? resumed.agentId() : "bagent_" + UUID.randomUUID().toString().substring(0, 8);
        String browserSessionId = resumed != null ? resumed.browserSessionId() : sessionId + "::browser::" + agentId;
        String label = !requestedLabel.isBlank() ? requestedLabel : resumed != null && !resumed.label().isBlank()
            ? resumed.label() : task.length() > 16 ? task.substring(0, 16) + "…" : task;
        List<Message> priorHistory = resumed != null ? resumed.messages() : List.of();

        if (backend instanceof BrowserRuntime runtime) {
            runtime.registerChildSession(sessionId, browserSessionId, agentId, label);
        }

        events.accept(new SubagentStarted(agentId, task, label));
        CopyOnWriteArrayList<BrowserEvidenceRecord> captured = new CopyOnWriteArrayList<>();
        BrowserEvidenceRecorder localRecorder = record -> {
            if (record != null) {
                captured.add(record);
                evidenceSink.record(record);
            }
        };

        AtomicReference<RunCompleted> completion = new AtomicReference<>();
        boolean slotHeld = false;
        boolean recordingStarted = false;
        try {
            parallelSlots.acquire();
            slotHeld = true;
            recordingStarted = startProcessRecording(browserSessionId);
            ToolRegistry childTools = isolatedChildTools(browserSessionId, agentId, localRecorder);
            AgentLoop.Config childConfig = subagentConfig(parentConfig, budget.maxSteps());
            AgentLoop loop = new AgentLoop(boundedClient(client), childTools, permissionGate, approvals, childConfig,
                cancelled::getAsBoolean);
            String prompt = buildPrompt(task, url, external, purpose, resumed != null);
            AgentLoop.Result result =
                loop.run(prompt, priorHistory, event -> forwardChildEvent(event, completion, agentId));
            rememberContext(agentId, browserSessionId, label, result);
            if (recordingStarted) {
                stopProcessRecording(browserSessionId, agentId, localRecorder, !captured.isEmpty());
                recordingStarted = false;
            }

            String hostFacts = buildHostFacts(agentId, browserSessionId, captured);
            List<Attachment> attachments = latestScreenshotAttachment(captured);
            String closing = truncateSummary(result.finalText());
            if (result.failed()) {
                String code = result.failureCode() == null ? "UNKNOWN" : result.failureCode();
                if (hasAuthoredConclusion(closing, code)) {
                    String report = "[浏览器子代理阶段结算：" + code + "]\n\n" + closing + hostFacts + "\nagent_id: " + agentId;
                    events.accept(new SubagentFinished(agentId, report, false, code, true));
                    return resultWith(report, attachments);
                }
                String report = "ERROR: 浏览器子代理未完成：" + code + hostFacts + "\nagent_id: " + agentId;
                events.accept(new SubagentFinished(agentId, report, false, code, false));
                return resultWith(report, attachments);
            }
            String body = closing.isBlank() ? "浏览器子代理已完成操作。" : closing;
            String report = body + hostFacts + "\nagent_id: " + agentId;
            events.accept(new SubagentFinished(agentId, report, true, "COMPLETED", false));
            return resultWith(report, attachments);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String report = "ERROR: 浏览器子代理等待执行槽位时被中断";
            events.accept(new SubagentFinished(agentId, report, false, "INTERRUPTED", false));
            return ToolResult.text(report);
        } catch (RuntimeException e) {
            if (recordingStarted) {
                stopProcessRecording(browserSessionId, agentId, localRecorder, !captured.isEmpty());
                recordingStarted = false;
            }
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String report = "ERROR: 浏览器子代理异常：" + message + buildHostFacts(agentId, browserSessionId, captured)
                + "\nagent_id: " + agentId;
            events.accept(new SubagentFinished(agentId, report, false, "RUNTIME_ERROR", false));
            return resultWith(report, latestScreenshotAttachment(captured));
        } finally {
            if (recordingStarted) {
                stopProcessRecording(browserSessionId, agentId, localRecorder, false);
            }
            if (slotHeld) {
                parallelSlots.release();
            }
        }
    }

    private ToolRegistry isolatedChildTools(String browserSessionId, String agentId, BrowserEvidenceRecorder recorder) {
        ToolRegistry registry = new ToolRegistry();
        com.pilot.core.browser.BrowserTool browserTool =
            new com.pilot.core.browser.BrowserTool(backend, browserSessionId, paths)
                .withRunContext(sessionId, runId, agentId).withAllowEvaluate(false).withHandoffBroker(handoffs)
                .withEvidenceRecorder(recorder);
        registry.register(browserTool);
        registry.enable(List.of(ToolNames.BROWSER));
        return registry;
    }

    private ModelClient boundedClient(ModelClient delegate) {
        int maxRequests = budget.maxSteps() + 1;
        AtomicInteger requests = new AtomicInteger();
        return MeteredModelClient.observeUsage(delegate, () -> {
            if (requests.incrementAndGet() > maxRequests) {
                throw new ExecutionPolicy.Stop(RunFailureCode.MAX_STEPS,
                    "浏览器子代理已达到模型请求上限 " + maxRequests + "，返回已核实证据。");
            }
            usageSink.beforeRequest();
        }, usageSink::afterRequest);
    }

    private void rememberContext(String agentId, String browserSessionId, String label, AgentLoop.Result result) {
        if (result == null || result.conversation() == null || result.conversation().isEmpty()) {
            return;
        }
        SubagentContext evicted = null;
        synchronized (contexts) {
            if (!contexts.containsKey(agentId)) {
                contextOrder.addLast(agentId);
            }
            contexts.put(agentId,
                new SubagentContext(agentId, browserSessionId, label, List.copyOf(result.conversation())));
            while (contextOrder.size() > MAX_RETAINED_CONTEXTS) {
                String oldestId = contextOrder.pollFirst();
                if (oldestId != null && !oldestId.equals(agentId)) {
                    evicted = contexts.remove(oldestId);
                    break;
                }
            }
        }
        if (evicted != null) {
            if (backend instanceof BrowserRuntime runtime) {
                runtime.unregisterChildSession(evicted.browserSessionId());
            }
            backend.closeSession(evicted.browserSessionId());
        }
    }

    private void forwardChildEvent(SessionEvent event, AtomicReference<RunCompleted> completion, String agentId) {
        if (event instanceof RunCompleted completed) {
            completion.set(completed);
            return;
        }
        if (event instanceof ToolStarted started) {
            events.accept(started.forAgent(agentId));
            return;
        }
        switch (event.type()) {
            case TOOL_STARTED, TOOL_COMPLETED, APPROVAL_REQUIRED, APPROVAL_RESOLVED -> events.accept(event);
            default -> {
            }
        }
    }

    private static AgentLoop.Config subagentConfig(AgentLoop.Config parent, int maxSteps) {
        int steps = Math.min(Math.max(2, parent.maxSteps()), maxSteps);
        String promptAddition = PROMPTS.get("subagent/browser").strip();
        String systemPrompt = parent.systemPrompt() == null || parent.systemPrompt().isBlank() ? promptAddition
            : parent.systemPrompt() + "\n\n" + promptAddition;
        return new AgentLoop.Config(steps, parent.repeatRemindAt(), parent.repeatAbortAt(), systemPrompt, List::of, 0,
            parent.contextLimit(), parent.completionChecks());
    }

    private static String buildPrompt(String task, String url, boolean external, String purpose, boolean resuming) {
        StringBuilder sb = new StringBuilder();
        if (resuming) {
            sb.append("继续同一浏览器子代理任务（保留原页面与登录状态；先 snapshot 再按需继续）。\n");
        }
        sb.append("任务目标：").append(task);
        if (!url.isBlank()) {
            sb.append("\n起始地址：").append(url);
            if (external) {
                sb.append("（external=true）");
            }
        }
        if (!purpose.isBlank()) {
            sb.append("\n目的说明：").append(purpose);
        }
        return sb.toString();
    }

    static String buildHostFacts(String agentId, String browserSessionId, List<BrowserEvidenceRecord> records) {
        Set<String> visitedOrigins = new LinkedHashSet<>();
        Set<String> blockedOrigins = new LinkedHashSet<>();
        List<String> downloads = new ArrayList<>();
        List<String> handoffs = new ArrayList<>();
        int consoleErrors = 0;
        int failedRequests = 0;
        String latestScreenshot = "";
        String latestRecording = "";
        for (BrowserEvidenceRecord rec : records) {
            if (!rec.url().isBlank()) {
                String origin = BrowserCommand.originOf(rec.url());
                if (origin != null && !origin.isBlank()) {
                    visitedOrigins.add(origin);
                }
            }
            blockedOrigins.addAll(rec.blockedOrigins());
            consoleErrors = Math.max(consoleErrors, rec.consoleErrors());
            failedRequests = Math.max(failedRequests, rec.failedRequests());
            if (!rec.downloadName().isBlank() || !rec.downloadPath().isBlank()) {
                String dlLabel = rec.downloadName().isBlank() ? rec.downloadPath() : rec.downloadName();
                downloads.add(dlLabel + " (" + rec.downloadBytes() + " bytes, status=" + rec.status() + ")");
            }
            if ("handoff".equals(rec.action()) || !rec.gateKind().isBlank()) {
                handoffs.add((rec.gateKind().isBlank() ? rec.action() : rec.gateKind()) + ":" + rec.status());
            }
            if (!rec.screenshotPath().isBlank() && Files.isRegularFile(Path.of(rec.screenshotPath()))) {
                latestScreenshot = rec.screenshotPath();
            }
            if (!rec.recordingPath().isBlank() && Files.isRegularFile(Path.of(rec.recordingPath()))) {
                latestRecording = rec.recordingPath();
            }
        }
        return "\n\n<browser_host_facts>\n" + "agent_id: " + agentId + "\n" + "browser_session_id: " + browserSessionId
            + "\n" + "steps_recorded: " + records.size() + "\n" + "visited_origins: " + visitedOrigins + "\n"
            + "blocked_origins: " + blockedOrigins + "\n" + "console_errors: " + consoleErrors + "\n"
            + "failed_requests: " + failedRequests + "\n" + "downloads: " + downloads + "\n" + "handoffs: " + handoffs
            + "\n" + "latest_screenshot_path: " + latestScreenshot + "\n" + "recording_path: " + latestRecording + "\n"
            + "</browser_host_facts>";
    }

    private boolean startProcessRecording(String browserSessionId) {
        if (!backend.supportsProcessRecording()) {
            return false;
        }
        try {
            backend.execute(browserSessionId, Map.of("op", "record", "mode", "start"), Duration.ofSeconds(5));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void stopProcessRecording(String browserSessionId, String agentId, BrowserEvidenceRecorder recorder,
        boolean saveEvidence) {
        try {
            Path target = BrowserTool.nextRecordingFile(browserSessionId);
            BrowserBackend.Reply reply = backend.execute(browserSessionId,
                Map.of("op", "record", "mode", "stop", "path", target.toString()), Duration.ofSeconds(10));
            if (!saveEvidence) {
                Files.deleteIfExists(target);
                return;
            }
            Map<String, Object> meta = reply.metadata();
            String recPath = "";
            if (meta.get("recording") instanceof Map<?, ?> rec) {
                recPath = str(rec.get("path"));
            }
            if (!recPath.isBlank()) {
                recorder.record(new BrowserEvidenceRecord("ev_" + UUID.randomUUID().toString().substring(0, 12),
                    sessionId, browserSessionId, runId, agentId, Instant.now(), "record", "ok",
                    str(meta.get("url")), str(meta.get("title")), reply.text(), "", "", "", "", 0L, List.of(),
                    meta.get("consoleErrors") instanceof Number n ? n.intValue() : 0,
                    meta.get("failedRequests") instanceof Number n ? n.intValue() : 0, "", recPath));
            }
        } catch (RuntimeException | IOException ignored) {
            // 录屏保存失败不影响子代理主结论交付
        }
    }

    private static List<Attachment> latestScreenshotAttachment(List<BrowserEvidenceRecord> records) {
        for (int i = records.size() - 1; i >= 0; i--) {
            String raw = records.get(i).screenshotPath();
            if (raw.isBlank()) {
                continue;
            }
            Path path = Path.of(raw);
            if (Files.isRegularFile(path)) {
                String fileName = path.getFileName().toString();
                String mime = fileName.toLowerCase(Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";
                return List.of(new Attachment(fileName, mime, path.toString()));
            }
        }
        return List.of();
    }

    private static ToolResult resultWith(String text, List<Attachment> attachments) {
        return attachments.isEmpty() ? ToolResult.text(text) : ToolResult.withImages(text, attachments);
    }

    private static boolean hasAuthoredConclusion(String closing, String code) {
        return !closing.isBlank() && !closing.startsWith("本轮未能完整结束（" + code + "）：");
    }

    private static String truncateSummary(String raw) {
        String summary = raw == null ? "" : raw.strip();
        if (summary.length() > MAX_SUMMARY_CHARS) {
            return summary.substring(0, MAX_SUMMARY_CHARS) + "…";
        }
        return summary;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
