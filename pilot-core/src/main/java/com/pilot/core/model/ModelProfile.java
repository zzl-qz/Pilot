package com.pilot.core.model;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** 端点能力快照。模型别名不能作为发送厂商专有参数的授权。 */
public record ModelProfile(String provider, String model, String cacheMode, String cacheTtl,
    boolean instructionMessages, boolean streamUsage, boolean cacheKey, String compactionMode, ModelPricing pricing) {
    public ModelProfile {
        pricing = pricing == null ? ModelPricing.unknown() : pricing;
    }

    public static ModelProfile generic() {
        return resolve(null, "", "auto", "auto", "", null, null, "short", null);
    }

    public static ModelProfile resolve(URI endpoint, String model, String provider, String cacheMode, String ttl,
        Boolean instructionMessages, Boolean streamUsage, String compactionMode, ModelPricing pricing) {
        String host = endpoint == null || endpoint.getHost() == null ? "" : endpoint.getHost().toLowerCase(Locale.ROOT);
        String detected = "api.openai.com".equals(host) ? "openai"
            : "api.deepseek.com".equals(host) ? "deepseek"
                : "api.anthropic.com".equals(host) ? "anthropic" : "generic";
        String actual = provider == null || provider.equals("auto") ? detected : provider;
        if (!Set.of("openai", "deepseek", "anthropic", "anthropic-compatible", "generic").contains(actual)) {
            throw new IllegalArgumentException("不支持的模型端点类型");
        }
        String mode = cacheMode == null ? "auto" : cacheMode;
        if (!Set.of("auto", "explicit", "none").contains(mode)) {
            throw new IllegalArgumentException("不支持的缓存策略");
        }
        if (mode.equals("explicit") && !actual.equals("anthropic-compatible")) {
            throw new IllegalArgumentException("显式缓存需要已确认支持 Anthropic 缓存格式的兼容端点");
        }
        if (mode.equals("auto")) {
            mode = actual.equals("anthropic-compatible") ? "explicit" : actual.equals("anthropic") ? "none" : "implicit";
        }
        String lifetime = ttl == null ? "" : ttl;
        if (!Set.of("", "5m", "1h").contains(lifetime)) {
            throw new IllegalArgumentException("显式缓存有效期只支持默认、5m 或 1h");
        }
        if (!lifetime.isBlank() && !mode.equals("explicit")) {
            throw new IllegalArgumentException("当前端点不支持显式缓存有效期");
        }
        String compact = compactionMode == null ? "short" : compactionMode;
        if (!Set.of("short", "prefix").contains(compact)) {
            throw new IllegalArgumentException("不支持的压缩策略");
        }
        boolean key = actual.equals("openai") && model != null
            && model.matches("(?:gpt-4.*|gpt-5(?:[.-](?:[0-5](?:[.-].*)?|mini|nano|codex).*)?|o[134].*)");
        return new ModelProfile(actual, model == null ? "" : model, mode, lifetime,
            instructionMessages == null ? actual.equals("openai") : instructionMessages,
            streamUsage == null ? actual.equals("openai") || actual.equals("deepseek") : streamUsage,
            key && !mode.equals("none"), compact, pricing);
    }
}
