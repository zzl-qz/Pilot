package com.pilot.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pilot.core.event.AssistantDelta;
import com.pilot.core.session.SessionRepository;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionStatus;
import com.pilot.server.stream.AsyncEventStream;

import tools.jackson.databind.ObjectMapper;

/** 真实 TCP 与 HTTP 的可选负载测量；内存仓储隔离传输成本，SQLite 另有独立测量。 */
@EnabledIfSystemProperty(named = "pilot.events.benchmark", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"pilot.store=memory",
    "pilot.events.session-connections=64", "logging.level.com.pilot.server.stream=DEBUG"})
class SessionEventLoadTest {
    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    int port;
    @Autowired
    SessionService sessions;
    @Autowired
    SessionRepository repository;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("pilot.data.dir",
            () -> Path.of(System.getProperty("java.io.tmpdir"), "pilot-load-" + UUID.randomUUID()).toString());
    }

    record Result(double firstMs, double replayMs, double caughtUpMs, long frames, long bytes) {
    }

    @Test
    void history100000WithOneTenAndFiftyReaders() throws Exception {
        var reports = new ArrayList<Map<String, Object>>();
        for (int clients : new int[] {1, 10, 50}) {
            String id = sessions.createSession("性能测量").id();
            for (int i = 0; i < 100000; i++) {
                repository.append(id, "r", Instant.EPOCH, new AssistantDelta("x".repeat(64)), SessionStatus.COMPLETED);
            }
            var connected = new CountDownLatch(clients);
            var futures = new ArrayList<Future<Result>>();
            long began = System.nanoTime();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < clients; i++) {
                    futures.add(executor.submit(() -> read(id, connected, began)));
                }
                assertTrue(connected.await(20, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (sessions.subscriberCount() < clients && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                assertEquals(clients, sessions.subscriberCount());
                double[] publishes = new double[100];
                for (int i = 0; i < 100; i++) {
                    long start = System.nanoTime();
                    sessions.publish(id, "r", new AssistantDelta("live"));
                    publishes[i] = (System.nanoTime() - start) / 1e6;
                }
                var results = new ArrayList<Result>();
                for (var future : futures) {
                    results.add(future.get(120, TimeUnit.SECONDS));
                }
                var report = new LinkedHashMap<String, Object>();
                report.put("clients", clients);
                report.put("history", 100000);
                report.put("live", 100);
                report.put("payloadChars", 64);
                report.put("publishMs", percentiles(publishes));
                report.put("firstMs", percentiles(results.stream().mapToDouble(Result::firstMs).toArray()));
                report.put("replayMs", percentiles(results.stream().mapToDouble(Result::replayMs).toArray()));
                report.put("caughtUpMs", percentiles(results.stream().mapToDouble(Result::caughtUpMs).toArray()));
                report.put("receivedFrames", results.stream().mapToLong(Result::frames).sum());
                report.put("receivedBytes", results.stream().mapToLong(Result::bytes).sum());
                report.put("core", sessions.streamMetrics());
                report.put("transport", AsyncEventStream.metrics());
                report.put("heapUsed", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
                reports.add(report);
                System.out.println("HTTP_EVENT_BENCHMARK " + new ObjectMapper().writeValueAsString(report));
                assertTrue(Arrays.stream(publishes).max().orElseThrow() < 1000, "发布不应被客户端长时间阻塞");
                assertTrue(results.stream().mapToDouble(Result::caughtUpMs).max().orElseThrow() < 120000);
            } finally {
                sessions.delete(id);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((sessions.streamMetrics().workers() != 0 || AsyncEventStream.metrics().connections() != 0)
                && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(0, sessions.streamMetrics().queuedBytes());
            assertEquals(0, AsyncEventStream.metrics().queuedBytes());
            System.gc();
            reports.getLast().put("heapAfterCleanupGc",
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        }
        Path directory = Path.of("target/event-benchmarks");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("http.json"), new ObjectMapper().writeValueAsString(reports));
    }

    private static Map<String, Double> percentiles(double[] samples) {
        Arrays.sort(samples);
        var result = new LinkedHashMap<String, Double>();
        for (int percentile : new int[] {50, 95, 99}) {
            result.put("p" + percentile,
                samples[Math.min(samples.length - 1, (int)Math.ceil(samples.length * percentile / 100.0) - 1)]);
        }
        return result;
    }

    private Result read(String id, CountDownLatch connected, long began) throws Exception {
        var connection = (HttpURLConnection)URI
            .create("http://127.0.0.1:" + port + "/api/sessions/" + id + "/events?after=0&protocol=2").toURL()
            .openConnection();
        connection.setReadTimeout(125000);
        connection.setConnectTimeout(10000);
        try (var reader =
            new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            long count = 0;
            long bytes = 0;

            double first = 0;
            double replay = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (line.equals("event:stream.ready")) {
                    connected.countDown();
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                int at = line.indexOf("\"sequence\":");
                if (at < 0) {
                    continue;
                }
                int start = at + 11;
                int end = start;

                while (end < line.length() && Character.isDigit(line.charAt(end))) {
                    end++;
                }
                long sequence = Long.parseLong(line.substring(start, end));
                assertEquals(++count, sequence);
                if (sequence == 1) {
                    first = (System.nanoTime() - began) / 1e6;
                }
                if (sequence == 100000) {
                    replay = (System.nanoTime() - began) / 1e6;
                }
                if (sequence == 100100) {
                    return new Result(first, replay, (System.nanoTime() - began) / 1e6, count, bytes);
                }
            }
            throw new AssertionError("回放提前断开，已收到 " + count + "，core=" + sessions.streamMetrics() + "，transport="
                + AsyncEventStream.metrics());
        } finally {
            connection.disconnect();
        }
    }
}
