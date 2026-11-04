package com.pilot.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pilot.core.event.ArbitrationRequired;
import com.pilot.core.event.ArbitrationResolved;
import com.pilot.core.event.ContextCompacted;
import com.pilot.core.event.ExecutionUpdated;
import com.pilot.core.event.PlanUpdated;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunRetrying;
import com.pilot.core.event.RunSteered;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.event.SandboxDiffReady;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.SubagentFinished;
import com.pilot.core.event.SubagentStarted;
import com.pilot.core.event.TaskUpdated;

import tools.jackson.databind.json.JsonMapper;

/** 新增事件必须能完整往返，否则历史回放会 500。 */
class EventCodecNewEventsTest {
    private final EventCodec codec = new EventCodec(JsonMapper.builder().build());

    @Test
    void roundTripsExecutionProgress() {
        ExecutionUpdated event = new ExecutionUpdated("GOAL", "VERIFYING", 4, "需要复现", "缺少回归");
        assertEquals(event, roundTrip("execution.updated", event));
    }

    @Test
    void roundTripsRunUsage() {
        RunUsageUpdated event = new RunUsageUpdated(3, 12_400, 860, 42_000, 120_000, false);
        assertEquals(event, roundTrip("run.usage", event));
    }

    @Test
    void roundTripsRunUsageWithCachedTokens() {
        RunUsageUpdated event = new RunUsageUpdated(3, 12_400, 860, 9_000, 42_000, 120_000, false);

        RunUsageUpdated decoded = assertInstanceOf(RunUsageUpdated.class, roundTrip("run.usage", event));
        assertEquals(9_000, decoded.cachedInputTokens(), "缓存命中必须随事件一起落库");
        assertEquals(event, decoded);
    }

    /** 老库里的 run.usage 没有 cachedInputTokens，必须按 0 解码，否则整条会话的 SSE 回放会 500。 */
    @Test
    void decodesLegacyRunUsageWithoutCachedTokens() {
        SessionEvent decoded =
            codec.decode("run.usage", "{\"modelRequests\":3,\"inputTokens\":12400,\"outputTokens\":860,"
                + "\"contextTokens\":42000,\"contextLimit\":120000,\"exact\":false}");

        RunUsageUpdated usage = assertInstanceOf(RunUsageUpdated.class, decoded);
        assertEquals(12_400, usage.inputTokens());
        assertEquals(0, usage.cachedInputTokens());
    }

    @Test
    void roundTripsContextCompacted() {
        ContextCompacted event = new ContextCompacted(4, 12_000, 3_000, "## 目标\n完成 P0-1");

        SessionEvent decoded = roundTrip("context.compacted", event);

        assertEquals(event, decoded);
    }

    @Test
    void roundTripsPlanUpdated() {
        PlanUpdated event = new PlanUpdated(List.of(new PlanUpdated.PlanItemPayload("读代码", "completed"),
            new PlanUpdated.PlanItemPayload("写实现", "in_progress")), "开始第二步");

        SessionEvent decoded = roundTrip("plan.updated", event);

        assertEquals(event, decoded);
    }

    @Test
    void roundTripsRunSteered() {
        RunSteered event = new RunSteered("只改 A 模块");

        SessionEvent decoded = roundTrip("run.steered", event);

        assertEquals(event, decoded);
    }

    @Test
    void roundTripsRunContinued() {
        RunContinued event = new RunContinued(2, 3, "步数触顶");

        SessionEvent decoded = roundTrip("run.continued", event);

        assertEquals(event, decoded);
    }

    @Test
    void roundTripsSubagentEvents() {
        SubagentStarted started = new SubagentStarted("agent_1", "盘点入口类");
        SubagentFinished finished = new SubagentFinished("agent_1", "有 3 个入口", true);

        assertEquals(started, roundTrip("subagent.started", started));
        assertEquals(finished, roundTrip("subagent.finished", finished));
    }

    @Test
    void roundTripsRunCompletedPlanProgress() {
        RunCompleted event = new RunCompleted("做到一半", List.of("A.java"), List.of("mvn test"), 2, 5, List.of("跑回归测试"));

        assertEquals(event, roundTrip("run.completed", event));
    }

    /** 老库里没有 planDone / planTotal / openSteps，必须按 0 / 空解码，否则整条会话的 SSE 回放会 500。 */
    @Test
    void decodesLegacyRunCompletedWithoutPlanFields() {
        SessionEvent decoded =
            codec.decode("run.completed", "{\"summary\":\"完成\",\"changedFiles\":[\"A.java\"],\"checks\":[]}");

        RunCompleted completed = assertInstanceOf(RunCompleted.class, decoded);
        assertEquals(0, completed.planDone().intValue());
        assertEquals(0, completed.planTotal().intValue());
        assertTrue(completed.openSteps().isEmpty());
        assertFalse(completed.planOpen());
    }

    @Test
    void roundTripsRunRetrying() {
        RunRetrying event = new RunRetrying(2, 4_000L, "HTTP/1.1 header parser received no bytes");

        SessionEvent decoded = roundTrip("run.retrying", event);

        assertEquals(event, decoded);
    }

    @Test
    void roundTripsMultiAgentEvents() {
        TaskUpdated task = new TaskUpdated("T1", "agent_1", "改路径校验", "done", "已完成");
        SandboxDiffReady diff = new SandboxDiffReady("sbx_1", "agent_1", "T1", "pilot-core/src/A.java", 3, 1);
        ArbitrationRequired required =
            new ArbitrationRequired("arb_1", "dissent", "T1", List.of("选用方案 A", "选用方案 B"), "报告正文");
        ArbitrationResolved resolved = new ArbitrationResolved("arb_1", "选用方案 A");

        assertEquals(task, roundTrip("task.updated", task));
        assertEquals(diff, roundTrip("sandbox.diff", diff));
        assertEquals(required, roundTrip("arbitration.required", required));
        assertEquals(resolved, roundTrip("arbitration.resolved", resolved));
    }

    @Test
    void roundTripsRunCancelled() {
        RunCancelled event = new RunCancelled("用户取消");

        SessionEvent decoded = roundTrip("run.cancelled", event);

        assertInstanceOf(RunCancelled.class, decoded);
        assertEquals(event, decoded);
    }

    private SessionEvent roundTrip(String type, SessionEvent event) {
        String json = codec.encode(event);
        return codec.decode(type, json);
    }
}
