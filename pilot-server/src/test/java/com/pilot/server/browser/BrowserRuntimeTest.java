package com.pilot.server.browser;

import com.pilot.core.browser.BrowserBackend;
import com.pilot.core.mcp.McpManager;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.tool.Availability;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.config.WebSearchProperties;
import com.pilot.server.workspace.WorkspaceToolFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserRuntimeTest {
    @TempDir
    Path data;

    @Test
    void disabledRuntimeDeclaresNoCapabilityAndExplainsWhy() {
        BrowserRuntime runtime = new BrowserRuntime(new PilotDataDir(data.toString()), new ObjectMapper(),
                false, false, false, true, true, "", -1);
        runtime.start();

        Availability availability = runtime.availability();

        assertFalse(availability.available());
        assertTrue(availability.reason().contains("pilot.browser.enabled=false"), availability.reason());
        BrowserBackend.BrowserException error = assertThrows(BrowserBackend.BrowserException.class,
                () -> runtime.execute("s", Map.of("op", "snapshot"), Duration.ofSeconds(1)));
        assertTrue(error.getMessage().startsWith("浏览器当前不可用："));
        runtime.stop();
    }

    @Test
    void toolFactoryRegistersSessionExtrasButTheyStayHiddenUntilTheirCapabilityIsReady(@TempDir Path workspace) {
        BrowserRuntime runtime = new BrowserRuntime(new PilotDataDir(data.toString()), new ObjectMapper(),
                false, false, false, true, true, "", -1);
        WorkspaceToolFactory factory = new WorkspaceToolFactory(
                new AgentProperties(workspace.toString(), PermissionMode.AUTO, 8, 3, 5, 300, 2, 1, 60_000, true),
                new WebSearchProperties(false, "http://127.0.0.1:9", "", ""),
                SnapshotSink.none(), new ObjectMapper());
        factory.setBrowser(runtime);
        factory.setMcp(new McpManager(new ObjectMapper(), null, null, () -> ""));

        List<Tool> extras = factory.sessionExtras("s1", workspace.toString());
        ToolRegistry registry = new ToolRegistry();
        extras.forEach(registry::register);

        assertEquals(java.util.Set.of("browser", "mcp_tools", "mcp_call"),
                extras.stream().map(Tool::name).collect(java.util.stream.Collectors.toSet()));
        assertTrue(registry.names().isEmpty(), "没有浏览器、没有 .mcp.json 时模型什么都看不到：" + registry.names());
        assertTrue(registry.unavailable().get("browser").contains("pilot.browser.enabled=false"));
        assertTrue(registry.unavailable().get("mcp_tools").contains(".mcp.json"));
    }

    @Test
    void realRuntimeBecomesAvailableOnlyAfterTheHandshake() throws Exception {
        Path home = Path.of(System.getProperty("user.dir")).resolveSibling(".pilot").resolve("browser");
        Path repoHome = Files.isDirectory(home) ? home : Path.of(System.getProperty("user.dir"), ".pilot", "browser");
        Assumptions.assumeTrue(Files.isDirectory(repoHome.resolve("node_modules").resolve("playwright-core")),
                "没有安装 playwright-core");
        BrowserRuntime runtime = new BrowserRuntime(new PilotDataDir(data.toString()), new ObjectMapper(),
                true, false, false, true, true, "", -1);
        try {
            assertFalse(runtime.availability().available(), "启动之前不能声明能力");
            runtime.start();
            long deadline = System.currentTimeMillis() + 60_000;
            while (!runtime.availability().available() && System.currentTimeMillis() < deadline
                    && runtime.availability().reason().contains("正在准备")) {
                Thread.sleep(200);
            }
            Availability availability = runtime.availability();
            Assumptions.assumeTrue(availability.available(), "本机无法启动浏览器：" + availability.reason());
            assertTrue(availability.available());
        } finally {
            runtime.stop();
        }
    }
}
