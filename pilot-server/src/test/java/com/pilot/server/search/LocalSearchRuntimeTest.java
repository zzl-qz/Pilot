package com.pilot.server.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.config.WebSearchProperties;

class LocalSearchRuntimeTest {
    @TempDir
    Path dataDir;

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
}
