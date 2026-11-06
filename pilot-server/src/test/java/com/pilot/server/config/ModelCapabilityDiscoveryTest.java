package com.pilot.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.Message;
import com.pilot.core.model.MeteredModelClient;
import com.pilot.core.model.NativeCapability;
import com.pilot.core.model.NativeToolRejectedException;
import com.pilot.core.model.RequestReport;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.store.InMemorySettingsStore;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ModelCapabilityDiscoveryTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String SEARCH_OK =
        "{\"status\":\"completed\",\"output\":[{\"type\":\"web_search_call\",\"status\":\"completed\"}],\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}";

    @Test
    void verifiesActualEndpointCoalescesAndRestoresBoundedCredentialIsolatedCache() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var store = new InMemorySettingsStore();
            var clock = new MutableClock();
            try (var service = discovery(store, clock)) {
                var target = fixture.target("opaque-alias", "secret-a");
                assertEquals("checking", service.inspect(target, "auto").state());
                for (int i = 0; i < 20; i++) {
                    service.inspect(target, "auto");
                }
                var report = await(service, target, "auto");
                assertEquals("verified", report.state());
                assertEquals("openai-responses", report.protocol());
                assertEquals(Set.of(NativeCapability.WEB_SEARCH), report.capabilities());
                assertEquals(1, report.probeRequests());
                assertEquals(12, report.inputTokens());
                assertEquals(1, fixture.responseProbes.get());
                assertTrue(fixture.paths.stream().allMatch(path -> path.startsWith("/llm/v1/")));
                String persisted = store.find("model.capability-cache.v1").orElseThrow();
                assertFalse(persisted.contains("secret-a"));
                assertFalse(persisted.contains(fixture.url()));
                try (var restored = discovery(store, clock)) {
                    assertEquals("verified", restored.inspect(target, "auto").state());
                }
                assertEquals(1, fixture.responseProbes.get());
                await(service, fixture.target("opaque-alias", "secret-b"), "auto");
                await(service, fixture.target("other-model", "secret-b"), "auto");
                assertEquals(3, fixture.responseProbes.get());
                clock.now.addAndGet(Duration.ofHours(7).toMillis());
                await(service, target, "auto");
                assertEquals(4, fixture.responseProbes.get());
            }
        }
    }

    @Test
    void modelNameMetadataClaimsAndSuccessfulPlainTextDoNotProveToolExecution() throws Exception {
        try (Fixture fixture = new Fixture();
            var service = discovery(new InMemorySettingsStore(), new MutableClock())) {
            fixture.responses =
                "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"I searched the web\"}]}]}";
            fixture.messagesCode = 200;
            fixture.messages = "{\"content\":[{\"type\":\"text\",\"text\":\"supported\"}]}";
            var report = await(service, fixture.target("gpt-5-official-looking", "key"), "auto");
            assertEquals("unknown", report.state());
            assertTrue(report.capabilities().isEmpty());
            assertEquals(3, report.probeRequests());
        }
    }

    @Test
    void discoversMessagesAndFetchWithoutManualProviderAndMatchesResultIds() throws Exception {
        try (Fixture fixture = new Fixture();
            var service = discovery(new InMemorySettingsStore(), new MutableClock())) {
            fixture.responsesCode = 404;
            fixture.responses = "{\"error\":\"route not found\"}";
            fixture.messagesCode = 200;
            fixture.autoMessages = true;
            var report = await(service, fixture.target("private-alias", "key"), "auto");
            assertEquals("anthropic-messages", report.protocol());
            assertEquals(Set.of(NativeCapability.WEB_SEARCH, NativeCapability.WEB_FETCH), report.capabilities());
            assertFalse(ModelCapabilityDiscovery.executed(JSON.readTree(
                "{\"content\":[{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"unmatched\",\"content\":[]}]}"),
                "anthropic-messages", NativeCapability.WEB_SEARCH));
        }
    }

    @Test
    void authRateLimitAndServerFailuresStayUnknownAndAreNotRepeatedPerRequest() throws Exception {
        for (int code : List.of(401, 402, 403, 429, 503)) {
            try (Fixture fixture = new Fixture();
                var service = discovery(new InMemorySettingsStore(), new MutableClock())) {
                fixture.responsesCode = code;
                fixture.responses = "{\"error\":\"redacted\"}";
                var target = fixture.target("alias", "key");
                var report = await(service, target, "auto");
                assertEquals("unknown", report.state());
                assertEquals(0, fixture.messageProbes.get());
                service.inspect(target, "auto");
                assertEquals(1, fixture.responseProbes.get());
                assertFalse(report.message().contains("key"));
            }
        }
    }

    @Test
    void definiteRejectionCachesNegativeResultsButTimeoutAndIgnoredToolsExpireSooner() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var clock = new MutableClock();
            try (var service = discovery(new InMemorySettingsStore(), clock)) {
                fixture.responsesCode = 400;
                fixture.responses = "{\"error\":\"unsupported web_search\"}";
                var target = fixture.target("alias", "key");
                var negative = await(service, target, "auto");
                assertEquals("unsupported", negative.state());
                int before = fixture.responseProbes.get();
                service.inspect(target, "auto");
                assertEquals(before, fixture.responseProbes.get());
                clock.now.addAndGet(Duration.ofMinutes(31).toMillis());
                fixture.responsesCode = 200;
                fixture.responses = SEARCH_OK;
                assertEquals("verified", await(service, target, "auto").state());
            }
        }
    }

    @Test
    void discoveryDoesNotFollowRedirectsOrAcceptUnboundedBodies() throws Exception {
        try (Fixture fixture = new Fixture(); Fixture destination = new Fixture()) {
            fixture.redirect = destination.url();
            try (var service = discovery(new InMemorySettingsStore(), new MutableClock())) {
                assertEquals("unknown", await(service, fixture.target("alias", "secret"), "auto").state());
                assertEquals(0, destination.requests.get());
            }
            fixture.redirect = null;
            fixture.responses = "x".repeat(1_048_577);
            try (var service = discovery(new InMemorySettingsStore(), new MutableClock())) {
                assertEquals("unknown", await(service, fixture.target("alias", "key"), "auto").state());
            }
        }
    }

    @Test
    void slowBodiesHaveDeadlineAndOnlyTwoDiscoveriesCanRunAtOnce() throws Exception {
        try (Fixture fixture = new Fixture(); var service = new ModelCapabilityDiscovery(new InMemorySettingsStore(),
            JSON, HttpClient.newHttpClient(), new MutableClock(), Duration.ofMillis(150))) {
            fixture.delayBody = true;
            var first = fixture.target("first", "key");
            var second = fixture.target("second", "key");
            assertEquals("checking", service.inspect(first, "auto").state());
            assertEquals("checking", service.inspect(second, "auto").state());
            assertEquals("unknown", service.inspect(fixture.target("third", "key"), "auto").state());
            assertEquals("unknown", await(service, first, "auto").state());
        }
    }

    @Test
    void runtimeRejectionCannotBeOverwrittenByLateProbeAndCacheIsBounded() throws Exception {
        var store = new InMemorySettingsStore();
        try (Fixture fixture = new Fixture(); var service = discovery(store, new MutableClock())) {
            fixture.gate = new CountDownLatch(1);
            var target = fixture.target("alias", "key");
            service.inspect(target, "auto");
            service.reject(target, "auto", "openai-responses");
            fixture.gate.countDown();
            assertEquals("unsupported", service.inspect(target, "auto").state());
            for (int i = 0; i < 70; i++) {
                var next = fixture.target("alias-" + i, "key");
                service.inspect(next, "auto");
                await(service, next, "auto");
            }
            service.reject(target, "auto", "openai-responses");
            assertTrue(JSON.readTree(store.find("model.capability-cache.v1").orElseThrow()).size() <= 64);
        }
    }

    @Test
    void agentFallsBackInSameRunRestoresLocalDefinitionAndMetersEveryAttempt() throws Exception {
        try (Fixture fixture = new Fixture();
            var service = discovery(new InMemorySettingsStore(), new MutableClock())) {
            var store = new InMemorySettingsStore();
            var models = new ModelSettings(new ModelProperties("key", fixture.url(), "private-model", null), store);
            var runtime = new ModelRuntimeSettings(store, models, JSON);
            runtime.setCapabilityDiscovery(service);
            await(service, models.snapshot(), "auto");
            fixture.rejectBusiness = true;
            var client = new DiscoveredModelClient(models.snapshot(), runtime, HttpClient.newHttpClient(), JSON,
                Duration.ofSeconds(2), false);
            var reports = new ArrayList<RequestReport>();
            var localCalls = new AtomicInteger();
            ToolRegistry registry = new ToolRegistry();
            registry.register(new Tool() {
                public String name() {
                    return "web_search";
                }

                public String description() {
                    return "Search locally";
                }

                public Map<String, Object> parameters() {
                    return Map.of("type", "object");
                }

                public boolean readOnly() {
                    return true;
                }

                public String execute(Map<String, Object> args) {
                    localCalls.incrementAndGet();
                    return "source from local";
                }
            });
            var loop = new AgentLoop(new MeteredModelClient(client, "test", "main", List.of(), reports::add), registry,
                new PolicyPermissionGate(PermissionMode.AUTO), new AgentLoop.Config(5, 3, "test"));
            var result = loop.run("research", List.of(), ignored -> {
            });
            assertNull(result.failureCode());
            assertEquals("done", result.finalText());
            assertEquals(1, localCalls.get());
            assertEquals(3, reports.size());
            assertEquals("failed", reports.getFirst().status());
            assertEquals("unsupported", service.inspect(models.snapshot(), "auto").state());
            assertTrue(fixture.localDefinitionSeen);
            assertFalse(client.disableRejectedNativeTools(new NativeToolRejectedException("second")));
        }
    }

    @Test
    void firstTaskAutomaticallySelectsMessagesWhileBackgroundVerificationIsPending() throws Exception {
        try (Fixture fixture = new Fixture();
            var service = discovery(new InMemorySettingsStore(), new MutableClock())) {
            fixture.gate = new CountDownLatch(1);
            fixture.chatRouteMissing = true;
            var store = new InMemorySettingsStore();
            var models = new ModelSettings(new ModelProperties("key", fixture.url(), "alias", null), store);
            var runtime = new ModelRuntimeSettings(store, models, JSON);
            runtime.setCapabilityDiscovery(service);
            var client = new DiscoveredModelClient(models.snapshot(), runtime, HttpClient.newHttpClient(), JSON,
                Duration.ofSeconds(2), false);
            var loop = new AgentLoop(client, new ToolRegistry(), new PolicyPermissionGate(PermissionMode.AUTO),
                new AgentLoop.Config(3, 3, "test"));
            var result = loop.run("research", List.of(), ignored -> {
            });
            assertNull(result.failureCode());
            assertEquals("done", result.finalText());
            assertEquals("anthropic-messages", service.inspect(models.snapshot(), "auto").protocol());
            fixture.gate.countDown();
        }
    }

    @Test
    void manualLocalModeAndGlobalDisableDoNotProbeAndConfigChangesTriggerAutomatically() throws Exception {
        try (Fixture fixture = new Fixture();
            var service = discovery(new InMemorySettingsStore(), new MutableClock())) {
            var models = new ModelSettings(new ModelProperties("key", fixture.url(), "alias", null),
                new InMemorySettingsStore());
            var runtime = new ModelRuntimeSettings(new InMemorySettingsStore(), models, JSON);
            runtime.setCapabilityDiscovery(service);
            runtime.update("alias", fixture.url(),
                new ModelRuntimeSettings.Options("auto", "auto", "", null, null, "short", "local", null));
            assertTrue(runtime.nativeCapabilities(models.snapshot()).isEmpty());
            assertEquals(0, fixture.requests.get());
            runtime.update("alias", fixture.url(), ModelRuntimeSettings.Options.defaults());
            assertEquals("verified", await(service, models.snapshot(), "auto").state());
            models.update(null, "new-alias", "new-key");
            assertEquals("verified", await(service, models.snapshot(), "auto").state());
            int requests = fixture.requests.get();
            service.setEnabled(false);
            assertEquals("disabled", service.inspect(fixture.target("disabled", "key"), "auto").state());
            assertEquals(requests, fixture.requests.get());
        }
    }

    @Test
    void existingClientAutomaticallyRecoversAfterNegativeCacheExpires() throws Exception {
        var clock = new MutableClock();
        try (Fixture fixture = new Fixture(); var service = discovery(new InMemorySettingsStore(), clock)) {
            var store = new InMemorySettingsStore();
            var models = new ModelSettings(new ModelProperties("key", fixture.url(), "alias", null), store);
            var runtime = new ModelRuntimeSettings(store, models, JSON);
            runtime.setCapabilityDiscovery(service);
            await(service, models.snapshot(), "auto");
            var client = new DiscoveredModelClient(models.snapshot(), runtime, HttpClient.newHttpClient(), JSON,
                Duration.ofSeconds(2), false);
            client.chat(List.of(Message.user("check")), List.of());
            assertTrue(client.disableRejectedNativeTools(new NativeToolRejectedException("unsupported web_search")));
            assertTrue(client.nativeCapabilities().isEmpty());
            clock.now.addAndGet(Duration.ofMinutes(31).toMillis());
            await(service, models.snapshot(), "auto");
            assertEquals(Set.of(NativeCapability.WEB_SEARCH), client.nativeCapabilities());
        }
    }

    @Test
    void invalidRequestAndMissingModelDoNotClaimCapabilityIsUnsupported() throws Exception {
        for (int code : List.of(400, 404)) {
            try (Fixture fixture = new Fixture();
                var service = discovery(new InMemorySettingsStore(), new MutableClock())) {
                fixture.responsesCode = code;
                fixture.responses = "{\"error\":\"model not found or invalid request field\"}";
                assertEquals("unknown", await(service, fixture.target("alias", "key"), "auto").state());
            }
        }
    }

    @Test
    void preservesExplicitProtocolRootsQueryAndLegacyCustomChatPath() throws Exception {
        assertEquals("https://relay.test/responses?tenant=a", ModelCapabilityDiscovery
            .endpoint("https://relay.test/chat/completions?tenant=a", "openai-responses").toString());
        assertEquals("https://relay.test/api/messages",
            ModelCapabilityDiscovery.endpoint("https://relay.test/api/responses", "anthropic-messages").toString());
        try (Fixture fixture = new Fixture();
            var service = discovery(new InMemorySettingsStore(), new MutableClock())) {
            service.setEnabled(false);
            var store = new InMemorySettingsStore();
            String endpoint = fixture.url().substring(0, fixture.url().indexOf("/llm/")) + "/opaque-chat";
            var models = new ModelSettings(new ModelProperties("key", endpoint, "alias", null), store);
            var runtime = new ModelRuntimeSettings(store, models, JSON);
            runtime.setCapabilityDiscovery(service);
            var client = new DiscoveredModelClient(models.snapshot(), runtime, HttpClient.newHttpClient(), JSON,
                Duration.ofSeconds(2), false);
            assertEquals("done", client.chat(List.of(Message.user("hello")), List.of()).content());
            assertEquals(List.of("/opaque-chat"), fixture.paths);
        }
    }

    private static ModelCapabilityDiscovery discovery(InMemorySettingsStore store, Clock clock) {
        return new ModelCapabilityDiscovery(store, JSON, HttpClient.newHttpClient(), clock, Duration.ofSeconds(1));
    }

    private static ModelCapabilityDiscovery.Report await(ModelCapabilityDiscovery service,
        ModelSettings.Snapshot target, String hint) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        ModelCapabilityDiscovery.Report report;
        do {
            report = service.inspect(target, hint);
            if (!"checking".equals(report.state())) {
                return report;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("后台验证未结束");
    }

    private static final class MutableClock extends Clock {
        final AtomicLong now = new AtomicLong(1_800_000_000_000L);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger responseProbes = new AtomicInteger();
        final AtomicInteger messageProbes = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        final List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile int responsesCode = 200;
        volatile String responses = SEARCH_OK;
        volatile int messagesCode = 404;
        volatile String messages = "{\"error\":\"route not found\"}";
        volatile boolean chatRouteMissing;
        volatile boolean autoMessages;
        volatile boolean rejectBusiness;
        volatile boolean localDefinitionSeen;
        volatile String redirect;
        volatile boolean delayBody;
        volatile CountDownLatch gate;

        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                paths.add(path);
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode body = raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
                int code = 200;
                String response = "{\"data\":[{\"id\":\"alias\",\"supported_parameters\":[\"tools\"]}]}";
                if (gate != null && (exchange.getRequestMethod().equals("GET") || body.has("tool_choice"))) {
                    try {
                        gate.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (path.equals("/opaque-chat")) {
                    response = "{\"choices\":[{\"message\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}";
                }
                if (chatRouteMissing && path.endsWith("/chat/completions")) {
                    code = 404;
                    response = "{\"error\":\"route not found\"}";
                }
                if (path.endsWith("/responses")) {
                    responseProbes.incrementAndGet();
                    code = responsesCode;
                    response = responses;
                    if (rejectBusiness && body.path("input").isArray()) {
                        boolean nativeTool = false;
                        for (JsonNode tool : body.path("tools")) {
                            nativeTool |= "web_search".equals(tool.path("type").asString());
                        }
                        if (nativeTool) {
                            code = 400;
                            response = "{\"error\":{\"message\":\"unsupported tool web_search\"}}";
                        } else {
                            localDefinitionSeen = body.path("tools").toString().contains("Search locally");
                            if (body.path("input").toString().contains("function_call_output")) {
                                response =
                                    "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]}]}";
                            } else {
                                response =
                                    "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"call_id\":\"local-1\",\"name\":\"web_search\",\"arguments\":\"{}\"}]}";
                            }
                        }
                    }
                }
                if (path.endsWith("/messages")) {
                    if (chatRouteMissing && !body.has("tool_choice")) {
                        byte[] done =
                            "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}"
                                .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, done.length);
                        try (var out = exchange.getResponseBody()) {
                            out.write(done);
                        }
                        return;
                    }
                    messageProbes.incrementAndGet();
                    code = messagesCode;
                    response = messages;
                    if (autoMessages) {
                        String name = body.path("tools").get(0).path("name").asString();
                        Object content = "web_search".equals(name) ? List.of()
                            : Map.of("type", "web_fetch_result", "content", Map.of("type", "document"));
                        response = JSON.writeValueAsString(Map.of("stop_reason", "end_turn", "content",
                            List.of(Map.of("type", "server_tool_use", "id", "srv-1", "name", name),
                                Map.of("type", name + "_tool_result", "tool_use_id", "srv-1", "content", content))));
                    }
                }
                if (redirect != null) {
                    code = 302;
                    exchange.getResponseHeaders().set("Location", redirect);
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    if (delayBody) {
                        out.write(bytes, 0, 1);
                        out.flush();
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        out.write(bytes, 1, bytes.length - 1);
                    } else {
                        out.write(bytes);
                    }
                } catch (IOException ignored) {
                    /* 测试客户端超时或限制大小时正常断开。 */ }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/llm/v1/chat/completions?tenant=test";
        }

        ModelSettings.Snapshot target(String model, String key) {
            return new ModelSettings.Snapshot(url(), model, key);
        }

        @Override
        public void close() {
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
