package com.pilot.server.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.MultipartConfigElement;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.pilot.server.config.ModelProperties;
import com.pilot.server.config.ModelSettings;
import com.pilot.server.store.InMemorySettingsStore;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

class VoiceServiceTest {
    @Test
    void normalizesEndpointWithoutChangingProviderOrVersionPrefix() {
        for (String suffix : List.of("", "/", "/v1", "/v1/", "/v1/chat/completions", "/v1/responses",
            "/v1/audio/transcriptions")) {
            assertEquals("https://voice.test/v1/audio/transcriptions",
                VoiceService.transcriptionEndpoint("https://voice.test" + suffix).toString());
        }
        assertEquals("http://localhost:8000/gateway/v2/audio/transcriptions",
            VoiceService.transcriptionEndpoint("http://localhost:8000/gateway/v2/responses").toString());
        assertEquals("https://voice.test//gateway/audio/transcriptions",
            VoiceService.transcriptionEndpoint("https://voice.test//gateway").toString());
        for (String invalid : List.of("file:///tmp", "https://secret@voice.test/v1", "https://voice.test?key=secret",
            "https://voice.test/#key", "https:/missing-host")) {
            assertThrows(IllegalArgumentException.class, () -> VoiceService.transcriptionEndpoint(invalid));
        }
    }

