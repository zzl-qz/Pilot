package com.pilot.server.multi;

import com.pilot.core.event.ArbitrationRequired;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.TaskUpdated;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.multi.TaskSpec;
import com.pilot.core.multi.TaskStatus;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.workspace.WorkspaceToolFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAgentServiceTest {

    private static final String REPORT = """
            【结论】
            src/A.java 里只有一个空类，没有需要改动的地方。

            【证据清单】
            - src/A.java:1 class A 定义为空

            【风险评估】
            - 无
            """;

    @TempDir
    Path workspace;

    @TempDir
    Path dataDir;

    private List<SessionEvent> events;

    private MultiAgentService service(ModelClient client) throws IOException {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/A.java"), "class A {}\n");
        var agent = new AgentProperties(workspace.toString(), PermissionMode.AUTO, 8, 3, 300, 2, 1, 60_000, true);
        var factory = new WorkspaceToolFactory(agent, SnapshotSink.none());
        var store = new SandboxStore(JsonMapper.builder().build(), new PilotDataDir(dataDir.toString()));
        var properties = new MultiAgentProperties(true, 2, 2, 8, 30, List.of());
        VerificationGate gate = (root, base, changes) -> VerificationGate.Report.skipped("测试环境跳过编译");
        events = new CopyOnWriteArrayList<>();
        return new MultiAgentService(client, factory, store, gate, properties,
                new PilotDataDir(dataDir.toString()), JsonMapper.builder().build());
    }

    private MultiAgentService.Dispatch dispatch(List<TaskSpec> specs) {
        return new MultiAgentService.Dispatch("ses_test", "run_test", workspace.toString(), specs,
                events::add, () -> false);
    }

    @Test
    void completesReadOnlyTaskAndReportsEvidence() throws IOException {
        ModelClient client = (messages, tools) -> new ModelResponse(REPORT, List.of());
        var service = service(client);
        var spec = TaskSpec.readOnly("T1", "盘点空类", "确认 src/A.java 是否需要改动",
                List.of(), List.of("src/**"));

        MultiAgentService.Result result = service.dispatch(dispatch(List.of(spec)));

        assertTrue(result.completed(), result.report());
        assertEquals(TaskStatus.DONE, result.outcomes().get(0).status());
        assertTrue(result.report().contains("全部任务通过"), result.report());
        assertTrue(events.stream().anyMatch(event -> event instanceof TaskUpdated updated
                && "done".equals(updated.status())));
        assertTrue(events.stream().anyMatch(event -> event instanceof TaskUpdated updated
                && "running".equals(updated.status())));
    }

    @Test
    void failedTaskBlocksItsDownstreamOnly() throws IOException {
        ModelClient failing = (messages, tools) -> {
            throw new IllegalStateException("boom");
        };
        var service = service(failing);
        var first = TaskSpec.readOnly("T1", "先做", "做第一件事", List.of(), List.of("src/**"));
        var second = TaskSpec.readOnly("T2", "后做", "做第二件事", List.of("T1"), List.of("src/**"));
        var independent = TaskSpec.readOnly("T3", "并行", "独立任务", List.of(), List.of("src/**"));

        MultiAgentService.Result result = service.dispatch(dispatch(List.of(first, second, independent)));

        assertFalse(result.completed());
        assertEquals(TaskStatus.FAILED, result.outcomes().get(0).status());
        assertEquals(TaskStatus.BLOCKED, result.outcomes().get(1).status());
        assertEquals(TaskStatus.FAILED, result.outcomes().get(2).status(), "独立任务不受上游影响");
        assertTrue(result.report().contains("需要人工处理"), result.report());
    }

    @Test
    void differingConclusionsTriggerHumanArbitration() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        ModelClient client = (messages, tools) -> {
            int index = calls.getAndIncrement();
            String conclusion = index % 2 == 0 ? "应该删除空类" : "应该保留空类并补测试";
            return new ModelResponse("【结论】\n" + conclusion
                    + "\n\n【证据清单】\n- src/A.java:1 空类\n\n【风险评估】\n- 无\n", List.of());
        };
        var service = service(client);
        var spec = TaskSpec.readOnly("T1", "空类处置", "判断空类去留", List.of(), List.of("src/**"))
                .withReviewers(2);

        MultiAgentService.Result result = service.dispatch(dispatch(List.of(spec)));

        assertFalse(result.completed());
        assertEquals(TaskStatus.DISSENT, result.outcomes().get(0).status());
        assertEquals(1, result.dissents().size());
        assertFalse(result.arbitrationId().isBlank());
        assertTrue(events.stream().anyMatch(event -> event instanceof ArbitrationRequired required
                && "dissent".equals(required.kind())));
        assertTrue(result.report().contains("立即停止推进"), result.report());
    }

    @Test
    void parsesTaskPlanFromToolArguments() {
        List<TaskSpec> specs = DispatchAgentsTool.parseTasks(Map.of("tasks", List.of(
                Map.of("id", "T1", "goal", "审计路径校验", "files", List.of("pilot-core/src/**"),
                        "writable", false, "risk", "low"),
                Map.of("id", "T2", "goal", "补一条回归测试", "depends_on", List.of("T1"),
                        "files", List.of("pilot-core/src/test/**"), "writable", true, "risk", "medium"))));

        assertEquals(2, specs.size());
        assertEquals(List.of("T1"), specs.get(1).dependsOn());
        assertTrue(specs.get(1).scope().writable());
        assertFalse(specs.get(0).scope().writable());
    }

    @Test
    void rejectsAbsoluteOrTraversalScopes() {
        Map<String, Object> absolute = Map.of("tasks", List.of(
                Map.of("id", "T1", "goal", "x", "files", List.of("/etc/passwd"))));
        Map<String, Object> traversal = Map.of("tasks", List.of(
                Map.of("id", "T1", "goal", "x", "files", List.of("../secret/**"))));

        assertTrue(assertThrowsMessage(absolute).contains("绝对路径"));
        assertTrue(assertThrowsMessage(traversal).contains("上级目录"));
    }

    private static String assertThrowsMessage(Map<String, Object> args) {
        try {
            DispatchAgentsTool.parseTasks(args);
            throw new AssertionError("应当拒绝越界白名单");
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
