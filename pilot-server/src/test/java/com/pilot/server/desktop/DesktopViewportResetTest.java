package com.pilot.server.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DesktopViewportResetTest {
    @TempDir
    Path temp;

    @Test
    void resetIsBoundToTheFinishingSessionAndBlankSessionDoesNothing() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emulate", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper();
            Path endpoint = temp.resolve("desktop-browser.json");
            Files.writeString(endpoint,
                mapper.writeValueAsString(Map.of("controlPort", server.getAddress().getPort(), "token", "test")));
            DesktopViewportReset reset = new DesktopViewportReset();
            reset.clear("", endpoint);
            assertNull(body.get());
            reset.clear("session-a", endpoint);
            JsonNode sent = mapper.readTree(body.get());
            assertEquals("session-a", sent.path("sessionId").asText());
            assertEquals(0, sent.path("width").asInt(-1));
            assertEquals(0, sent.path("height").asInt(-1));
        } finally {
            server.stop(0);
        }
    }
}
