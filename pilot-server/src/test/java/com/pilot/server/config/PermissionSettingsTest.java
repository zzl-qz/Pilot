package com.pilot.server.config;

import com.pilot.core.permission.PermissionMode;
import com.pilot.server.store.InMemorySettingsStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionSettingsTest {
    @Test
    void fallsBackToConfiguredModeWhenNothingSaved() {
        PermissionSettings settings = new PermissionSettings(properties(PermissionMode.READ_ONLY), new InMemorySettingsStore());

        assertEquals(PermissionMode.READ_ONLY, settings.mode());
    }

    @Test
    void savedValueWinsOverConfiguredDefault() {
        InMemorySettingsStore store = new InMemorySettingsStore();
        store.save("agent.permission-mode", "AUTO_EDIT");

        PermissionSettings settings = new PermissionSettings(properties(PermissionMode.READ_ONLY), store);

        assertEquals(PermissionMode.AUTO_EDIT, settings.mode());
    }

    @Test
    void updateAppliesImmediatelyAndPersists() {
        InMemorySettingsStore store = new InMemorySettingsStore();
        PermissionSettings settings = new PermissionSettings(properties(PermissionMode.READ_ONLY), store);

        settings.update(PermissionMode.ASK);

        assertEquals(PermissionMode.ASK, settings.mode());
        // 模拟重启：新的实例应从存储里恢复
        assertEquals(PermissionMode.ASK, new PermissionSettings(properties(PermissionMode.READ_ONLY), store).mode());
    }

    @Test
    void unrecognizedSavedValueFallsBackToConfiguredDefault() {
        InMemorySettingsStore store = new InMemorySettingsStore();
        store.save("agent.permission-mode", "ROOT");

        PermissionSettings settings = new PermissionSettings(properties(PermissionMode.ASK), store);

        assertEquals(PermissionMode.ASK, settings.mode());
    }

    private static AgentProperties properties(PermissionMode mode) {
        return new AgentProperties("E:\\work", mode, 120, 3, 5, 300, 4, 3, 120_000, true);
    }
}
