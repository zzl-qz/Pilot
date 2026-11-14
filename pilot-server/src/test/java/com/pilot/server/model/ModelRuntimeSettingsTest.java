package com.pilot.server.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.pilot.core.loop.Message;
import com.pilot.core.model.MeteredModelClient;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelPricing;
import com.pilot.core.model.ModelProfile;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.model.NativeCapability;
import com.pilot.core.model.RequestReport;
import com.pilot.core.model.ToolDefinition;
import com.pilot.server.store.InMemorySettingsStore;

import tools.jackson.databind.json.JsonMapper;

class ModelRuntimeSettingsTest {
    @Test
    void persistsPerEndpointAndModelAndRejectsStaleOrInvalidUpdates() {
        var store = new InMemorySettingsStore();
        var models = new ModelSettings(
            new ModelProperties("test", "https://one.test/v1", "model-a", List.of("model-a", "model-b")), store);
        var runtime = new ModelRuntimeSettings(store, models, new JsonMapper());
        var options = new ModelRuntimeSettings.Options("generic", "auto", "", false, true, "short",
            new ModelPricing("CNY", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN, "测试报价"));
        runtime.update("model-a", "https://one.test/v1", options);
        assertEquals(options, new ModelRuntimeSettings(store, models, new JsonMapper()).current().options());
        models.update(null, "model-b", null);
        assertNull(runtime.current().options().pricing().input());
        assertThrows(IllegalArgumentException.class, () -> runtime.update("model-a", "https://one.test/v1", options));
        models.update("https://two.test/v1", "model-a", null);
        assertNull(runtime.current().options().pricing().input());
        models.update("https://one.test/v1", "model-a", null);
        assertEquals(options, runtime.current().options());
        assertThrows(IllegalArgumentException.class, () -> runtime.update("model-a", "https://one.test/v1",
            new ModelRuntimeSettings.Options("generic", "explicit", "", null, null, "short", null)));
        assertEquals(options, runtime.current().options());
    }

    @Test
    void hotReloadForwardsStreamingAndPinsPricingToActualRequest() {
        var store = new InMemorySettingsStore();
        var models = new ModelSettings(new ModelProperties("test", "https://one.test/v1", "model-a", null), store);
        var runtime = new ModelRuntimeSettings(store, models, new JsonMapper());
        var built = new AtomicInteger();
        var provider = new ModelClientProvider(models, snapshot -> {
            built.incrementAndGet();
            var profile = runtime.profile(snapshot);
            return new ModelClient() {
                public ModelProfile profile() {
                    return profile;
                }

                public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                    fail("streaming overload lost");
                    return null;
                }

                public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> text,
                    Consumer<String> reasoning) {
                    text.accept("text");
                    reasoning.accept("reasoning");
                    return new ModelResponse("text", List.of(), new ModelUsage(1000, 10, 0, 0, 0, true));
                }
            };
        }, runtime::revision);
        var before = provider.snapshot();
        runtime.update("model-a", "https://one.test/v1",
            new ModelRuntimeSettings.Options("generic", "auto", "", null, null, "short",
                new ModelPricing("USD", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, "test")));
        var reports = new ArrayList<RequestReport>();
        var output = new ArrayList<String>();
        new MeteredModelClient(provider, "session", "main", List.of(), reports::add).chat(List.of(Message.user("hi")),
            List.of(), output::add, output::add);
        assertEquals(List.of("text", "reasoning"), output);
        assertEquals(2, built.get());
        assertNull(before.profile().pricing().input());
        assertNotNull(reports.getFirst().cost().total());
        assertNotNull(reports.getFirst().firstTokenMs());
    }

    @Test
    void autoSearchCapabilityIsLimitedToKnownOfficialOpenAiModelsAndCanBeOverridden() {
        var store = new InMemorySettingsStore();
        var models = new ModelSettings(
            new ModelProperties("key", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini", null), store);
        var runtime = new ModelRuntimeSettings(store, models, new JsonMapper());

        assertTrue(runtime.nativeWebSearch(models.snapshot()));
        models.update("https://gateway.test/v1/chat/completions", "gpt-4o-mini", null);
        assertFalse(runtime.nativeWebSearch(models.snapshot()),
            "compatible gateway must not inherit vendor capabilities");
        runtime.update("gpt-4o-mini", "https://gateway.test/v1/chat/completions",
            new ModelRuntimeSettings.Options("openai", "auto", "", null, null, "short", "openai-responses", null));
        assertTrue(runtime.nativeWebSearch(models.snapshot()),
            "manual mode enables only the confirmed Responses protocol");
        runtime.update("gpt-4o-mini", "https://gateway.test/v1/chat/completions",
            new ModelRuntimeSettings.Options("openai", "auto", "", null, null, "short", "local", null));
        assertFalse(runtime.nativeWebSearch(models.snapshot()));
    }

    @Test
    void autoDetectsAnthropicSearchWhileKeepingWebFetchInPilot() {
        var store = new InMemorySettingsStore();
        var models = new ModelSettings(
            new ModelProperties("key", "https://api.anthropic.com/v1/messages", "claude-sonnet-5-5", null), store);
        var runtime = new ModelRuntimeSettings(store, models, new JsonMapper());

        assertEquals(Set.of(NativeCapability.WEB_SEARCH), runtime.nativeCapabilities(models.snapshot()));
        assertTrue(runtime.anthropicMessages(models.snapshot()));
        assertThrows(IllegalArgumentException.class,
            () -> runtime.update("claude-sonnet-5-5", "https://api.anthropic.com/v1/messages",
                new ModelRuntimeSettings.Options("auto", "auto", "", null, null, "short", "openai-responses", null)));
    }

    @Test
    void anthropicModelsOutsideTheVerifiedToolMatrixKeepLocalSearch() {
        var store = new InMemorySettingsStore();
        var models = new ModelSettings(
            new ModelProperties("key", "https://api.anthropic.com/v1/messages", "claude-haiku-5-5", null), store);
        var runtime = new ModelRuntimeSettings(store, models, new JsonMapper());

        assertTrue(runtime.anthropicMessages(models.snapshot()));
        assertTrue(runtime.nativeCapabilities(models.snapshot()).isEmpty());
    }

    @Test
    void autoSearchDoesNotGuessUnknownModelFamilies() {
        var store = new InMemorySettingsStore();
        var models =
            new ModelSettings(new ModelProperties("key", "https://api.openai.com/v1", "gpt-6-sol", null), store);
        var runtime = new ModelRuntimeSettings(store, models, new JsonMapper());

        assertFalse(runtime.nativeWebSearch(models.snapshot()));
    }

    @Test
    void manualResponsesModeRejectsAnthropicHostEvenWithGenericProvider() {
        var store = new InMemorySettingsStore();
        var models = new ModelSettings(
            new ModelProperties("key", "https://api.anthropic.com/v1/messages", "claude-sonnet-5-5", null), store);
        var runtime = new ModelRuntimeSettings(store, models, new JsonMapper());

        assertThrows(IllegalArgumentException.class,
            () -> runtime.update("claude-sonnet-5-5", "https://api.anthropic.com/v1/messages",
                new ModelRuntimeSettings.Options("generic", "auto", "", null, null, "short", "openai-responses", null)),
            "官方 Anthropic 主机名不能被通用兼容端点配置绕过协议校验");
        assertTrue(runtime.anthropicMessages(models.snapshot()), "默认 auto 仍按端点识别 Messages 协议");
    }
}
