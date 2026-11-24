package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.pilot.core.WindowsJdkLoopbackSensitive;
import com.pilot.core.message.Message;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

@WindowsJdkLoopbackSensitive
class ModelZeroKeyAuthHeaderTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void openAiCompatibleOmitsAuthorizationWhenApiKeyIsBlankInSyncAndStream() throws IOException {
        AtomicReference<String> authHeader = new AtomicReference<>("unset");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean stream = req.contains("\"stream\":true");
            byte[] payload;
            if (stream) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                payload = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                payload = """
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            OpenAiCompatibleModelClient blankClient = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), json,
                uri, "   ", "qwen2.5-coder", Duration.ofSeconds(5));
            assertEquals("ok", blankClient.chat(List.of(Message.user("hi")), List.of()).content());
            assertNull(authHeader.get(), "同步模式空白密钥不应发送 Authorization 头");

            authHeader.set("unset");
            StringBuilder streamed = new StringBuilder();
            assertEquals("ok",
                blankClient.chat(List.of(Message.user("hi")), List.of(), streamed::append, ignored -> {}).content());
            assertNull(authHeader.get(), "流式模式空白密钥不应发送 Authorization 头");

            OpenAiCompatibleModelClient keyedClient = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), json,
                uri, " sk-test-123 ", "qwen2.5-coder", Duration.ofSeconds(5));
            keyedClient.chat(List.of(Message.user("hi")), List.of());
            assertEquals("Bearer sk-test-123", authHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiResponsesOmitsAuthorizationWhenApiKeyIsBlank() throws IOException {
        AtomicReference<String> authHeader = new AtomicReference<>("unset");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] payload = """
                {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"pong"}]}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            OpenAiResponsesModelClient client = new OpenAiResponsesModelClient(HttpClient.newHttpClient(), json, uri,
                "", "local-model", Duration.ofSeconds(5), ModelProfile.generic());
            assertEquals("pong", client.chat(List.of(Message.user("ping")), List.of()).content());
            assertNull(authHeader.get(), "Responses 协议空白密钥不应发送 Authorization 头");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anthropicMessagesOmitsApiKeyHeaderWhenApiKeyIsBlank() throws IOException {
        AtomicReference<String> keyHeader = new AtomicReference<>("unset");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            keyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] payload = """
                {"stop_reason":"end_turn","content":[{"type":"text","text":"hello"}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            AnthropicMessagesModelClient client = new AnthropicMessagesModelClient(HttpClient.newHttpClient(), json,
                uri, null, "local-claude", Duration.ofSeconds(5), ModelProfile.generic(), java.util.Set.of());
            assertEquals("hello", client.chat(List.of(Message.user("hi")), List.of()).content());
            assertNull(keyHeader.get(), "Anthropic 协议空白密钥不应发送 x-api-key 头");

            AnthropicMessagesModelClient keyed = new AnthropicMessagesModelClient(HttpClient.newHttpClient(), json, uri,
                " ant-key ", "local-claude", Duration.ofSeconds(5), ModelProfile.generic(), java.util.Set.of());
            keyed.chat(List.of(Message.user("hi")), List.of());
            assertEquals("ant-key", keyHeader.get());
        } finally {
            server.stop(0);
        }
    }
}
