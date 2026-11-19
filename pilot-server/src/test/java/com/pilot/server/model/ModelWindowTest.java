package com.pilot.server.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pilot.core.model.ModelCapacity;
import com.pilot.server.store.InMemorySettingsStore;

class ModelWindowTest {
    @Test
    void blankWindowKeepsTheDefaultAndASavedWindowIsRestored() {
        InMemorySettingsStore store = new InMemorySettingsStore();
        ModelProperties defaults = new ModelProperties("", "https://api.example.com/v1", "gpt", List.of("gpt"));
        ModelSettings settings = new ModelSettings(defaults, store);
        assertNull(settings.snapshot().contextTokens());
        assertEquals(128_000L, settings.snapshot().capacity().contextTokens());

        settings.updateWindow(200_000L, 8_000);
        ModelSettings restored = new ModelSettings(defaults, store);
        assertEquals(200_000L, restored.snapshot().contextTokens());
        assertEquals(8_000, restored.snapshot().maxOutputTokens());
        assertEquals(200_000L, ModelCapacity.active().contextTokens());

        settings.updateWindow(0L, 0);
        assertNull(settings.snapshot().contextTokens());
        ModelCapacity.activate(null);
    }

    @Test
    void oversizedOutputDoesNotRejectStartup() {
        ModelCapacity capacity = ModelCapacity.of(32_000L, 100_000);
        org.junit.jupiter.api.Assertions.assertTrue(capacity.outputTokens() < capacity.contextTokens());
    }
}
