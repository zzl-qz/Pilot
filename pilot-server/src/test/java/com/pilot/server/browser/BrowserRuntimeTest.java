package com.pilot.server.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import com.pilot.server.runtime.RuntimeMirrors;
import com.pilot.server.workspace.WorkspaceToolFactory;

import tools.jackson.databind.ObjectMapper;

class BrowserRuntimeTest {
    @TempDir
    Path data;

    @Test
    void installsBrowserDependenciesUsingTheConfiguredFallbackAfterFailure() throws Exception {
        Path home = Files.createDirectory(data.resolve("浏览器 runtime"));
        Path bin = Files.createDirectory(data.resolve("node bin"));
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path node = Files.createFile(bin.resolve(windows ? "node.exe" : "node"));
        Path npm = Files.createFile(bin.resolve(windows ? "npm.cmd" : "npm"));
        if (!windows) {
            assertTrue(npm.toFile().setExecutable(true));
        }
        Path dependency = home.resolve("node_modules/playwright-core");
        var environments = new ArrayList<Map<String, String>>();
        var commands = new ArrayList<List<String>>();
        var mirrors = new RuntimeMirrors("https://mirror.test/npm", "https://mirror.test/chromium/",
            "https://mirror.test/electron");
        BrowserRuntime runtime = new BrowserRuntime(browserHome(home.toString()), new ObjectMapper(), mirrors, true,
            true, true, true, true, "", -1) {
            @Override
            int run(Path directory, Path executable, Duration timeout, List<String> command,
                Map<String, String> environment) {
                environments.add(Map.copyOf(environment));
                commands.add(command);
                if (environments.size() == 2) {
                    assertTrue(dependency.toFile().mkdirs());
                } else if (environments.size() == 4) {
                    assertTrue(home.resolve("browsers/chromium-test").toFile().mkdirs());
                }
                return environments.size() % 2;
            }
        };

        assertTrue(runtime.install(home, node));
        Files.createFile(dependency.resolve("cli.js"));
        assertTrue(runtime.installChromium(home, node));
        assertEquals(commands.get(0), commands.get(1));
        assertEquals(commands.get(2), commands.get(3));
        assertEquals(Map.of(), environments.get(0));
        assertEquals(Map.of("npm_config_registry", "https://mirror.test/npm"), environments.get(1));
        assertEquals(Set.of("PLAYWRIGHT_BROWSERS_PATH"), environments.get(2).keySet());
        assertEquals("https://mirror.test/chromium", environments.get(3).get("PLAYWRIGHT_CHROMIUM_DOWNLOAD_HOST"));
        assertEquals("https://mirror.test/chromium", environments.get(3).get("PLAYWRIGHT_DOWNLOAD_HOST"));
    }

    @Test
    void disabledRuntimeDeclaresNoCapabilityAndExplainsWhy() {
        BrowserRuntime runtime = new BrowserRuntime(browserHome(""), new ObjectMapper(), RuntimeMirrors.defaults(),
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
        BrowserRuntime runtime = new BrowserRuntime(browserHome(""), new ObjectMapper(), RuntimeMirrors.defaults(),
            false, false, false, true, true, "", -1);
        WorkspaceToolFactory factory = new WorkspaceToolFactory(
            new AgentProperties(workspace.toString(), PermissionMode.AUTO, 8, 3, 5, 300, 2, 1, 60_000, true),
            new WebSearchProperties(false, "http://127.0.0.1:9", "", ""), SnapshotSink.none(), new ObjectMapper());
        factory.setBrowser(runtime);
        factory.setMcp(new McpManager(new ObjectMapper(), null, null, () -> ""));

        List<Tool> extras = factory.sessionExtras("s1", workspace.toString());
        ToolRegistry registry = new ToolRegistry();
        extras.forEach(registry::register);

        assertEquals(Set.of("browser", "mcp_tools", "mcp_call"),
            extras.stream().map(Tool::name).collect(Collectors.toSet()));
        assertTrue(registry.names().isEmpty(), "没有浏览器、没有 .mcp.json 时模型什么都看不到：" + registry.names());
        assertTrue(registry.unavailable().get("browser").contains("pilot.browser.enabled=false"));
        assertTrue(registry.unavailable().get("mcp_tools").contains(".mcp.json"));
    }

    /**
     * 桌面会话判定不能用 GraphicsEnvironment.isHeadless()（Spring Boot 启动时统一设
     * java.awt.headless=true，本地有桌面也误降级——实测踩过）。这里锁定真实信号：
     * Windows 会话类型、Linux 的 DISPLAY。
     */
    @Test
    void desktopDetectionUsesOsSignalsInsteadOfJvmHeadlessFlag() {
        // Windows：本地控制台 / RDP 会话有桌面，变量缺失按有桌面处理（本地 Web 应用是主流形态）
        assertTrue(BrowserRuntime.desktopAvailable("Windows 11", "Console", null, null));
        assertTrue(BrowserRuntime.desktopAvailable("Windows 11", "RDP-Tcp#0", null, null));
        assertTrue(BrowserRuntime.desktopAvailable("Windows 11", null, null, null));
        // Windows 服务会话（Session 0）没有交互桌面
        assertFalse(BrowserRuntime.desktopAvailable("Windows 11", "Service", null, null));
        // Linux：有 X11 / Wayland 会话才有桌面，CI 与纯 SSH 环境正确降级
        assertTrue(BrowserRuntime.desktopAvailable("Linux", "", ":0", null));
        assertTrue(BrowserRuntime.desktopAvailable("Linux", "", null, "wayland-0"));
        assertFalse(BrowserRuntime.desktopAvailable("Linux", "", null, null));
        // macOS 本地运行常在桌面
        assertTrue(BrowserRuntime.desktopAvailable("Mac OS X", null, null, null));
        assertTrue(BrowserRuntime.desktopAvailable("Darwin", null, null, null));
    }

    @Test
    void realRuntimeBecomesAvailableOnlyAfterTheHandshake() throws Exception {
        BrowserHome home = browserHome("");
        Assumptions.assumeTrue(Files.isDirectory(home.directory().resolve("node_modules").resolve("playwright-core")),
            "没有安装 playwright-core");
        BrowserRuntime runtime = new BrowserRuntime(home, new ObjectMapper(), RuntimeMirrors.defaults(), true, false,
            false, true, true, "", -1);
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

    private BrowserHome browserHome(String configured) {
        return new BrowserHome(new PilotDataDir(data.toString()), configured);
    }
}
