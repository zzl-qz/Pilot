package com.pilot.server.workspace;

import com.pilot.core.multi.AgentScope;
import com.pilot.core.tool.SandboxSink;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.config.WebSearchProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 子 Agent 工具表的权限边界：只读不拿通用命令，任何任务都拿不到派发入口。 */
class RegistryForScopeTest {

    @TempDir
    Path root;

    private WorkspaceToolFactory factory() {
        // registryForScope 只用到工作区路径与 snapshots；后者为空时命令改动跟踪退化为 no-op
        return new WorkspaceToolFactory(null, new WebSearchProperties(false, null, null, null), null, null);
    }

    private ToolRegistry registry(AgentScope scope) {
        return factory().registryForScope(root.toString(), scope, SandboxSink.rejectAll());
    }

    private static List<String> names(ToolRegistry registry) {
        return registry.tools().stream().map(Tool::name).toList();
    }

    @Test
    void readOnlyTaskDoesNotGetGeneralCommandRunner() {
        AgentScope scope = AgentScope.readOnly(List.of("**"), List.of())
                .withTools(List.of("read_file", "run_command"), List.of());

        List<String> names = names(registry(scope));

        assertTrue(names.contains("read_file"), names.toString());
        assertFalse(names.contains("run_command"),
                "只读任务不能拿到退回内置白名单的通用命令：" + names);
    }

    @Test
    void readOnlyTaskGetsCommandRunnerOnlyWithExplicitWhitelist() {
        AgentScope scope = AgentScope.readOnly(List.of("**"), List.of())
                .withTools(List.of("read_file", "run_command"), List.of())
                .withCommands(List.of("mvn test"), List.of());

        assertTrue(names(registry(scope)).contains("run_command"),
                "显式声明命令白名单的调研任务仍可执行受限命令");
    }

    @Test
    void writableTaskKeepsCommandRunner() {
        AgentScope scope = AgentScope.readWrite(List.of("**"), List.of())
                .withTools(List.of("read_file", "run_command"), List.of());

        assertTrue(names(registry(scope)).contains("run_command"));
    }

    @Test
    void subagentRegistryNeverExposesDelegationTools() {
        AgentScope scope = AgentScope.readWrite(List.of("**"), List.of());

        List<String> names = names(registry(scope));

        assertFalse(names.contains("spawn_agent"), names.toString());
        assertFalse(names.contains("dispatch_agents"), names.toString());
    }
}
