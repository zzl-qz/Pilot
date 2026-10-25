package com.pilot.server.session;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.ModelProperties;
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
    private final ToolRegistry tools;
    private final PermissionGate permissionGate;
    private final ApprovalBroker approvals;
    private final AgentProperties properties;
    private final ModelProperties modelProperties;
    private final ExecutorService executor;
    private final ConcurrentMap<String, List<Message>> conversations = new ConcurrentHashMap<>();

    public AgentRunService(SessionService sessions, ModelClient modelClient, ToolRegistry tools,
                           PermissionGate permissionGate, ApprovalBroker approvals,
                           AgentProperties properties, ModelProperties modelProperties) {
        this.sessions = sessions;
        this.modelClient = modelClient;
        this.tools = tools;
        this.permissionGate = permissionGate;
        this.approvals = approvals;
        this.properties = properties;
        this.modelProperties = modelProperties;
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "pilot-agent-run");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start(String sessionId, String runId, String input) {
        executor.execute(() -> execute(sessionId, runId, input));
    }

    private void execute(String sessionId, String runId, String input) {
        List<Message> history = conversations.getOrDefault(sessionId, List.of());
        var config = new AgentLoop.Config(properties.maxSteps(), properties.repeatedToolCallLimit(),
                AgentLoop.DEFAULT_SYSTEM_PROMPT);
        var loop = new AgentLoop(modelClient, tools, permissionGate, approvals, config);
        try {
            AgentLoop.Result result = loop.run(input, history, event -> publish(sessionId, runId, event));
            conversations.put(sessionId, result.conversation());
            log.info("Agent run {} completed: failureCode={}, messages={}", runId, result.failureCode(),
                    result.conversation().size());
        } catch (RuntimeException e) {
            log.error("Agent run {} failed unexpectedly", runId, e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            publish(sessionId, runId, new RunFailed("INTERNAL_ERROR", "执行失败：" + reason, false));
        }
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
