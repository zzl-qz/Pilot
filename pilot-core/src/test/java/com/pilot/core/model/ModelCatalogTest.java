package com.pilot.core.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模型清单探测：端点写法换算、鉴权头有无、错误 fail-visible。 */
class ModelCatalogTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void resolvesModelsUriFromEveryCommonEndpointStyle() {
        assertEquals(URI.create("https://gateway.test/v1/models"),
                ModelCatalog.resolveModelsUri(URI.create("https://gateway.test")));
        assertEquals(URI.create("https://gateway.test/v1/models"),
                ModelCatalog.resolveModelsUri(URI.create("https://gateway.test/")));
        assertEquals(URI.create("https://gateway.test/v1/models"),
                ModelCatalog.resolveModelsUri(URI.create("https://gateway.test/v1")));
        assertEquals(URI.create("https://gateway.test/v1/models"),
                ModelCatalog.resolveModelsUri(URI.create("https://gateway.test/v1/")));
        // 项目默认端点就是完整的 chat/completions 地址，必须能换算回 /models
        assertEquals(URI.create("https://gateway.test/v1/models"),
                ModelCatalog.resolveModelsUri(URI.create("https://gateway.test/v1/chat/completions")));
        // 自定义路径：原样追加，猜错会 fail-visible
        assertEquals(URI.create("https://gateway.test/custom/models"),
                ModelCatalog.resolveModelsUri(URI.create("https://gateway.test/custom")));
        // query 保留
        assertEquals(URI.create("https://gateway.test/v1/models?api-version=2024"),
                ModelCatalog.resolveModelsUri(URI.create("https://gateway.test/v1/chat/completions?api-version=2024")));
    }

    /** 与 chat 客户端同一套约定：带前缀的 /v1 基址也要补 /models。 */
    @Test
    void resolvesModelsUriForPrefixedV1Endpoints() {
        assertEquals(URI.create("https://gateway.test/api/llm/v1/models"),
                ModelCatalog.resolveModelsUri(URI.create("https://gateway.test/api/llm/v1")));
        assertEquals(URI.create("https://gateway.test/api/llm/v1/models"),
                ModelCatalog.resolveModelsUri(URI.create("https://gateway.test/api/llm/v1/")));
        assertEquals(URI.create("https://gateway.test/v11/models"),
                ModelCatalog.resolveModelsUri(URI.create("https://gateway.test/v11")));
    }

    @Test
    void fetchesSortedAndDeduplicatedModelIds() throws IOException {
        var server = stubServer(200, """
                {"object":"list","data":[
                  {"id":"zeta-2"},
                  {"id":"Alpha-1"},
                  {"id":"alpha-1"},
                  {"id":"beta"},
                  {"object":"model","owned_by":"system"}]}
                """);
        server.start();
        try {
            List<String> models = ModelCatalog.fetch(httpClient, json, base(server), "sk-test");

            assertEquals(List.of("Alpha-1", "beta", "zeta-2"), models);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsBearerTokenOnlyWhenKeyPresent() throws IOException {
        var captured = new ConcurrentHashMap<String, String>();
        var server = headerCaptureServer(captured, "{\"data\":[{\"id\":\"m\"}]}");
        server.start();
        try {
            ModelCatalog.fetch(httpClient, json, base(server), " sk-key ");
            assertEquals("Bearer sk-key", captured.get("authorization"));

            captured.clear();
            ModelCatalog.fetch(httpClient, json, base(server), "");
            assertNull(captured.get("authorization"), "空密钥不应带 Authorization 头，本机端点允许匿名");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsNonStandardModelsArrayAsFallback() throws IOException {
        var server = stubServer(200, "{\"models\":[{\"id\":\"qwen-max\"}]}");
        server.start();
        try {
            assertEquals(List.of("qwen-max"),
                    ModelCatalog.fetch(httpClient, json, base(server), "k"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsEmptyListWhenEndpointHasNoModels() throws IOException {
        var server = stubServer(200, "{\"object\":\"list\",\"data\":[]}");
        server.start();
        try {
            assertEquals(List.of(), ModelCatalog.fetch(httpClient, json, base(server), "k"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void surfacesHttpErrorsWithResolvedUri() throws IOException {
        var server = stubServer(404, "{\"error\":\"no such route\"}");
        server.start();
        try {
            var exception = assertThrows(ModelClientException.class,
                    () -> ModelCatalog.fetch(httpClient, json, base(server), "k"));
            assertTrue(exception.getMessage().contains("404"), exception.getMessage());
            assertTrue(exception.getMessage().contains("/v1/models"), exception.getMessage());
            assertTrue(exception.getMessage().contains("no such route"), exception.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsBodiesWithoutModelList() throws IOException {
        var server = stubServer(200, "{\"error\":{\"message\":\"bad key\"}}");
        server.start();
        try {
            var exception = assertThrows(ModelClientException.class,
                    () -> ModelCatalog.fetch(httpClient, json, base(server), "k"));
            assertTrue(exception.getMessage().contains("缺少 data 数组"), exception.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonJsonBodies() throws IOException {
        var server = stubServer(200, "<html>login page</html>");
        server.start();
        try {
            var exception = assertThrows(ModelClientException.class,
                    () -> ModelCatalog.fetch(httpClient, json, base(server), "k"));
            assertTrue(exception.getMessage().contains("不是合法 JSON"), exception.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsUnreachableEndpoints() {
        var exception = assertThrows(ModelClientException.class,
                () -> ModelCatalog.fetch(httpClient, json, URI.create("http://127.0.0.1:1/v1"), "sk-never-leak-123"));
        assertTrue(exception.getMessage().contains("模型列表请求失败"), exception.getMessage());
        assertFalse(exception.getMessage().contains("sk-never-leak-123"), "报错里不能带密钥");
    }

    private static URI base(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    private static HttpServer stubServer(int status, String responseBody) throws IOException {
        return respond(exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    /** 记录请求头后再回固定响应，用于核对鉴权头的有无。 */
    private static HttpServer headerCaptureServer(Map<String, String> captured, String responseBody)
            throws IOException {
        return respond(exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                captured.put("authorization", authorization);
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private static HttpServer respond(ThrowingHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return server;
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
