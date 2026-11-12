package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.pilot.core.http.BoundedHttp;
import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.Message;
import com.pilot.core.tool.ToolCall;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
        HttpServer server = startServer(captured, 200,
            """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"pom.xml\\"}"}},
                  {"id":"call_2","type":"function","function":{"name":"list_dir","arguments":"{\\"path\\":\\".\\"}"}}]}}]}
                """);
        server.start();
        try {
            ModelResponse response = client(server).chat(List.of(Message.user("看看 pom")),
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
    void streamsReasoningSeparatelyFromTheAnswer() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200, """
            data: {"choices":[{"delta":{"reasoning_content":"先看分隔条"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"<think>再核对"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"宽度</think>可以拖"},"finish_reason":"stop"}]}

            data: [DONE]

            """);
        server.start();
        try {
            StringBuilder answer = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            ModelResponse response =
                client(server).chat(List.of(Message.user("开始")), List.of(), answer::append, reasoning::append);
            assertEquals("可以拖", response.content());
            assertEquals("可以拖", answer.toString());
            assertEquals("先看分隔条再核对宽度", reasoning.toString());
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

    /** 给界面的流式回调必须是增量：每帧只含新生成的字，不能把之前的内容重发一遍。 */
    @Test
    void streamsEachChunkOnlyOnceWithoutRepeatingEarlierText() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200, """
            data: {"choices":[{"delta":{"content":"第一段"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"第二段"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"第三段"},"finish_reason":"stop"}]}

            data: [DONE]

            """);
        server.start();
        try {
            List<String> frames = new ArrayList<String>();
            ModelResponse response = client(server).chat(List.of(Message.user("开始")), List.of(), frames::add);

            assertEquals(List.of("第一段", "第二段", "第三段"), frames, "每一帧必须是增量，不能把已经发过的内容再发一次");
            assertEquals("第一段第二段第三段", response.content());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serializesAssistantToolCallsAndToolResults() throws IOException {
        AtomicReference<String> captured = new AtomicReference<String>();
        HttpServer server = startServer(captured, 200, """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"已完成"}}]}
            """);
        server.start();
        try {
            ToolCall call = new ToolCall("call_1", "read_file", Map.of("path", "pom.xml"));
            ModelResponse response = client(server).chat(List.of(Message.user("读 pom.xml"),
                Message.assistant("", List.of(call)), Message.tool("call_1", "1 | <project/>")), List.of());

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
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"完成"}}],
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
    void readsCachedTokensFromOpenAiStyleUsage() throws IOException {
        // OpenAI 风格：prompt_tokens 已包含缓存部分，缓存是分子，不能重复加进总输入
        HttpServer server = startServer(new AtomicReference<>(), 200, """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"完成"}}],
             "usage":{"prompt_tokens":1000,"completion_tokens":17,
               "prompt_tokens_details":{"cached_tokens":800}}}
            """);
        server.start();
        try {
            ModelResponse response = client(server).chat(List.of(Message.user("开始")), List.of());
            assertEquals(1000, response.usage().inputTokens(), "总输入已含缓存，再相加会虚增成本");
            assertEquals(800, response.usage().cachedInputTokens());
            assertEquals(0, response.usage().cacheWriteTokens(), "OpenAI 端点不返回缓存写入量");
            assertTrue(response.usage().exact());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void addsAnthropicCacheTokensBackIntoTotalInput() throws IOException {
        // Anthropic 风格：input_tokens 不含缓存，缓存量是加数
        HttpServer server = startServer(new AtomicReference<>(), 200, """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"完成"}}],
             "usage":{"input_tokens":100,"completion_tokens":17,
               "cache_read_input_tokens":700,"cache_creation_input_tokens":200}}
            """);
        server.start();
        try {
            ModelResponse response = client(server).chat(List.of(Message.user("开始")), List.of());
            assertEquals(1000, response.usage().inputTokens());
            assertEquals(700, response.usage().cachedInputTokens());
            assertEquals(200, response.usage().cacheWriteTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void countsAnthropicCacheWriteWithoutRead() throws IOException {
        // 首次写入缓存：只有 creation 没有 read，仍按 Anthropic 口径处理
        HttpServer server = startServer(new AtomicReference<>(), 200, """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"完成"}}],
             "usage":{"input_tokens":50,"completion_tokens":17,
               "cache_creation_input_tokens":250}}
            """);
        server.start();
        try {
            ModelResponse response = client(server).chat(List.of(Message.user("开始")), List.of());
            assertEquals(300, response.usage().inputTokens());
            assertEquals(0, response.usage().cachedInputTokens());
            assertEquals(250, response.usage().cacheWriteTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToFlatCachedTokens() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200, """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"完成"}}],
             "usage":{"prompt_tokens":600,"completion_tokens":17,"cached_tokens":300}}
            """);
        server.start();
        try {
            ModelResponse response = client(server).chat(List.of(Message.user("开始")), List.of());
            assertEquals(600, response.usage().inputTokens());
            assertEquals(300, response.usage().cachedInputTokens(), "网关把缓存量平铺时也要读到");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsZeroCacheWhenEndpointOmitsIt() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200, """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"完成"}}],
             "usage":{"prompt_tokens":321,"completion_tokens":17}}
            """);
        server.start();
        try {
            ModelResponse response = client(server).chat(List.of(Message.user("开始")), List.of());
            assertEquals(321, response.usage().inputTokens());
            assertEquals(0, response.usage().cachedInputTokens());
            assertEquals(0d, response.usage().cacheHitRatio());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clampsCacheAboveReportedInput() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200, """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"完成"}}],
             "usage":{"prompt_tokens":100,"completion_tokens":17,
               "prompt_tokens_details":{"cached_tokens":400}}}
            """);
        server.start();
        try {
            ModelResponse response = client(server).chat(List.of(Message.user("开始")), List.of());
            assertEquals(100, response.usage().inputTokens());
            assertEquals(100, response.usage().cachedInputTokens(), "命中率不允许超过 100%");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsImageAttachmentAsMultimodalParts() throws IOException {
        Path image = Files.createTempFile("pilot-attach", ".png");
        Files.write(image, new byte[] {(byte)0x89, 'P', 'N', 'G'});
        AtomicReference<String> captured = new AtomicReference<String>();
        HttpServer server = startServer(captured, 200, """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"看到了"}}]}
            """);
        server.start();
        try {
            client(server).chat(
                List.of(Message.user("看这张截图", List.of(new Attachment("shot.png", "image/png", image.toString())))),
                List.of());

            JsonNode content = json.readTree(captured.get()).get("messages").get(0).get("content");
            assertTrue(content.isArray(), "带图片的用户消息应折叠成多模态数组");
            assertEquals("text", content.get(0).get("type").asText());
            assertEquals("image_url", content.get(1).get("type").asText());
            assertTrue(content.get(1).get("image_url").get("url").asText().startsWith("data:image/png;base64,"));
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
        Files.write(image, new byte[] {1, 2, 3});
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
    void degradesMissingUserImageToPlaceholderInsteadOfFailing() {
        // 附件保留策略可能清理掉旧图片；历史回放不能因为图片缺失整段发不出去
        Message message =
            Message.user("看这张截图", List.of(new Attachment("shot.png", "image/png", "C:\\不存在的目录\\shot.png")));

        JsonNode content = json.valueToTree(OpenAiCompatibleModelClient.contentFor(message));

        assertEquals("text", content.get(0).get("type").asText());
        assertEquals("text", content.get(1).get("type").asText(), "用户图片缺失也应降级为文字占位");
        assertTrue(content.get(1).get("text").asText().contains("图片已不存在"));
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
            {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","tool_calls":[
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

    /** 非流式响应缺有效 finish_reason 属于未完成，不能当成正常结束提交。 */
    @Test
    void rejectsNonStreamResponseWithoutFinishReason() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200, """
            {"choices":[{"message":{"role":"assistant","content":"半截"}}]}
            """);
        server.start();
        try {
            ModelClientException exception = assertThrows(ModelClientException.class,
                () -> client(server).chat(List.of(Message.user("hi")), List.of()));
            assertEquals(ModelFailureKind.INCOMPLETE, exception.kind());
        } finally {
            server.stop(0);
        }
    }

    /** 流在有效 finish_reason 到达前 EOF：半截工具调用不得返回给调用方执行。 */
    @Test
    void rejectsStreamEndedBeforeFinishReason() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200,
            """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{}"}}]}}]}

                """);
        server.start();
        try {
            ModelClientException exception = assertThrows(ModelClientException.class,
                () -> client(server).chat(List.of(Message.user("hi")), List.of(), frame -> {
                }));
            assertEquals(ModelFailureKind.INCOMPLETE, exception.kind());
        } finally {
            server.stop(0);
        }
    }

    /** 只有 DONE、没有任何 finish_reason 不是有效终止信息。 */
    @Test
    void rejectsStreamWithOnlyDoneMarker() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200, "data: [DONE]\n\n");
        server.start();
        try {
            ModelClientException exception = assertThrows(ModelClientException.class,
                () -> client(server).chat(List.of(Message.user("hi")), List.of(), frame -> {
                }));
            assertEquals(ModelFailureKind.INCOMPLETE, exception.kind());
        } finally {
            server.stop(0);
        }
    }

    /** 流式响应直接 EOF、没给 [DONE] 也没给 finish_reason，不能把截断回复补成 STOP 当成完成。 */
    @Test
    void prematureEndWithoutTerminationIsIncompleteNotStop() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200, """
            data: {"choices":[{"delta":{"content":"只生成了一半的回答"},"finish_reason":null}]}

            """);
        server.start();
        try {
            ModelClientException exception = assertThrows(ModelClientException.class,
                () -> client(server).chat(List.of(Message.user("开始")), List.of(), ignored -> {
                }));
            assertEquals(ModelFailureKind.INCOMPLETE, exception.kind(), "提前断流应判为可重试的网络错误，而不是把部分回复当成完整答复返回");
        } finally {
            server.stop(0);
        }
    }

    /** 断流时工具参数可能只收到一半：必须抛可重试错误，不能把它当 TOOL_CALLS 去执行。 */
    @Test
    void prematureEndWithPartialToolCallIsRetryableNotPermanent() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200,
            """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{"}}],"finish_reason":null}}]}

                """);
        server.start();
        try {
            ModelClientException exception = assertThrows(ModelClientException.class,
                () -> client(server).chat(List.of(Message.user("读文件")), List.of(), ignored -> {
                }));
            assertEquals(ModelFailureKind.INCOMPLETE, exception.kind(), "断流下的半截工具调用属于网络问题，不应被误判成永久参数错误");
        } finally {
            server.stop(0);
        }
    }

    /** DONE 只标记传输结束，缺少 finish_reason 时不能确认业务输出完整。 */
    @Test
    void rejectsDoneOnlyStreamWithoutConfirmedFinish() throws IOException {
        HttpServer server = startServer(new AtomicReference<>(), 200,
            "data: {\"choices\":[{\"delta\":{\"content\":\"回答\"}}]}\n\ndata: [DONE]\n\n");
        server.start();
        try {
            ModelClientException exception = assertThrows(ModelClientException.class,
                () -> client(server).chat(List.of(Message.user("开始")), List.of(), ignored -> {
                }));
            assertEquals(ModelFailureKind.INCOMPLETE, exception.kind());
        } finally {
            server.stop(0);
        }
    }

    /** 响应头到达后后端不再发数据（半开连接）：看门狗应在空闲超时后关闭流，报为可重试网络错误，而不是无限等待。 */
    @Test
    void streamTimesOutWhenBackendStallsAfterHeaders() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"开头\"},\"finish_reason\":null}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
                out.flush();
                // 发完第一个分片后保持连接、不再响应，制造半开卡死
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            ModelClientException exception = assertThrows(ModelClientException.class, () -> client(server)
                .withIdleTimeout(Duration.ofMillis(300)).chat(List.of(Message.user("开始")), List.of(), ignored -> {
                }));
            assertEquals(ModelFailureKind.NETWORK, exception.kind());
            assertTrue(exception.getMessage().contains("超时"), exception.getMessage());
        } finally {
            server.stop(0);
            executor.shutdownNow();

        }
    }

    /** 异常端点给单条超长分片时，应在达到上限后停止读取，而不是整行驻留内存。 */
    @Test
    void rejectsOversizedStreamingLine() throws IOException {
        String oversized = "x".repeat(BoundedHttp.SSE_LINE_CHARS + 1);
        HttpServer server = startServer(new AtomicReference<>(), 200, "data: " + oversized + "\n\n");
        server.start();
        try {
            ModelClientException exception = assertThrows(ModelClientException.class,
                () -> client(server).chat(List.of(Message.user("开始")), List.of(), ignored -> {
                }));
            assertEquals(ModelFailureKind.PERMANENT, exception.kind());
            assertTrue(exception.getMessage().contains("SSE"), exception.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private static OpenAiCompatibleModelClient client(HttpServer server) {
        return new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), JsonMapper.builder().build(),
            URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions"), "test-key",
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
