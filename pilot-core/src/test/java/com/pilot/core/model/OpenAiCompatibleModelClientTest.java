package com.pilot.core.model;

import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.Message;
import com.pilot.core.tool.ToolCall;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** 自建网关常把兼容层挂在 /api/llm/v1 这类前缀下：只认裸 /v1 会把 POST 打到网关根。 */
    @Test
    void completesChatPathForPrefixedV1Endpoints() {
        assertEquals(URI.create("https://gateway.test/api/llm/v1/chat/completions"),
                OpenAiCompatibleModelClient.resolveEndpoint(URI.create("https://gateway.test/api/llm/v1")));
        assertEquals(URI.create("https://gateway.test/api/llm/v1/chat/completions"),
                OpenAiCompatibleModelClient.resolveEndpoint(URI.create("https://gateway.test/api/llm/v1/")));
        // /v11、/service-v1 这类不是兼容层基址，仍原样使用
        assertEquals(URI.create("https://gateway.test/v11"),
                OpenAiCompatibleModelClient.resolveEndpoint(URI.create("https://gateway.test/v11")));
        assertEquals(URI.create("https://gateway.test/service-v1"),
                OpenAiCompatibleModelClient.resolveEndpoint(URI.create("https://gateway.test/service-v1")));
    }

    @Test
    void parsesToolCallsFromResponse() throws IOException {
        AtomicReference<String> captured = new AtomicReference<String>();
        HttpServer server = startServer(captured, 200, """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[
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
            assertEquals(ModelResponse.FinishReason.TOOL_CALLS, response.finishReason());

            JsonNode request = json.readTree(captured.get());
            assertEquals("test-model", request.get("model").asText());
            assertEquals("read_file", request.get("tools").get(0).get("function").get("name").asText());
            assertEquals("user", request.get("messages").get(0).get("role").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesStreamingLengthFinishReason() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200, """
                data: {"choices":[{"delta":{"content":"部分结果"},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"length"}]}

                data: [DONE]

                """);
        server.start();
        try {
            StringBuilder streamed = new StringBuilder();
            ModelResponse response = client(server).chat(List.of(Message.user("开始")), List.of(), streamed::append);
            assertEquals("部分结果", response.content());
            assertEquals("部分结果", streamed.toString());
            assertEquals(ModelResponse.FinishReason.LENGTH, response.finishReason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serializesAssistantToolCallsAndToolResults() throws IOException {
        AtomicReference<String> captured = new AtomicReference<String>();
        HttpServer server = startServer(captured, 200, """
                {"choices":[{"message":{"role":"assistant","content":"已完成"}}]}
                """);
        server.start();
        try {
            ToolCall call = new ToolCall("call_1", "read_file", Map.of("path", "pom.xml"));
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
    void keepsExactTokenUsageWhenEndpointReportsIt() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200, """
                {"choices":[{"message":{"role":"assistant","content":"完成"}}],
                 "usage":{"prompt_tokens":321,"completion_tokens":17,
                   "completion_tokens_details":{"reasoning_tokens":9}}}
                """);
        server.start();
        try {
            ModelResponse response = client(server).chat(List.of(Message.user("开始")), List.of());
            assertEquals(new ModelUsage(321, 17, 9, true), response.usage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsImageAttachmentAsMultimodalParts() throws IOException {
        Path image = Files.createTempFile("pilot-attach", ".png");
        Files.write(image, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        AtomicReference<String> captured = new AtomicReference<String>();
        HttpServer server = startServer(captured, 200, """
                {"choices":[{"message":{"role":"assistant","content":"看到了"}}]}
                """);
        server.start();
        try {
            client(server).chat(List.of(Message.user("看这张截图",
                    List.of(new Attachment("shot.png", "image/png", image.toString())))), List.of());

            JsonNode content = json.readTree(captured.get()).get("messages").get(0).get("content");
            assertTrue(content.isArray(), "带图片的用户消息应折叠成多模态数组");
            assertEquals("text", content.get(0).get("type").asText());
            assertEquals("image_url", content.get(1).get("type").asText());
            assertTrue(content.get(1).get("image_url").get("url").asText()
                    .startsWith("data:image/png;base64,"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(image);
        }
    }

    @Test
    void keepsPlainTextWhenNoImageAttachment() {
        assertEquals("hi", OpenAiCompatibleModelClient.contentFor(Message.user("hi")));
    }

    @Test
    void preservesImagePositionBetweenText() throws IOException {
        Path image = Files.createTempFile("pilot-position", ".png");
        Files.write(image, new byte[] { 1, 2, 3 });
        try {
            Message message = Message.user("beforeafter",
                    List.of(new Attachment("image.png", "image/png", image.toString())), List.of(6));
            JsonNode content = json.valueToTree(OpenAiCompatibleModelClient.contentFor(message));
            assertEquals("before", content.get(0).get("text").asText());
            assertEquals("image_url", content.get(1).get("type").asText());
            assertEquals("after", content.get(2).get("text").asText());
        } finally {
            Files.deleteIfExists(image);
        }
    }

    @Test
    void surfacesHttpErrorStatus() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 503, "{\"error\":\"overloaded\"}");
        server.start();
        try {
            ModelClientException exception = assertThrows(ModelClientException.class,
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
            ModelClientException exception = assertThrows(ModelClientException.class,
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
            ModelClientException exception = assertThrows(ModelClientException.class,
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
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        return server;
    }
}
