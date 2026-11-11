package com.pilot.server.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ScheduleRestartTest {
    @TempDir
    Path root;
    final List<String> keys = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final AtomicInteger status = new AtomicInteger(204);
    HttpServer receiver;
    String target;

    @BeforeEach
    void setup() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        receiver.start();
        target = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void close() {
        receiver.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"occurrence", "dispatch", "completed", "claim", "receipt"})
    void abruptExitRecoversOneTaskAndRetriesOnlyNotification(String phase) throws Exception {
        run("seed", "2026-11-01T05:00:00Z", "ONCE", 0);
        run(phase, "2026-11-01T06:10:00Z", "ONCE", 23);
        run("recover", "2026-11-01T06:11:00Z", "ONCE", 0);
        var report = report();
        assertCompletedOnce(report);
        assertEquals(1, report.path("deliveries").size());
        assertEquals("DELIVERED", report.path("deliveries").get(0).path("state").asText());
        int deliveries = "receipt".equals(phase) ? 2 : 1;
        assertEquals(deliveries, keys.size());
        assertEquals(1, keys.stream().distinct().count());
        var mapper = JsonMapper.builder().build();
        assertEquals(1, bodies.stream().map(mapper::readTree).distinct().count(), "回执丢失后仍使用同一通知内容和幂等键");
        run("recover", "2026-11-01T06:30:00Z", "ONCE", 0);
        assertCompletedOnce(report());
        assertEquals(deliveries, keys.size(), "重复启动及夏令时回拨不能再次执行或投递");
    }

    @Test
    void uncertainDispatchDoesNotBlindlyRunAgain() throws Exception {
        run("seed", "2026-11-01T05:00:00Z", "ONCE", 0);
        run("unbound", "2026-11-01T06:10:00Z", "ONCE", 23);
        run("recover", "2026-11-01T06:11:00Z", "ONCE", 0);
        var report = report();
        assertEquals(1, report.path("starts").asInt());
        assertEquals(0, report.path("executions").asInt());
        assertEquals("NEEDS_ATTENTION", report.path("jobs").get(0).path("state").asText());
        assertEquals(1, keys.size());
    }

    @Test
    void offlineSkipCreatesNoCatchupTaskOrNotification() throws Exception {
        run("seed", "2026-11-01T05:00:00Z", "SKIP", 0);
        run("recover", "2026-11-01T06:10:00Z", "SKIP", 0);
        run("recover", "2026-11-01T06:30:00Z", "SKIP", 0);
        assertEquals(0, report().path("jobs").size());
        assertEquals(0, keys.size());
        run("recover", "2026-11-02T06:30:00Z", "SKIP", 0);
        assertCompletedOnce(report());
        assertEquals(1, keys.size());
    }

    @Test
    void failedHttpDeliveryRetriesAfterRestartWithoutExecutingTaskAgain() throws Exception {
        run("seed", "2026-11-01T05:00:00Z", "ONCE", 0);
        status.set(503);
        run("recover", "2026-11-01T06:10:00Z", "ONCE", 0);
        assertCompletedOnce(report());
        assertEquals("RETRY", report().path("deliveries").get(0).path("state").asText());
        status.set(204);
        run("recover", "2026-11-01T06:11:00Z", "ONCE", 0);
        assertCompletedOnce(report());
        assertEquals("DELIVERED", report().path("deliveries").get(0).path("state").asText());
        assertEquals(2, keys.size());
        assertEquals(keys.get(0), keys.get(1));
    }

    private void assertCompletedOnce(JsonNode report) {
        assertEquals(1, report.path("starts").asInt());
        assertEquals(1, report.path("executions").asInt());
        assertEquals(1, report.path("jobs").size());
        assertEquals("COMPLETED", report.path("jobs").get(0).path("state").asText());
        assertEquals(1, report.path("series").get(0).path("occurrences").asInt());
    }

    private JsonNode report() throws Exception {
        return JsonMapper.builder().build().readTree(Files.readString(root.resolve("report.json")));
    }

    private void run(String phase, String at, String misfire, int exit) throws Exception {
        Path log = root.resolve("process-" + phase + "-" + System.nanoTime() + ".log");
        String java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        // 清单 classpath 用 URI 转义空格和非 ASCII 字符，并避免 Windows 命令行长度上限。
        Path launcher = root.resolve("测试 classpath.jar");
        if (!Files.exists(launcher)) {
            var manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            String classpath = String.join(" ",
                Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                    .filter(value -> !value.isEmpty()).map(value -> Path.of(value).toUri().toASCIIString()).toList());
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classpath);
            try (var jar = new JarOutputStream(Files.newOutputStream(launcher), manifest)) {
                jar.finish();
            }
        }
        var process = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "-cp", launcher.toString(),
            ScheduleCrashMain.class.getName(), root.toString(), phase, at, target, misfire).redirectErrorStream(true)
            .redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "测试子进程超时：" + log);
            assertEquals(exit, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }
}
