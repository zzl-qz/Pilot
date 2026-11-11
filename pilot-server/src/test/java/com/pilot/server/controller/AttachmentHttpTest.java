package com.pilot.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pilot.server.config.AttachmentLimits;

/** 经过真实 Tomcat、分块传输和 Jackson 的入口限流验证，不连接模型。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"pilot.store=memory",
    "pilot.attachments.max-files=2", "pilot.attachments.max-file-bytes=3", "pilot.attachments.max-total-bytes=4"})
class AttachmentHttpTest {
    @Value("${local.server.port}")
    int port;
    @Autowired
    AttachmentLimits limits;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Path data = Path.of(System.getProperty("java.io.tmpdir"), "pilot-attachment-http-" + UUID.randomUUID());
        registry.add("pilot.data.dir", data::toString);
    }

    @Test
    void rejectsOversizedJsonWithBothFixedLengthAndChunkedTransfer() throws Exception {
        byte[] body =
            ("{\"content\":\"" + "x".repeat((int)limits.maxRequestBytes()) + "\"}").getBytes(StandardCharsets.UTF_8);
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            for (boolean chunked : new boolean[] {false, true}) {
                var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                    : HttpRequest.BodyPublishers.ofByteArray(body);
                HttpRequest request = HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:" + port + "/api/sessions/test/messages"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").POST(publisher).build();
                assertEquals(413, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(),
                    "chunked=" + chunked);
            }
        }
    }
}
