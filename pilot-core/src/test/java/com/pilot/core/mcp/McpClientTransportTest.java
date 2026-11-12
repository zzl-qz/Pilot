package com.pilot.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

class McpClientTransportTest {
    @TempDir
    Path workspace;

    private McpClient client(String mode) {
        Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        McpServerConfig config =
            new McpServerConfig("fixture", java.toString(), List.of("-cp", System.getProperty("java.class.path"),
                McpProcessFixture.class.getName(), mode, workspace.resolve("ready").toString()), Map.of(), null);
        McpClient client = new McpClient("fixture", config, workspace, null, new ObjectMapper(), "");
        client.start(Duration.ofSeconds(3));
        return client;
    }

    private void ready() throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!Files.exists(workspace.resolve("ready")) && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(workspace.resolve("ready")));
    }

    @Test
    void blockedPipeUsesTheSameDeadlineAsResponseWait() throws Exception {
        try (McpClient client = client("block")) {
            ready();
            long start = System.nanoTime();
            McpException error = assertThrows(McpException.class,
                () -> client.call("write", Map.of("text", "x".repeat(1024 * 1024)), Duration.ofMillis(200)));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1500, "写入阻塞不能越过调用时限");
            assertTrue(error.getMessage().contains("结果未确认"), error.getMessage());
            assertFalse(client.running());
            long pid = Long.parseLong(Files.readString(workspace.resolve("ready")));
            long until = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void responseTimeoutDoesNotRetryASideEffect() throws Exception {
        try (McpClient client = client("hang")) {
            McpException error =
                assertThrows(McpException.class, () -> client.call("write", Map.of(), Duration.ofMillis(300)));
            assertTrue(error.getMessage().contains("结果未确认"), error.getMessage());
            assertFalse(client.running());
            ready();
        }
    }

    @Test
    void interruptionStopsBlockedWriterAndPreservesInterruptStatus() throws Exception {
        try (McpClient client = client("block")) {
            ready();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<Boolean> interrupted = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    client.call("write", Map.of("text", "x".repeat(1024 * 1024)), Duration.ofSeconds(5));
                } catch (Throwable e) {
                    failure.set(e);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            worker.start();
            Thread.sleep(150);
            worker.interrupt();
            worker.join(1200);
            boolean stopped = !worker.isAlive();
            client.close();
            worker.join(4000);
            assertTrue(stopped, "中断不能等到管道写入完成");
            assertInstanceOf(McpException.class, failure.get());
            assertEquals(Boolean.TRUE, interrupted.get());
            assertFalse(client.running());
        }
    }

    @Test
    void queuedRequestHasItsOwnDeadlineAndDoesNotGetSentAfterItExpires() throws Exception {
        try (McpClient client = client("block")) {
            ready();
            AtomicReference<McpException> first = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    client.call("write", Map.of("text", "x".repeat(1024 * 1024)), Duration.ofSeconds(5));
                } catch (McpException e) {
                    first.set(e);
                }
            });
            worker.start();
            Thread.sleep(150);
            long start = System.nanoTime();
            McpException error =
                assertThrows(McpException.class, () -> client.call("queued", Map.of(), Duration.ofMillis(200)));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1500);
            assertFalse(error.outcomeUnknown(), "尚未写入的排队请求不应标为可能执行");
            worker.join(1200);
            assertFalse(worker.isAlive());
            assertTrue(first.get().outcomeUnknown());
        }
    }

    @Test
    void closeDoesNotWaitForBlockedPipeMonitor() throws Exception {
        McpClient client = client("block");
        ready();
        Thread worker = new Thread(() -> {
            try {
                client.call("write", Map.of("text", "x".repeat(1024 * 1024)), Duration.ofSeconds(5));
            } catch (McpException ignored) {
                // 关闭唤醒调用方。
            }
        });
        worker.start();
        Thread.sleep(150);
        long start = System.nanoTime();
        client.close();
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        worker.join(4000);
        assertTrue(elapsed < 1200, "关闭不得先等待 OutputStream.close 的阻塞锁：" + elapsed);
        assertFalse(worker.isAlive());
    }
}
