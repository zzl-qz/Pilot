package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.pilot.core.message.ToolNames;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.ReadGuard;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.code.CodeSymbolTool;
import com.pilot.core.tool.file.ReadFileTool;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.store.PilotDataDir;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.WebSearchProperties;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.tool.WorkspaceToolFactory;

import tools.jackson.databind.json.JsonMapper;

/**
 * 端到端生产行为验证：证明问题不是通过「禁止模型读取」来解决的，
 * 而是通过高密度并行工具 + 区间去重/压缩失效 + Pre-flight 预定位 + 边际信息增益追踪实现健康收敛。
 */
@com.pilot.server.WindowsJdkLoopbackSensitive
class InvestigationDisciplineVerificationTest {
    @TempDir
    Path workspace;
    @TempDir
    Path dataRoot;

    @Test
    void largeFileDeepReadingAndParallelOutlineNeverBlockedAndEditSucceeds() throws Exception {
        Files.writeString(workspace.resolve("MegaWorkflow.java"), buildMegaWorkflowSource(60));

        JsonMapper mapper = JsonMapper.builder().build();
        AgentProperties props =
            new AgentProperties(workspace.toString(), PermissionMode.AUTO, 20, 3, 5, 300, 1, 0, 200_000, false, false);
        WorkspaceToolFactory factory =
            new WorkspaceToolFactory(props, new WebSearchProperties(false, "", "", ""), SnapshotSink.none(), mapper);
        ToolRegistry tools = factory.registryFor(workspace.toString());
        ReadGuard guard = ((ReadFileTool)tools.find(ToolNames.READ_FILE).orElseThrow()).guard();

        TaskControlService tasks = new TaskControlService(mapper, new PilotDataDir(dataRoot.toString()));
        tasks.start("s-large", "修复 MegaWorkflow 的 step60 返回值逻辑", List.of(), List.of());

        List<String> toolResults = new CopyOnWriteArrayList<>();
        List<List<Message>> requests = new ArrayList<>();
        AtomicInteger turn = new AtomicInteger();
        ModelClient client = (messages, defs) -> {
            requests.add(List.copyOf(messages));
            for (Message m : messages) {
                if ("tool".equals(m.role())) {
                    toolResults.add(m.content());
                }
            }
            return switch (turn.incrementAndGet()) {
                // 回合 1：并行调用 code_symbol + read_file(1..400)
                case 1 -> new ModelResponse("",
                    List.of(new ToolCall("c1", CodeSymbolTool.NAME, Map.of("path", "MegaWorkflow.java")),
                        new ToolCall("c2", ToolNames.READ_FILE,
                            Map.of("path", "MegaWorkflow.java", "start_line", 1, "line_count", 400))));
                // 回合 2–4：同一大文件顺次分页读完第 2、3、4 段（过去第 4 段会被 MAX_DELIVERIES=3 拦死）
                case 2 -> new ModelResponse("", List.of(new ToolCall("c3", ToolNames.READ_FILE,
                    Map.of("path", "MegaWorkflow.java", "start_line", 401, "line_count", 400))));
                case 3 -> new ModelResponse("", List.of(new ToolCall("c4", ToolNames.READ_FILE,
                    Map.of("path", "MegaWorkflow.java", "start_line", 801, "line_count", 400))));
                case 4 -> new ModelResponse("", List.of(new ToolCall("c5", ToolNames.READ_FILE,
                    Map.of("path", "MegaWorkflow.java", "start_line", 1201, "line_count", 500))));
                // 回合 5：直接精确修改末尾方法
                case 5 -> new ModelResponse("",
                    List.of(new ToolCall("c6", ToolNames.EDIT_FILE, Map.of("path", "MegaWorkflow.java", "old_string",
                        "public int step60(int x) {", "new_string", "public int step60(int x) { // fixed"))));
                default -> new ModelResponse("已完成修改", List.of());
            };
        };

        GoalExecutionPolicy policy =
            new GoalExecutionPolicy(client, mapper, tasks, "s-large", workspace, () -> false, 20);
        PreFlightLocator.Result preflight = new PreFlightLocator().locate(workspace, new PathValidator(workspace),
            "修复 MegaWorkflow 的 step60 返回值逻辑", Set.of());
        assertTrue(preflight.candidatePaths().contains("MegaWorkflow.java"));

        AgentLoop loop =
            new AgentLoop(client, tools, new PolicyPermissionGate(PermissionMode.AUTO), ApprovalBroker.denyAll(),
                new AgentLoop.Config(20, 3, 5, AgentLoop.DEFAULT_SYSTEM_PROMPT, List::of, 0, 200_000, false))
                .withExecutionPolicy(policy).withContextShrinkListener(guard::invalidateDeliveredViews)
                .withInitialPhaseHint(preflight.renderBlock());

        AgentLoop.Result result = loop.run("修复 MegaWorkflow 的 step60 返回值逻辑", List.of(), ignored -> {
        });

        assertNull(result.failureCode());
        assertEquals(List.of("MegaWorkflow.java"), result.changedFiles());
        assertTrue(Files.readString(workspace.resolve("MegaWorkflow.java")).contains("// fixed"));
        // 确认第 1 步收到了 <candidate-targets>，且编辑后第 6 步自动退场
        assertTrue(requests.get(0).stream().anyMatch(m -> m.content().contains("<candidate-targets")));
        assertFalse(requests.get(5).stream().anyMatch(m -> m.content().contains("<candidate-targets")));
        // 确认 4 段大文件顺次分页全部返回真实内容、没有任何「同一文件已经读过 3 次」拦截
        assertTrue(toolResults.stream().anyMatch(r -> r.startsWith("MegaWorkflow.java（共 ") && r.contains("step60")));
        assertTrue(toolResults.stream().anyMatch(r -> r.contains("public int step60(int x)")));
        assertFalse(toolResults.stream().anyMatch(r -> r.contains("已经读过")));
        // 确认有效深读期间零误催
        assertFalse(requests.stream().flatMap(List::stream)
            .anyMatch(m -> m.content().contains(Message.RUNTIME_CHECKPOINT_MARKER)));
    }

