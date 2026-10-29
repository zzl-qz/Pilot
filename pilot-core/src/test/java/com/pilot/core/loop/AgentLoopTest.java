package com.pilot.core.loop;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunRetrying;
import com.pilot.core.event.RunUsageUpdated;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {
    private final List<SessionEvent> events = new ArrayList<>();

    @Test
    void executesToolCallThenReturnsFinalAnswer() {
        CountingTool tool = new CountingTool("list_dir", "a.txt\nsrc/");
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "list_dir", Map.of("path", ".")))),
                new ModelResponse("工作区有 a.txt 和 src 目录。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("列出目录", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals("工作区有 a.txt 和 src 目录。", result.finalText());
        assertEquals(1, tool.executions.get());
        assertEquals(List.of("tool.started", "tool.completed", "assistant.delta", "run.completed"),
                eventTypesWithoutUsage());
        assertEquals(4, events.stream().filter(RunUsageUpdated.class::isInstance).count());

        List<Message> secondRequest = client.requests.get(1);
        Message toolMessage = secondRequest.get(secondRequest.size() - 1);
        assertEquals("tool", toolMessage.role());
        assertEquals("call_1", toolMessage.toolCallId());
        assertEquals("a.txt\nsrc/", toolMessage.content());
        assertTrue(secondRequest.get(secondRequest.size() - 2).hasToolCalls());
    }

    @Test
    void sendsSystemPromptUserInputAndToolDefinitions() {
        CountingTool tool = new CountingTool("read_file", "content");
        ScriptedModelClient client = new ScriptedModelClient(new ModelResponse("不需要工具", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));

        loop.run("读 pom.xml", List.of(), events::add);

        List<Message> firstRequest = client.requests.get(0);
        assertEquals("system", firstRequest.get(0).role());
        assertEquals("user", firstRequest.get(1).role());
        assertEquals("读 pom.xml", firstRequest.get(1).content());
        assertEquals(List.of("read_file"), client.toolDefinitions.get(0).stream().map(ToolDefinition::name).toList());
        assertEquals(List.of("assistant.delta", "run.completed"), eventTypesWithoutUsage());
        assertEquals(2, events.stream().filter(RunUsageUpdated.class::isInstance).count());
    }

    @Test
    void injectsExecutionPolicyGuidanceAsEphemeralSystemContext() {
        ScriptedModelClient client = new ScriptedModelClient(new ModelResponse("已按现有证据实施。", List.of()));
        AtomicBoolean delivered = new AtomicBoolean();
        ExecutionPolicy policy = new ExecutionPolicy() {
            @Override public void beforeRequest(List<Message> history) { }
            @Override public String requestGuidance() {
                return delivered.compareAndSet(false, true) ? "[执行效率检查] 立即实施或交付。" : null;
            }
            @Override public String review(List<Message> history, Set<String> changedFiles, List<String> checks) {
                return null;
            }
        };
        AgentLoop loop = new AgentLoop(client, registry(), readOnlyGate(), config(8, 3))
                .withExecutionPolicy(policy);

        AgentLoop.Result result = loop.run("按方案开始修改", List.of(), events::add);

        assertNull(result.failureCode());
        assertTrue(client.requests.getFirst().getFirst().content().contains("执行效率检查"),
                client.requests.getFirst().getFirst().content());
        assertFalse(client.requests.getFirst().stream().anyMatch(message -> "user".equals(message.role())
                && message.content().contains("执行效率检查")),
                "运行时 guidance 不能伪装成用户请求");
        assertFalse(result.conversation().stream().anyMatch(message -> message.content().contains("执行效率检查")),
                "运行时 guidance 不能进入持久会话");
    }

    @Test
    void injectsPolicyContinuationAsEphemeralSystemContext() {
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("候选结果。", List.of()),
                new ModelResponse("最终结果。", List.of()));
        AtomicInteger reviews = new AtomicInteger();
        ExecutionPolicy policy = new ExecutionPolicy() {
            @Override public void beforeRequest(List<Message> history) { }
            @Override public String review(List<Message> history, Set<String> changedFiles, List<String> checks) {
                return reviews.getAndIncrement() == 0 ? "只执行唯一下一动作：补充针对性验证。" : null;
            }
        };
        AgentLoop loop = new AgentLoop(client, registry(), readOnlyGate(), config(8, 3))
                .withExecutionPolicy(policy);

        AgentLoop.Result result = loop.run("完成任务", List.of(), events::add);

        assertNull(result.failureCode());
        assertTrue(client.requests.get(1).getFirst().content().contains("补充针对性验证"));
        assertFalse(client.requests.get(1).stream().anyMatch(message -> "user".equals(message.role())
                && message.content().contains("补充针对性验证")));
        assertFalse(result.conversation().stream()
                .anyMatch(message -> message.content().contains("补充针对性验证")));
    }

    @Test
    void interruptsAfterThreeIdenticalToolCalls() {
        CountingTool tool = new CountingTool("read_file", "content");
        ToolCall call = new ToolCall("call_1", "read_file", Map.of("path", "pom.xml"));
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(call)),
                new ModelResponse("", List.of(call)),
                new ModelResponse("", List.of(call)),
                new ModelResponse("不该走到这里", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("读 pom.xml", List.of(), events::add);

        assertEquals("REPEATED_TOOL_CALL", result.failureCode());
        assertEquals(2, tool.executions.get());
        assertEquals(4, client.requests.size());
        assertEquals("不该走到这里", result.finalText(), "停止时最后一个模型请求应改为无工具收尾");
        assertTrue(client.toolDefinitions.getLast().isEmpty());
        assertTrue(events.stream().anyMatch(event -> event instanceof RunFailed failed
                && failed.code().equals("REPEATED_TOOL_CALL")));
        assertFalse(eventTypes().contains("run.completed"));
    }

    /** 渐进提醒：第 3 次跳过执行并注入提醒，第 4 次只跳过，第 5 次才中断。 */
    @Test
    void remindsThenSkipsBeforeAbortingOnRepeatedCalls() {
        CountingTool tool = new CountingTool("read_file", "content");
        ToolCall call = new ToolCall("call_1", "read_file", Map.of("path", "pom.xml"));
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(call)),   // 1: 执行
                new ModelResponse("", List.of(call)),   // 2: 执行
                new ModelResponse("", List.of(call)),   // 3: 跳过 + 注入提醒
                new ModelResponse("", List.of(call)),   // 4: 跳过（不再新提醒）
                new ModelResponse("", List.of(call)),   // 5: 中断
                new ModelResponse("不该走到这里", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(tool), readOnlyGate(),
                new AgentLoop.Config(8, 3, 5, "你是测试代理"));

        AgentLoop.Result result = loop.run("读 pom.xml", List.of(), events::add);

        assertEquals("REPEATED_TOOL_CALL", result.failureCode());
        assertEquals(2, tool.executions.get(), "达到提醒档后的重复调用不应再执行");
        assertEquals(6, client.requests.size());
        // 第 4 次请求通过一次性 system 上下文收到提醒；不能伪装成用户消息或写入会话。
        assertTrue(client.requests.get(3).getFirst().content().contains("连续第 3 次"));
        assertFalse(client.requests.get(3).stream()
                .anyMatch(message -> "user".equals(message.role())
                        && message.content().contains("连续第 3 次")));
        assertFalse(result.conversation().stream()
                .anyMatch(message -> "user".equals(message.role())
                        && message.content().contains("连续第 3 次")));
        assertTrue(events.stream().anyMatch(event -> event instanceof RunContinued continued
                        && continued.reason().contains("重复调用提醒")),
                "提醒应有对应事件");
        assertTrue(events.stream().anyMatch(event -> event instanceof RunFailed failed
                && failed.code().equals("REPEATED_TOOL_CALL")));
        // 跳过执行的合成结果要让模型知道为什么没有真实结果
        String skipped = client.requests.get(3).stream()
                .filter(message -> "tool".equals(message.role()))
                .map(Message::content)
                .filter(content -> content.contains("已跳过执行"))
                .findFirst().orElse("");
        assertTrue(skipped.contains("连续第 3 次"), skipped);
    }

    /** 后台状态是动态观察：相同参数仍需真正执行，不能在第三次就按静态重复读取吞掉。 */
    @Test
    void allowsBoundedRepeatedPollingWhileBackgroundCommandIsRunning() {
        String running = "任务ID: cmd_1\n状态: RUNNING\n命令: mvn test";
        SequencedTool status = new SequencedTool("command_status",
                running, running, running, running, running,
                "任务ID: cmd_1\n状态: COMPLETED\n退出码: 0");
        ToolCall poll = new ToolCall("poll", "command_status",
                Map.of("taskId", "cmd_1", "waitSeconds", 5));
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("后台测试已经完成。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(status), readOnlyGate(),
                new AgentLoop.Config(12, 3, 5, "你是测试代理"));

        AgentLoop.Result result = loop.run("等待后台任务完成", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals(6, status.executions.get(), "每次动态观察都应真正执行，包括最终完成状态");
        assertTrue(events.stream().anyMatch(event -> event instanceof RunContinued continued
                && continued.reason().contains("后台状态连续 3 次未变化")));
        assertFalse(events.stream().anyMatch(event -> event instanceof RunFailed));
    }

    @Test
    void treatsNonEofCommandLogsAsDynamicObservation() {
        String open = "任务ID: cmd_1\noffset: 0\nnextOffset: 0\neof: false\n输出:\n";
        SequencedTool logs = new SequencedTool("command_logs", open, open, open,
                "任务ID: cmd_1\noffset: 0\nnextOffset: 4\neof: true\n输出:\ndone");
        ToolCall poll = new ToolCall("logs", "command_logs", Map.of("taskId", "cmd_1", "offset", 0));
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("日志读取完成。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(logs), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("等待后台日志", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals(4, logs.executions.get());
    }

    /** 动态观察也必须有保险丝：长期完全不变时先提醒，最终停止高频轮询。 */
    @Test
    void stopsAfterExtendedUnchangedDynamicPolling() {
        String running = "任务ID: cmd_1\n状态: RUNNING\n命令: mvn test";
        SequencedTool status = new SequencedTool("command_status",
                running, running, running, running, running, running, running, running);
        ToolCall poll = new ToolCall("poll", "command_status", Map.of("taskId", "cmd_1"));
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("", List.of(poll)),
                new ModelResponse("后台任务仍在运行，稍后可继续查询。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(status), readOnlyGate(),
                new AgentLoop.Config(12, 3, 4, "你是测试代理"));

        AgentLoop.Result result = loop.run("等待后台任务完成", List.of(), events::add);

        assertEquals("WAITING", result.failureCode());
        assertEquals(8, status.executions.get(), "动态轮询保险丝前不应伪造跳过结果");
        assertTrue(events.stream().anyMatch(event -> event instanceof RunFailed failed
                && failed.code().equals("WAITING")));
        assertTrue(result.finalText().contains("taskId") || result.finalText().contains("稍后"), result.finalText());
    }

    @Test
    void stopsAtMaxSteps() {
        CountingTool tool = new CountingTool("read_file", "content");
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "read_file", Map.of("path", "a")))),
                new ModelResponse("", List.of(new ToolCall("call_2", "read_file", Map.of("path", "b")))),
                new ModelResponse("done", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(2, 3));

        AgentLoop.Result result = loop.run("读两个文件", List.of(), events::add);

        assertEquals("MAX_STEPS", result.failureCode());
        assertEquals(3, client.requests.size());
        assertEquals(2, tool.executions.get());
        assertEquals("done", result.finalText());
        assertTrue(client.toolDefinitions.getLast().isEmpty());
    }

    @Test
    void deniedToolIsReportedWithoutExecution() {
        CountingTool tool = new CountingTool("write_file", "written");
        PermissionGate denyAll = (call, candidate) -> PermissionResult.deny("Sprint 2 只支持只读");
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "write_file", Map.of("path", "a.txt")))),
                new ModelResponse("好的，我不写文件。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(tool), denyAll, config(8, 3));

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
        WritingTool tool = new WritingTool("edit_file", "a.txt", "mvn -q test → exit 0");
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "edit_file", Map.of("path", "a.txt")))),
                new ModelResponse("改好了，测试通过。", List.of()),
                new ModelResponse("核对完毕，目标已完成。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(tool), new PolicyPermissionGate(PermissionMode.AUTO), config(8, 3));

        loop.run("改一下 a.txt", List.of(), events::add);

        RunCompleted completed = (RunCompleted) events.stream()
                .filter(event -> event instanceof RunCompleted).findFirst().orElseThrow();
        assertEquals(List.of("a.txt"), completed.changedFiles());
        assertEquals(List.of("mvn -q test → exit 0"), completed.checks());
        // 有文件改动时，收尾前会先自审一轮
        assertEquals(3, client.requests.size());
        assertTrue(lastContent(client.requests.get(2)).contains("收尾前自查"));
    }

    /** 改了代码却没验证就想收尾：先被推回一轮，跑通构建后才允许结束。 */
    @Test
    void asksForVerificationBeforeCompletingCodeChange() {
        WritingTool write = new WritingTool("edit_file", "src/main/java/A.java", null);
        WritingTool run = new WritingTool("run_command", null, "mvn -q test → exit 0");
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "edit_file",
                        Map.of("path", "src/main/java/A.java")))),
                new ModelResponse("改完了。", List.of()),
                new ModelResponse("", List.of(new ToolCall("call_2", "run_command",
                        Map.of("command", "mvn -q test")))),
                new ModelResponse("测试通过。", List.of()),
                new ModelResponse("核对完毕，完成。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(write, run),
                new PolicyPermissionGate(PermissionMode.AUTO), config(12, 3));

        AgentLoop.Result result = loop.run("修一下 A", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals(5, client.requests.size(), "验证一轮 + 自审一轮");
        assertTrue(lastContent(client.requests.get(2)).contains("收尾前验证"));
        assertTrue(lastContent(client.requests.get(4)).contains("收尾前自查"));
        assertEquals(2, events.stream().filter(RunContinued.class::isInstance).count());
    }

    /** 只改文档不强制跑构建，但同样要自审一轮。 */
    @Test
    void docOnlyChangeSkipsVerification() {
        WritingTool write = new WritingTool("edit_file", "README.md", null);
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "edit_file", Map.of("path", "README.md")))),
                new ModelResponse("文档更新完。", List.of()),
                new ModelResponse("核对完毕。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(write),
                new PolicyPermissionGate(PermissionMode.AUTO), config(8, 3));

        loop.run("更新 README", List.of(), events::add);

        assertEquals(3, client.requests.size());
        assertTrue(lastContent(client.requests.get(2)).contains("收尾前自查"));
        assertFalse(lastContent(client.requests.get(2)).contains("收尾前验证"));
    }

    @Test
    void deniedWriteDoesNotCountAsChange() {
        WritingTool tool = new WritingTool("edit_file", "a.txt", "mvn -q test → exit 0");
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "edit_file", Map.of("path", "a.txt")))),
                new ModelResponse("没改成。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));

        loop.run("改一下 a.txt", List.of(), events::add);

        RunCompleted completed = (RunCompleted) events.stream()
                .filter(event -> event instanceof RunCompleted).findFirst().orElseThrow();
        assertTrue(completed.changedFiles().isEmpty(), completed.changedFiles().toString());
        assertTrue(completed.checks().isEmpty(), completed.checks().toString());
    }

    @Test
    void cancellationStopsBeforeCallingModel() {
        ScriptedModelClient client = new ScriptedModelClient(new ModelResponse("不该被调用", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(), readOnlyGate(), ApprovalBroker.denyAll(),
                config(8, 3), () -> true);

        AgentLoop.Result result = loop.run("你好", List.of(), events::add);

        assertEquals("CANCELLED", result.failureCode());
        assertTrue(client.requests.isEmpty(), "取消后不应再请求模型");
        assertTrue(events.stream().anyMatch(event -> event instanceof RunCancelled));
    }

    @Test
    void compactsLargeToolOutputBeforeNextRequest() {
        CountingTool tool = new CountingTool("read_file", "x".repeat(20_000));
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "read_file", Map.of("path", "big.txt")))),
                new ModelResponse("读完了", List.of()));
        System.setProperty("pilot.context.max-chars", "2000");
        try {
            AgentLoop loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));
            loop.run("读大文件", List.of(), events::add);
        } finally {
            System.clearProperty("pilot.context.max-chars");
        }

        List<Message> secondRequest = client.requests.get(1);
        String toolResult = secondRequest.getLast().content();
        assertTrue(toolResult.startsWith("[旧工具输出已清理"), toolResult);
    }

    @Test
    void unknownToolIsReportedToModel() {
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "drop_database", Map.of()))),
                new ModelResponse("工具不存在。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("删库", List.of(), events::add);

        assertNull(result.failureCode());
        String toolResult = client.requests.get(1).getLast().content();
        assertTrue(toolResult.contains("未知工具 drop_database"), toolResult);
    }

    @Test
    void permanentModelFailureEndsRun() {
        ModelClient failing = (messages, tools) -> {
            throw new ModelClientException("模型接口返回 HTTP 401（20 ms, model=gpt）：invalid api key");
        };
        AgentLoop loop = new AgentLoop(failing, registry(), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("你好", List.of(), events::add);

        assertEquals("MODEL_ERROR", result.failureCode());
        assertEquals(List.of("assistant.delta", "run.failed"), eventTypesWithoutUsage());
        assertEquals(4, events.stream().filter(RunUsageUpdated.class::isInstance).count());
        assertTrue(result.finalText().contains("已有进度已保存"));
        RunFailed failed = events.stream()
                .filter(RunFailed.class::isInstance)
                .map(RunFailed.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(failed.message().contains("HTTP 401"), failed.message());
        assertTrue(failed.retryable());
    }

    @Test
    void retriesTransientModelFailureUntilItSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        ModelClient client = (messages, tools) -> {
            int n = calls.getAndIncrement();
            if (n == 0) {
                throw new ModelClientException(
                        "模型请求失败（138 ms, model=gpt）：HTTP/1.1 header parser received no bytes");
            }
            if (n == 1) {
                throw new ModelClientException("模型接口返回 HTTP 503（40 ms, model=gpt）：unavailable");
            }
            return new ModelResponse("恢复了", List.of());
        };
        ArrayList<Long> waited = new ArrayList<Long>();
        AgentLoop loop = new AgentLoop(client, registry(), readOnlyGate(), ApprovalBroker.denyAll(),
                config(8, 3), Cancellation.none(), () -> null, (call, result, latencyMs) -> { },
                waited::add, null);

        AgentLoop.Result result = loop.run("你好", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals("恢复了", result.finalText());
        assertEquals(3, calls.get());
        assertFalse(eventTypes().contains("run.failed"));
        List<RunRetrying> retries = events.stream()
                .filter(RunRetrying.class::isInstance)
                .map(RunRetrying.class::cast)
                .toList();
        assertEquals(2, retries.size());
        assertEquals(2_000L, retries.get(0).delayMs());
        assertEquals(4_000L, retries.get(1).delayMs());
        assertEquals(6_000L, waited.stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void retainsEphemeralGuidanceAcrossTransientRetry() {
        AtomicInteger calls = new AtomicInteger();
        ArrayList<List<Message>> requests = new ArrayList<>();
        ModelClient client = (messages, tools) -> {
            requests.add(List.copyOf(messages));
            if (calls.getAndIncrement() == 0) {
                throw new ModelClientException("模型接口返回 HTTP 503：unavailable");
            }
            return new ModelResponse("恢复完成。", List.of());
        };
        AtomicBoolean issued = new AtomicBoolean();
        ExecutionPolicy policy = new ExecutionPolicy() {
            @Override public void beforeRequest(List<Message> history) { }
            @Override public String requestGuidance() {
                return issued.compareAndSet(false, true) ? "本次唯一动作：运行定向验证。" : null;
            }
            @Override public String review(List<Message> history, Set<String> changedFiles, List<String> checks) {
                return null;
            }
        };
        AgentLoop loop = new AgentLoop(client, registry(), readOnlyGate(), ApprovalBroker.denyAll(),
                config(8, 3), Cancellation.none(), () -> null, (call, result, latencyMs) -> { },
                millis -> { }, null).withExecutionPolicy(policy);

        AgentLoop.Result result = loop.run("继续", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals(2, requests.size());
        assertTrue(requests.stream().allMatch(request -> request.getFirst().content().contains("运行定向验证")),
                "瞬时重试必须保留同一份一次性 guidance");
        assertFalse(result.conversation().stream()
                .anyMatch(message -> message.content().contains("运行定向验证")));
    }

    @Test
    void cancelStopsModelRetry() {
        AtomicBoolean cancelled = new AtomicBoolean();
        ModelClient client = (messages, tools) -> {
            throw new ModelClientException("模型请求失败（10 ms, model=gpt）：connection reset");
        };
        AgentLoop loop = new AgentLoop(client, registry(), readOnlyGate(), ApprovalBroker.denyAll(),
                config(8, 3), cancelled::get, () -> null, (call, result, latencyMs) -> { },
                millis -> cancelled.set(true), null);

        AgentLoop.Result result = loop.run("你好", List.of(), events::add);

        assertEquals("CANCELLED", result.failureCode());
        assertTrue(eventTypes().contains("run.retrying"));
        assertTrue(eventTypes().contains("run.cancelled"));
        assertFalse(eventTypes().contains("run.failed"));
    }

    @Test
    void stopsAfterThreeTransientRetries() {
        AtomicInteger calls = new AtomicInteger();
        ModelClient client = (messages, tools) -> {
            calls.incrementAndGet();
            throw new ModelClientException("模型请求失败（10 ms, model=gpt）：connection reset");
        };
        AgentLoop loop = new AgentLoop(client, registry(), readOnlyGate(), ApprovalBroker.denyAll(),
                config(8, 3), Cancellation.none(), () -> null, (call, result, latencyMs) -> { },
                millis -> { }, null);

        AgentLoop.Result result = loop.run("你好", List.of(), events::add);

        assertEquals("MODEL_ERROR", result.failureCode());
        assertEquals(5, calls.get(), "四次业务请求失败后仍会尝试一次无工具收尾");
        assertEquals(3, events.stream().filter(RunRetrying.class::isInstance).count());
        assertTrue(eventTypes().contains("run.failed"));
    }

    @Test
    void outputLengthIsContinuedInsteadOfBeingMistakenForCompletion() {
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("第一段", List.of(), com.pilot.core.model.ModelUsage.unknown(),
                        ModelResponse.FinishReason.LENGTH),
                new ModelResponse("第二段", List.of(), com.pilot.core.model.ModelUsage.unknown(),
                        ModelResponse.FinishReason.LENGTH),
                new ModelResponse("最终完整结果", List.of(), com.pilot.core.model.ModelUsage.unknown(),
                        ModelResponse.FinishReason.STOP));
        AgentLoop loop = new AgentLoop(client, registry(), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("输出完整结果", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals("最终完整结果", result.finalText());
        assertEquals(3, client.requests.size());
        assertEquals(2, events.stream().filter(RunContinued.class::isInstance).count());
        assertTrue(client.requests.get(1).getFirst().content().contains("输出长度上限"));
        assertTrue(client.requests.get(2).getFirst().content().contains("输出长度上限"));
        assertFalse(result.conversation().stream().anyMatch(message -> "user".equals(message.role())
                && message.content().contains("输出长度上限")));
        assertFalse(events.stream().anyMatch(event -> event instanceof RunCompleted completed
                && completed.summary().equals("第一段")));
    }

    /** 运行中追加的指令应在下一轮采样前进入历史，并且不新开 run。 */
    @Test
    void injectsSteeredInputBeforeNextStep() {
        CountingTool tool = new CountingTool("list_dir", "a.txt");
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("先看看", List.of(new ToolCall("call_1", "list_dir", Map.of("path", ".")))),
                new ModelResponse("完成", List.of()));
        Deque<String> inbox = new ArrayDeque<>(List.of("只改 A 模块，别动 B"));
        AgentLoop loop = new AgentLoop(client, registry(tool), readOnlyGate(), ApprovalBroker.denyAll(),
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
            ArrayList<Message> history = new ArrayList<Message>();
            for (int i = 0; i < 12; i++) {
                history.add(Message.user("第 " + i + " 轮：" + "y".repeat(300)));
            }
            ScriptedModelClient client = new ScriptedModelClient(
                    new ModelResponse("## 目标\n继续任务", List.of()),
                    new ModelResponse("完成", List.of()));
            AgentLoop loop = new AgentLoop(client, registry(new CountingTool("list_dir", "")), readOnlyGate(), config(4, 3));

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

    private List<String> eventTypesWithoutUsage() {
        return events.stream()
                .map(event -> event.type().wireName())
                .filter(type -> !type.equals("run.usage"))
                .toList();
    }

    private static AgentLoop.Config config(int maxSteps, int repeatedLimit) {
        return new AgentLoop.Config(maxSteps, repeatedLimit, "你是测试代理");
    }

    /** 关闭自动续跑时，模型提前收尾必须把差距交给界面，别让人以为干完了。 */
    @Test
    void reportsOpenPlanStepsWhenModelStopsEarly() {
        ScriptedModelClient client = new ScriptedModelClient(new ModelResponse("先做到这里。", List.of()));
        List<PlanItem> plan = List.of(
                new PlanItem("检查分支状态", PlanItem.COMPLETED),
                new PlanItem("跑回归测试", PlanItem.PENDING));
        AgentLoop loop = new AgentLoop(client, registry(new CountingTool("list_dir", "a.txt")), readOnlyGate(),
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
        ScriptedModelClient client = new ScriptedModelClient(new ModelResponse("全部完成。", List.of()));
        List<PlanItem> plan = List.of(new PlanItem("跑回归测试", PlanItem.COMPLETED));
        AgentLoop loop = new AgentLoop(client, registry(new CountingTool("list_dir", "a.txt")), readOnlyGate(),
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
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("先做到这里。", List.of()),
                new ModelResponse("全部做完了。", List.of()));
        AtomicInteger round = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<List<PlanItem>> plan = () -> round.getAndIncrement() == 0
                ? List.of(new PlanItem("检查分支状态", PlanItem.COMPLETED),
                          new PlanItem("跑回归测试", PlanItem.PENDING))
                : List.of(new PlanItem("检查分支状态", PlanItem.COMPLETED),
                          new PlanItem("跑回归测试", PlanItem.COMPLETED));
        AgentLoop loop = new AgentLoop(client, registry(new CountingTool("list_dir", "a.txt")), readOnlyGate(),
                new AgentLoop.Config(8, 3, "你是测试代理", plan, 3));
        List<List<Message>> checkpoints = new ArrayList<>();

        AgentLoop.Result result = loop.run("继续", List.of(), events::add,
                messages -> checkpoints.add(List.copyOf(messages)));

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
        String secondRound = client.requests.get(1).getFirst().content();
        assertTrue(secondRound.contains("跑回归测试"), secondRound);
        assertTrue(secondRound.contains("自动继续"), secondRound);
        assertFalse(result.conversation().stream()
                .anyMatch(message -> message.content().contains("自动继续")));
        assertTrue(checkpoints.stream().flatMap(List::stream)
                .noneMatch(message -> message.content().contains("自动继续")),
                "内部续跑提示不能写进 checkpoint");
    }

    /** ExecutionPolicy 只增加核验，不能让 DIRECT/GOAL 绕过共同的未完成计划门。 */
    @Test
    void executionPolicyStillContinuesWhilePlanHasOpenSteps() {
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("先做到这里。", List.of()),
                new ModelResponse("全部做完了。", List.of()));
        AtomicInteger round = new AtomicInteger();
        java.util.function.Supplier<List<PlanItem>> plan = () -> round.getAndIncrement() == 0
                ? List.of(new PlanItem("实现接口", PlanItem.COMPLETED),
                          new PlanItem("跑回归测试", PlanItem.PENDING))
                : List.of(new PlanItem("实现接口", PlanItem.COMPLETED),
                          new PlanItem("跑回归测试", PlanItem.COMPLETED));
        AtomicInteger reviews = new AtomicInteger();
        ExecutionPolicy policy = new ExecutionPolicy() {
            @Override public void beforeRequest(List<Message> history) { }
            @Override public String review(List<Message> history, Set<String> changedFiles, List<String> checks) {
                reviews.incrementAndGet();
                return null;
            }
        };
        AgentLoop loop = new AgentLoop(client, registry(), readOnlyGate(),
                new AgentLoop.Config(8, 3, "你是测试代理", plan, 3))
                .withExecutionPolicy(policy);

        AgentLoop.Result result = loop.run("继续完成计划", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals(2, client.requests.size());
        assertEquals(1, events.stream().filter(RunContinued.class::isInstance).count());
        assertEquals(1, reviews.get(), "policy reviewer 只能在共同计划门通过后运行");
        RunCompleted completed = events.stream().filter(RunCompleted.class::isInstance)
                .map(RunCompleted.class::cast).findFirst().orElseThrow();
        assertFalse(completed.planOpen());
        assertEquals(2, completed.planDone());
        assertEquals(2, completed.planTotal());
    }

    /** 自动续跑到限时也必须保留真实计划，不能因为启用了 policy 就伪装成无计划完成。 */
    @Test
    void executionPolicyPreservesOpenPlanAtContinueLimit() {
        ScriptedModelClient client = new ScriptedModelClient(new ModelResponse("只能先做到这里。", List.of()));
        List<PlanItem> plan = List.of(
                new PlanItem("实现接口", PlanItem.COMPLETED),
                new PlanItem("跑回归测试", PlanItem.PENDING));
        ExecutionPolicy policy = new ExecutionPolicy() {
            @Override public void beforeRequest(List<Message> history) { }
            @Override public String review(List<Message> history, Set<String> changedFiles, List<String> checks) {
                return null;
            }
        };
        AgentLoop loop = new AgentLoop(client, registry(), readOnlyGate(),
                new AgentLoop.Config(8, 3, "你是测试代理", () -> plan, 0))
                .withExecutionPolicy(policy);

        loop.run("继续", List.of(), events::add);

        RunCompleted completed = events.stream().filter(RunCompleted.class::isInstance)
                .map(RunCompleted.class::cast).findFirst().orElseThrow();
        assertTrue(completed.planOpen());
        assertEquals(1, completed.planDone());
        assertEquals(2, completed.planTotal());
        assertEquals(List.of("跑回归测试"), completed.openSteps());
    }

    /** 有 policy 时仍必须经过通用验证门，不能让 policy 的 null 直接放过未验证代码。 */
    @Test
    void executionPolicyCannotBypassCommonVerificationGate() {
        WritingTool write = new WritingTool("edit_file", "src/A.java", null);
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "edit_file", Map.of("path", "src/A.java")))),
                new ModelResponse("已经改完。", List.of()),
                new ModelResponse("仍未能运行测试。", List.of()),
                new ModelResponse("确认当前结果。", List.of()),
                new ModelResponse("最终说明。", List.of()));
        AtomicInteger reviews = new AtomicInteger();
        ExecutionPolicy policy = new ExecutionPolicy() {
            @Override public void beforeRequest(List<Message> history) { }
            @Override public String review(List<Message> history, Set<String> changedFiles, List<String> checks) {
                reviews.incrementAndGet();
                return null;
            }
        };
        AgentLoop loop = new AgentLoop(client, registry(write), new PolicyPermissionGate(PermissionMode.AUTO),
                config(10, 3)).withExecutionPolicy(policy);

        AgentLoop.Result result = loop.run("修改 A", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals(5, client.requests.size(), "两次验证提醒和一次自审都应先于 policy reviewer");
        assertTrue(lastContent(client.requests.get(2)).contains("收尾前验证"));
        assertTrue(lastContent(client.requests.get(4)).contains("收尾前自查"));
        assertEquals(1, reviews.get());
    }

    /** 模型死活不改计划时必须停手，把未完成项交回界面，不能无限催。 */
    @Test
    void stopsAfterAutoContinueLimit() {
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("做到这里。", List.of()),
                new ModelResponse("还是做到这里。", List.of()));
        List<PlanItem> plan = List.of(new PlanItem("跑回归测试", PlanItem.PENDING));
        AgentLoop loop = new AgentLoop(client, registry(new CountingTool("list_dir", "a.txt")), readOnlyGate(),
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

    /** 只读但属于「分析 / 梳理」的请求：工具用得太少时先自查一轮，别读两个文件就下结论。 */
    @Test
    void reviewsReadOnlyResearchBeforeAnswering() {
        CountingTool tool = new CountingTool("read_file", "内容");
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "read_file", Map.of("path", "A.java")))),
                new ModelResponse("", List.of(new ToolCall("call_2", "read_file", Map.of("path", "B.java")))),
                new ModelResponse("调用链是 A → B。", List.of()),
                new ModelResponse("补充确认后：A → B → C。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(tool), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.run("分析一下这个模块的调用链", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals(4, client.requests.size(), "回答前应有一轮自查");
        String review = lastContent(client.requests.get(3));
        assertTrue(review.contains("收尾前自查"), review);
        assertTrue(review.contains("A.java") && review.contains("B.java"), review);
        assertFalse(result.conversation().stream()
                .anyMatch(message -> message.content().contains("收尾前自查")));
        assertEquals(1, events.stream().filter(RunContinued.class::isInstance).count());
    }

    @Test
    void resumeRestoresOriginalResearchGoalAndPriorInspections() {
        List<Message> history = List.of(
                Message.user("分析一下这个模块的调用链"),
                Message.assistant("", List.of(new ToolCall("call_1", "read_file", Map.of("path", "A.java")))),
                Message.tool("call_1", "A"),
                Message.assistant("", List.of(new ToolCall("call_2", "read_file", Map.of("path", "B.java")))),
                Message.tool("call_2", "B"));
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("初步结论。", List.of()),
                new ModelResponse("复核覆盖范围后的结论。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(), readOnlyGate(), config(8, 3));

        AgentLoop.Result result = loop.resume(history, events::add, messages -> { }, "分析一下这个模块的调用链");

        assertNull(result.failureCode());
        assertEquals(2, client.requests.size(), "恢复后的只读调研仍应经过一次覆盖范围自审");
        String review = client.requests.get(1).getFirst().content();
        assertTrue(review.contains("A.java") && review.contains("B.java"), review);
        assertTrue(review.contains("分析一下这个模块的调用链"), review);
    }

    @Test
    void resumeSeedsPersistedChangesAndChecksFromExecutionPolicy() {
        String priorCheck = "mvnw test → exit 0";
        AtomicBoolean reviewed = new AtomicBoolean();
        ExecutionPolicy policy = new ExecutionPolicy() {
            @Override public void beforeRequest(List<Message> history) { }
            @Override public Set<String> priorChangedPaths() { return Set.of("src/Persisted.java"); }
            @Override public List<String> priorChecks() { return List.of(priorCheck); }
            @Override public String review(List<Message> history, Set<String> changedFiles, List<String> checks) {
                assertEquals(Set.of("src/Persisted.java"), changedFiles);
                assertEquals(List.of(priorCheck), checks);
                reviewed.set(true);
                return null;
            }
        };
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("已经完成。", List.of()),
                new ModelResponse("复核后确认完成。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(), readOnlyGate(), config(8, 3))
                .withExecutionPolicy(policy);

        AgentLoop.Result result = loop.resume(List.of(Message.user("修复持久化问题")), events::add,
                messages -> { }, "修复持久化问题");

        assertNull(result.failureCode());
        assertTrue(reviewed.get());
        RunCompleted completed = events.stream().filter(RunCompleted.class::isInstance)
                .map(RunCompleted.class::cast).findFirst().orElseThrow();
        assertEquals(List.of("src/Persisted.java"), completed.changedFiles());
        assertEquals(List.of(priorCheck), completed.checks());
    }

    /** 失败的验证也要记录，且不能算「已验证」：会继续被推回要求验证。 */
    @Test
    void failedVerificationIsRecordedAndDoesNotUnlockCompletion() {
        WritingTool write = new WritingTool("edit_file", "src/A.java", null);
        WritingTool failing = new WritingTool("run_command", null, "mvn -q test → exit 1");
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "edit_file",
                        Map.of("path", "src/A.java")))),
                new ModelResponse("", List.of(new ToolCall("call_2", "run_command",
                        Map.of("command", "mvn -q test")))),
                new ModelResponse("测试挂了，先这样。", List.of()),
                new ModelResponse("还是挂着。", List.of()),
                new ModelResponse("确认完成。", List.of()),
                new ModelResponse("真的完成。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(write, failing),
                new PolicyPermissionGate(PermissionMode.AUTO), config(12, 3));

        loop.run("修一下 A", List.of(), events::add);

        RunCompleted completed = (RunCompleted) events.stream()
                .filter(event -> event instanceof RunCompleted).findFirst().orElseThrow();
        assertEquals(List.of("mvn -q test → exit 1"), completed.checks());
        assertEquals(6, client.requests.size(), "两次催验证 + 一次自审");
        assertTrue(lastContent(client.requests.get(3)).contains("收尾前验证"));
        assertTrue(lastContent(client.requests.get(5)).contains("收尾前自查"));
        assertEquals(3, events.stream().filter(RunContinued.class::isInstance).count());
    }

    /** 权限拒绝属于「没执行」，不能当成验证结果写进 checks。 */
    @Test
    void deniedCommandDoesNotRecordCheck() {
        WritingTool run = new WritingTool("run_command", null, "mvn -q test → exit 0");
        ScriptedModelClient client = new ScriptedModelClient(
                new ModelResponse("", List.of(new ToolCall("call_1", "run_command",
                        Map.of("command", "mvn -q test")))),
                new ModelResponse("没跑成。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry(run), readOnlyGate(), config(8, 3));

        loop.run("跑测试", List.of(), events::add);

        RunCompleted completed = (RunCompleted) events.stream()
                .filter(event -> event instanceof RunCompleted).findFirst().orElseThrow();
        assertTrue(completed.checks().isEmpty(), completed.checks().toString());
    }

    private static PolicyPermissionGate readOnlyGate() {
        return new PolicyPermissionGate(PermissionMode.READ_ONLY);
    }

    private static String lastContent(List<Message> messages) {
        return String.valueOf(messages.getFirst().content());
    }

    private static ToolRegistry registry(Tool... tools) {
        ToolRegistry registry = new ToolRegistry();
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

    private static final class SequencedTool implements Tool {
        private final String name;
        private final Deque<String> results = new ArrayDeque<>();
        private final AtomicInteger executions = new AtomicInteger();

        private SequencedTool(String name, String... results) {
            this.name = name;
            this.results.addAll(List.of(results));
        }

        @Override public String name() { return name; }
        @Override public String description() { return "测试动态状态工具"; }
        @Override public Map<String, Object> parameters() { return Map.of("type", "object"); }
        @Override public boolean readOnly() { return true; }

        @Override
        public String execute(Map<String, Object> args) {
            executions.incrementAndGet();
            String result = results.poll();
            if (result == null) throw new IllegalStateException("动态状态脚本已用完");
            return result;
        }
    }
}
