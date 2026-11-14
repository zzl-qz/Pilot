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

import com.pilot.server.browser.BrowserHome;
import com.pilot.server.common.data.PilotDataDir;
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
            Path custom = Files.createDirectory(temp.resolve("运行 目录"));
            BrowserHome home = new BrowserHome(new PilotDataDir(temp.toString()), custom.toString());
            Path endpoint = home.desktopEndpoint();
            Files.writeString(endpoint,
                mapper.writeValueAsString(Map.of("controlPort", server.getAddress().getPort(), "token", "test")));
            DesktopViewportReset reset = new DesktopViewportReset(home);
            reset.clear("");
            assertNull(body.get());
            reset.clear("session-a");
            JsonNode sent = mapper.readTree(body.get());
            assertEquals("session-a", sent.path("sessionId").asText());
            assertEquals(0, sent.path("width").asInt(-1));
            assertEquals(0, sent.path("height").asInt(-1));
        } finally {
            server.stop(0);
        }
    }
}
