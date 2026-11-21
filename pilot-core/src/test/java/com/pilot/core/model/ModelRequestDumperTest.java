package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.model.diagnostics.ModelRequestDumper;
import com.pilot.core.model.diagnostics.ModelRequestScope;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ModelRequestDumperTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void resetDumper() {
        ModelRequestDumper.configure(false, null);
    }

    @Test
    void disabledByDefaultCreatesNoFiles(@TempDir Path tempDir) {
        ModelRequestDumper.configure(false, tempDir);
        assertFalse(ModelRequestDumper.isEnabled());

        ModelRequestScope.within("main", "grp", false, "ses_off", "run_1",
            () -> ModelHttp.jsonPost(URI.create("https://api.example.com/v1/chat/completions"), Duration.ofSeconds(10),
                mapper, Map.of("model", "demo-model", "messages", List.of(Map.of("role", "user", "content", "hi")))));

        assertFalse(Files.exists(tempDir.resolve("debug-requests")));
    }

    @Test
    void enabledDumpsSanitizedRequestsPerSessionAndRun(@TempDir Path tempDir) throws Exception {
        ModelRequestDumper.configure(true, tempDir);
        assertTrue(ModelRequestDumper.isEnabled());

        URI endpointWithSecrets =
            URI.create("https://user:topsecret@api.example.com/v1/chat/completions?api_key=sk-url-999");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "deepseek-v4.1-flash-1017");
        body.put("messages",
            List.of(Map.of("role", "system", "content", "sys prompt"), Map.of("role", "user", "content", "fix bug")));
        body.put("tools", List.of(Map.of("type", "function", "function", Map.of("name", "read_file"))));
        body.put("authorization", "Bearer sk-body-secret");
        body.put("extra", Map.of("api_key", "sk-nested-secret", "x-api-key", "sk-x-secret"));

        ModelRequestScope.within("main", "grp", false, "ses_demo", "run_42", () -> {
            ModelHttp.jsonPost(endpointWithSecrets, Duration.ofSeconds(10), mapper, body);
            ModelHttp.jsonPost(endpointWithSecrets, Duration.ofSeconds(10), mapper, body);
            return null;
        });

        Path runDir = tempDir.resolve("debug-requests").resolve("ses_demo").resolve("run_42");
        Path req1 = runDir.resolve("req-1.json");
        Path req2 = runDir.resolve("req-2.json");
        assertTrue(Files.isRegularFile(req1));
        assertTrue(Files.isRegularFile(req2));

        String raw1 = Files.readString(req1, StandardCharsets.UTF_8);
        assertFalse(raw1.contains("sk-url-999"));
        assertFalse(raw1.contains("sk-body-secret"));
        assertFalse(raw1.contains("sk-nested-secret"));
        assertFalse(raw1.contains("sk-x-secret"));
        assertFalse(raw1.contains("topsecret"));

        JsonNode parsed1 = mapper.readTree(raw1);
        assertEquals(1, parsed1.path("sequence").asInt());
        assertEquals("ses_demo", parsed1.path("sessionId").asString());
        assertEquals("run_42", parsed1.path("runId").asString());
        assertEquals("main", parsed1.path("purpose").asString());
        assertEquals("https://api.example.com/v1/chat/completions", parsed1.path("endpoint").asString());
        assertEquals("deepseek-v4.1-flash-1017", parsed1.path("body").path("model").asString());
        assertEquals(2, parsed1.path("body").path("messages").size());
        assertEquals(1, parsed1.path("body").path("tools").size());
        assertEquals("[REDACTED]", parsed1.path("body").path("authorization").asString());
        assertEquals("[REDACTED]", parsed1.path("body").path("extra").path("api_key").asString());
        assertEquals("[REDACTED]", parsed1.path("body").path("extra").path("x-api-key").asString());

        JsonNode parsed2 = mapper.readTree(Files.readString(req2, StandardCharsets.UTF_8));
        assertEquals(2, parsed2.path("sequence").asInt());
    }

    @Test
    void pathSegmentsAreSanitizedAgainstTraversal(@TempDir Path tempDir) {
        ModelRequestDumper.configure(true, tempDir);
        ModelRequestScope.within("main", "grp", false, "../escape", "..\\run/1",
            () -> ModelHttp.jsonPost(URI.create("https://api.example.com/v1/chat/completions"), Duration.ofSeconds(10),
                mapper, Map.of("model", "demo")));

        Path debugRoot = tempDir.resolve("debug-requests").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(debugRoot));
        assertFalse(Files.exists(tempDir.resolve("escape")));
    }
}
