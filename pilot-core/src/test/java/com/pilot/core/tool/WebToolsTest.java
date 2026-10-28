package com.pilot.core.tool;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void searchReadsSearxngJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            assertNotNull(query);
            assertTrue(query.contains("format=json"));
            byte[] body = "{\"results\":[{\"title\":\"Example\",\"url\":\"https://example.com\",\"content\":\"A result\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var tool = new WebSearchTool(HttpClient.newHttpClient(), mapper,
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            String result = tool.execute(Map.of("query", "example"));
            assertTrue(result.contains("https://example.com"));
            assertTrue(result.contains("A result"));
            assertTrue(result.contains("Example"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchSaysWhenLocalEngineIsDown() {
        var tool = new WebSearchTool(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), mapper,
                java.net.URI.create("http://127.0.0.1:1"));
        assertTrue(tool.execute(Map.of("query", "pilot")).contains("本地搜索未启动"));
    }

    @Test
    void fetchRejectsLocalAndNonHttpUrls() {
        var tool = new WebFetchTool(HttpClient.newHttpClient());
        assertTrue(tool.execute(Map.of("url", "http://127.0.0.1/secret")).startsWith("ERROR:"));
        assertTrue(tool.execute(Map.of("url", "file:///etc/passwd")).startsWith("ERROR:"));
    }

    @Test
    void fetchDoesNotFollowRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1/secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            // 本机地址在发出请求前就拒绝，不会顺着 Location 再访问一次
            String result = new WebFetchTool(HttpClient.newHttpClient()).execute(
                    Map.of("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            assertTrue(result.startsWith("ERROR:"), result);
        } finally {
            server.stop(0);
        }
    }
}
