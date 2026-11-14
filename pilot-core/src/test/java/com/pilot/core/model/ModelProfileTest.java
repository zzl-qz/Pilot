package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.junit.jupiter.api.Test;

/** 供应商、缓存、压缩这些取值都存进了用户设置，常量的值和自动识别结果不能变。 */
class ModelProfileTest {

    @Test
    void settingValuesStayStable() {
        assertEquals("auto", ModelProfile.AUTO);
        assertEquals("openai", ModelProfile.PROVIDER_OPENAI);
        assertEquals("deepseek", ModelProfile.PROVIDER_DEEPSEEK);
        assertEquals("anthropic", ModelProfile.PROVIDER_ANTHROPIC);
        assertEquals("anthropic-compatible", ModelProfile.PROVIDER_ANTHROPIC_COMPATIBLE);
        assertEquals("generic", ModelProfile.PROVIDER_GENERIC);
        assertEquals("explicit", ModelProfile.CACHE_EXPLICIT);
        assertEquals("none", ModelProfile.CACHE_NONE);
        assertEquals("implicit", ModelProfile.CACHE_IMPLICIT);
        assertEquals("short", ModelProfile.COMPACTION_SHORT);
        assertEquals("prefix", ModelProfile.COMPACTION_PREFIX);
    }

    @Test
    void detectsProviderFromOfficialHost() {
        assertEquals("openai", auto("https://api.openai.com/v1/chat/completions").provider());
        assertEquals("deepseek", auto("https://api.deepseek.com/chat/completions").provider());
        assertEquals("anthropic", auto("https://api.anthropic.com/v1/messages").provider());
        assertEquals("generic", auto("https://llm.example.com/v1/chat/completions").provider());
        assertEquals("generic", ModelProfile.generic().provider());
    }

    @Test
    void autoCacheModeFollowsProvider() {
        assertEquals("implicit", auto("https://api.openai.com/v1/chat/completions").cacheMode());
        assertEquals("none", auto("https://api.anthropic.com/v1/messages").cacheMode());
        assertEquals("explicit", ModelProfile.resolve(URI.create("https://llm.example.com/v1"), "m",
            "anthropic-compatible", "auto", "", null, null, "short", null).cacheMode());
    }

    @Test
    void rejectsUnknownSettingValues() {
        URI endpoint = URI.create("https://llm.example.com/v1");
        assertThrows(IllegalArgumentException.class,
            () -> ModelProfile.resolve(endpoint, "m", "azure", "auto", "", null, null, "short", null));
        assertThrows(IllegalArgumentException.class,
            () -> ModelProfile.resolve(endpoint, "m", "auto", "always", "", null, null, "short", null));
        assertThrows(IllegalArgumentException.class,
            () -> ModelProfile.resolve(endpoint, "m", "auto", "auto", "", null, null, "long", null));
        assertThrows(IllegalArgumentException.class,
            () -> ModelProfile.resolve(endpoint, "m", "generic", "explicit", "", null, null, "short", null));
    }

    private static ModelProfile auto(String endpoint) {
        return ModelProfile.resolve(URI.create(endpoint), "m", "auto", "auto", "", null, null, "short", null);
    }
}
