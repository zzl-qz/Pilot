package com.pilot.server.session;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.ModelProperties;
import com.pilot.server.workspace.RunSnapshotService;
import com.pilot.server.workspace.WorkspaceToolFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * real 模式：在独立线程上执行 {@link AgentLoop}，把循环产生的事件写入会话。
 * 会话历史保存在内存中，支持同一会话的连续追问；进程重启即丢失。
 */
@Component
@ConditionalOnProperty(prefix = "pilot.agent", name = "mode", havingValue = "real")
public class AgentRunService implements RunStarter {
    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    private final SessionService sessions;
    private final ModelClient modelClient;
    private final WorkspaceToolFactory toolFactory;
    private final PermissionGate permissionGate;
    private final ApprovalBroker approvals;
    private final AgentProperties properties;
    private final ModelProperties modelProperties;
    private final RunSnapshotService snapshots;
    private final RunRegistry registry;
    private final com.pilot.core.session.RunRepository runRepository;
    private final ExecutorService executor;
    private final ConcurrentMap<String, List<Message>> conversations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RunHandle> runs = new ConcurrentHashMap<>();

    public AgentRunService(SessionService sessions, ModelClient modelClient, WorkspaceToolFactory toolFactory,
                           PermissionGate permissionGate, ApprovalBroker approvals,
                           AgentProperties properties, ModelProperties modelProperties,
                           RunSnapshotService snapshots, RunRegistry registry,
                           com.pilot.core.session.RunRepository runRepository) {
        this.sessions = sessions;
        this.modelClient = modelClient;
        this.toolFactory = toolFactory;
        this.permissionGate = permissionGate;
        this.approvals = approvals;
        this.properties = properties;
        this.modelProperties = modelProperties;
        this.snapshots = snapshots;
        this.registry = registry;
        this.runRepository = runRepository;
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "pilot-agent-run");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start(String sessionId, String runId, String input) {
        var handle = new RunHandle();
        runs.put(runId, handle);
        registry.register(runId, sessionId);
        runRepository.started(runId, sessionId, java.time.Instant.now());
        handle.future = executor.submit(() -> {
            String status = "FAILED";
            try {
                status = execute(sessionId, runId, input, handle);
            } finally {
                registry.unregister(runId);
                runRepository.finished(runId, status, java.time.Instant.now());
                runs.remove(runId, handle);
            }
        });
    }

    @Override
    public java.util.Optional<String> activeRun(String sessionId) {
        return registry.activeRun(sessionId);
    }

    /** 重新生成：先丢掉上一轮对话（含最后一条用户消息），再用同样的输入重跑，避免历史重复。 */
    @Override
    public void regenerate(String sessionId, String runId, String input) {
        conversations.computeIfPresent(sessionId, (key, history) -> {
            int lastUser = -1;
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).role())) {
                    lastUser = i;
                    break;
                }
            }
            return lastUser < 0 ? history : new java.util.ArrayList<>(history.subList(0, lastUser));
        });
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

    private String execute(String sessionId, String runId, String input, RunHandle handle) {
        List<Message> history = conversations.getOrDefault(sessionId, List.of());
        String workspace = sessions.find(sessionId).map(Session::workspace).orElse(null);
        ToolRegistry tools = toolFactory.registryFor(workspace);
        var config = new AgentLoop.Config(properties.maxSteps(), properties.repeatedToolCallLimit(),
                AgentLoop.DEFAULT_SYSTEM_PROMPT);
        var loop = new AgentLoop(modelClient, tools, permissionGate, approvals, config, handle.cancelled::get);
        snapshots.bind(runId, toolFactory.resolve(workspace));
        try {
            AgentLoop.Result result = loop.run(input, history, event -> publish(sessionId, runId, event));
            conversations.put(sessionId, result.conversation());
            log.info("Agent run {} completed: failureCode={}, messages={}, workspace={}", runId, result.failureCode(),
                    result.conversation().size(), workspace == null ? properties.workspace() : workspace);
            if (result.failureCode() == null) {
                return "COMPLETED";
            }
            return "CANCELLED".equals(result.failureCode()) ? "CANCELLED" : "FAILED";
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
        if (modelProperties.apiKey() == null || modelProperties.apiKey().isBlank()) {
            log.warn("pilot.agent.mode=real，但 pilot.model.api-key 为空，模型请求会失败；请设置环境变量 PILOT_MODEL_API_KEY");
        } else {
            log.info("真实模型模式：model={} endpoint={} workspace={}",
                    modelProperties.model(), modelProperties.endpoint(), properties.workspace());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
