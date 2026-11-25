package com.pilot.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class McpHttpTransportTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void streamableHttpJsonAndSseResponsesWithSessionIdAndDeleteCleanup() throws Exception {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        AtomicReference<String> seenSessionOnCall = new AtomicReference<>();
        CountDownLatch deleted = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                if ("sess-42".equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
                    deleted.countDown();
                }
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode msg = MAPPER.readTree(reqBody);
            String method = msg.path("method").asString("");
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            long id = msg.path("id").asLong();
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().set("Mcp-Session-Id", "sess-42");
                writeJson(exchange, Map.of("jsonrpc", "2.0", "id", id, "result",
                    Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of("tools", Map.of()))));
                return;
            }
            if ("tools/list".equals(method)) {
                // 使用 text/event-stream 流式返回工具清单
                String sse = "event: message\ndata: "
                    + MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "result",
                        Map.of("tools", List.of(Map.of("name", "ping_remote", "description", "Remote ping",
                            "annotations", Map.of("readOnlyHint", true), "inputSchema", Map.of("type", "object"))))))
                    + "\n\n";
                byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
                return;
            }
            if ("tools/call".equals(method)) {
                seenSessionOnCall.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                String arg = msg.path("params").path("arguments").path("msg").asString("");
                writeJson(exchange, Map.of("jsonrpc", "2.0", "id", id, "result",
                    Map.of("content", List.of(Map.of("type", "text", "text", "pong:" + arg)))));
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            Files.writeString(workspace.resolve(".mcp.json"),
                MAPPER.writeValueAsString(Map.of("mcpServers", Map.of("remote",
                    Map.of("type", "http", "url", url, "headers", Map.of("Authorization", "Bearer top-secret"))))));

            try (McpManager manager = new McpManager(MAPPER, null, null, null)) {
                McpServerConfig cfg = manager.servers(workspace).get("remote");
                assertTrue(cfg.isRemote());
                assertTrue(cfg.usable());
                assertFalse(cfg.toString().contains("top-secret"), "toString 不得泄露请求头令牌");

                List<McpToolInfo> tools = manager.tools(workspace, "remote");
                assertEquals(1, tools.size());
                assertEquals("ping_remote", tools.getFirst().name());
                assertTrue(tools.getFirst().readOnlyHint());
                assertTrue(manager.running(workspace, "remote"));

                JsonNode result = manager.call(workspace, "remote", "ping_remote", Map.of("msg", "hello"));
                assertEquals("pong:hello", result.path("content").get(0).path("text").asString());
                assertEquals("Bearer top-secret", seenAuth.get());
                assertEquals("sess-42", seenSessionOnCall.get());
            }
            assertTrue(deleted.await(3, TimeUnit.SECONDS), "关闭时应发送 DELETE 清理远端 MCP 会话");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void legacySseAndAutomatic405FallbackWorkEndToEnd() throws Exception {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        AtomicBoolean sseOpen = new AtomicBoolean(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/sse", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                // 模拟仅支持旧 SSE 的服务端对 POST 返回 405，触发自动回退
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("event: endpoint\ndata: /messages\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                while (sseOpen.get()) {
                    String next = events.poll(200, TimeUnit.MILLISECONDS);
                    if (next != null) {
                        out.write(("event: message\ndata: " + next + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.createContext("/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode msg = MAPPER.readTree(body);
            String method = msg.path("method").asString("");
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            if ("notifications/initialized".equals(method)) {
                return;
            }
            long id = msg.path("id").asLong();
            if ("initialize".equals(method)) {
                events.offer(MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "result",
                    Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of()))));
            } else if ("tools/list".equals(method)) {
                events.offer(MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "result",
                    Map.of("tools", List.of(Map.of("name", "legacy_echo", "description", "SSE echo"))))));
            } else if ("tools/call".equals(method)) {
                events.offer(MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "result",
                    Map.of("content", List.of(Map.of("type", "text", "text", "sse-ok"))))));
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/sse";
            // 未显式指定 sse（默认 http），应由 405 自动回退到旧 SSE
            Files.writeString(workspace.resolve(".mcp.json"),
                MAPPER.writeValueAsString(Map.of("mcpServers", Map.of("legacy", Map.of("url", url)))));
            try (McpManager manager = new McpManager(MAPPER, null, null, null)) {
                List<McpToolInfo> tools = manager.tools(workspace, "legacy");
                assertEquals(1, tools.size());
                assertEquals("legacy_echo", tools.getFirst().name());
                JsonNode res = manager.call(workspace, "legacy", "legacy_echo", Map.of());
                assertEquals("sse-ok", res.path("content").get(0).path("text").asString());
            }
        } finally {
            sseOpen.set(false);
            server.stop(0);
        }
    }

    @Test
    void rejectsUnsafeUrlsRedirectsAndTracksTrustHashDrift() throws Exception {
        assertTrue(McpServerConfig.validateRemoteUrl("file:///etc/passwd").contains("http/https"));
        assertTrue(McpServerConfig.validateRemoteUrl("https://user:pass@example.com/mcp").contains("账号密码"));

        McpServerConfig a = McpServerConfig.remote("r", "http", "https://a.test/mcp", Map.of("X-Key", "1"), null);
        McpServerConfig b = McpServerConfig.remote("r", "http", "https://a.test/mcp", Map.of("X-Key", "2"), null);
        McpServerConfig c = McpServerConfig.remote("r", "sse", "https://a.test/mcp", Map.of("X-Key", "1"), null);
        assertNotEquals(McpTrustStore.hash(a), McpTrustStore.hash(b), "请求头变化必须改变配置指纹");
        assertNotEquals(McpTrustStore.hash(a), McpTrustStore.hash(c), "传输方式变化必须改变配置指纹");

        HttpServer redirectServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirectServer.createContext("/mcp", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://evil.example/steal");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        redirectServer.start();
        try {
            String url = "http://127.0.0.1:" + redirectServer.getAddress().getPort() + "/mcp";
            Files.writeString(workspace.resolve(".mcp.json"),
                MAPPER.writeValueAsString(Map.of("mcpServers", Map.of("redir", Map.of("url", url)))));
            try (McpManager manager = new McpManager(MAPPER, null, null, null)) {
                McpException ex = assertThrows(McpException.class, () -> manager.tools(workspace, "redir"));
                assertTrue(ex.getMessage().contains("重定向"), ex.getMessage());
            }
        } finally {
            redirectServer.stop(0);
        }
    }

    private static void writeJson(HttpExchange exchange, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
