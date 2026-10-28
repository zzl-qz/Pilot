package com.pilot.server.multi;

import com.pilot.core.multi.TaskSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchAgentsToolTest {

    @Test
    void parsesCommandWhitelistIntoScope() {
        List<TaskSpec> specs = DispatchAgentsTool.parseTasks(Map.of("tasks", List.of(Map.of(
                "id", "T1",
                "goal", "跑测试并核对结果",
                "files", List.of("pilot-core/**"),
                "tools", List.of("read_*", "run_command"),
                "commands", List.of("mvn test"),
                "deny_commands", List.of("mvn deploy"),
                "writable", false))));

        var scope = specs.get(0).scope();
        assertTrue(scope.allowsTool("read_file"));
        assertTrue(scope.allowsCommand("mvn test -q"));
        assertFalse(scope.allowsCommand("mvn deploy"));
        assertFalse(scope.writable());
    }

    @Test
    void defaultsToReadOnlyWhenWritableIsMissing() {
        List<TaskSpec> specs = DispatchAgentsTool.parseTasks(Map.of("tasks", List.of(Map.of(
                "id", "T1", "goal", "只做代码调研"))));

        var scope = specs.get(0).scope();
        assertFalse(scope.writable());
        assertTrue(scope.allowsPath("README.md"));
        assertFalse(scope.allowsCommand(""));
    }
}
