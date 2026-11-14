package com.pilot.core.model;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** 端点能力快照。模型别名不能作为发送厂商专有参数的授权。 */
public record ModelProfile(String provider, String model, String cacheMode, String cacheTtl,
    boolean instructionMessages, boolean streamUsage, boolean cacheKey, String compactionMode, ModelPricing pricing) {
    /** 供应商类型和缓存策略都可以填它，表示交给 Pilot 按端点自己判断。 */
    public static final String AUTO = "auto";

    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_ANTHROPIC = "anthropic";
    public static final String PROVIDER_ANTHROPIC_COMPATIBLE = "anthropic-compatible";
    public static final String PROVIDER_GENERIC = "generic";

    /** 官方接口的主机名，用来认出供应商。 */
    public static final String HOST_OPENAI = "api.openai.com";
    public static final String HOST_DEEPSEEK = "api.deepseek.com";
    public static final String HOST_ANTHROPIC = "api.anthropic.com";

    /** 缓存策略：显式打缓存标记、不发缓存参数、靠服务端自动缓存。 */
    public static final String CACHE_EXPLICIT = "explicit";
    public static final String CACHE_NONE = "none";
    public static final String CACHE_IMPLICIT = "implicit";

    /** 压缩策略：整体重写成短摘要，或保留前缀以命中缓存。 */
    public static final String COMPACTION_SHORT = "short";
    public static final String COMPACTION_PREFIX = "prefix";

    private static final Set<String> PROVIDERS =
        Set.of(PROVIDER_OPENAI, PROVIDER_DEEPSEEK, PROVIDER_ANTHROPIC, PROVIDER_ANTHROPIC_COMPATIBLE, PROVIDER_GENERIC);

    public ModelProfile {
        pricing = pricing == null ? ModelPricing.unknown() : pricing;
    }

    public static ModelProfile generic() {
        return resolve(null, "", AUTO, AUTO, "", null, null, COMPACTION_SHORT, null);
    }

    public static ModelProfile resolve(URI endpoint, String model, String provider, String cacheMode, String ttl,
        Boolean instructionMessages, Boolean streamUsage, String compactionMode, ModelPricing pricing) {
        String host = endpoint == null || endpoint.getHost() == null ? "" : endpoint.getHost().toLowerCase(Locale.ROOT);
        String detected = HOST_OPENAI.equals(host) ? PROVIDER_OPENAI : HOST_DEEPSEEK.equals(host) ? PROVIDER_DEEPSEEK
            : HOST_ANTHROPIC.equals(host) ? PROVIDER_ANTHROPIC : PROVIDER_GENERIC;
        String actual = provider == null || provider.equals(AUTO) ? detected : provider;
        if (!PROVIDERS.contains(actual)) {
            throw new IllegalArgumentException("不支持的模型端点类型");
        }
        String mode = cacheMode == null ? AUTO : cacheMode;
        if (!Set.of(AUTO, CACHE_EXPLICIT, CACHE_NONE).contains(mode)) {
            throw new IllegalArgumentException("不支持的缓存策略");
        }
        if (mode.equals(CACHE_EXPLICIT) && !actual.equals(PROVIDER_ANTHROPIC_COMPATIBLE)) {
            throw new IllegalArgumentException("显式缓存需要已确认支持 Anthropic 缓存格式的兼容端点");
        }
        if (mode.equals(AUTO)) {
            mode = actual.equals(PROVIDER_ANTHROPIC_COMPATIBLE) ? CACHE_EXPLICIT
                : actual.equals(PROVIDER_ANTHROPIC) ? CACHE_NONE : CACHE_IMPLICIT;
        }
        String lifetime = ttl == null ? "" : ttl;
        if (!Set.of("", "5m", "1h").contains(lifetime)) {
            throw new IllegalArgumentException("显式缓存有效期只支持默认、5m 或 1h");
        }
        if (!lifetime.isBlank() && !mode.equals(CACHE_EXPLICIT)) {
            throw new IllegalArgumentException("当前端点不支持显式缓存有效期");
        }
        String compact = compactionMode == null ? COMPACTION_SHORT : compactionMode;
        if (!Set.of(COMPACTION_SHORT, COMPACTION_PREFIX).contains(compact)) {
            throw new IllegalArgumentException("不支持的压缩策略");
        }
        boolean key = actual.equals(PROVIDER_OPENAI) && model != null
            && model.matches("(?:gpt-4.*|gpt-5(?:[.-](?:[0-5](?:[.-].*)?|mini|nano|codex).*)?|o[134].*)");
        return new ModelProfile(actual, model == null ? "" : model, mode, lifetime,
            instructionMessages == null ? actual.equals(PROVIDER_OPENAI) : instructionMessages,
            streamUsage == null ? actual.equals(PROVIDER_OPENAI) || actual.equals(PROVIDER_DEEPSEEK) : streamUsage,
            key && !mode.equals(CACHE_NONE), compact, pricing);
    }
}
