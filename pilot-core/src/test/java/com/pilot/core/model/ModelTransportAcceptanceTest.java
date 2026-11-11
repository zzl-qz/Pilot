package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.pilot.core.loop.Message;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/** 独立验收：真实本地 HTTP 响应，不依赖客户端内部实现或外部模型。 */
class ModelTransportAcceptanceTest {
    private static final String CHAT = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"完成\"}}]}";
    private static final String STREAM =
        "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";

    @ParameterizedTest
    @ValueSource(strings = {"", "data: [DONE]\n\n"})
    void rejectsEofAndDoneWithoutConfirmedFinishEvenWithValidToolArguments(String ending) throws Exception {
        String partial =
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call\",\"function\":{\"name\":\"write_file\",\"arguments\":\"{}\"}}]}}]}\n\n";
        try (Endpoint endpoint = new Endpoint(body(partial + ending))) {
            ModelClientException error =
                assertThrows(ModelClientException.class, () -> client(endpoint, "stream", Duration.ofSeconds(2))
                    .chat(List.of(Message.user("执行")), List.of(), text -> {
                    }));
            assertEquals("INCOMPLETE", error.kind().name());
        }
    }

    @Test
    void acceptsExplicitFinishAndUsageWithoutDoneTrailer() throws Exception {
        String text = "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":2}}\n\n";
        try (Endpoint endpoint = new Endpoint(body(text))) {
            var result = client(endpoint, "stream", Duration.ofSeconds(2)).chat(List.of(Message.user("执行")), List.of(),
                delta -> {
                });
            assertEquals("完成", result.content());
            assertEquals(ModelResponse.FinishReason.STOP, result.finishReason());
            assertTrue(result.usage().exact());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"buffered", "anthropic", "responses"})
    void rejectsBufferedResponsesWithoutTerminalStatus(String protocol) throws Exception {
        String payload = switch (protocol) {
            case "anthropic" -> "{\"content\":[{\"type\":\"text\",\"text\":\"半截\"}]}";
            case "responses" -> "{\"output\":[]}";
            default -> "{\"choices\":[{\"message\":{\"content\":\"半截\"}}]}";
        };
        try (Endpoint endpoint = new Endpoint(body(payload))) {
            var error = assertThrows(ModelClientException.class,
                () -> client(endpoint, protocol, Duration.ofSeconds(2)).chat(List.of(Message.user("执行")), List.of()));
            assertEquals("INCOMPLETE", error.kind().name());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stream", "buffered", "anthropic", "responses"})
    void deadlineIncludesBodyAfterHeaders(String protocol) throws Exception {
        try (Endpoint endpoint = new Endpoint(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(900);
                exchange.getResponseBody()
                    .write((protocol.equals("stream") ? STREAM : CHAT).getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        })) {
            long started = System.nanoTime();
            var error = assertThrows(ModelClientException.class, () -> {
                var model = client(endpoint, protocol, Duration.ofMillis(200));
                if (protocol.equals("stream")) {
                    model.chat(List.of(Message.user("执行")), List.of(), delta -> {
                    });
                } else {
                    model.chat(List.of(Message.user("执行")), List.of());
                }
            });
            assertEquals(ModelFailureKind.NETWORK, error.kind());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 750, "响应头不能解除正文超时");
        }
    }

    @Test
    void missingFinishTakesPrecedenceOverTruncatedBufferedToolArguments() throws Exception {
        String payload = """
            {"choices":[{"message":{"tool_calls":[{"id":"call","function":{"name":"write_file","arguments":"{"}}]}}]}
            """;
        try (Endpoint endpoint = new Endpoint(body(payload))) {
            var error = assertThrows(ModelClientException.class,
                () -> client(endpoint, "buffered", Duration.ofSeconds(2)).chat(List.of(Message.user("执行")), List.of()));
            assertEquals(ModelFailureKind.INCOMPLETE, error.kind());
            assertTrue(error.kind().retryable());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stream", "buffered", "anthropic", "responses"})
    void outputLimitDoesNotParseOrExecutePartialToolArguments(String protocol) throws Exception {
        String payload = switch (protocol) {
            case "stream" ->
                """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call","function":{"name":"write_file","arguments":"{"}}]},"finish_reason":"length"}]}

                    data: [DONE]

                    """;
            case "anthropic" -> """
                {"stop_reason":"max_tokens","content":[{"type":"tool_use","id":"call","name":"write_file","input":"{"}]}
                """;
            case "responses" ->
                """
                    {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"function_call","call_id":"call","name":"write_file","arguments":"{"}]}
                    """;
            default ->
                """
                    {"choices":[{"finish_reason":"length","message":{"tool_calls":[{"id":"call","function":{"name":"write_file","arguments":"{"}}]}}]}
                    """;
        };
        try (Endpoint endpoint = new Endpoint(body(payload))) {
            var model = client(endpoint, protocol, Duration.ofSeconds(2));
            var result = protocol.equals("stream") ? model.chat(List.of(Message.user("执行")), List.of(), ignored -> {
            }) : model.chat(List.of(Message.user("执行")), List.of());
            assertEquals(ModelResponse.FinishReason.LENGTH, result.finishReason());
            assertTrue(result.toolCalls().isEmpty());
        }
    }

    @Test
    void oversizedSseLineIsRejectedBeforeJsonParsing() throws Exception {
        try (Endpoint endpoint = new Endpoint(body("data: " + "x".repeat(1024 * 1024)))) {
            var error = assertThrows(ModelClientException.class, () -> client(endpoint, "stream", Duration.ofSeconds(2))
                .chat(List.of(Message.user("执行")), List.of(), ignored -> {
                }));
            assertEquals(ModelFailureKind.PERMANENT, error.kind());
            assertTrue(error.getMessage().contains("SSE"));
        }
    }

    @Test
    void interruptionClosesBlockedReadAndKeepsCancellationClassification() throws Exception {
        var headersSent = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (Endpoint endpoint = new Endpoint(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            headersSent.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        })) {
            var failed = new AtomicReference<Throwable>();
            var finished = new CountDownLatch(1);
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    client(endpoint, "stream", Duration.ofSeconds(30)).chat(List.of(Message.user("执行")), List.of(),
                        delta -> {
                        });
                } catch (Throwable failure) {
                    failed.set(failure);
                } finally {
                    finished.countDown();
                }
            });
            try {
                assertTrue(headersSent.await(3, TimeUnit.SECONDS));
                worker.interrupt();
                assertTrue(finished.await(2, TimeUnit.SECONDS));
                assertTrue(failed.get() instanceof ModelClientException);
                assertEquals(ModelFailureKind.INTERRUPTED, ((ModelClientException)failed.get()).kind());
            } finally {
                release.countDown();
                worker.interrupt();
            }
        }
    }

    private static ModelClient client(Endpoint endpoint, String protocol, Duration timeout) {
        var http = HttpClient.newHttpClient();
        var mapper = JsonMapper.builder().build();
        return switch (protocol) {
            case "anthropic" -> new AnthropicMessagesModelClient(http, mapper, endpoint.uri(), "local", "test", timeout,
                ModelProfile.generic(), Set.of());
            case "responses" -> new OpenAiResponsesModelClient(http, mapper, endpoint.uri(), "local", "test", timeout,
                ModelProfile.generic());
            default -> new OpenAiCompatibleModelClient(http, mapper, endpoint.uri(), "local", "test", timeout);
        };
    }

    private static HttpHandler body(String payload) {
        return exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        };
    }

    private static class Endpoint implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        Endpoint(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", handler);
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
