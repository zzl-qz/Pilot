package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.hook.Hooks;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;

class ToolExecutorJournalTest {

    /** 只实现最小能力：写文件 + 报告改动路径。 */
    static final class WriteTool implements Tool, WritesFiles {
        int executions;

        @Override
        public String name() {
            return "write_file";
        }

        @Override
        public String description() {
            return "写文件";
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
            executions++;
            return "已写入 " + args.get("path");
        }

        @Override
        public String changedPath(ToolCall call) {
            return String.valueOf(call.args().get("path"));
        }
    }

    /** 指挥工具：同样带参数，但不该被幂等账本重放。 */
    static final class CommandTool implements Tool {
        int executions;

        @Override
        public String name() {
            return "run_command";
        }

        @Override
        public String description() {
            return "执行命令";
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
            executions++;
            return "退出码: 0";
        }
    }

    /** 按 fingerprint 记录，模拟持久化账本。 */
    static final class RecordingJournal implements ToolCallJournal {
        final Map<String, Recorded> entries = new LinkedHashMap<>();

        @Override
        public Optional<Recorded> replay(String runId, ToolCall call) {
            return Optional.ofNullable(entries.get(runId + "|" + ToolCallJournal.fingerprint(call)));
        }

        @Override
        public void record(String runId, ToolCall call, String result, List<String> changedPaths, List<String> checks,
            boolean replayable) {
            if (!replayable) {
                return;
            }
            entries.put(runId + "|" + ToolCallJournal.fingerprint(call),
                new Recorded(result, changedPaths, checks, ToolCallJournal.fingerprint(call), "", true));
        }

        @Override
        public int deleteBySession(String sessionId) {
            int size = entries.size();
            entries.clear();
            return size;
        }
    }

    private static ToolExecutor executor(Tool tool, RecordingJournal journal) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return new ToolExecutor(registry, new PolicyPermissionGate(PermissionMode.AUTO), ApprovalBroker.denyAll(),
            (call, result, latencyMs) -> {
            }, Hooks.none()).withJournal("run_1", journal);
    }

    @Test
    void identicalWriteIsReplayedInsteadOfExecutedTwice() {
        WriteTool tool = new WriteTool();
        RecordingJournal journal = new RecordingJournal();
        ToolExecutor executor = executor(tool, journal);
        List<SessionEvent> events = new ArrayList<>();

        ToolExecutor.Outcome first = executor.execute(
            new ToolCall("call_1", "write_file", Map.of("path", "notes/a.md", "content", "hello")), events::add);
        ToolExecutor.Outcome second = executor.execute(
            new ToolCall("call_2", "write_file", Map.of("path", "notes/a.md", "content", "hello")), events::add);

        assertEquals(1, tool.executions, "第二次相同参数的写文件调用应复用结果");
        assertTrue(first.usableEvidence());
        assertTrue(second.usableEvidence());
        assertTrue(second.result().contains("幂等重放"), second.result());
        assertEquals(List.of("notes/a.md"), second.changedPaths());
        assertTrue(events.stream().anyMatch(event -> event instanceof ToolCompleted));
    }

    @Test
    void differentArgumentsRunAgain() {
        WriteTool tool = new WriteTool();
        ToolExecutor executor = executor(tool, new RecordingJournal());

        executor.execute(new ToolCall("call_1", "write_file", Map.of("path", "a.md", "content", "one")), event -> {
        });
        executor.execute(new ToolCall("call_2", "write_file", Map.of("path", "a.md", "content", "two")), event -> {
        });

        assertEquals(2, tool.executions);
    }

    @Test
    void commandsAreNeverReplayed() {
        CommandTool tool = new CommandTool();
        ToolExecutor executor = executor(tool, new RecordingJournal());

        executor.execute(new ToolCall("call_1", "run_command", Map.of("command", "mvn test")), event -> {
        });
        executor.execute(new ToolCall("call_2", "run_command", Map.of("command", "mvn test")), event -> {
        });

        // 命令重跑是预期行为：测试要重新执行，不能被「幂等」吞掉
        assertEquals(2, tool.executions);
    }

    @Test
    void journalFailureDoesNotBreakExecution() {
        WriteTool tool = new WriteTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        ToolCallJournal broken = new ToolCallJournal() {
            @Override
            public Optional<Recorded> replay(String runId, ToolCall call) {
                throw new IllegalStateException("ledger down");
            }

            @Override
            public void record(String runId, ToolCall call, String result, List<String> changedPaths,
                List<String> checks, boolean replayable) {
                throw new IllegalStateException("ledger down");
            }

            @Override
            public int deleteBySession(String sessionId) {
                return 0;
            }
        };
        ToolExecutor executor = new ToolExecutor(registry, new PolicyPermissionGate(PermissionMode.AUTO),
            ApprovalBroker.denyAll(), (call, result, latencyMs) -> {
            }, Hooks.none()).withJournal("run_1", broken);

        ToolExecutor.Outcome outcome =
            executor.execute(new ToolCall("call_1", "write_file", Map.of("path", "a.md", "content", "x")), event -> {
            });

        assertEquals(1, tool.executions);
        assertFalse(outcome.result().startsWith("ERROR:"));
    }
}
