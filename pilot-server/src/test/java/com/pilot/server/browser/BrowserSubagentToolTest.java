package com.pilot.server.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.browser.BrowserBackend;
import com.pilot.core.browser.BrowserHandoffBroker;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.SubagentFinished;
import com.pilot.core.event.SubagentStarted;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.message.ToolCall;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.Availability;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.multi.BrowserSubagentTool;
import com.pilot.server.multi.SpawnAgentTool;

import tools.jackson.databind.ObjectMapper;

class BrowserSubagentToolTest {
    @TempDir
    Path temp;

    @Test
    void isolatesToolsBlocksEvaluateAttachesScreenshotAndHostFacts() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        BrowserEvidenceService evidence = new BrowserEvidenceService(temp.resolve("evidence"), new ObjectMapper());
        FakeBackend backend = new FakeBackend();
        List<SessionEvent> events = new ArrayList<>();
        AtomicInteger turn = new AtomicInteger();

        ModelClient client = (request, definitions) -> {
            assertEquals(1, definitions.size(), "浏览器子代理只能看到 browser 一个工具");
            assertEquals("browser", definitions.getFirst().name());
            int step = turn.getAndIncrement();
            if (step == 0) {
                return new ModelResponse("",
                    List.of(new ToolCall("c1", "browser", Map.of("action", "evaluate", "expression", "1+1"))));
            }
            if (step == 1) {
                return new ModelResponse("", List.of(new ToolCall("c2", "browser", Map.of("action", "screenshot"))));
            }
            return new ModelResponse("已完成移动端验收，页面渲染正常。", List.of());
        };

        BrowserSubagentTool subagent = new BrowserSubagentTool(new BrowserSubagentTool.Dependencies(backend, "ses_main",
            "run_1", new PathValidator(workspace), client, new PolicyPermissionGate(PermissionMode.ASK),
            ApprovalBroker.denyAll(), new AgentLoop.Config(10, 4, "parent"), events::add, () -> false,
            SpawnAgentTool.UsageSink.none(), new BrowserHandoffBroker(), evidence));

        ToolResult result =
            subagent.executeResult(Map.of("task", "检查首页并截图", "url", "http://127.0.0.1:5173/", "label", "首页巡检"));

