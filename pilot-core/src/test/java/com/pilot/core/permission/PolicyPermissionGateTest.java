package com.pilot.core.permission;

import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.WritesFiles;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyPermissionGateTest {
    private static final ToolCall CALL = new ToolCall("call_1", "tool", Map.of());

    private final Tool readTool = new StubTool("read_file", true);
    private final Tool writeTool = new WriteTool();
    private final Tool commandTool = new StubTool("run_command", false);

    @Test
    void readOnlyDeniesEverythingThatHasSideEffects() {
        var gate = new PolicyPermissionGate(PermissionMode.READ_ONLY);

        assertEquals(PermissionDecision.ALLOW, gate.check(CALL, readTool).decision());
        assertEquals(PermissionDecision.DENY, gate.check(CALL, writeTool).decision());
        assertEquals(PermissionDecision.DENY, gate.check(CALL, commandTool).decision());
    }

    @Test
    void askRequiresApprovalForWritesAndCommands() {
        var gate = new PolicyPermissionGate(PermissionMode.ASK);

        assertEquals(PermissionDecision.ALLOW, gate.check(CALL, readTool).decision());
        assertEquals(PermissionDecision.ASK, gate.check(CALL, writeTool).decision());
        assertEquals(PermissionDecision.ASK, gate.check(CALL, commandTool).decision());
    }

    /** 像 Codex 一样改文件不再逐次打断，但命令有快照无法回滚的副作用，仍需确认。 */
    @Test
    void autoEditAppliesWritesButStillAsksForCommands() {
        var gate = new PolicyPermissionGate(PermissionMode.AUTO_EDIT);

        assertEquals(PermissionDecision.ALLOW, gate.check(CALL, readTool).decision());
        assertEquals(PermissionDecision.ALLOW, gate.check(CALL, writeTool).decision());
        assertEquals(PermissionDecision.ASK, gate.check(CALL, commandTool).decision());
    }

    @Test
    void autoAllowsRegisteredTools() {
        var gate = new PolicyPermissionGate(PermissionMode.AUTO);

        assertEquals(PermissionDecision.ALLOW, gate.check(CALL, writeTool).decision());
        assertEquals(PermissionDecision.ALLOW, gate.check(CALL, commandTool).decision());
    }

    private record StubTool(String name, boolean readOnly) implements Tool {
        @Override
        public String description() {
            return name;
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object");
        }

        @Override
        public String execute(Map<String, Object> args) {
            return "";
        }
    }

    private record WriteTool() implements Tool, WritesFiles {
        @Override
        public String name() {
            return "edit_file";
        }

        @Override
        public String description() {
            return "写入文件";
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
            return "已修改";
        }

        @Override
        public String changedPath(ToolCall call) {
            return "a.txt";
        }
    }
}
