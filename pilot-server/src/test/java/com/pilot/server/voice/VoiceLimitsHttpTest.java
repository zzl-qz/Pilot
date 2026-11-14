package com.pilot.server.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;

import jakarta.servlet.MultipartConfigElement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"pilot.store=memory", "spring.servlet.multipart.max-file-size=4KB",
        "spring.servlet.multipart.max-request-size=3KB", "pilot.voice.max-recording-seconds=73"})
class VoiceLimitsHttpTest {
    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectMapper mapper;

    @DynamicPropertySource
    static void isolatedData(DynamicPropertyRegistry registry) {
        Path data = Path.of(System.getProperty("java.io.tmpdir"), "pilot-voice-limits-" + UUID.randomUUID());
        registry.add("pilot.data.dir", data::toString);
    }

    @Test
    void publishesTheEffectiveMultipartBudgetAndRejectsLargerAudio() throws Exception {
        String base = "http://127.0.0.1:" + port;
        try (HttpClient client = HttpClient.newHttpClient()) {
            var settings = client.send(HttpRequest.newBuilder(URI.create(base + "/api/settings")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, settings.statusCode());
            var budget = mapper.readTree(settings.body()).path("voiceLimits");
            int maxBytes = budget.path("maxBytes").asInt();
            assertEquals(2048, maxBytes);
            assertEquals(73, budget.path("maxSeconds").asInt());

            // 边界内到达音频格式校验，边界外由共享限额拒绝，两种情况都不会请求外部模型。
            assertEquals(400, upload(client, base, maxBytes).statusCode());
            var oversized = upload(client, base, maxBytes + 1);
            assertEquals(413, oversized.statusCode());
            assertEquals("录音不能超过 2048 字节", mapper.readTree(oversized.body()).path("message").asText());
        }
    }

    @Test
    void rejectsNonPositiveRecordingDurationOrImpossibleRequestBudget() {
        assertThrows(IllegalArgumentException.class, () -> new VoiceLimits((MultipartConfigElement)null, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new VoiceLimits(new MultipartConfigElement("", 1024, 1024, 0), 120));
    }

    private static HttpResponse<String> upload(HttpClient client, String base, int bytes) throws Exception {
        String body = "--test-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.webm\"\r\n"
            + "Content-Type: text/plain\r\n\r\n" + "x".repeat(bytes) + "\r\n--test-boundary--\r\n";
        var request = HttpRequest.newBuilder(URI.create(base + "/api/voice/transcriptions"))
            .header("Content-Type", "multipart/form-data; boundary=test-boundary")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