        assertTrue(result.text().contains("已完成移动端验收"), result.text());
        assertTrue(result.text().contains("<browser_host_facts>"), result.text());
        assertTrue(result.text().contains("visited_origins: [http://127.0.0.1:5173]"), result.text());
        assertEquals(1, result.images().size(), "最新截图必须作为真实附件随结果交回");
        assertTrue(Files.isRegularFile(Path.of(result.images().getFirst().path())));
        assertEquals(1, backend.commands.size(), "evaluate 被禁用后不得发往后端，只有 screenshot 到达后端");
        assertEquals("screenshot", backend.commands.getFirst().get("op"));
        assertTrue(backend.sessions.getFirst().startsWith("ses_main::browser::bagent_"));
        assertTrue(events.stream().anyMatch(SubagentStarted.class::isInstance));
        assertTrue(events.stream().anyMatch(e -> e instanceof ToolStarted ts && !ts.agentId().isBlank()));
        assertTrue(events.stream().anyMatch(e -> e instanceof SubagentFinished sf && sf.ok()));
        assertFalse(evidence.list("ses_main", "run_1", 10).isEmpty());
    }

    @Test
    void supportsResumeAgentIdAndEnforcesMaxSpawnsPerRun() throws IOException {
        Path workspace = Files.createDirectories(temp.resolve("workspace-2"));
        FakeBackend backend = new FakeBackend();
        List<String> promptsSeen = new ArrayList<>();
        ModelClient client = (request, definitions) -> {
            promptsSeen.add(request.getLast().content());
            return new ModelResponse("第 " + promptsSeen.size() + " 次完成", List.of());
        };
        BrowserSubagentTool.Dependencies dependencies = new BrowserSubagentTool.Dependencies(backend, "ses_2", "run_2",
            new PathValidator(workspace), client, new PolicyPermissionGate(PermissionMode.READ_ONLY),
            ApprovalBroker.denyAll(), new AgentLoop.Config(6, 4, "sys"), event -> {
            }, () -> false, SpawnAgentTool.UsageSink.none(), new BrowserHandoffBroker(), null);
        BrowserSubagentTool subagent = new BrowserSubagentTool(dependencies, new BrowserSubagentTool.Budget(8, 1, 2));

        String first = subagent.execute(Map.of("task", "打开登录页"));
        String agentId = first.lines().filter(l -> l.startsWith("agent_id: ")).findFirst().orElseThrow()
            .substring("agent_id: ".length()).strip();

        String resumed = subagent.execute(Map.of("task", "点击继续", "resume_agent_id", agentId));
        assertTrue(resumed.contains("agent_id: " + agentId), resumed);
        assertTrue(promptsSeen.get(1).contains("继续同一浏览器子代理任务"));

        String exceeded = subagent.execute(Map.of("task", "再开一个子代理"));
        assertTrue(exceeded.startsWith("ERROR: 本轮浏览器子代理创建次数已达上限"), exceeded);
    }

    @Test
    void recordsSubagentExecutionAndExposesReplayPathInHostFacts() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace-rec"));
        BrowserEvidenceService evidence = new BrowserEvidenceService(temp.resolve("evidence-rec"), new ObjectMapper());
        FakeBackend backend = new FakeBackend(true);
        AtomicInteger turn = new AtomicInteger();
        ModelClient client = (request, definitions) -> turn.getAndIncrement() == 0
            ? new ModelResponse("", List.of(new ToolCall("c1", "browser", Map.of("action", "screenshot"))))
            : new ModelResponse("已完成录制任务。", List.of());

        String prevArtifacts = System.getProperty("pilot.artifacts.dir");
        System.setProperty("pilot.artifacts.dir", temp.resolve("artifacts-rec").toString());
        try {
            BrowserSubagentTool subagent =
                new BrowserSubagentTool(new BrowserSubagentTool.Dependencies(backend, "ses_rec", "run_rec",
                    new PathValidator(workspace), client, new PolicyPermissionGate(PermissionMode.ASK),
                    ApprovalBroker.denyAll(), new AgentLoop.Config(6, 4, "parent"), event -> {
                    }, () -> false, SpawnAgentTool.UsageSink.none(), new BrowserHandoffBroker(), evidence));

            String output = subagent.execute(Map.of("task", "记录测试过程", "label", "自动录屏"));
            String recordingLine =
                output.lines().filter(l -> l.startsWith("recording_path:")).findFirst().orElseThrow();
            assertTrue(recordingLine.endsWith(".replay.json"), recordingLine);
            assertTrue(
                backend.commands.stream().anyMatch(c -> "record".equals(c.get("op")) && "start".equals(c.get("mode"))));
            assertTrue(
                backend.commands.stream().anyMatch(c -> "record".equals(c.get("op")) && "stop".equals(c.get("mode"))));
            assertTrue(evidence.list("ses_rec", "run_rec", 10).stream().anyMatch(
                rec -> "record".equals(rec.action()) && rec.recordingPath() != null && !rec.recordingPath().isBlank()));
        } finally {
            if (prevArtifacts == null) {
                System.clearProperty("pilot.artifacts.dir");
            } else {
                System.setProperty("pilot.artifacts.dir", prevArtifacts);
            }
        }
    }

    private static final class FakeBackend implements BrowserBackend {
        private final List<Map<String, Object>> commands = new ArrayList<>();
        private final List<String> sessions = new ArrayList<>();
        private final boolean recordSupported;

        private FakeBackend() {
            this(false);
        }

        private FakeBackend(boolean recordSupported) {
            this.recordSupported = recordSupported;
        }

        @Override
        public Availability availability() {
            return Availability.ready();
        }

        @Override
        public boolean supportsProcessRecording() {
            return recordSupported;
        }

        @Override
        public Reply execute(String sessionId, Map<String, Object> command, Duration timeout) {
            commands.add(command);
            sessions.add(sessionId);
            if ("screenshot".equals(command.get("op"))) {
                try {
                    Path path = Path.of(String.valueOf(command.get("path")));
                    Files.createDirectories(path.getParent());
                    Files.write(path, new byte[] {1, 2, 3, 4});
                    return new Reply("已截图", path.toString(), Map.of("url", "http://127.0.0.1:5173/", "title", "Demo"));
                } catch (IOException e) {
                    throw new BrowserException(e.getMessage());
                }
            }
            if ("record".equals(command.get("op")) && "stop".equals(command.get("mode"))) {
                try {
                    Path path = Path.of(String.valueOf(command.get("path")));
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, "{\"version\":1,\"frames\":[],\"actions\":[]}");
                    return new Reply("已保存录像", null,
                        Map.of("url", "http://127.0.0.1:5173/", "recording", Map.of("path", path.toString())));
                } catch (IOException e) {
                    throw new BrowserException(e.getMessage());
                }
            }
            return new Reply("ok", null, Map.of("url", "http://127.0.0.1:5173/"));
        }

        @Override
        public void closeSession(String sessionId) {
        }
    }
}
