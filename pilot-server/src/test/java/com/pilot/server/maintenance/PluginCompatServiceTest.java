package com.pilot.server.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import com.pilot.core.mcp.McpManager;
import com.pilot.core.message.ToolNames;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.session.SessionService;
import com.pilot.core.skill.SkillSource;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.SkillProperties;
import com.pilot.server.skill.SkillService;
import com.pilot.server.tool.WorkspaceToolFactory;

/**
 * 升级前体检的口径：只读、不启动服务器，并把「静默失效」显式说出来。
 *
 * <p>这里不验证 MCP 服务器能否真的启动——那属于集成测试；本测试只保证分类与文案
 * 不会把「会坏」说成「没问题」。
 */
class PluginCompatServiceTest {
    @TempDir
    Path root;

    private Path workspace;
    private AgentProperties properties;
    private SkillService skills;
    private WorkspaceToolFactory toolFactory;
    private SessionService sessions;
    private String session;

    @BeforeEach
    void setup() throws Exception {
        workspace = Files.createDirectory(root.resolve("workspace"));
        properties =
            new AgentProperties(workspace.toString(), PermissionMode.READ_ONLY, 8, 3, 5, 300, 1, 0, 60000, false);
        sessions = new SessionService();
        session = sessions.createSession("体检", workspace.toString()).id();
        skills = new SkillService(new SkillProperties(true, null), properties,
            new PilotDataDir(root.resolve("data").toString()));
        ToolRegistry registry = new ToolRegistry();
        for (String name : List.of(ToolNames.READ_FILE, ToolNames.EDIT_FILE, ToolNames.RUN_COMMAND)) {
            registry.register(stubTool(name));
        }
        toolFactory = mock(WorkspaceToolFactory.class);
        when(toolFactory.resolve(anyString())).thenReturn(workspace);
        when(toolFactory.registryFor(anyString())).thenReturn(registry);
    }

    private PluginCompatService service() {
        return new PluginCompatService(sessions, toolFactory, skills, properties,
            new DefaultListableBeanFactory().getBeanProvider(McpManager.class));
    }

    @Test
    void emptyWorkspaceReportsNoPluginsInsteadOfFailing() {
        var report = service().inspect(session);
        assertTrue(report.items().isEmpty(), "没有插件时应返回空清单，而不是报错或编造条目");
        assertEquals(0, report.counts().broken());
        assertTrue(report.markdown().contains("没有需要检查的插件"));
        assertNotNull(report.generatedAt());
    }

    @Test
    void hookPointingAtARemovedToolIsReportedAsBrokenWithReason() throws Exception {
        writeHooks(
            """
                {
                  "hooks": {
                    "PostToolUse": [
                      { "matcher": "edit_file", "hooks": [ { "type": "command", "command": "echo ok" } ] },
                      { "matcher": "tool_that_no_longer_exists", "hooks": [ { "type": "command", "command": "echo gone" } ] }
                    ]
                  }
                }
                """);

        var hooks = service().inspect(session).items().stream().filter(item -> "hook".equals(item.kind())).toList();
        assertEquals(2, hooks.size());
        var broken =
            hooks.stream().filter(item -> PluginCompatService.BROKEN.equals(item.status())).findFirst().orElseThrow();
        assertTrue(broken.name().contains("tool_that_no_longer_exists"));
        assertTrue(broken.reason().contains("静默"), "必须说明失效是静默的，用户才知道要主动去查：" + broken.reason());
        assertFalse(broken.details().isEmpty(), "要给出具体原因，而不只是一个状态");
        assertEquals(1, hooks.stream().filter(item -> PluginCompatService.OK.equals(item.status())).count());
    }

    @Test
    void skillDeclaringAMissingToolIsDowngradedButNotCalledBroken() throws Exception {
        Path skill = workspace.resolve(".pilot/skills/research");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), """
            ---
            description: 整理调研结论
            allowed-tools: read_file, tool_that_never_existed
            ---

            ## 步骤
            读来源再写结论。
            """);

        var items = service().inspect(session).items().stream().filter(item -> "skill".equals(item.kind())).toList();
        assertEquals(1, items.size());
        // 技能是提示词：声明的工具不存在不会让技能整体失效，所以是 changed 而不是 broken。
        assertEquals(PluginCompatService.CHANGED, items.getFirst().status());
        assertTrue(items.getFirst().details().getFirst().contains("tool_that_never_existed"));
        assertEquals(SkillSource.PROJECT.name().toLowerCase(), items.getFirst().source());
    }

    @Test
    void skillDeclaringOnlyExistingToolsStaysOk() throws Exception {
        Path skill = workspace.resolve(".pilot/skills/research");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), """
            ---
            description: 整理调研结论
            allowed-tools: read_file, edit_file
            ---

            正文
            """);

        var items = service().inspect(session).items();
        assertEquals(1, items.size());
        assertEquals(PluginCompatService.OK, items.getFirst().status());
    }

    @Test
    void reportCountsMatchTheItemListAndMarkdownMirrorsThem() throws Exception {
        writeHooks("""
            {
              "hooks": {
                "PostToolUse": [
                  { "matcher": "gone_tool", "hooks": [ { "type": "command", "command": "echo x" } ] },
                  { "matcher": "read_file", "hooks": [ { "type": "command", "command": "echo y" } ] }
                ]
              }
            }
            """);
        var report = service().inspect(session);
        var counts = report.counts();
        assertEquals(report.items().size(), counts.ok() + counts.changed() + counts.broken() + counts.unknown(),
            "汇总数字必须与明细一致，否则页面会自相矛盾");
        assertEquals(1, counts.broken());
        assertEquals(1, counts.ok());
        assertTrue(report.markdown().contains("| 类型 | 名称 | 状态 | 说明 |"));
        assertTrue(report.markdown().contains("gone_tool"));
    }

    @Test
    void missingMcpManagerDegradesInsteadOfThrowing() {
        // 未装配 MCP 时不应把整个体检打挂：只是没有 MCP 相关条目。
        var report = new PluginCompatService(sessions, toolFactory, mock(SkillService.class), properties,
            new DefaultListableBeanFactory().getBeanProvider(McpManager.class)).inspect(session);
        assertTrue(report.items().isEmpty());
        assertTrue(report.items().stream().noneMatch(item -> "mcp".equals(item.kind())));
    }

    private void writeHooks(String json) throws Exception {
        Files.createDirectories(workspace.resolve(".pilot"));
        Files.writeString(workspace.resolve(".pilot/settings.json"), json);
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
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public boolean readOnly() {
                return true;
            }

            @Override
            public String execute(Map<String, Object> args) {
                return "";
            }
        };
    }
}
