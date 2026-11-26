package com.pilot.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.server.config.WebSearchProperties;
import com.pilot.server.store.PilotDataDir;

class LocalSearchRuntimeTest {
    @TempDir
    Path dataDir;

    @Test
    void pythonCandidateFilteringRejectsWindowsStoreZeroByteAliases() throws Exception {
        Path store = dataDir.resolve("WindowsApps");
        Files.createDirectories(store);
        Path stub = Files.createFile(store.resolve("python.exe"));
        assertFalse(LocalSearchRuntime.usablePythonPath(stub));
        Path installed = Files.writeString(store.resolve("py.exe"), "python launcher");
        assertTrue(LocalSearchRuntime.usablePythonPath(installed));

        Path parent = Path.of("C:/pilot/search");
        var cmd =
            LocalSearchRuntime.tarExtractCommand(parent, parent.resolve("searxng.tar.gz"), parent.resolve("unpack"));
        assertEquals("searxng.tar.gz", cmd.get(2));
        assertEquals("unpack", cmd.get(4));
        assertFalse(cmd.get(2).contains(":"));
    }

    @Test
    void staysDownWhenDisabled() {
        LocalSearchRuntime runtime = new LocalSearchRuntime(
            new WebSearchProperties(false, "http://127.0.0.1:9", "", ""), new PilotDataDir(dataDir.toString()));

        runtime.start();

        assertFalse(runtime.isRunning());
    }

    @Test
    void reusesServiceOnConfiguredPort() throws Exception {
        try (ServerSocket service = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var properties = new WebSearchProperties(true, "http://127.0.0.1:" + service.getLocalPort(), "", "");
            var runtime = new LocalSearchRuntime(properties, new PilotDataDir(dataDir.toString()));
            runtime.start();
            assertTrue(runtime.isRunning());
            runtime.stop();
            assertFalse(service.isClosed());
        }
    }

    @Test
    void distinguishesListeningFromReservedIpv6Port() throws Exception {
        InetAddress loopback = InetAddress.getByName("::1");
        try (ServerSocket service = new ServerSocket()) {
            try {
                service.bind(new InetSocketAddress(loopback, 0));
            } catch (IOException unsupported) {
                assumeTrue(false, "当前环境不能监听 IPv6 回环");
            }
            var address = new InetSocketAddress(loopback, service.getLocalPort());
            assertTrue(LocalSearchRuntime.portOpen(address));
            service.close();
            assertFalse(LocalSearchRuntime.portOpen(address));
        }
    }

    @Test
    void syncManagedSettingsCreatesUpgradesV1ToV3AndPreservesCustomUserFile() throws Exception {
        Path settings = dataDir.resolve("searxng").resolve("settings.yml");
        assertFalse(LocalSearchRuntime.isSettingsUpToDate(settings));
        assertTrue(LocalSearchRuntime.syncManagedSettings(settings));
        assertTrue(LocalSearchRuntime.isSettingsUpToDate(settings));
        String v4 = Files.readString(settings);
        assertTrue(v4.startsWith("# pilot-managed-settings: v4"));
        assertTrue(v4.contains("- bing"));
        assertTrue(v4.contains("- 360search"));
        assertTrue(v4.contains("- duckduckgo news"));
        assertTrue(v4.contains("- baidu kaifa"));
        assertTrue(v4.contains("- openalex"));
        assertFalse(v4.contains("- sogou"));
        assertFalse(v4.contains("- baidu\n"));
        assertFalse(v4.contains("- wikidata"));

        // 已经是 v4 时不再重复覆盖
        assertFalse(LocalSearchRuntime.syncManagedSettings(settings));

        // 模拟旧版 v1 / v2 / v3 托管文件：均应自动备份并升级为 v4
        for (String legacy : new String[] {"# Pilot 写给本机 SearXNG 的配置。只监听 127.0.0.1，不对外。\nuse_default_settings: true\n",
            "# pilot-managed-settings: v2\nuse_default_settings: true\n",
            "# pilot-managed-settings: v3\nuse_default_settings: true\n"}) {
            Files.writeString(settings, legacy);
            assertFalse(LocalSearchRuntime.isSettingsUpToDate(settings));
            assertTrue(LocalSearchRuntime.syncManagedSettings(settings));
            assertTrue(LocalSearchRuntime.isSettingsUpToDate(settings));
            assertTrue(Files.readString(settings).startsWith("# pilot-managed-settings: v4"));
            assertEquals(legacy, Files.readString(settings.resolveSibling("settings.yml.bak")));
        }

        // 用户去掉托管头自行定制后，绝不覆盖
        String custom = "# my custom searxng settings\nserver:\n  port: 8888\n";
        Files.writeString(settings, custom);
        assertTrue(LocalSearchRuntime.isSettingsUpToDate(settings));
        assertFalse(LocalSearchRuntime.syncManagedSettings(settings));
        assertEquals(custom, Files.readString(settings));
    }
}
