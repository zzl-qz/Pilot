package com.pilot.server.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.pilot.core.mcp.McpManager;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.capability.LocalCapabilityService;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.compat.PluginCompatService;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.SkillProperties;
import com.pilot.server.model.ModelSettings;
import com.pilot.server.permission.PermissionSettings;
import com.pilot.server.skill.SkillService;

/** 能力页与升级前体检共用同一份插件判断，页面不另写一套健康度口径。 */
class CapabilitiesControllerTest {
    @TempDir
    Path root;

    @Test
    void capabilitiesPageCarriesTheSamePluginVerdictAsTheUpgradePreview() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Files.createDirectories(workspace.resolve(".pilot"));
        Files.writeString(workspace.resolve(".pilot/settings.json"), """
            {"hooks":{"PostToolUse":[{"matcher":"gone_tool","hooks":[{"type":"command","command":"echo x"}]}]}}
            """);
        SessionService sessions = new SessionService();
        String session = sessions.createSession("能力", workspace.toString()).id();
        AgentProperties properties =
            new AgentProperties(workspace.toString(), PermissionMode.READ_ONLY, 8, 3, 5, 300, 1, 0, 60000, false);
        SkillService skills = new SkillService(new SkillProperties(true, null), properties,
            new PilotDataDir(root.resolve("data").toString()));
        WorkspaceToolFactory factory = mock(WorkspaceToolFactory.class);
        when(factory.resolve(anyString())).thenReturn(workspace);
        when(factory.registryFor(anyString())).thenReturn(new ToolRegistry());
        when(factory.sessionExtras(anyString(), anyString())).thenReturn(List.of());
        PluginCompatService plugins = new PluginCompatService(sessions, factory, skills, properties,
            new DefaultListableBeanFactory().getBeanProvider(McpManager.class));
        LocalCapabilityService local = mock(LocalCapabilityService.class);
        when(local.discover()).thenReturn(List.of());
        ModelSettings models = mock(ModelSettings.class);
        when(models.snapshot()).thenReturn(mock(ModelSettings.Snapshot.class));
        PermissionSettings permissions = mock(PermissionSettings.class);
        when(permissions.mode()).thenReturn(PermissionMode.READ_ONLY);

        var controller = new CapabilitiesController(sessions, properties, permissions, models,
            new DefaultListableBeanFactory().getBeanProvider(ToolRegistry.class),
            new DefaultListableBeanFactory().getBeanProvider(WorkspaceToolFactory.class),
            new DefaultListableBeanFactory().getBeanProvider(McpManager.class), local, plugins);

        var response = controller.capabilities(session);
        var expected = plugins.inspect(session);
        assertEquals(expected.items(), response.plugins(), "页面的插件清单必须与升级前体检完全一致");
        assertEquals(expected.counts(), response.pluginCounts());
        assertEquals(expected.markdown(), response.pluginReport());
        assertEquals(1, response.pluginCounts().broken());
        assertTrue(response.plugins().getFirst().reason().contains("静默"));
        assertFalse(response.pluginReport().isBlank());
    }
}
