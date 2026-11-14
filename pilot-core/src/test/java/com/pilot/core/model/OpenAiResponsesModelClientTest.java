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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiResponsesModelClientTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void convertsChatHistoryAndLocalToolsToResponsesWireFormatAndAddsNativeSearch() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server(requestBody,
            """
                {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Found it.","annotations":[{"type":"url_citation","title":"Example","url":"https://example.com"}]}]}],"usage":{"input_tokens":20,"output_tokens":5}}
                """);
        server.start();
        try {
            OpenAiResponsesModelClient client = client(server);
            ModelResponse response = client.chat(List.of(Message.system("system prompt"), Message.user("find source")),
                List.of(new ToolDefinition("read_file", "Read file", Map.of("type", "object")),
                    new ToolDefinition("web_search", "Local search", Map.of("type", "object"))));

            JsonNode body = mapper.readTree(requestBody.get());
            assertEquals(ModelCapacity.configured().outputTokens(), body.path("max_output_tokens").asInt());
            assertEquals("test-model", body.path("model").asString());
            assertEquals("system", body.path("input").get(0).path("role").asString());
            assertEquals("web_search", body.path("tools").get(1).path("type").asString());
            assertTrue(body.path("tools").toString().contains("read_file"));
            assertFalse(body.path("tools").toString().contains("Local search"));
            assertTrue(response.content().contains("Found it."));
            assertTrue(response.content().contains("https://example.com"));
            assertEquals(new ModelUsage(20, 5, 0, true), response.usage());
            assertEquals(NativeCapability.WEB_SEARCH, client.nativeCapabilities().iterator().next());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsFunctionCallsAndResultsAcrossResponsesTurns() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server(requestBody,
            """
                {"status":"completed","output":[{"type":"function_call","call_id":"call-1","name":"read_file","arguments":"{\\"path\\":\\"pom.xml\\"}"}]}
                """);
        server.start();
        try {
            ModelResponse response = client(server).chat(
                List.of(Message.user("read"),
                    Message.assistant("", List.of(new ToolCall("call-0", "list_dir", Map.of("path", ".")))),
                    Message.tool("call-0", "pom.xml")),
                List.of(new ToolDefinition("read_file", "Read", Map.of("type", "object"))));

            JsonNode body = mapper.readTree(requestBody.get());
            assertEquals(ModelCapacity.configured().outputTokens(), body.path("max_output_tokens").asInt());
            assertEquals("function_call", body.path("input").get(1).path("type").asString());
            assertEquals("function_call_output", body.path("input").get(2).path("type").asString());
            assertEquals("pom.xml", response.toolCalls().getFirst().args().get("path"));
            assertEquals(ModelResponse.FinishReason.TOOL_CALLS, response.finishReason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvesCommonOpenAiChatEndpointsToResponses() {
        assertEquals(URI.create("https://api.openai.com/v1/responses"),
            OpenAiResponsesModelClient.resolveEndpoint(URI.create("https://api.openai.com/v1/chat/completions")));
        assertEquals(URI.create("https://gateway.test/api/llm/v1/responses"),
            OpenAiResponsesModelClient.resolveEndpoint(URI.create("https://gateway.test/api/llm/v1")));
    }

    private static OpenAiResponsesModelClient client(HttpServer server) {
        return new OpenAiResponsesModelClient(HttpClient.newHttpClient(), JsonMapper.builder().build(),
            URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions"), "key",
            "test-model", Duration.ofSeconds(10), ModelProfile.generic());
    }

    private static HttpServer server(AtomicReference<String> requestBody, String response) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> {
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
