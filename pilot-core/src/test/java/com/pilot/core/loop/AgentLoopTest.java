package com.pilot.core.loop;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelClientException;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PermissionResult;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.tool.ProducesCheck;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.WritesFiles;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {
    private final List<SessionEvent> events = new ArrayList<>();

    @Test
    void executesToolCallThenReturnsFinalAnswer() {
        var tool = new CountingTool("list_dir", "a.txt\nsrc/");
        var client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "list_dir", Map.of("path", ".")))),
                new ModelResponse("工作区有 a.txt 和 src 目录。", List.of()));
        var loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("列出目录", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals("工作区有 a.txt 和 src 目录。", result.finalText());
        assertEquals(1, tool.executions.get());
        assertEquals(List.of("tool.started", "tool.completed", "assistant.delta", "run.completed"),
                eventTypes());

        List<Message> secondRequest = client.requests.get(1);
        Message toolMessage = secondRequest.get(secondRequest.size() - 1);
        assertEquals("tool", toolMessage.role());
        assertEquals("call_1", toolMessage.toolCallId());
        assertEquals("a.txt\nsrc/", toolMessage.content());
        assertTrue(secondRequest.get(secondRequest.size() - 2).hasToolCalls());
    }

    @Test
    void sendsSystemPromptUserInputAndToolDefinitions() {
        var tool = new CountingTool("read_file", "content");
        var client = new ScriptedModelClient(new ModelResponse("不需要工具", List.of()));
        var loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));

        loop.run("读 pom.xml", List.of(), events::add);

        List<Message> firstRequest = client.requests.get(0);
        assertEquals("system", firstRequest.get(0).role());
        assertEquals("user", firstRequest.get(1).role());
        assertEquals("读 pom.xml", firstRequest.get(1).content());
        assertEquals(List.of("read_file"), client.toolDefinitions.get(0).stream().map(ToolDefinition::name).toList());
        assertEquals(List.of("assistant.delta", "run.completed"), eventTypes());
    }

    @Test
    void interruptsAfterThreeIdenticalToolCalls() {
        var tool = new CountingTool("read_file", "content");
        var call = new ToolCall("call_1", "read_file", Map.of("path", "pom.xml"));
        var client = new ScriptedModelClient(
                new ModelResponse("", List.of(call)),
                new ModelResponse("", List.of(call)),
                new ModelResponse("", List.of(call)),
                new ModelResponse("不该走到这里", List.of()));
        var loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("读 pom.xml", List.of(), events::add);

        assertEquals("REPEATED_TOOL_CALL", result.failureCode());
        assertEquals(2, tool.executions.get());
        assertEquals(3, client.requests.size());
        assertTrue(events.stream().anyMatch(event -> event instanceof RunFailed failed
                && failed.code().equals("REPEATED_TOOL_CALL")));
        assertFalse(eventTypes().contains("run.completed"));
    }

    @Test
    void stopsAtMaxSteps() {
        var tool = new CountingTool("read_file", "content");
        var client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "read_file", Map.of("path", "a")))),
                new ModelResponse("", List.of(new ToolCall("call_2", "read_file", Map.of("path", "b")))),
                new ModelResponse("done", List.of()));
        var loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(2, 3));

        AgentLoop.Result result = loop.run("读两个文件", List.of(), events::add);

        assertEquals("MAX_STEPS", result.failureCode());
        assertEquals(2, client.requests.size());
        assertEquals(2, tool.executions.get());
    }

    @Test
    void deniedToolIsReportedWithoutExecution() {
        var tool = new CountingTool("write_file", "written");
        PermissionGate denyAll = (call, candidate) -> PermissionResult.deny("Sprint 2 只支持只读");
        var client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "write_file", Map.of("path", "a.txt")))),
                new ModelResponse("好的，我不写文件。", List.of()));
        var loop = new AgentLoop(client, registry(tool), denyAll, config(8, 3));

        AgentLoop.Result result = loop.run("写个文件", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals(0, tool.executions.get());
        List<Message> secondRequest = client.requests.get(1);
        String toolResult = secondRequest.get(secondRequest.size() - 1).content();
        assertTrue(toolResult.startsWith("ERROR: 权限拒绝"), toolResult);
        ToolCompleted completed = (ToolCompleted) events.stream()
                .filter(event -> event instanceof ToolCompleted).findFirst().orElseThrow();
        assertTrue(completed.resultSummary().contains("权限拒绝"), completed.resultSummary());
    }

    @Test
    void reportsChangedFilesAndChecksOnCompletion() {
        var tool = new WritingTool("edit_file", "a.txt", "mvn -q test → exit 0");
        var client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "edit_file", Map.of("path", "a.txt")))),
                new ModelResponse("改好了，测试通过。", List.of()));
        var loop = new AgentLoop(client, registry(tool), new PolicyPermissionGate(PermissionMode.AUTO), config(8, 3));

        loop.run("改一下 a.txt", List.of(), events::add);

        RunCompleted completed = (RunCompleted) events.stream()
                .filter(event -> event instanceof RunCompleted).findFirst().orElseThrow();
        assertEquals(List.of("a.txt"), completed.changedFiles());
        assertEquals(List.of("mvn -q test → exit 0"), completed.checks());
    }

    @Test
    void deniedWriteDoesNotCountAsChange() {
        var tool = new WritingTool("edit_file", "a.txt", "mvn -q test → exit 0");
        var client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "edit_file", Map.of("path", "a.txt")))),
                new ModelResponse("没改成。", List.of()));
        var loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));

        loop.run("改一下 a.txt", List.of(), events::add);

        RunCompleted completed = (RunCompleted) events.stream()
                .filter(event -> event instanceof RunCompleted).findFirst().orElseThrow();
        assertTrue(completed.changedFiles().isEmpty(), completed.changedFiles().toString());
        assertTrue(completed.checks().isEmpty(), completed.checks().toString());
    }

    @Test
    void cancellationStopsBeforeCallingModel() {
        var client = new ScriptedModelClient(new ModelResponse("不该被调用", List.of()));
        var loop = new AgentLoop(client, registry(), readOnlyGate(), ApprovalBroker.denyAll(),
                config(8, 3), () -> true);

        AgentLoop.Result result = loop.run("你好", List.of(), events::add);

        assertEquals("CANCELLED", result.failureCode());
        assertTrue(client.requests.isEmpty(), "取消后不应再请求模型");
        assertTrue(events.stream().anyMatch(event -> event instanceof RunCancelled));
    }

    @Test
    void compactsLargeToolOutputBeforeNextRequest() {
        var tool = new CountingTool("read_file", "x".repeat(20_000));
        var client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "read_file", Map.of("path", "big.txt")))),
                new ModelResponse("读完了", List.of()));
        System.setProperty("pilot.context.max-chars", "2000");
        try {
            var loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));
            loop.run("读大文件", List.of(), events::add);
        } finally {
            System.clearProperty("pilot.context.max-chars");
        }

        List<Message> secondRequest = client.requests.get(1);
        String toolResult = secondRequest.getLast().content();
        assertTrue(toolResult.startsWith("[工具输出已省略"), toolResult);
    }

    @Test
    void unknownToolIsReportedToModel() {
        var client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "drop_database", Map.of()))),
                new ModelResponse("工具不存在。", List.of()));
        var loop = new AgentLoop(client, registry(), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("删库", List.of(), events::add);

        assertNull(result.failureCode());
        String toolResult = client.requests.get(1).getLast().content();
        assertTrue(toolResult.contains("未知工具 drop_database"), toolResult);
    }

    @Test
    void modelFailureEndsRun() {
        ModelClient failing = (messages, tools) -> {
            throw new ModelClientException("HTTP 503");
        };
        var loop = new AgentLoop(failing, registry(), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("你好", List.of(), events::add);

        assertEquals("MODEL_ERROR", result.failureCode());
        assertEquals(List.of("run.failed"), eventTypes());
        RunFailed failed = (RunFailed) events.getFirst();
        assertTrue(failed.message().contains("HTTP 503"), failed.message());
        assertTrue(failed.retryable());
    }

    /** 运行中追加的指令应在下一轮采样前进入历史，并且不新开 run。 */
    @Test
    void injectsSteeredInputBeforeNextStep() {
        var tool = new CountingTool("list_dir", "a.txt");
        var client = new ScriptedModelClient(
                new ModelResponse("先看看", List.of(new ToolCall("call_1", "list_dir", Map.of("path", ".")))),
                new ModelResponse("完成", List.of()));
        Deque<String> inbox = new ArrayDeque<>(List.of("只改 A 模块，别动 B"));
        var loop = new AgentLoop(client, registry(tool), readOnlyGate(), ApprovalBroker.denyAll(),
                config(8, 3), Cancellation.none(), inbox::poll);

        AgentLoop.Result result = loop.run("整理代码", List.of(), events::add);

        assertNull(result.failureCode());
        assertTrue(eventTypes().contains("run.steered"));
        List<Message> secondRequest = client.requests.get(1);
        assertTrue(secondRequest.stream().anyMatch(message -> "user".equals(message.role())
                        && message.content().contains("只改 A 模块")),
                "追加的指令应作为 user 消息进入下一轮请求");
    }

    /** 跨过 70% 预算就该在采样前压缩，而不是等到爆掉。 */
    @Test
    void compactsBeforeSamplingWhenBudgetCrossesSoftLimit() {
        String previous = System.getProperty("pilot.context.max-chars");
        System.setProperty("pilot.context.max-chars", "4000");
        try {
            var history = new ArrayList<Message>();
            for (int i = 0; i < 12; i++) {
                history.add(Message.user("第 " + i + " 轮：" + "y".repeat(300)));
            }
            var client = new ScriptedModelClient(
                    new ModelResponse("## 目标\n继续任务", List.of()),
                    new ModelResponse("完成", List.of()));
            var loop = new AgentLoop(client, registry(new CountingTool("list_dir", "")), readOnlyGate(), config(4, 3));

            loop.run("继续任务", history, events::add);

            assertTrue(eventTypes().contains("context.compacted"), "超过软阈值应先压缩再采样");
        } finally {
            if (previous == null) {
                System.clearProperty("pilot.context.max-chars");
            } else {
                System.setProperty("pilot.context.max-chars", previous);
            }
        }
    }

    private List<String> eventTypes() {
        return events.stream().map(event -> event.type().wireName()).toList();
    }

    private static AgentLoop.Config config(int maxSteps, int repeatedLimit) {
        return new AgentLoop.Config(maxSteps, repeatedLimit, "你是测试代理");
    }

    /** 关闭自动续跑时，模型提前收尾必须把差距交给界面，别让人以为干完了。 */
    @Test
    void reportsOpenPlanStepsWhenModelStopsEarly() {
        var client = new ScriptedModelClient(new ModelResponse("先做到这里。", List.of()));
        var plan = List.of(
                new PlanItem("检查分支状态", PlanItem.COMPLETED),
                new PlanItem("跑回归测试", PlanItem.PENDING));
        var loop = new AgentLoop(client, registry(new CountingTool("list_dir", "a.txt")), readOnlyGate(),
                new AgentLoop.Config(8, 3, "你是测试代理", () -> plan, 0));

        loop.run("继续", List.of(), events::add);

        RunCompleted completed = events.stream()
                .filter(RunCompleted.class::isInstance)
                .map(RunCompleted.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(completed.planOpen(), "还有 pending 项时不能算做完");
        assertEquals(1, completed.planDone().intValue());
        assertEquals(2, completed.planTotal().intValue());
        assertEquals(List.of("跑回归测试"), completed.openSteps());
    }

    @Test
    void reportsPlainCompletionWhenPlanIsDone() {
        var client = new ScriptedModelClient(new ModelResponse("全部完成。", List.of()));
        var plan = List.of(new PlanItem("跑回归测试", PlanItem.COMPLETED));
        var loop = new AgentLoop(client, registry(new CountingTool("list_dir", "a.txt")), readOnlyGate(),
                new AgentLoop.Config(8, 3, "你是测试代理", () -> plan));

        loop.run("继续", List.of(), events::add);

        RunCompleted completed = events.stream()
                .filter(RunCompleted.class::isInstance)
                .map(RunCompleted.class::cast)
                .findFirst()
                .orElseThrow();
        assertFalse(completed.planOpen());
        assertEquals(1, completed.planDone().intValue());
        assertEquals(1, completed.planTotal().intValue());
    }

    /** 计划没做完时不该停下来等用户，应该自动催一轮继续，直到计划标记完成。 */
    @Test
    void continuesAutomaticallyWhilePlanHasOpenSteps() {
        var client = new ScriptedModelClient(
                new ModelResponse("先做到这里。", List.of()),
                new ModelResponse("全部做完了。", List.of()));
        var round = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<List<PlanItem>> plan = () -> round.getAndIncrement() == 0
                ? List.of(new PlanItem("检查分支状态", PlanItem.COMPLETED),
                          new PlanItem("跑回归测试", PlanItem.PENDING))
                : List.of(new PlanItem("检查分支状态", PlanItem.COMPLETED),
                          new PlanItem("跑回归测试", PlanItem.COMPLETED));
        var loop = new AgentLoop(client, registry(new CountingTool("list_dir", "a.txt")), readOnlyGate(),
                new AgentLoop.Config(8, 3, "你是测试代理", plan, 3));

        loop.run("继续", List.of(), events::add);

        assertEquals(1, events.stream().filter(RunContinued.class::isInstance).count(),
                "应自动提示模型继续一次");
        assertEquals(2, client.requests.size(), "模型应被调用两轮");
        RunCompleted completed = events.stream()
                .filter(RunCompleted.class::isInstance)
                .map(RunCompleted.class::cast)
                .findFirst()
                .orElseThrow();
        assertFalse(completed.planOpen(), "第二轮计划已全部完成");
        // 继续提示要真的送进下一轮上下文，否则模型不知道还要做什么
        String secondRound = client.requests.get(1).get(client.requests.get(1).size() - 1).content();
        assertTrue(secondRound.contains("跑回归测试"), secondRound);
        assertTrue(secondRound.contains("自动继续"), secondRound);
    }

    /** 模型死活不改计划时必须停手，把未完成项交回界面，不能无限催。 */
    @Test
    void stopsAfterAutoContinueLimit() {
        var client = new ScriptedModelClient(
                new ModelResponse("做到这里。", List.of()),
                new ModelResponse("还是做到这里。", List.of()));
        var plan = List.of(new PlanItem("跑回归测试", PlanItem.PENDING));
        var loop = new AgentLoop(client, registry(new CountingTool("list_dir", "a.txt")), readOnlyGate(),
                new AgentLoop.Config(8, 3, "你是测试代理", () -> plan, 1));

        loop.run("继续", List.of(), events::add);

        assertEquals(1, events.stream().filter(RunContinued.class::isInstance).count());
        RunCompleted completed = events.stream()
                .filter(RunCompleted.class::isInstance)
                .map(RunCompleted.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(completed.planOpen(), "到达上限后仍未完成，要如实标出来");
        assertEquals(List.of("跑回归测试"), completed.openSteps());
    }

    private static PolicyPermissionGate readOnlyGate() {
        return new PolicyPermissionGate(PermissionMode.READ_ONLY);
    }

    private static ToolRegistry registry(Tool... tools) {
        var registry = new ToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private static final class ScriptedModelClient implements ModelClient {
        private final Deque<ModelResponse> script = new ArrayDeque<>();
        private final List<List<Message>> requests = new ArrayList<>();
        private final List<List<ToolDefinition>> toolDefinitions = new ArrayList<>();

        private ScriptedModelClient(ModelResponse... responses) {
            script.addAll(List.of(responses));
        }

        @Override
        public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            requests.add(List.copyOf(messages));
            toolDefinitions.add(List.copyOf(tools));
            ModelResponse next = script.poll();
            if (next == null) {
                throw new IllegalStateException("脚本已用完");
            }
            return next;
        }
    }

    /** 模拟写文件 + 命令验证工具：用于断言 changedFiles / checks 汇总。 */
    private static final class WritingTool implements Tool, WritesFiles, ProducesCheck {
        private final String name;
        private final String path;
        private final String check;

        private WritingTool(String name, String path, String check) {
            this.name = name;
            this.path = path;
            this.check = check;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试用写工具";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object");
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public String execute(Map<String, Object> args) {
            return "已修改 " + path;
        }

        @Override
        public String changedPath(ToolCall call) {
            return path;
        }

        @Override
        public String checkSummary(ToolCall call, String result) {
            return check;
        }
    }

    private static final class CountingTool implements Tool {
        private final String name;
        private final String result;
        private final AtomicInteger executions = new AtomicInteger();

        private CountingTool(String name, String result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试工具";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object");
        }

        @Override
        public boolean readOnly() {
            return name.startsWith("read") || name.startsWith("list") || name.startsWith("search");
        }

        @Override
        public String execute(Map<String, Object> args) {
            executions.incrementAndGet();
            return result;
        }
    }
}
