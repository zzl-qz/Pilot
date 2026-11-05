package com.pilot.server.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;

import com.pilot.server.config.PilotDataDir;
import com.pilot.server.security.SecurityProperties;

import tools.jackson.databind.ObjectMapper;

class MobileConnectionServiceTest {
    @TempDir
    Path directory;

    @Test
    void missingClasspathIsClassifiedFromRealChildProcessWithoutExposingItsOutput() {
        var service = service(directory.toString());
        try {
            ready(service);
            String message =
                assertThrows(IllegalStateException.class, () -> service.request("status", null)).getMessage();
            assertTrue(message.contains("缺少依赖"), message);
            assertTrue(message.contains("node scripts/mobile.mjs build"));
        } finally {
            service.close();
        }
    }

    @Test
    void invalidExplicitLibraryDirectoryDoesNotSilentlyUseAnotherInstallation() {
        var service = service(directory.resolve("missing").toString());
        try {
            ready(service);
            assertTrue(assertThrows(IllegalStateException.class, () -> service.request("status", null)).getMessage()
                .contains("pilot.mobile.libraries"));
        } finally {
            service.close();
        }
    }

    @Test
    void acceptsOnlyExplicitConnectionModes() {
        var service = service("");
        try {
            service.configureConnectionMode("auto");
            service.configureConnectionMode("direct");
            assertThrows(IllegalArgumentException.class, () -> service.configureConnectionMode("public"));
        } finally {
            service.close();
        }
    }

    private MobileConnectionService service(String libraries) {
        return new MobileConnectionService(new PilotDataDir(directory.toString()),
            new SecurityProperties(null, null, null, null, null), new ObjectMapper(), libraries, "");
    }

    private static void ready(MobileConnectionService service) {
        var server = mock(WebServer.class);
        when(server.getPort()).thenReturn(9999);
        var event = mock(WebServerInitializedEvent.class);
        when(event.getWebServer()).thenReturn(server);
        service.serverReady(event);
    }

    @Test
    void shutdownCannotRestartTheBridge() {
        var service = new MobileConnectionService(new PilotDataDir(directory.toString()),
            new SecurityProperties(null, null, null, null, null), new ObjectMapper(), "", "");
        service.close();
        assertEquals("Pilot 已关闭，请启动后重新连接",
            assertThrows(IllegalStateException.class, () -> service.request("status", null)).getMessage());
    }

    @Test
    void acceptsSupportedPlatformsAndRejectsMissingNativeLibraries() {
        MobileConnectionService.requirePlatform("Mac OS X", "aarch64");
        MobileConnectionService.requirePlatform("Windows 11", "amd64");
        MobileConnectionService.requirePlatform("Linux", "x86_64");
        MobileConnectionService.requirePlatform("Linux", "aarch64");
        assertThrows(IllegalStateException.class, () -> MobileConnectionService.requirePlatform("Mac OS X", "x86_64"));
        assertThrows(IllegalStateException.class,
            () -> MobileConnectionService.requirePlatform("Windows 11", "aarch64"));
    }

    @Test
    void classpathWildcardIsJoinedAsTextBecauseWindowsRejectsStarInPaths() {
        // Path.resolve("*") 在 Windows 里是非法路径（InvalidPathException），通配符只能拼字符串。
        String classpath = MobileConnectionService.classpath(directory);
        assertEquals(directory + File.separator + "*", classpath);
    }

    @Test
    void adminAddressMustBeLoopbackWithOnlyAFragmentCredential() {
        String key = "x".repeat(43);
        assertEquals(1234, MobileConnectionService.validateAdmin("http://127.0.0.1:1234/#" + key).getPort());
        for (String invalid : new String[] {"https://127.0.0.1:1234/#", "http://remote:1234/#",
            "http://user@127.0.0.1:1234/#", "http://127.0.0.1:1234/?token=x#", "http://127.0.0.1:1234/other#"}) {
            assertThrows(IllegalArgumentException.class, () -> MobileConnectionService.validateAdmin(invalid + key));
        }
    }
}
