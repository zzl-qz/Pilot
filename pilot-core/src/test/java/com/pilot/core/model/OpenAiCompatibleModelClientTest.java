package com.pilot.core.model;

import com.pilot.core.loop.Message;
import com.pilot.core.tool.ToolCall;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleModelClientTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void completesEndpointPathFromBaseUrl() {
        URI root = URI.create("https://gateway.test");
        URI rootSlash = URI.create("https://gateway.test/");
        URI v1 = URI.create("https://gateway.test/v1");
        URI full = URI.create("https://gateway.test/v1/chat/completions");

        assertEquals(URI.create("https://gateway.test/v1/chat/completions"),
                OpenAiCompatibleModelClient.resolveEndpoint(root));
        assertEquals(URI.create("https://gateway.test/v1/chat/completions"),
                OpenAiCompatibleModelClient.resolveEndpoint(rootSlash));
        assertEquals(URI.create("https://gateway.test/v1/chat/completions"),
                OpenAiCompatibleModelClient.resolveEndpoint(v1));
        assertEquals(full, OpenAiCompatibleModelClient.resolveEndpoint(full));
        assertEquals(URI.create("https://gateway.test/custom/chat"),
                OpenAiCompatibleModelClient.resolveEndpoint(URI.create("https://gateway.test/custom/chat")));
    }

    @Test
    void parsesToolCallsFromResponse() throws IOException {
        var captured = new AtomicReference<String>();
        HttpServer server = startServer(captured, 200, """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"pom.xml\\"}"}},
                  {"id":"call_2","type":"function","function":{"name":"list_dir","arguments":"{\\"path\\":\\".\\"}"}}]}}]}
                """);
        server.start();
        try {
            ModelResponse response = client(server).chat(
                    List.of(Message.user("看看 pom")),
                    List.of(new ToolDefinition("read_file", "读文件", Map.of("type", "object"))));

            assertEquals("", response.content());
            assertEquals(2, response.toolCalls().size());
            assertEquals("call_1", response.toolCalls().get(0).callId());
            assertEquals("read_file", response.toolCalls().get(0).name());
            assertEquals("pom.xml", response.toolCalls().get(0).args().get("path"));
            assertEquals("list_dir", response.toolCalls().get(1).name());

            JsonNode request = json.readTree(captured.get());
            assertEquals("test-model", request.get("model").asText());
            assertEquals("read_file", request.get("tools").get(0).get("function").get("name").asText());
            assertEquals("user", request.get("messages").get(0).get("role").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serializesAssistantToolCallsAndToolResults() throws IOException {
        var captured = new AtomicReference<String>();
        HttpServer server = startServer(captured, 200, """
                {"choices":[{"message":{"role":"assistant","content":"已完成"}}]}
                """);
        server.start();
        try {
            var call = new ToolCall("call_1", "read_file", Map.of("path", "pom.xml"));
            ModelResponse response = client(server).chat(
                    List.of(
                            Message.user("读 pom.xml"),
                            Message.assistant("", List.of(call)),
                            Message.tool("call_1", "1 | <project/>")),
                    List.of());

            assertEquals("已完成", response.content());
            JsonNode messages = json.readTree(captured.get()).get("messages");
            assertTrue(messages.get(1).get("content").isNull());
            assertEquals("function", messages.get(1).get("tool_calls").get(0).get("type").asText());
            assertEquals("{\"path\":\"pom.xml\"}",
                    messages.get(1).get("tool_calls").get(0).get("function").get("arguments").asText());
            assertEquals("call_1", messages.get(2).get("tool_call_id").asText());
            assertEquals("tool", messages.get(2).get("role").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void surfacesHttpErrorStatus() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 503, "{\"error\":\"overloaded\"}");
        server.start();
        try {
            var exception = assertThrows(ModelClientException.class,
                    () -> client(server).chat(List.of(Message.user("hi")), List.of()));
            assertTrue(exception.getMessage().contains("503"), exception.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMalformedToolArguments() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200, """
                {"choices":[{"message":{"role":"assistant","content":"","tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{not json"}}]}}]}
                """);
        server.start();
        try {
            var exception = assertThrows(ModelClientException.class,
                    () -> client(server).chat(List.of(Message.user("hi")), List.of()));
            assertTrue(exception.getMessage().contains("工具调用参数"), exception.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsResponseWithoutChoices() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200, "{\"error\":{\"message\":\"bad key\"}}");
        server.start();
        try {
            var exception = assertThrows(ModelClientException.class,
                    () -> client(server).chat(List.of(Message.user("hi")), List.of()));
            assertTrue(exception.getMessage().contains("choices"), exception.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private static OpenAiCompatibleModelClient client(HttpServer server) {
        return new OpenAiCompatibleModelClient(
                HttpClient.newHttpClient(),
                JsonMapper.builder().build(),
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions"),
                "test-key",
                "test-model");
    }

    private static HttpServer startServer(AtomicReference<String> captured, int status, String responseBody)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        return server;
    }
}
