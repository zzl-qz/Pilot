package com.pilot.server.multi;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailureCode;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.ExecutionPolicy;
import com.pilot.core.model.MeteredModelClient;
import com.pilot.core.model.ModelClient;

/**
 * 子代理工具与多智能体调度共享的事件转发、预算限流、模型路由和结论收口辅助。
 */
final class SubagentSupport {
    static final int MAX_SUMMARY_CHARS = 6_000;

    private SubagentSupport() {
    }

    /**
     * 按显式白名单转发子代理事件到父 sink：
     * 子 run 的 assistant/run/plan/usage 事件不带 agentId，透传会污染父时间线或误判父任务终态。
     */
    static void forwardChildEvent(Consumer<SessionEvent> sink, SessionEvent event, String agentId,
        AtomicReference<RunCompleted> completion, boolean forwardApprovals) {
        if (event instanceof RunCompleted completed) {
            if (completion != null) {
                completion.set(completed);
            }
            return;
        }
        if (event instanceof ToolStarted started) {
            // 标上归属，前端才能把这些操作收进对应的子智能体，而不是混在主智能体的操作里。
            sink.accept(started.forAgent(agentId));
            return;
        }
        switch (event.type()) {
            case TOOL_COMPLETED, SUBAGENT_STARTED, SUBAGENT_FINISHED -> sink.accept(event);
            case APPROVAL_REQUIRED, APPROVAL_RESOLVED -> {
                if (forwardApprovals) {
                    sink.accept(event);
                }
            }
            default -> {
            }
        }
    }

    static String truncateSummary(String raw) {
        String summary = raw == null ? "" : raw.strip();
        if (summary.length() > MAX_SUMMARY_CHARS) {
            return summary.substring(0, MAX_SUMMARY_CHARS) + "…";
        }
        return summary;
    }

    /** 排除 AgentLoop 本地的兜底空模板，只有模型真正写出了阶段结论才按阶段交付结算。 */
    static boolean hasAuthoredConclusion(String closing, String code) {
        return !closing.isBlank() && !closing.startsWith("本轮未能完整结束（" + code + "）：");
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    static AgentLoop.Config subagentConfig(AgentLoop.Config parent, int maxSteps, String promptAddition) {
        int steps = Math.min(Math.max(2, parent.maxSteps()), maxSteps);
        String systemPrompt = parent.systemPrompt() == null || parent.systemPrompt().isBlank() ? promptAddition
            : parent.systemPrompt() + "\n\n" + promptAddition;
        return new AgentLoop.Config(steps, parent.repeatRemindAt(), parent.repeatAbortAt(), systemPrompt, List::of, 0,
            parent.contextLimit(), parent.completionChecks());
    }

    static ModelClient resolveRoutedClient(ModelClient fallback, String route,
        Function<String, ModelClient> routedModels) {
        if (route == null || route.isBlank()) {
            return fallback;
        }
        String name = route.strip();
        ModelClient routed = routedModels == null ? null : routedModels.apply(name);
        if (routed == null) {
            throw new IllegalStateException("子代理模型 " + name + " 未能创建");
        }
        return routed;
    }

    static ModelClient boundedClient(ModelClient delegate, int maxRequests, String childLimitMessage,
        AtomicInteger runRequests, int maxRequestsPerRun, String runLimitMessage, SpawnAgentTool.UsageSink usageSink) {
        AtomicInteger requests = new AtomicInteger();
        return MeteredModelClient.observeUsage(delegate, () -> {
            if (requests.incrementAndGet() > maxRequests) {
                throw new ExecutionPolicy.Stop(RunFailureCode.MAX_STEPS, childLimitMessage);
            }
            if (runRequests != null && runRequests.incrementAndGet() > maxRequestsPerRun) {
                throw new ExecutionPolicy.Stop(RunFailureCode.MAX_STEPS, runLimitMessage);
            }
            usageSink.beforeRequest();
        }, usageSink::afterRequest);
    }
}
