package com.pilot.server.config;

import com.pilot.server.store.InMemorySettingsStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSettingsTest {

    private final InMemorySettingsStore store = new InMemorySettingsStore();
    private final ModelProperties defaults =
            new ModelProperties("env-key", "https://api.example.com/v1", "gpt-default", List.of("gpt-default"));

    @Test
    void fallsBackToConfiguredProperties() {
        var settings = new ModelSettings(defaults, store);

        assertEquals("gpt-default", settings.snapshot().model());
        assertEquals("https://api.example.com/v1", settings.snapshot().endpoint());
        assertTrue(settings.snapshot().hasApiKey());
    }

    @Test
    void updatePersistsAndSurvivesRestart() {
        var settings = new ModelSettings(defaults, store);

        settings.update("https://gateway.test/v1", "gpt-new", "sk-new-key");

        var restarted = new ModelSettings(defaults, store);
        assertEquals("gpt-new", restarted.snapshot().model());
        assertEquals("https://gateway.test/v1", restarted.snapshot().endpoint());
        assertEquals("sk-new-key", restarted.snapshot().apiKey());
    }

    @Test
    void blankFieldsKeepPreviousValues() {
        var settings = new ModelSettings(defaults, store);

        settings.update(null, "only-model", "   ");

        assertEquals("only-model", settings.snapshot().model());
        assertEquals("https://api.example.com/v1", settings.snapshot().endpoint());
        assertEquals("env-key", settings.snapshot().apiKey(), "空白 Key 不应清掉原值");
    }

    @Test
    void rejectsNonHttpEndpoint() {
        var settings = new ModelSettings(defaults, store);

        assertThrows(IllegalArgumentException.class,
                () -> settings.update("file:/etc/passwd", null, null));
        assertEquals("https://api.example.com/v1", settings.snapshot().endpoint(), "失败的更新不应落盘");
    }

    @Test
    void masksApiKeyForDisplay() {
        var settings = new ModelSettings(defaults, store);
        settings.update(null, null, "sk-1234567890abcdef");

        var snapshot = settings.snapshot();
        assertTrue(snapshot.hasApiKey());
        assertFalse(snapshot.maskedApiKey().contains("1234567890abcdef"), "掩码不能泄露完整 Key");
        assertTrue(snapshot.maskedApiKey().endsWith("cdef"));
    }

    @Test
    void reportsMissingKeyWhenBlank() {
        var settings = new ModelSettings(
                new ModelProperties("", "https://api.example.com/v1", "m", List.of("m")), store);

        assertFalse(settings.snapshot().hasApiKey());
        assertEquals("", settings.snapshot().maskedApiKey());
    }
}
