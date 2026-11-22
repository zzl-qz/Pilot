package com.pilot.core.loop;

import java.util.Map;
import java.util.Set;

import com.pilot.core.prompt.PromptLibrary;

/** Agent 循环使用的提示词模板目录入口。 */
public final class LoopPromptCatalog {
    private LoopPromptCatalog() {
    }

    public static String defaultSystemPrompt() {
        PromptLibrary prompts = PromptLibrary.standard();
        return prompts.system(PromptLibrary.environment(null), Set.of());
    }

    public static String render(String template, Map<String, String> values) {
        return PromptLibrary.standard().render(template, values);
    }
}
