package com.pilot.server.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.runtime.bootstrap.SourceEnvironment;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.security.SecurityProperties;

import tools.jackson.databind.ObjectMapper;

class MobileEnvironmentTest {
    @TempDir
    Path directory;

    @Test
    void desktopStartupLeavesOptionalPhoneComponentsUncheckedUntilRequested() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        var data = new PilotDataDir(directory.toString());
        var environment = new MobileEnvironment(data, true, "", (root, cache, libraries, progress) -> {
            entered.countDown();
            return new SourceEnvironment.Mobile(null, null, false, "手机连接组件尚未打包");
        });
        var service = new MobileConnectionService(data, new SecurityProperties(null, null, null, null, null),
            new ObjectMapper(), "", "");
        service.configureEnvironment(environment);
        try {
            assertEquals("unchecked", environment.status().state());
            assertEquals(1, entered.getCount());
            service.request("status", null);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            awaitState(environment, "failed");
        } finally {
            service.close();
            environment.close();
        }
    }

    @Test
    void ideModuleStartKeepsDefaultDownloadsInThePilotRoot() throws Exception {
        Path root = directory.resolve("Pilot 中文 with spaces");
        Files.createDirectories(root.resolve("pilot-mobile/bridge/src"));
        Path module = root.resolve("pilot-server");
        Files.createDirectories(module);
        assertEquals(root.resolve(".pilot/bootstrap"),
            MobileEnvironment.defaultCache(module, module.resolve(".pilot")));
        assertEquals(directory.resolve("custom-data/bootstrap"),
            MobileEnvironment.defaultCache(module, directory.resolve("custom-data")));
        assertEquals(root.resolve(".pilot/bootstrap"), MobileEnvironment.defaultCache(root, root.resolve(".pilot")));
    }

    @Test
    void preparingIsNonBlockingAndRepeatedRepairSharesOneJob() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        var data = new PilotDataDir(directory.toString());
        var environment = new MobileEnvironment(data, true, "", (root, cache, libraries, progress) -> {
            attempts.incrementAndGet();
            progress.accept("正在下载并校验依赖");
            entered.countDown();
            finish.await();
            return new SourceEnvironment.Mobile(directory, directory, true, "已就绪");
        });
        var service = new MobileConnectionService(data, new SecurityProperties(null, null, null, null, null),
            new ObjectMapper(), "", "");
        service.configureEnvironment(environment);
        try {
            environment.repair();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            environment.repair();
            var status = service.request("status", null);
            assertFalse(status.path("running").asBoolean());
            assertEquals("preparing", status.path("environment").path("state").asText());
            assertTrue(status.path("environment").path("message").asText().contains("校验"));
            assertEquals(1, attempts.get());
            assertThrows(IllegalStateException.class, () -> service.request("pair", null));
            finish.countDown();
            awaitState(environment, "ready");
            assertEquals(directory, environment.prepared().javaHome());
        } finally {
            finish.countDown();
            service.close();
            environment.close();
        }
    }

    @Test
    void failureDoesNotLeakCredentialsAndOnlyExplicitRepairRetries() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        var environment = new MobileEnvironment(new PilotDataDir(directory.toString()), true, "",
            (root, cache, libraries, progress) -> {
                attempts.incrementAndGet();
                throw new IOException("https://user:secret@proxy.invalid/private");
            });
        try {
            environment.repair();
            awaitState(environment, "failed");
            assertFalse(environment.status().message().contains("secret"));
            assertEquals(1, attempts.get());
            environment.repair();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (attempts.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(2, attempts.get());
        } finally {
            environment.close();
        }
    }

    @Test
    void disabledModeDoesNotBuildOrDownload() {
        var environment = new MobileEnvironment(new PilotDataDir(directory.toString()), false, "",
            (root, cache, libraries, progress) -> {
                throw new AssertionError("不应执行安装");
            });
        try {
            environment.repair();
            assertTrue(environment.status().ready());
            assertEquals("disabled", environment.status().state());
        } finally {
            environment.close();
        }
    }

    private static void awaitState(MobileEnvironment environment, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!expected.equals(environment.status().state()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, environment.status().state());
    }
}
