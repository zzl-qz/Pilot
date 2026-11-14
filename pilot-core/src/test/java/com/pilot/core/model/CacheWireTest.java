package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pilot.core.message.Message;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

class CacheWireTest {
    @Test
    void rejectedMarkersRetryOnceAndKeepFutureRequestsCompatible() throws Exception {
        var bodies = new ArrayList<String>();
        var reports = new ArrayList<RequestReport>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(request);
            boolean rejected = request.contains("cache_control");
            String response = rejected ? "{\"error\":{\"message\":\"unsupported cache_control\"}}"
                : "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":10,\"prompt_cache_hit_tokens\":700,\"cache_creation_input_tokens\":0}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(rejected ? 400 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var raw =
                new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new JsonMapper(), endpoint, "test", "alias")
                    .withProfile(ModelProfile.resolve(endpoint, "alias", "anthropic-compatible", "explicit", "", null,
                        null, "short", null));
            var measured = new MeteredModelClient(raw, "session", "main", List.of(), reports::add);
            var messages = List.of(Message.system("stable"), Message.user("hello"));
            assertEquals("ok", measured.chat(messages, List.of()).content());
            assertEquals("ok", measured.chat(messages, List.of()).content());
            assertEquals(3, bodies.size());
            assertTrue(bodies.getFirst().contains("cache_control"));
            assertFalse(bodies.get(1).contains("cache_control"));
            assertEquals(bodies.get(1), bodies.get(2));
            assertEquals(List.of("failed", "completed", "completed"),
                reports.stream().map(RequestReport::status).toList());
            assertFalse(reports.getFirst().usage().exact());
            assertNull(reports.getFirst().cost().total());
            assertEquals(700, reports.get(1).usage().cachedInputTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downgradeNeverReplaysAuthenticationOrGeneralFailures() {
        var raw = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new JsonMapper(),
            URI.create("https://gateway.test"), "test", "alias");
        assertFalse(raw.disableRejectedCache(new ModelClientException("HTTP 401 unsupported cache_control")));
        assertFalse(raw.disableRejectedCache(new ModelClientException("HTTP 400 invalid messages")));
        assertFalse(raw.disableRejectedCache(new IllegalStateException("HTTP 400 unsupported cache_control")));
        assertTrue(raw.disableRejectedCache(new ModelClientException("HTTP 400 unsupported cache_control")));
        assertFalse(raw.disableRejectedCache(new ModelClientException("HTTP 400 unsupported cache_control")));
    }

    @Test
    void nestedSchemasAndToolArgumentsHaveStableWireOrder() {
        var raw = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new JsonMapper(),
            URI.create("https://api.openai.com"), "test", "gpt-5.1");
        var schemaA = new LinkedHashMap<String, Object>();
        schemaA.put("z", Map.of("y", 1, "x", 2));
        schemaA.put("a", List.of("b", "a"));
        var schemaB = Map.<String, Object>of("a", List.of("b", "a"), "z", Map.of("x", 2, "y", 1));
        var json = new JsonMapper();
        var a = json.writeValueAsString(CanonicalJson.ordered(raw.requestBody(List.of(Message.user("hello")),
            List.of(new ToolDefinition("tool", "desc", schemaA)), true)));
        var b = json.writeValueAsString(CanonicalJson.ordered(raw.requestBody(List.of(Message.user("hello")),
            List.of(new ToolDefinition("tool", "desc", schemaB)), true)));
        assertEquals(a, b);
        assertTrue(a.contains("include_usage"));
        String first = ModelRequestScope.within(null, "session-a", false,
            () -> raw.requestBody(List.of(), List.of(), true).get("prompt_cache_key").toString());
        String again = ModelRequestScope.within(null, "session-a", false,
            () -> raw.requestBody(List.of(), List.of(), true).get("prompt_cache_key").toString());
        String other = ModelRequestScope.within(null, "session-b", false,
            () -> raw.requestBody(List.of(), List.of(), true).get("prompt_cache_key").toString());
        assertEquals(first, again);
        assertNotEquals(first, other);
        assertFalse(first.contains("session-a"));
    }
}
