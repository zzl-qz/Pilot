package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.pilot.core.loop.Message;
import com.pilot.core.tool.ToolCall;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
    void resolvesAnthropicMessagesEndpoint() {
        assertEquals(URI.create("https://api.anthropic.com/v1/messages"),
            AnthropicMessagesModelClient.resolveEndpoint(URI.create("https://api.anthropic.com/v1")));
    }

    @Test
    void preservesNonVersionedMessagesPathAndQuery() {
        assertEquals(URI.create("https://relay.test/api/messages?tenant=a"),
            AnthropicMessagesModelClient.resolveEndpoint(URI.create("https://relay.test/api/messages?tenant=a")));
    }

    private static AnthropicMessagesModelClient client(HttpServer server, Set<NativeCapability> capabilities) {
        return new AnthropicMessagesModelClient(HttpClient.newHttpClient(), JsonMapper.builder().build(),
            URI.create("http://localhost:" + server.getAddress().getPort() + "/v1"), "key", "claude-test",
            Duration.ofSeconds(10), ModelProfile.generic(), capabilities);
    }

    private static HttpServer server(AtomicReference<String> requestBody, String response) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        return server;
    }
}
