package com.pilot.core.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.Test;

class LoopPromptCatalogTest {
    @Test
    void rendersLoopTemplateAndDefaultSystemPrompt() {
        assertFalse(LoopPromptCatalog.defaultSystemPrompt().isBlank());
        assertFalse(LoopPromptCatalog.render("loop/budget-approaching", Map.of()).isBlank());
    }
}