    @Test
    void multipartRequestUsesConfiguredProviderAndReturnsChineseDraft() throws Exception {
        try (Fixture f = new Fixture()) {
            var mvc = MockMvcBuilders.standaloneSetup(new VoiceController(f.service)).build();
            mvc.perform(multipart("/api/voice/transcriptions")
                .file(new MockMultipartFile("file", "录音.webm", "audio/webm;codecs=opus", new byte[] {1, 2, 3})))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("请检查队列引导"));
            assertEquals("Bearer chat-test-key", f.auth.get());
            assertTrue(f.body.get().contains("name=\"model\"\r\n\r\ngpt-4o-mini-transcribe"));
            assertTrue(f.body.get().contains("filename=\"recording.webm\""));
            assertTrue(f.body.get().contains("Content-Type: audio/webm\r\n"));
        }
    }

    @Test
    void separateProviderNeverInheritsChatCredentialAndSettingsNeverExposeKey() throws Exception {
        try (Fixture f = new Fixture()) {
            var settings = f.service.update(new VoiceService.Update(f.endpoint, "whisper-1", null, null));
            assertFalse(settings.apiKeySet());
            f.service.transcribe(new byte[] {1}, "audio/mp4");
            assertNull(f.auth.get());
            settings = f.service.update(new VoiceService.Update(null, null, "voice-test-key", null));
            assertTrue(settings.apiKeySet());
            assertFalse(settings.toString().contains("voice-test-key"));
            f.service.transcribe(new byte[] {1}, "audio/ogg");
            assertEquals("Bearer voice-test-key", f.auth.get());
            f.service.update(new VoiceService.Update(null, null, null, true));
            f.service.transcribe(new byte[] {1}, "audio/wav");
            assertNull(f.auth.get());
            assertThrows(IllegalArgumentException.class,
                () -> f.service.update(new VoiceService.Update(null, "bad\r\nmodel", null, null)));
        }
    }

    @Test
    void validatesEmptyOversizeAndUnsupportedAudioBeforeCallingProvider() throws Exception {
        try (Fixture f = new Fixture()) {
            int maxBytes = f.service.limits().budget().maxBytes();
            assertThrows(IllegalArgumentException.class, () -> f.service.transcribe(new byte[0], "audio/webm"));
            assertThrows(IllegalArgumentException.class,
                () -> f.service.transcribe(new byte[maxBytes + 1], "audio/webm"));
            assertThrows(IllegalArgumentException.class, () -> f.service.transcribe(new byte[] {1}, "text/plain"));
            assertNull(f.body.get());
            var mvc = MockMvcBuilders.standaloneSetup(new VoiceController(f.service)).build();
            mvc.perform(
                multipart("/api/voice/transcriptions").file(new MockMultipartFile("file", new byte[maxBytes + 1])))
                .andExpect(status().isPayloadTooLarge());
            mvc.perform(multipart("/api/voice/transcriptions")
                .file(new MockMultipartFile("file", "empty.webm", "audio/webm", new byte[0])))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void upstreamFailureDoesNotLeakProviderResponseAndEmptyTranscriptionCanRetry() throws Exception {
        try (Fixture f = new Fixture()) {
            f.response.set("{\"error\":\"secret-provider-detail\"}");
            for (int code : List.of(401, 403, 404, 405, 429, 500, 502, 503, 504, 302)) {
                f.code.set(code);
                var error = assertThrows(VoiceService.VoiceException.class,
                    () -> f.service.transcribe(new byte[] {1}, "audio/webm"));
                assertFalse(error.getMessage().contains("secret-provider-detail"));
            }
            f.code.set(200);
            for (String response : List.of("{\"text\":\" \"}", "not-json", "{}", "x".repeat(256 * 1024 + 1))) {
                f.response.set(response);
                assertThrows(VoiceService.VoiceException.class,
                    () -> f.service.transcribe(new byte[] {1}, "audio/webm"));
            }
            f.response.set("{\"text\":\"重试成功\"}");
            assertEquals("重试成功", f.service.transcribe(new byte[] {1}, "audio/webm"));
        }
    }

    @Test
    void settingsExposeTheEndpointThatWillActuallyBeCalled() throws Exception {
        try (Fixture f = new Fixture()) {
            // 留空 = 沿用聊天端点，路径换成 /audio/transcriptions，界面据此告诉用户请求打到哪
            assertTrue(f.service.settings().effectiveEndpoint().endsWith("/v1/audio/transcriptions"));
            var settings = f.service.update(new VoiceService.Update("https://voice.example/v1", null, null, null));
            assertEquals("https://voice.example/v1/audio/transcriptions", settings.effectiveEndpoint());
        }
    }

    @Test
    void gatewayWithoutTranscriptionPointsAtTheSeparateEndpointSetting() throws Exception {
        try (Fixture f = new Fixture()) {
            // new-api 中转站的 503 原样带出来：「当前分组上游负载已饱和」/「无可用渠道」
            f.response.set("{\"error\":{\"message\":\"当前分组上游负载已饱和，请稍后再试\"}}");
            f.code.set(503);
            var error = assertThrows(VoiceService.VoiceException.class,
                () -> f.service.transcribe(new byte[] {1}, "audio/webm"));
            assertTrue(error.getMessage().contains("503"), error.getMessage());
            assertTrue(error.getMessage().contains("独立"), error.getMessage());
            assertTrue(error.getMessage().contains("当前分组上游负载已饱和"), error.getMessage());
        }
    }

    @Test
    void oneApiStyleErrorBodyIsAlsoSurfaced() throws Exception {
        try (Fixture f = new Fixture()) {
            f.response.set("{\"message\":\"无效的令牌\"}");
            f.code.set(401);
            var error = assertThrows(VoiceService.VoiceException.class,
                () -> f.service.transcribe(new byte[] {1}, "audio/webm"));
            assertTrue(error.getMessage().contains("鉴权失败"), error.getMessage());
            assertTrue(error.getMessage().contains("无效的令牌"), error.getMessage());
        }
    }

    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicReference<String> body = new AtomicReference<>();
        final AtomicReference<String> auth = new AtomicReference<>();
        final AtomicReference<String> response = new AtomicReference<>("{\"text\":\"请检查队列引导\"}");
        final AtomicReference<Integer> code = new AtomicReference<>(200);
        final String endpoint;
        final VoiceService service;

        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            server.createContext("/v1/audio/transcriptions", exchange -> {
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(code.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            var store = new InMemorySettingsStore();
            service = new VoiceService(
                new ModelSettings(new ModelProperties("chat-test-key", endpoint, "test-chat", List.of()), store), store,
                JsonMapper.builder().build(), new VoiceLimits(new MultipartConfigElement("", 2048, 4096, 0), 73),
                "gpt-4o-mini-transcribe");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
