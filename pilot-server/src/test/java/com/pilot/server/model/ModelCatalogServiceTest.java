package com.pilot.server.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

class ModelCatalogServiceTest {

    @Test
    void scansLoopbackServicesConcurrentlySkipsSlowAndOfflineAndNeverSendsAuthHeader() throws Exception {
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        HttpServer online = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        online.createContext("/v1/models", exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes =
                "{\"data\":[{\"id\":\"qwen2.5-coder:14b\"},{\"id\":\"llama3.2\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        online.start();

        CountDownLatch slowRelease = new CountDownLatch(1);
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/v1/models", exchange -> {
            try {
                slowRelease.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        slow.start();

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        ModelCatalogService service = new ModelCatalogService(null, new ObjectMapper(),
            HttpClient.newBuilder().connectTimeout(Duration.ofMillis(400)).build());
        long started = System.nanoTime();
        try {
            List<ModelCatalogService.LocalModelService> found = service.scanLocalServices(List.of(
                new ModelCatalogService.LocalService("Ollama",
                    "http://127.0.0.1:" + online.getAddress().getPort() + "/v1"),
                new ModelCatalogService.LocalService("Slow", "http://127.0.0.1:" + slow.getAddress().getPort() + "/v1"),
                new ModelCatalogService.LocalService("Offline", "http://127.0.0.1:" + closedPort + "/v1")),
                Duration.ofMillis(450));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertEquals(1, found.size());
            assertEquals("Ollama", found.getFirst().name());
            assertEquals(List.of("llama3.2", "qwen2.5-coder:14b"), found.getFirst().models());
            assertNull(receivedAuth.get(), "本机扫描不得发送 Authorization 头");
            assertTrue(elapsedMs < 2000, "并发短超时扫描不应被慢端口阻塞，实际耗时 " + elapsedMs + " ms");
        } finally {
            slowRelease.countDown();
            online.stop(0);
            slow.stop(0);
        }
    }
}
