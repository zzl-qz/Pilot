package com.pilot.server.attachment;

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

import tools.jackson.databind.ObjectMapper;

/** 经过真实 Tomcat、分块传输和 Jackson 的入口限流验证，不连接模型。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"pilot.store=memory",
    "pilot.attachments.max-files=2", "pilot.attachments.max-file-bytes=3", "pilot.attachments.max-total-bytes=4"})
class AttachmentHttpTest {
    @Value("${local.server.port}")
    int port;
    @Autowired
    AttachmentLimits limits;
    @Autowired
    ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Path data = Path.of(System.getProperty("java.io.tmpdir"), "pilot-attachment-http-" + UUID.randomUUID());
        registry.add("pilot.data.dir", data::toString);
    }

    @Test
    void settingsAdvertiseTheSameConfiguredBudgetAsTheRequestFilter() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/settings")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var budget = mapper.readTree(response.body()).path("attachmentLimits");
            assertEquals(2, budget.path("maxFiles").asInt());
            assertEquals(3, budget.path("maxFileBytes").asInt());
            assertEquals(4, budget.path("maxTotalBytes").asInt());
            assertEquals(limits.maxRequestBytes(), budget.path("maxRequestBytes").asLong());
        }
    }

    @Test
    void rejectsOversizedJsonWithBothFixedLengthAndChunkedTransfer() throws Exception {
        byte[] body =
            ("{\"content\":\"" + "x".repeat((int)limits.maxRequestBytes()) + "\"}").getBytes(StandardCharsets.UTF_8);
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            for (String route : new String[] {"messages", "%6dessages"}) {
                for (boolean chunked : new boolean[] {false, true}) {
                    var publisher =
                        chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                            : HttpRequest.BodyPublishers.ofByteArray(body);
                    HttpRequest request =
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/sessions/test/" + route))
                            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").POST(publisher)
                            .build();
                    assertEquals(413, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(),
                        "route=" + route + ", chunked=" + chunked);
                }
            }
        }
    }
}
