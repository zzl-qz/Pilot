package com.pilot.server.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.pilot.core.event.RunContinued;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.loop.RetryBudget;
import com.pilot.core.message.Message;
import com.pilot.core.model.ModelCapacity;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelClientException;
import com.pilot.core.model.ModelFailureKind;
import com.pilot.core.model.ModelProfile;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.store.SettingsStore;

import tools.jackson.databind.json.JsonMapper;

class ModelFailoverTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static SettingsStore memoryStore() {
        Map<String, String> data = new HashMap<>();
        return new SettingsStore() {
            @Override
            public Optional<String> find(String key) {
                return Optional.ofNullable(data.get(key));
            }

            @Override
            public void save(String key, String value) {
                data.put(key, value);
            }

            @Override
            public void saveAll(Map<String, String> values) {
                data.putAll(values);
            }
        };
    }

    @Test
    void agentLoopFailsOverToBackupProfileOnRateLimitWithoutChangingGlobalActive() {
        SettingsStore store = memoryStore();
        ModelSettings settings = new ModelSettings(
            new ModelProperties("sk-primary", "https://free.example/v1", "free-cloud", List.of()), store);
        ModelProfileStore profiles = new ModelProfileStore(store, settings, MAPPER);
        var created = profiles.create(new ModelProfileStore.SaveRequest("本地 Ollama", "http://127.0.0.1:11434/v1",
            "qwen2.5-coder:14b", "", true, false, null, null, "", false));
        String backupId = created.profiles().getFirst().id();
        profiles.setFallback(backupId);

        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger backupCalls = new AtomicInteger();
        ModelClientProvider provider = new ModelClientProvider(settings, snap -> new ModelClient() {
            @Override
            public ModelProfile profile() {
                return ModelProfile.generic();
            }

            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                if ("free-cloud".equals(snap.model())) {
                    primaryCalls.incrementAndGet();
                    throw ModelClientException.httpError(ModelFailureKind.TRANSIENT, 429, 0L, "HTTP 429 rate limit");
                }
                backupCalls.incrementAndGet();
                return new ModelResponse("本地备用模型已完成回答", List.of(), ModelUsage.unknown(),
                    ModelResponse.FinishReason.STOP);
            }
        }).withFallback(profiles::fallbackTarget);

        List<SessionEvent> events = new ArrayList<>();
        AgentLoop loop = new AgentLoop(provider.forRun(), new ToolRegistry(),
            new PolicyPermissionGate(PermissionMode.READ_ONLY), new AgentLoop.Config(4, 3, "system", List::of))
            .withRetryBudget(new RetryBudget(new RetryBudget.Settings(5, 60_000, 1, 1, 0)));
        AgentLoop.Result result = loop.run("帮我看下", List.of(), events::add);

        assertNull(result.failureCode());
        assertEquals("本地备用模型已完成回答", result.finalText());
        assertEquals(2, primaryCalls.get(), "主模型先重试一次后仍失败即切备用");
        assertEquals(1, backupCalls.get());
        assertTrue(events.stream().anyMatch(e -> e instanceof RunContinued c && c.reason().contains("本地 Ollama")
            && c.reason().contains("qwen2.5-coder:14b")));
        assertEquals("free-cloud", settings.snapshot().model(), "单次运行故障转移不得改写全局主配置");
    }

    @Test
    void immediateFailoverOnQuotaExhaustedAndSkipOnBadRequest400() {
        SettingsStore store = memoryStore();
        ModelSettings settings = new ModelSettings(
            new ModelProperties("sk-primary", "https://free.example/v1", "free-cloud", List.of()), store);
        ModelProfileStore profiles = new ModelProfileStore(store, settings, MAPPER);
        String backupId = profiles.create(new ModelProfileStore.SaveRequest("本地兜底", "http://127.0.0.1:11434/v1",
            "qwen2.5-coder", "", true, false, null, null, "", false)).profiles().getFirst().id();
        profiles.setFallback(backupId);

        ModelClientProvider provider = new ModelClientProvider(settings, snap -> new ModelClient() {
            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                if ("free-cloud".equals(snap.model())) {
                    throw ModelClientException.httpError(ModelFailureKind.PERMANENT, 402, -1L,
                        "insufficient_quota: credit balance is too low");
                }
                return new ModelResponse("quota-fallback-ok", List.of(), ModelUsage.unknown(),
                    ModelResponse.FinishReason.STOP);
            }
        }).withFallback(profiles::fallbackTarget);

        AgentLoop loop = new AgentLoop(provider.forRun(), new ToolRegistry(),
            new PolicyPermissionGate(PermissionMode.READ_ONLY), new AgentLoop.Config(4, 3, "system", List::of))
            .withRetryBudget(new RetryBudget(new RetryBudget.Settings(5, 60_000, 1, 1, 0)));
        AgentLoop.Result result = loop.run("hello", List.of(), ignored -> {
        });
        assertNull(result.failureCode());
        assertEquals("quota-fallback-ok", result.finalText());

        // 400 请求格式错误不应盲目切备用
        ModelClient badRequestRun = provider.forRun();
        assertEquals("", badRequestRun.failover(
            ModelClientException.httpError(ModelFailureKind.PERMANENT, 400, -1L, "HTTP 400 invalid_request")));
        assertTrue(
            ModelClientProvider.isFailoverEligible(
                ModelClientException.httpError(ModelFailureKind.PERMANENT, 404, -1L, "HTTP 404 model_not_found")),
            "主模型下线返回 404 时应允许切换备用预设");
        assertFalse(ModelClientProvider.isFailoverEligible(
            new ModelClientException(ModelFailureKind.CONTEXT_OVERFLOW, "context_length_exceeded")));
        assertFalse(ModelClientProvider
            .isFailoverEligible(new ModelClientException(ModelFailureKind.INTERRUPTED, "cancelled")));
    }

    @Test
    void failoverUsesBackupProfileCapacityInsteadOfPrimaryGlobalCapacity() {
        SettingsStore store = memoryStore();
        ModelSettings settings = new ModelSettings(
            new ModelProperties("sk-primary", "https://free.example/v1", "big-primary", List.of()), store);
        settings.updateWindow(200_000L, 16_000);
        try {
            ModelProfileStore profiles = new ModelProfileStore(store, settings, MAPPER);
            String backupId = profiles.create(new ModelProfileStore.SaveRequest("小窗备模", "http://127.0.0.1:11434/v1",
                "small-backup", "", true, false, 32_000L, 4_000, "", false)).profiles().getFirst().id();
            profiles.setFallback(backupId);

            ModelClientProvider provider = new ModelClientProvider(settings, snap -> new ModelClient() {
                @Override
                public ModelCapacity capacity() {
                    return snap.capacity();
                }

                @Override
                public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                    return new ModelResponse("ok", List.of(), ModelUsage.unknown(), ModelResponse.FinishReason.STOP);
                }
            }).withFallback(profiles::fallbackTarget);

            ModelClient runClient = provider.forRun();
            assertEquals(16_000, runClient.capacity().outputTokens());
            assertFalse(runClient
                .failover(ModelClientException.httpError(ModelFailureKind.PERMANENT, 404, -1L, "model not found"))
                .isBlank());
            assertEquals(32_000L, runClient.capacity().contextTokens());
            assertEquals(4_000, runClient.capacity().outputTokens());
            assertEquals(16_000, settings.snapshot().capacity().outputTokens(), "故障转移后全局主模型容量保持不变");
        } finally {
            ModelCapacity.activate(null);
        }
    }
}
