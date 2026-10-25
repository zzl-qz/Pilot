package com.pilot.server.session;

import com.pilot.core.event.ApprovalDecision;
import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.ApprovalResolved;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.session.SessionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sprint 1 的假执行器：收到消息后按固定节奏向会话推送一组 Mock 事件，
 * 用于验证 SSE 协议与前端接收链路。Sprint 2 起由真实 AgentLoop 取代。
 */
@Component
@ConditionalOnProperty(prefix = "pilot.agent", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockRunSimulator implements RunStarter {
    private static final Logger log = LoggerFactory.getLogger(MockRunSimulator.class);

    private final SessionService sessions;
    private final boolean enabled;
    private final long stepDelayMs;
    private final ScheduledExecutorService scheduler;

    public MockRunSimulator(SessionService sessions,
                            @Value("${pilot.mock.enabled:true}") boolean enabled,
                            @Value("${pilot.mock.step-delay-ms:250}") long stepDelayMs) {
        this.sessions = sessions;
        this.enabled = enabled;
        this.stepDelayMs = Math.max(1L, stepDelayMs);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pilot-mock-run");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start(String sessionId, String runId, String input) {
        if (!enabled) {
            return;
        }
        List<SessionEvent> steps = input.strip().startsWith("/fail") ? failure(input) : success(input);
        scheduleNext(sessionId, runId, steps, 0);
    }

    private void scheduleNext(String sessionId, String runId, List<SessionEvent> steps, int index) {
        if (index >= steps.size()) {
            return;
        }
        scheduler.schedule(() -> {
            try {
                sessions.publish(sessionId, runId, steps.get(index));
            } catch (RuntimeException e) {
                log.warn("Mock event {} could not be published for session {}", steps.get(index).type().wireName(), sessionId, e);
                return;
            }
            scheduleNext(sessionId, runId, steps, index + 1);
        }, stepDelayMs, TimeUnit.MILLISECONDS);
    }

    private static List<SessionEvent> success(String input) {
        String task = abbreviate(input);
        return List.of(
                new AssistantDelta("收到任务：「" + task + "」。先检查工作区结构。"),
                new AssistantDelta("定位到 2 个相关文件，读取入口与调用方。"),
                new ToolStarted("call_1", "read_file", "pilot-core/src/main/java/com/pilot/core/loop/AgentLoop.java"),
                new ToolCompleted("call_1", "读取 42 行", 128L),
                new ApprovalRequired("call_2", "write_file", "修改 AgentLoop.java：+18 -4"),
                new ApprovalResolved("call_2", ApprovalDecision.APPROVED),
                new ToolStarted("call_2", "write_file", "pilot-core/src/main/java/com/pilot/core/loop/AgentLoop.java"),
                new ToolCompleted("call_2", "写入完成：+18 -4", 96L),
                new AssistantDelta("改动已写入，正在运行相关测试。"),
                new RunCompleted(
                        "已完成「" + task + "」：修改 1 个文件，1 项检查通过。",
                        List.of("pilot-core/src/main/java/com/pilot/core/loop/AgentLoop.java"),
                        List.of("mvn -q test：12 passed"))
        );
    }

    private static List<SessionEvent> failure(String input) {
        return List.of(
                new AssistantDelta("开始执行：「" + abbreviate(input) + "」。"),
                new RunFailed("MODEL_UNAVAILABLE", "模型端点返回 503，请检查 pilot.model 配置后重试。", true)
        );
    }

    private static String abbreviate(String input) {
        String text = input.strip().replaceAll("\\s+", " ");
        return text.length() <= 30 ? text : text.substring(0, 30) + "…";
    }

    @PostConstruct
    void logMode() {
        if (enabled) {
            log.info("Mock 模式：事件由内置剧本产生，未调用真实模型（设置 pilot.agent.mode=real 切换）");
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
