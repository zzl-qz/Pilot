package com.pilot.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pilot.server.store.InMemorySettingsStore;
import com.pilot.server.store.SettingsStore;

class ModelSettingsTest {

    private final InMemorySettingsStore store = new InMemorySettingsStore();
    private final ModelProperties defaults =
        new ModelProperties("env-key", "https://api.example.com/v1", "gpt-default", List.of("gpt-default"));

    @Test
    void fallsBackToConfiguredProperties() {
        ModelSettings settings = new ModelSettings(defaults, store);

        assertEquals("gpt-default", settings.snapshot().model());
        assertEquals("https://api.example.com/v1", settings.snapshot().endpoint());
        assertTrue(settings.snapshot().hasApiKey());
    }

    @Test
    void updatePersistsAndSurvivesRestart() {
        ModelSettings settings = new ModelSettings(defaults, store);

        settings.update("https://gateway.test/v1", "gpt-new", "sk-new-key");

        ModelSettings restarted = new ModelSettings(defaults, store);
        assertEquals("gpt-new", restarted.snapshot().model());
        assertEquals("https://gateway.test/v1", restarted.snapshot().endpoint());
        assertEquals("sk-new-key", restarted.snapshot().apiKey());
    }

    @Test
    void blankFieldsKeepPreviousValues() {
        ModelSettings settings = new ModelSettings(defaults, store);

        settings.update(null, "only-model", "   ");

        assertEquals("only-model", settings.snapshot().model());
        assertEquals("https://api.example.com/v1", settings.snapshot().endpoint());
        assertEquals("env-key", settings.snapshot().apiKey(), "空白 Key 不应清掉原值");
    }

    @Test
    void explicitlyClearsApiKeyAndPersistsTheClear() {
        ModelSettings settings = new ModelSettings(defaults, store);
        settings.update(null, null, null, true);

        assertFalse(settings.snapshot().hasApiKey());
        assertFalse(new ModelSettings(defaults, store).snapshot().hasApiKey());
    }

    @Test
    void failedAtomicSaveDoesNotPublishNewSnapshot() {
        SettingsStore failingStore = new SettingsStore() {
            @Override
            public Optional<String> find(String key) {
                return Optional.empty();
            }

            @Override
            public void save(String key, String value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void saveAll(Map<String, String> values) {
                throw new IllegalStateException("write failed");
            }
        };
        ModelSettings settings = new ModelSettings(defaults, failingStore);

        assertThrows(IllegalStateException.class,
            () -> settings.update("https://gateway.test/v1", "new-model", "new-key"));
        assertEquals("gpt-default", settings.snapshot().model());
        assertEquals("https://api.example.com/v1", settings.snapshot().endpoint());
        assertEquals("env-key", settings.snapshot().apiKey());
    }

    /** 端点/模型/密钥必须在一次 saveAll 里落盘，否则崩溃时可能留下半套配置。 */
    @Test
    void persistsAllModelKeysInOneAtomicWrite() {
        ArrayList<Map<String, String>> batches = new ArrayList<Map<String, String>>();
        SettingsStore recording = new SettingsStore() {
            private final Map<String, String> values = new HashMap<>();

            @Override
            public Optional<String> find(String key) {
                return Optional.ofNullable(values.get(key));
            }

            @Override
            public void save(String key, String value) {
                throw new AssertionError("模型配置应走 saveAll");
            }

            @Override
            public void saveAll(Map<String, String> updates) {
                batches.add(Map.copyOf(updates));
                values.putAll(updates);
            }
        };
        ModelSettings settings = new ModelSettings(defaults, recording);

        settings.update("https://gateway.test/v1", "gpt-new", "sk-1");

        assertEquals(1, batches.size(), "一次更新只应有一次批量写入");
        assertEquals(Set.of("model.endpoint", "model.name", "model.api-key"), batches.get(0).keySet());
        assertEquals("sk-1", batches.get(0).get("model.api-key"));
    }

    @Test
    void rejectsNonHttpEndpoint() {
        ModelSettings settings = new ModelSettings(defaults, store);

        assertThrows(IllegalArgumentException.class, () -> settings.update("file:/etc/passwd", null, null));
        assertEquals("https://api.example.com/v1", settings.snapshot().endpoint(), "失败的更新不应落盘");
    }

    @Test
    void masksApiKeyForDisplay() {
        ModelSettings settings = new ModelSettings(defaults, store);
        settings.update(null, null, "sk-1234567890abcdef");

        ModelSettings.Snapshot snapshot = settings.snapshot();
        assertTrue(snapshot.hasApiKey());
        assertFalse(snapshot.maskedApiKey().contains("1234567890abcdef"), "掩码不能泄露完整 Key");
        assertTrue(snapshot.maskedApiKey().endsWith("cdef"));
    }

    @Test
    void reportsMissingKeyWhenBlank() {
        ModelSettings settings =
            new ModelSettings(new ModelProperties("", "https://api.example.com/v1", "m", List.of("m")), store);

        assertFalse(settings.snapshot().hasApiKey());
        assertEquals("", settings.snapshot().maskedApiKey());
    }
}