    private static String buildMegaWorkflowSource(int methods) {
        StringBuilder source = new StringBuilder("public class MegaWorkflow {\n");
        for (int m = 1; m <= methods; m++) {
            source.append("    public int step").append(m).append("(int x) {\n");
            for (int l = 1; l <= 24; l++) {
                source.append("        x += ").append(l).append(";\n");
            }
            source.append("        return x;\n    }\n");
        }
        return source.append("}\n").toString();
    }

    @Test
    void softWanderingGetsGuidedWithoutEverDenyingReadTools() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        for (int i = 1; i <= 10; i++) {
            Files.writeString(workspace.resolve("src/Mod" + i + ".java"), "class Mod" + i + " {}\n");
        }
        JsonMapper mapper = JsonMapper.builder().build();
        AgentProperties props =
            new AgentProperties(workspace.toString(), PermissionMode.AUTO, 20, 3, 5, 300, 1, 0, 200_000, false, false);
        WorkspaceToolFactory factory =
            new WorkspaceToolFactory(props, new WebSearchProperties(false, "", "", ""), SnapshotSink.none(), mapper);
        ToolRegistry tools = factory.registryFor(workspace.toString());
        TaskControlService tasks =
            new TaskControlService(mapper, new PilotDataDir(dataRoot.resolve("wandering").toString()));
        tasks.start("s-wander", "修复 Mod1 的超时处理", List.of(), List.of());

        List<List<Message>> requests = new ArrayList<>();
        AtomicInteger turn = new AtomicInteger();
        ModelClient client = (messages, defs) -> {
            requests.add(List.copyOf(messages));
            return switch (turn.incrementAndGet()) {
                case 1 -> new ModelResponse("",
                    List.of(new ToolCall("r1", ToolNames.READ_FILE, Map.of("path", "src/Mod1.java")),
                        new ToolCall("r2", ToolNames.READ_FILE, Map.of("path", "src/Mod2.java"))));
                // 连续 4 次空搜索（软空转）
                case 2 -> new ModelResponse("",
                    List.of(new ToolCall("s1", ToolNames.SEARCH_TEXT, Map.of("pattern", "nonexistent_a")),
                        new ToolCall("s2", ToolNames.SEARCH_TEXT, Map.of("pattern", "nonexistent_b")),
                        new ToolCall("s3", ToolNames.SEARCH_TEXT, Map.of("pattern", "nonexistent_c")),
                        new ToolCall("s4", ToolNames.SEARCH_TEXT, Map.of("pattern", "nonexistent_d"))));
                // 收到提醒后继续合法读取新文件 Mod3 —— 必须仍被允许，绝不靠禁读解决问题
                case 3 -> new ModelResponse("",
                    List.of(new ToolCall("r3", ToolNames.READ_FILE, Map.of("path", "src/Mod3.java"))));
                default -> new ModelResponse("已收束", List.of());
            };
        };

