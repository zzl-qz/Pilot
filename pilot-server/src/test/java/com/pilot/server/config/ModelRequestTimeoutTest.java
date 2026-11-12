package com.pilot.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClientException;
import com.pilot.core.model.ModelFailureKind;
import com.pilot.server.store.InMemorySettingsStore;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

class ModelRequestTimeoutTest {
    private static final JsonMapper JSON = new JsonMapper();

    private static ModelRuntimeSettings.Options options(String provider, Long total, Long idle) {
        return new ModelRuntimeSettings.Options(provider, "auto", "", null, null, "short", "local", null, total, idle);
    }

    @Test
    void limitsArePersistedPerEndpointAndModelAndOldRecordsUseConfiguredDefaults() {
        var store = new InMemorySettingsStore();
        var models = new ModelSettings(new ModelProperties("key", "https://one.test/v1", "local-model", null), store);
        var runtime = new ModelRuntimeSettings(store, models, JSON, 120, 60);
        var custom = options("generic", 900L, 600L);
        runtime.update("local-model", "https://one.test/v1", custom);
        assertEquals(new ModelRuntimeSettings.RequestTimeouts(900, 600), runtime.current().timeouts());
        assertEquals(custom, new ModelRuntimeSettings(store, models, JSON).current().options());
        models.update(null, "other-model", null);
        assertEquals(new ModelRuntimeSettings.RequestTimeouts(120, 60), runtime.current().timeouts());
        models.update("https://two.test/v1", "local-model", null);
        assertEquals(new ModelRuntimeSettings.RequestTimeouts(120, 60), runtime.current().timeouts());
        models.update("https://one.test/v1", "local-model", null);
        assertEquals(custom, runtime.current().options());
        var legacy = JSON.readValue("{\"provider\":\"generic\",\"cacheMode\":\"auto\",\"compactionMode\":\"short\"}",
            ModelRuntimeSettings.Options.class);
        assertNull(legacy.timeoutSeconds());
        runtime.update("local-model", "https://one.test/v1", legacy);
        assertEquals(new ModelRuntimeSettings.RequestTimeouts(120, 60), runtime.current().timeouts());
    }

    @Test
    void validatesBeforeSavingAndResolvesIdleLimitAgainstCurrentTotal() {
        var store = new InMemorySettingsStore();
        var models = new ModelSettings(new ModelProperties("key", "https://one.test/v1", "m", null), store);
        var runtime = new ModelRuntimeSettings(store, models, JSON, 120, 60);
        for (var invalid : List.of(options("generic", 0L, null), options("generic", -1L, null),
            options("generic", 86401L, null), options("generic", null, -1L), options("generic", null, 86401L))) {
            assertThrows(IllegalArgumentException.class, () -> runtime.update("m", "https://one.test/v1", invalid));
            assertEquals("", runtime.revision());
        }
        runtime.update("m", "https://one.test/v1", options("generic", 30L, null));
        assertEquals(new ModelRuntimeSettings.RequestTimeouts(30, 30), runtime.current().timeouts());
        runtime.update("m", "https://one.test/v1", options("generic", 180L, 0L));
        assertEquals(new ModelRuntimeSettings.RequestTimeouts(180, 180), runtime.current().timeouts());
        runtime.update("m", "https://one.test/v1", options("generic", null, null));
        assertEquals(new ModelRuntimeSettings.RequestTimeouts(120, 60), runtime.current().timeouts());
        assertEquals(new ModelRuntimeSettings.RequestTimeouts(120, 60), runtime.current().timeoutDefaults());
        var followTotal = new ModelRuntimeSettings(store, models, JSON, 120, 0);
        followTotal.update("m", "https://one.test/v1", options("generic", 180L, null));
        assertEquals(new ModelRuntimeSettings.RequestTimeouts(180, 180), followTotal.current().timeouts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat/completions", "responses", "messages"})
    void productionClientAppliesModelLimitsToBodyAndHotReloadsWithoutChangingPinnedRequests(String protocol)
        throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var delay = new AtomicLong();
        String response = switch (protocol) {
            case "responses" ->
                "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}";
            case "messages" -> "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}";
            default -> "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}";
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/v1/" + protocol, exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        Thread.sleep(delay.get());
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // 超时客户端关闭连接，服务器仍须退出处理并释放资源。
                }
            });
            server.start();
            try {
                String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/" + protocol;
                var store = new InMemorySettingsStore();
                var models = new ModelSettings(new ModelProperties("key", endpoint, "arbitrary-model", null), store);
                var runtime = new ModelRuntimeSettings(store, models, JSON);
                String provider = protocol.equals("messages") ? "anthropic" : "generic";
                runtime.update("arbitrary-model", endpoint, options(provider, 4L, 1L));
                var client = new ModelClientConfiguration().modelClient(models, JSON, runtime, false);
                var pinned = client.snapshot();
                var input = List.of(Message.user("hi"));
                assertEquals("ok", client.chat(input, List.of()).content());
                delay.set(1600);
                assertEquals(ModelFailureKind.NETWORK,
                    assertThrows(ModelClientException.class, () -> client.chat(input, List.of())).kind());
                runtime.update("arbitrary-model", endpoint, options(provider, 5L, 4L));
                assertEquals("ok", client.chat(input, List.of()).content());
                assertEquals(ModelFailureKind.NETWORK,
                    assertThrows(ModelClientException.class, () -> pinned.chat(input, List.of())).kind());
            } finally {
                server.stop(0);
            }
        }
    }
}
