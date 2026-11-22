package com.pilot.server.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.pilot.core.contract.Contracts;
import com.pilot.core.mcp.McpManager;
import com.pilot.core.message.ToolNames;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.session.SessionService;
import com.pilot.core.skill.SkillSource;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.store.PilotDataDir;
import com.pilot.server.store.StorageHealthService;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.SkillProperties;
import com.pilot.server.maintenance.MaintenanceController;
import com.pilot.server.skill.SkillService;
import com.pilot.server.tool.WorkspaceToolFactory;

/**
 * 插件契约：插件依赖的是契约版本，不是核心版本号。
 *
 * <p>这组测试关心的是「缺契约时有没有说清楚」，而不是「技能能不能跑」——
 * 静默少一个技能或被跳过的钩子，比直接报错更难查。
 */
class PluginContractCompatTest {
    @TempDir
    Path root;

    private Path workspace;
    private AgentProperties properties;
    private SessionService sessions;
    private WorkspaceToolFactory toolFactory;
    private SkillService skills;
    private String session;

    @BeforeEach
    void setup() throws Exception {
        workspace = Files.createDirectory(root.resolve("workspace"));
        properties =
            new AgentProperties(workspace.toString(), PermissionMode.READ_ONLY, 8, 3, 5, 300, 1, 0, 60000, false);
        sessions = new SessionService();
        session = sessions.createSession("契约", workspace.toString()).id();
        skills = new SkillService(new SkillProperties(true, null), properties,
            new PilotDataDir(root.resolve("data").toString()));
        ToolRegistry registry = new ToolRegistry();
        registry.register(stubTool(ToolNames.READ_FILE));
        toolFactory = mock(WorkspaceToolFactory.class);
        when(toolFactory.resolve(anyString())).thenReturn(workspace);
        when(toolFactory.registryFor(anyString())).thenReturn(registry);
    }

    private PluginCompatService service() {
        return new PluginCompatService(sessions, toolFactory, skills, properties,
            new DefaultListableBeanFactory().getBeanProvider(McpManager.class));
    }

    @Test
    void skillWithSatisfiedContractsStaysOkAndListsItsContracts() throws Exception {
        writeSkill("research", """
            ---
            description: 整理调研结论
            requires: tool.read_file@1, event.run_end@1
            requires-optional: tool.edit_file@1
            ---

            正文
            """);

        var items = service().inspect(session).items();
        assertEquals(1, items.size());
        assertEquals(PluginCompatService.OK, items.getFirst().status());
        assertEquals(List.of("tool.read_file@1", "event.run_end@1", "tool.edit_file@1"), items.getFirst().contracts());
        assertEquals(SkillSource.PROJECT.name().toLowerCase(), items.getFirst().source());
    }

    @Test
    void skillMissingARequiredContractIsNotLoadedAndSaysWhy() throws Exception {
        writeSkill("teleport", """
            ---
            description: 需要核心还不提供的能力
            requires: tool.teleport@1
            ---

            正文
            """);

        // 技能根本没进目录：不是「加载了但不好用」，而是「不会加载」。
        assertTrue(skills.catalogFor(workspace.toString()).find("teleport").isEmpty());
        assertFalse(skills.catalogFor(workspace.toString()).invalid().isEmpty());
        assertTrue(skills.catalogFor(workspace.toString()).invalid().getFirst().reason().contains("缺少必需契约"));

        // 升级前体检把它列为会失效，并指出缺的是哪个契约。
        var report = service().inspect(session);
        var item = report.items().stream().filter(entry -> "skill".equals(entry.kind())).findFirst().orElseThrow();
        assertEquals(PluginCompatService.BROKEN, item.status());
        assertTrue(item.details().getFirst().contains("tool.teleport@1"), item.details().toString());
        assertTrue(report.counts().broken() >= 1);
    }

    @Test
    void unsatisfiedOptionalContractOnlyDowngrades() throws Exception {
        writeSkill("polish", """
            ---
            description: 需要可选能力的技能
            requires-optional: tool.format_markdown@1
            ---

            正文
            """);

        assertTrue(skills.catalogFor(workspace.toString()).find("polish").isPresent(), "可选契约不满足时技能仍应加载");
        var item = service().inspect(session).items().getFirst();
        assertEquals(PluginCompatService.CHANGED, item.status());
        assertTrue(item.details().getFirst().contains("降级"));
    }

    @Test
    void hookGroupWithUnsatisfiedContractIsSkippedAndReported() throws Exception {
        Files.createDirectories(workspace.resolve(".pilot"));
        Files.writeString(workspace.resolve(".pilot/settings.json"), """
            {
              "hooks": {
                "PostToolUse": [
                  { "matcher": "read_file", "requires": "hook.post_tool_use@1",
                    "hooks": [ { "type": "command", "command": "echo kept" } ] },
                  { "matcher": "read_file", "requires": "hook.after_commit@1",
                    "hooks": [ { "type": "command", "command": "echo dropped" } ] }
                ]
              }
            }
            """);

        var items = service().inspect(session).items();
        assertEquals(2, items.size(), "跳过的钩子也要出现在清单里，否则用户只会以为配置丢了");
        assertTrue(items.stream()
            .anyMatch(item -> item.name().contains("未加载") && item.status().equals(PluginCompatService.BROKEN)));
        assertTrue(items.stream().anyMatch(item -> item.reason().contains("hook.after_commit@1")));
        assertTrue(items.stream().anyMatch(item -> item.status().equals(PluginCompatService.OK)));
    }

    @Test
    void contractsEndpointPublishesVersionedReferences() {
        var controller = new MaintenanceController(null, null, null, service(), new StorageHealthService());
        var response = controller.contracts();
        assertEquals(Contracts.publishedContracts().size(), response.contracts().size());
        assertTrue(response.catalogue().containsKey("tool.read_file@1"));
        assertTrue(response.catalogue().containsKey("hook.pre_tool_use@1"));
        // 目录与契约表的键必须一一对应，否则对外文档和实际判断会两套。
        assertEquals(response.contracts().stream().map(contract -> contract.reference()).sorted().toList(),
            response.catalogue().keySet().stream().sorted().toList());
    }

    private void writeSkill(String name, String markdown) throws Exception {
        Path directory = workspace.resolve(".pilot/skills").resolve(name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), markdown);
    }

    private static Tool stubTool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public boolean readOnly() {
                return true;
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public String execute(Map<String, Object> args) {
                return "";
            }
        };
    }
}
