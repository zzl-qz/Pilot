package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.pilot.core.WindowsJdkLoopbackSensitive;
import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.pilot.core.model.diagnostics.ModelRequestScope;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@WindowsJdkLoopbackSensitive
class AnthropicMessagesModelClientTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void declaresNativeSearchSeparatelyFromPilotFetchAndKeepsCitationSources() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server(requestBody,
            """
                {"stop_reason":"end_turn","content":[{"type":"text","text":"Summary.","citations":[{"type":"web_search_result_location","url":"https://example.com/article","title":"Example article"}]}],"usage":{"input_tokens":30,"output_tokens":8}}
                """);
        server.start();
        try {
            AnthropicMessagesModelClient client = client(server, Set.of(NativeCapability.WEB_SEARCH));
            ModelResponse response = client.chat(List.of(Message.system("rules"), Message.user("research")),
                List.of(new ToolDefinition("web_fetch", "Fetch safely", Map.of("type", "object"))));

            JsonNode body = mapper.readTree(requestBody.get());
            assertEquals(ModelCapacity.configured().outputTokens(), body.path("max_tokens").asInt());
            assertEquals("rules", body.path("system").asString());
            assertEquals("web_search_20250305", body.path("tools").get(1).path("type").asString());
            assertFalse(body.path("tools").toString().contains("web_fetch_20250910"));
            assertTrue(body.path("tools").toString().contains("Fetch safely"));
            assertTrue(response.content().contains("https://example.com/article"));
            assertEquals(new ModelUsage(30, 8, 0, true), response.usage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsPilotToolUseAndToolResultsToMessagesProtocol() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server(requestBody,
            """
                {"stop_reason":"tool_use","content":[{"type":"tool_use","id":"use-1","name":"read_file","input":{"path":"pom.xml"}}]}
                """);
        server.start();
        try {
            ModelResponse response = client(server, Set.of()).chat(
                List.of(Message.user("read"),
                    Message.assistant("", List.of(new ToolCall("use-0", "list_dir", Map.of("path", ".")))),
                    Message.tool("use-0", "pom.xml")),
                List.of(new ToolDefinition("read_file", "Read", Map.of("type", "object"))));

            JsonNode body = mapper.readTree(requestBody.get());
            assertEquals(ModelCapacity.configured().outputTokens(), body.path("max_tokens").asInt());
            assertEquals("tool_use", body.path("messages").get(1).path("content").get(0).path("type").asString());
            assertEquals("tool_result", body.path("messages").get(2).path("content").get(0).path("type").asString());
            assertEquals("pom.xml", response.toolCalls().getFirst().args().get("path"));
            assertEquals(ModelResponse.FinishReason.TOOL_CALLS, response.finishReason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void replaysThinkingOnlyInsideTheCurrentUserTurn() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server(requestBody,
            """
                {"stop_reason":"tool_use","content":[{"type":"thinking","thinking":"先看文件","signature":"sig-1"},{"type":"tool_use","id":"use-1","name":"read_file","input":{"path":"A.java"}}]}
                """);
        server.start();
        try {
            String stored = "先看文件" + AnthropicMessagesModelClient.THINKING_SPLIT + "sig-1";
            Message previous =
                Message.assistant("", List.of(new ToolCall("old", "read_file", Map.of("path", "B.java"))), stored);
            ModelResponse response =
                client(server, Set.of()).chat(List.of(Message.user("上一问"), previous, Message.tool("old", "内容"),
                    Message.user("这一问"), Message.assistant("",
                        List.of(new ToolCall("use-0", "read_file", Map.of("path", "A.java"))), stored),
                    Message.tool("use-0", "新内容")), List.of());

            JsonNode messages = mapper.readTree(requestBody.get()).path("messages");
            assertFalse(messages.get(1).toString().contains("sig-1"));
            assertEquals("thinking", messages.get(4).path("content").get(0).path("type").asString());
            assertEquals("sig-1", messages.get(4).path("content").get(0).path("signature").asString());
            assertTrue(response.reasoningContent().contains("sig-1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvesAnthropicMessagesEndpoint() {
        assertEquals(URI.create("https://api.anthropic.com/v1/messages"),
            AnthropicMessagesModelClient.resolveEndpoint(URI.create("https://api.anthropic.com/v1")));
    }

    @Test
    void preservesNonVersionedMessagesPathAndQuery() {
        assertEquals(URI.create("https://relay.test/api/messages?tenant=a"),
            AnthropicMessagesModelClient.resolveEndpoint(URI.create("https://relay.test/api/messages?tenant=a")));
    }

    @Test
    void marksSystemToolsAndTailMessageWhenExplicitCachingEnabled() {
        ModelProfile explicit = ModelProfile.resolve(URI.create("https://api.anthropic.com/v1/messages"),
            "claude-sonnet-4-6", "auto", "explicit", "5m", null, null, "short", null);
        AnthropicMessagesModelClient client = new AnthropicMessagesModelClient(HttpClient.newHttpClient(), mapper,
            URI.create("https://api.anthropic.com/v1/messages"), "key", "claude-sonnet-4-6", Duration.ofSeconds(10),
            explicit, Set.of(NativeCapability.WEB_SEARCH));

        Map<String,
            Object> body =
                client
                    .requestBody(
                        List.of(Message.system("稳定指令\n\n[Runtime task context]\n动态状态"), Message.user("读 pom"),
                            Message.assistant("",
                                List.of(new ToolCall("use-0", "read_file", Map.of("path", "pom.xml")))),
                            Message.tool("use-0", "<project/>")),
                        List.of(new ToolDefinition("read_file", "Read", Map.of("type", "object")),
                            new ToolDefinition("list_dir", "List", Map.of("type", "object"))));

        JsonNode root = mapper.valueToTree(body);
        JsonNode system = root.path("system");
        assertTrue(system.isArray());
        assertEquals(2, system.size());
        assertEquals("ephemeral", system.get(0).path("cache_control").path("type").asString());
        assertEquals("5m", system.get(0).path("cache_control").path("ttl").asString());
        assertTrue(system.get(1).path("cache_control").isMissingNode(), "动态上下文段不打断点");

        JsonNode tools = root.path("tools");
        assertEquals(3, tools.size());
        assertTrue(tools.get(0).path("cache_control").isMissingNode());
        assertEquals("ephemeral", tools.get(1).path("cache_control").path("type").asString(), "只标最后一个自定义工具");
        assertTrue(tools.get(2).path("cache_control").isMissingNode(), "服务端 native tool 不打缓存断点");

        JsonNode messages = root.path("messages");
        assertTrue(messages.get(0).path("content").isTextual());
        JsonNode lastResult = messages.get(2).path("content").get(0);
        assertEquals("tool_result", lastResult.path("type").asString());
        assertEquals("ephemeral", lastResult.path("cache_control").path("type").asString());
    }

    @Test
    void omitsCacheMarkersWhenCacheModeIsNone() {
        ModelProfile disabled = ModelProfile.resolve(URI.create("https://api.anthropic.com/v1/messages"),
            "claude-sonnet-4-6", "auto", "none", "", null, null, "short", null);
        AnthropicMessagesModelClient client = new AnthropicMessagesModelClient(HttpClient.newHttpClient(), mapper,
            URI.create("https://api.anthropic.com/v1/messages"), "key", "claude-sonnet-4-6", Duration.ofSeconds(10),
            disabled, Set.of());

        Map<String, Object> body = client.requestBody(List.of(Message.system("rules"), Message.user("hi")),
            List.of(new ToolDefinition("read_file", "Read", Map.of("type", "object"))));
        JsonNode root = mapper.valueToTree(body);
        assertEquals("rules", root.path("system").asString());
        assertFalse(root.toString().contains("cache_control"));
    }

    @Test
    void downgradesAndRetriesOnceWhenGatewayRejectsCacheControl() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(request);
            boolean rejected = request.contains("cache_control");
            String payload = rejected ? "{\"error\":{\"message\":\"Extra inputs are not permitted: cache_control\"}}"
                : "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":50,\"output_tokens\":5}}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(rejected ? 400 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages");
            ModelProfile explicit = ModelProfile.resolve(URI.create("https://api.anthropic.com/v1/messages"),
                "claude-sonnet-4-6", "anthropic", "explicit", "5m", null, null, "short", null);
            AnthropicMessagesModelClient raw = new AnthropicMessagesModelClient(HttpClient.newHttpClient(), mapper,
                endpoint, "key", "claude-sonnet-4-6", Duration.ofSeconds(10), explicit, Set.of());
            MeteredModelClient metered = new MeteredModelClient(raw, "ses", "main", List.of(), report -> {
            });

            ModelResponse response = metered.chat(List.of(Message.system("rules"), Message.user("hello")), List.of());
            assertEquals("ok", response.content());
            assertEquals(2, bodies.size());
            assertTrue(bodies.get(0).contains("cache_control"));
            assertFalse(bodies.get(1).contains("cache_control"));
            assertEquals(ModelProfile.CACHE_NONE, raw.profile().cacheMode());
            assertFalse(raw.disableRejectedCache(new ModelClientException("HTTP 401 unsupported cache_control")));
            assertFalse(raw.disableRejectedCache(new ModelClientException("HTTP 400 invalid messages")));
            assertFalse(raw.disableRejectedCache(new ModelClientException("HTTP 400 unsupported cache_control")),
                "已降级后不再重复返回 true");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsConversationCacheMarkerAheadOfEphemeralTailGuidance() {
        ModelProfile explicit = ModelProfile.resolve(URI.create("https://api.anthropic.com/v1/messages"),
            "claude-sonnet-4-6", "anthropic", "explicit", "", null, null, "short", null);
        AnthropicMessagesModelClient client = new AnthropicMessagesModelClient(HttpClient.newHttpClient(), mapper,
            URI.create("https://api.anthropic.com/v1/messages"), "key", "claude-sonnet-4-6", Duration.ofSeconds(10),
            explicit, Set.of());

        Map<String, Object> body = client.requestBody(
            List.of(Message.system("base"), Message.user("hello"), Message.system("临时引导：只读检查")), List.of());
        JsonNode root = mapper.valueToTree(body);
        JsonNode messages = root.path("messages");
        assertEquals(1, messages.size());
        JsonNode blocks = messages.get(0).path("content");
        assertEquals(2, blocks.size());
        assertEquals("hello", blocks.get(0).path("text").asString());
        assertEquals("ephemeral", blocks.get(0).path("cache_control").path("type").asString(), "缓存断点必须留在稳定的真实对话块上");
        assertEquals("临时引导：只读检查", blocks.get(1).path("text").asString());
        assertFalse(blocks.get(1).has("cache_control"), "瞬态尾部引导不能携带 cache_control");
    }

    @Test
    void streamsThinkingTextToolUseCitationsAndMergedCacheUsageOverSse() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = sseServer(requestBody, sampleAnthropicSseStream());
        server.start();
        try {
            AnthropicMessagesModelClient client = client(server, Set.of());
            StringBuilder liveText = new StringBuilder();
            StringBuilder liveReasoning = new StringBuilder();
            ModelResponse response = client.chat(List.of(Message.user("检查源码")),
                List.of(new ToolDefinition("read_file", "Read", Map.of("type", "object"))), liveText::append,
                liveReasoning::append);

            JsonNode request = mapper.readTree(requestBody.get());
            assertTrue(request.path("stream").asBoolean());
            assertEquals("先查定义", liveReasoning.toString());
            assertTrue(liveText.toString().startsWith("正在读取"));
            assertTrue(liveText.toString().contains("https://docs.example/a"));
            assertEquals(liveText.toString(), response.content());
            assertEquals("先查定义" + AnthropicMessagesModelClient.THINKING_SPLIT + "sig-sse-1",
                response.reasoningContent());
            assertEquals(ModelResponse.FinishReason.TOOL_CALLS, response.finishReason());
            assertEquals(1, response.toolCalls().size());
            assertEquals("src/App.java", response.toolCalls().getFirst().args().get("path"));
            assertEquals(100L, response.usage().inputTokens());
            assertEquals(70L, response.usage().cachedInputTokens());
            assertEquals(20L, response.usage().cacheWriteTokens());
            assertEquals(18L, response.usage().outputTokens());
            assertTrue(response.usage().exact());
        } finally {
            server.stop(0);
        }
    }

    private static String sampleAnthropicSseStream() {
        return """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","stop_reason":null,"usage":{"input_tokens":10,"cache_creation_input_tokens":20,"cache_read_input_tokens":70,"output_tokens":1}}}

            : keep-alive

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先查"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"定义"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-sse-1"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"正在读取"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"citations_delta","citation":{"url":"https://docs.example/a","title":"Doc A"}}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: content_block_start
            data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_file","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"src/App.java\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":2}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":18}}

            event: message_stop
            data: {"type":"message_stop"}

            """;
    }

    @Test
    void streamingDowngradesAndRetriesOnCacheRejectionHandlesJsonFallbackAndErrors() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(request);
            if (request.contains("cache_control")) {
                byte[] err = "{\"error\":{\"message\":\"unsupported parameter cache_control\"}}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, err.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(err);
                }
                return;
            }
            if (request.contains("fallback-json")) {
                byte[] json = ("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"json-ok\"}],"
                    + "\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(json);
                }
                return;
            }
            if (request.contains("incomplete-sse")) {
                byte[] partial =
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"half\"}}\n\n"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, partial.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(partial);
                }
                return;
            }
            byte[] overloaded =
                "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, overloaded.length);
            try (var output = exchange.getResponseBody()) {
                output.write(overloaded);
            }
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages");
            ModelProfile explicit = ModelProfile.resolve(URI.create("https://api.anthropic.com/v1/messages"),
                "claude-sonnet-4-6", "anthropic", "explicit", "5m", null, null, "short", null);
            AnthropicMessagesModelClient raw = new AnthropicMessagesModelClient(HttpClient.newHttpClient(), mapper,
                endpoint, "key", "claude-sonnet-4-6", Duration.ofSeconds(10), explicit, Set.of());
            MeteredModelClient metered = new MeteredModelClient(raw, "ses", "main", List.of(), report -> {
            });

            StringBuilder live = new StringBuilder();
            ModelResponse downgraded =
                metered.chat(List.of(Message.system("rules"), Message.user("fallback-json")), List.of(), live::append);
            assertEquals("json-ok", downgraded.content());
            assertEquals("json-ok", live.toString());
            assertEquals(2, bodies.size());
            assertEquals(ModelProfile.CACHE_NONE, raw.profile().cacheMode());

            ModelClientException incomplete = assertThrows(ModelClientException.class,
                () -> raw.chat(List.of(Message.user("incomplete-sse")), List.of(), ignored -> {
                }));
            assertEquals(ModelFailureKind.INCOMPLETE, incomplete.kind());

            ModelClientException transientErr = assertThrows(ModelClientException.class,
                () -> raw.chat(List.of(Message.user("overloaded")), List.of(), ignored -> {
                }));
            assertEquals(ModelFailureKind.TRANSIENT, transientErr.kind());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesToolsAndPlacesCompactionBreakpointOnLastHistoryMessage() {
        ModelProfile explicit = ModelProfile.resolve(URI.create("https://api.anthropic.com/v1/messages"),
            "claude-sonnet-4-6", "anthropic", "explicit", "", null, null, "short", null);
        List<ToolDefinition> definitions = List.of(new ToolDefinition("read_file", "Read", Map.of("type", "object")));
        List<Message> history = List.of(Message.system("stable sys"), Message.user("第一轮问题"),
            Message.assistant("已有回答", List.of()), Message.user("一次性压缩整理指令"));

        AnthropicMessagesModelClient noNative = new AnthropicMessagesModelClient(HttpClient.newHttpClient(), mapper,
            URI.create("https://api.anthropic.com/v1/messages"), "key", "claude-sonnet-4-6", Duration.ofSeconds(10),
            explicit, Set.of());
        Map<String, Object> body =
            ModelRequestScope.within("compaction", "ses", true, () -> noNative.requestBody(history, definitions));
        JsonNode root = mapper.valueToTree(body);
        assertEquals(1, root.path("tools").size(), "带工具前缀压缩时必须保留 tools 维持前段缓存");
        assertTrue(root.path("tools").get(0).has("cache_control"));
        assertFalse(root.has("tool_choice"), "无服务端原生工具时不要发 tool_choice 打断 messages 缓存");
        JsonNode msgs = root.path("messages");
        assertEquals("ephemeral", msgs.get(1).path("content").get(0).path("cache_control").path("type").asString(),
            "压缩断点必须落在主对话最后一条历史消息上，而不是末尾一次性压缩指令");
        assertEquals("一次性压缩整理指令", msgs.get(2).path("content").asString());

        AnthropicMessagesModelClient withNative = new AnthropicMessagesModelClient(HttpClient.newHttpClient(), mapper,
            URI.create("https://api.anthropic.com/v1/messages"), "key", "claude-sonnet-4-6", Duration.ofSeconds(10),
            explicit, Set.of(NativeCapability.WEB_SEARCH));
        JsonNode nativeBody = mapper.valueToTree(
            ModelRequestScope.within("compaction", "ses", true, () -> withNative.requestBody(history, definitions)));
        assertEquals("none", nativeBody.path("tool_choice").path("type").asString());
        assertEquals(2, nativeBody.path("tools").size());

        JsonNode shortBody = mapper.valueToTree(
            ModelRequestScope.within("compaction", "ses", true, () -> withNative.requestBody(history, List.of())));
        assertFalse(shortBody.has("tools"), "短摘录模式无 definitions 时不发 tools");
    }

    private static AnthropicMessagesModelClient client(HttpServer server, Set<NativeCapability> capabilities) {
        return new AnthropicMessagesModelClient(HttpClient.newHttpClient(), JsonMapper.builder().build(),
            URI.create("http://localhost:" + server.getAddress().getPort() + "/v1"), "key", "claude-test",
            Duration.ofSeconds(10), ModelProfile.generic(), capabilities);
    }

    private static HttpServer server(AtomicReference<String> requestBody, String response) throws IOException {
        return rawServer(requestBody, "application/json", response);
    }

    private static HttpServer sseServer(AtomicReference<String> requestBody, String response) throws IOException {
        return rawServer(requestBody, "text/event-stream", response);
    }

    private static HttpServer rawServer(AtomicReference<String> requestBody, String contentType, String response)
        throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        return server;
    }
}
