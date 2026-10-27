package com.pilot.server.store;

import com.pilot.core.event.ContextCompacted;
import com.pilot.core.event.PlanUpdated;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunRetrying;
import com.pilot.core.event.SessionEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 新增事件必须能完整往返，否则历史回放会 500。 */
class EventCodecNewEventsTest {
    private final EventCodec codec = new EventCodec(JsonMapper.builder().build());

    @Test
    void roundTripsContextCompacted() {
        var event = new ContextCompacted(4, 12_000, 3_000, "## 目标\n完成 P0-1");

        SessionEvent decoded = roundTrip("context.compacted", event);

        assertEquals(event, decoded);
    }

    @Test
    void roundTripsPlanUpdated() {
        var event = new PlanUpdated(
                List.of(new PlanUpdated.PlanItemPayload("读代码", "completed"),
                        new PlanUpdated.PlanItemPayload("写实现", "in_progress")),
                "开始第二步");

        SessionEvent decoded = roundTrip("plan.updated", event);

        assertEquals(event, decoded);
    }

    @Test
    void roundTripsRunSteered() {
        var event = new com.pilot.core.event.RunSteered("只改 A 模块");

        SessionEvent decoded = roundTrip("run.steered", event);

        assertEquals(event, decoded);
    }

    @Test
    void roundTripsRunContinued() {
        var event = new com.pilot.core.event.RunContinued(2, 3, "步数触顶");

        SessionEvent decoded = roundTrip("run.continued", event);

        assertEquals(event, decoded);
    }

    @Test
    void roundTripsSubagentEvents() {
        var started = new com.pilot.core.event.SubagentStarted("agent_1", "盘点入口类");
        var finished = new com.pilot.core.event.SubagentFinished("agent_1", "有 3 个入口", true);

        assertEquals(started, roundTrip("subagent.started", started));
        assertEquals(finished, roundTrip("subagent.finished", finished));
    }

    @Test
    void roundTripsRunCompletedPlanProgress() {
        var event = new com.pilot.core.event.RunCompleted("做到一半", List.of("A.java"), List.of("mvn test"),
                2, 5, List.of("跑回归测试"));

        assertEquals(event, roundTrip("run.completed", event));
    }

    /** 老库里没有 planDone / planTotal / openSteps，必须按 0 / 空解码，否则整条会话的 SSE 回放会 500。 */
    @Test
    void decodesLegacyRunCompletedWithoutPlanFields() {
        SessionEvent decoded = codec.decode("run.completed",
                "{\"summary\":\"完成\",\"changedFiles\":[\"A.java\"],\"checks\":[]}");

        var completed = assertInstanceOf(com.pilot.core.event.RunCompleted.class, decoded);
        assertEquals(0, completed.planDone().intValue());
        assertEquals(0, completed.planTotal().intValue());
        assertTrue(completed.openSteps().isEmpty());
        assertFalse(completed.planOpen());
    }

    @Test
    void roundTripsRunRetrying() {
        var event = new RunRetrying(2, 4_000L, "HTTP/1.1 header parser received no bytes");

        SessionEvent decoded = roundTrip("run.retrying", event);

        assertEquals(event, decoded);
    }

    @Test
    void roundTripsMultiAgentEvents() {
        var task = new com.pilot.core.event.TaskUpdated("T1", "agent_1", "改路径校验",
                "done", "已完成");
        var diff = new com.pilot.core.event.SandboxDiffReady("sbx_1", "agent_1", "T1",
                "pilot-core/src/A.java", 3, 1);
        var required = new com.pilot.core.event.ArbitrationRequired("arb_1", "dissent", "T1",
                List.of("选用方案 A", "选用方案 B"), "报告正文");
        var resolved = new com.pilot.core.event.ArbitrationResolved("arb_1", "选用方案 A");

        assertEquals(task, roundTrip("task.updated", task));
        assertEquals(diff, roundTrip("sandbox.diff", diff));
        assertEquals(required, roundTrip("arbitration.required", required));
        assertEquals(resolved, roundTrip("arbitration.resolved", resolved));
    }

    @Test
    void roundTripsRunCancelled() {
        var event = new RunCancelled("用户取消");

        SessionEvent decoded = roundTrip("run.cancelled", event);

        assertInstanceOf(RunCancelled.class, decoded);
        assertEquals(event, decoded);
    }

    private SessionEvent roundTrip(String type, SessionEvent event) {
        String json = codec.encode(event);
        return codec.decode(type, json);
    }
}