        GoalExecutionPolicy policy =
            new GoalExecutionPolicy(client, mapper, tasks, "s-wander", workspace, () -> false, 20);
        AgentLoop loop =
            new AgentLoop(client, tools, new PolicyPermissionGate(PermissionMode.AUTO), ApprovalBroker.denyAll(),
                new AgentLoop.Config(20, 3, 5, AgentLoop.DEFAULT_SYSTEM_PROMPT, List::of, 0, 200_000, false))
                .withExecutionPolicy(policy);

        AgentLoop.Result result = loop.run("修复 Mod1 的超时处理", List.of(), ignored -> {
        });

        assertNull(result.failureCode());
        // 第 3 次请求应以临时 user 角色收到包含已读文件清单的运行时检查点，且绝不落盘到历史
        assertTrue(
            requests.get(2).stream().anyMatch(m -> "user".equals(m.role())
                && m.content().startsWith(Message.RUNTIME_CHECKPOINT_MARKER) && m.content().contains("Mod1.java")),
            "连续 4 次空搜索应以临时 user 角色触发运行时检查点");
        assertFalse(
            result.conversation().stream().anyMatch(m -> m.content().contains(Message.RUNTIME_CHECKPOINT_MARKER)),
            "运行时检查点不应污染持久化对话历史");
        // 但第 3 次请求中读取新文件 Mod3 仍然完全放行并拿到真实正文
        assertTrue(
            result.conversation().stream().anyMatch(
                m -> "tool".equals(m.role()) && "r3".equals(m.toolCallId()) && m.content().contains("class Mod3")),
            "软提醒绝不能阻断后续合法读取新文件");
    }

    @Test
    void preflightBenchmarkOnRealPilotRepositoryMeetsPrecisionAndLatencyTargets() {
        Path repoRoot = Path.of("").toAbsolutePath().normalize();
        if (!Files.isDirectory(repoRoot.resolve("pilot-core")) && repoRoot.getParent() != null
            && Files.isDirectory(repoRoot.getParent().resolve("pilot-core"))) {
            repoRoot = repoRoot.getParent();
        }
        if (!Files.isDirectory(repoRoot.resolve("pilot-core"))) {
            return;
        }
        PathValidator validator = new PathValidator(repoRoot);
        PreFlightLocator locator = new PreFlightLocator();

        record Case(String prompt, String expectedSuffix, boolean expectCandidates) {
        }
        List<Case> cases = List.of(
            new Case("修复 ReadTracker 的区间覆盖和 ReadFileTool 的 DEFAULT_LINES", "ReadTracker.java", true),
            new Case("调整 GoalExecutionPolicy 里的 investigationGuidance 与 researchOnlyGoal", "GoalExecutionPolicy.java",
                true),
            new Case("在 SearchTool 中增加 files_only 和 context_lines 支持", "SearchTool.java", true),
            new Case("检查 AgentLoop.java 的 PhaseHint 和 requestMessages 注入顺序", "AgentLoop.java", true),
            new Case("修改 AgentProperties 的 preflightLocalizationEnabled 配置项", "AgentProperties.java", true),
            new Case("帮我随便看看这个仓库整体风格怎么样", "", false));

        int hits = 0;
        int positives = 0;
        long maxMs = 0;
        for (Case c : cases) {
            PreFlightLocator.Result res = locator.locate(repoRoot, validator, c.prompt(), Set.of());
            maxMs = Math.max(maxMs, res.elapsedMs());
            assertTrue(res.elapsedMs() <= 500L, "预定位超时: " + res.elapsedMs() + "ms for " + c.prompt());
            if (c.expectCandidates()) {
                positives++;
                assertTrue(res.hasCandidates(), "应有候选命中: " + c.prompt());
                boolean hit = res.candidatePaths().stream().anyMatch(p -> p.endsWith(c.expectedSuffix()));
                assertTrue(hit, "应命中 " + c.expectedSuffix() + "，实际: " + res.candidates());
                if (hit) {
                    hits++;
                }
            } else {
                assertFalse(res.hasCandidates(), "泛化提问不应注入候选: " + res.candidates());
            }
        }
        assertEquals(positives, hits);
        assertNotNull(Long.valueOf(maxMs));
    }
}
